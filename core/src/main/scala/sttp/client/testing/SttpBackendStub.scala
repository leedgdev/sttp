package sttp.client.testing

import java.io.InputStream

import sttp.capabilities.{Effect, WebSockets}
import sttp.client.internal.{SttpFile, _}
import sttp.client.monad.IdMonad
import sttp.client.testing.SttpBackendStub._
import sttp.client.{IgnoreResponse, ResponseAs, ResponseAsByteArray, SttpBackend, _}
import sttp.model.StatusCode
import sttp.monad.{FutureMonad, MonadError}
import sttp.ws.{WebSocket, WebSocketFrame}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * A stub backend to use in tests.
  *
  * The stub can be configured to respond with a given response if the
  * request matches a predicate (see the [[whenRequestMatches()]] method).
  *
  * Note however, that this is not type-safe with respect to the type of the
  * response body - the stub doesn't have a way to check if the type of the
  * body in the configured response is the same as the one specified by the
  * request. Some conversions will be attempted (e.g. from a `String` to
  * a custom mapped type, as specified in the request, see the documentation
  * for more details).
  *
  * Hence, the predicates can match requests basing on the URI
  * or headers. A [[ClassCastException]] might occur if for a given request,
  * a response is specified with the incorrect or inconvertible body type.
  */
class SttpBackendStub[F[_], +P](
    monad: MonadError[F],
    matchers: PartialFunction[Request[_, _], F[Response[_]]],
    fallback: Option[SttpBackend[F, P]]
) extends SttpBackend[F, P] {

  /**
    * Specify how the stub backend should respond to requests matching the
    * given predicate.
    *
    * Note that the stubs are immutable, and each new
    * specification that is added yields a new stub instance.
    */
  def whenRequestMatches(p: Request[_, _] => Boolean): WhenRequest =
    new WhenRequest(p)

  /**
    * Specify how the stub backend should respond to any request (catch-all).
    *
    * Note that the stubs are immutable, and each new
    * specification that is added yields a new stub instance.
    */
  def whenAnyRequest: WhenRequest = whenRequestMatches(_ => true)

  /**
    * Specify how the stub backend should respond to requests using the
    * given partial function.
    *
    * Note that the stubs are immutable, and each new
    * specification that is added yields a new stub instance.
    */
  def whenRequestMatchesPartial(
      partial: PartialFunction[Request[_, _], Response[_]]
  ): SttpBackendStub[F, P] = {
    val wrappedPartial: PartialFunction[Request[_, _], F[Response[_]]] =
      partial.andThen((r: Response[_]) => monad.unit(r))
    new SttpBackendStub[F, P](monad, matchers.orElse(wrappedPartial), fallback)
  }

  override def send[T, R >: P with Effect[F]](request: Request[T, R]): F[Response[T]] = {
    Try(matchers.lift(request)) match {
      case Success(Some(response)) =>
        tryAdjustResponseType(monad, request.response, response.asInstanceOf[F[Response[T]]])
      case Success(None) =>
        fallback match {
          case None =>
            val response = wrapResponse(
              Response[String](s"Not Found: ${request.uri}", StatusCode.NotFound, "Not Found", Nil, Nil)
            )
            tryAdjustResponseType(monad, request.response, response)
          case Some(fb) => fb.send(request)
        }
      case Failure(e) => monad.error(e)
    }
  }

  private def wrapResponse[T](r: Response[_]): F[Response[T]] =
    monad.unit(r.asInstanceOf[Response[T]])

  override def close(): F[Unit] = monad.unit(())

  override def responseMonad: MonadError[F] = monad

  class WhenRequest(p: Request[_, _] => Boolean) {
    def thenRespondOk(): SttpBackendStub[F, P] =
      thenRespondWithCode(StatusCode.Ok)
    def thenRespondNotFound(): SttpBackendStub[F, P] =
      thenRespondWithCode(StatusCode.NotFound, "Not found")
    def thenRespondServerError(): SttpBackendStub[F, P] =
      thenRespondWithCode(StatusCode.InternalServerError, "Internal server error")
    def thenRespondWithCode(status: StatusCode, msg: String = ""): SttpBackendStub[F, P] = {
      thenRespond(Response(msg, status, msg))
    }
    def thenRespond[T](body: T): SttpBackendStub[F, P] =
      thenRespond(Response[T](body, StatusCode.Ok, "OK"))
    def thenRespond[T](resp: => Response[T]): SttpBackendStub[F, P] = {
      val m: PartialFunction[Request[_, _], F[Response[_]]] = {
        case r if p(r) => monad.eval(resp)
      }
      new SttpBackendStub[F, P](monad, matchers.orElse(m), fallback)
    }

    /**
      * Not thread-safe!
      */
    def thenRespondCyclic[T](bodies: T*): SttpBackendStub[F, P] = {
      thenRespondCyclicResponses(bodies.map(body => Response[T](body, StatusCode.Ok, "OK")): _*)
    }

    /**
      * Not thread-safe!
      */
    def thenRespondCyclicResponses[T](responses: Response[T]*): SttpBackendStub[F, P] = {
      val iterator = Iterator.continually(responses).flatten
      thenRespond(iterator.next)
    }
    def thenRespondWrapped(resp: => F[Response[_]]): SttpBackendStub[F, P] = {
      val m: PartialFunction[Request[_, _], F[Response[_]]] = {
        case r if p(r) => resp
      }
      new SttpBackendStub[F, P](monad, matchers.orElse(m), fallback)
    }
    def thenRespondWrapped(resp: Request[_, _] => F[Response[_]]): SttpBackendStub[F, P] = {
      val m: PartialFunction[Request[_, _], F[Response[_]]] = {
        case r if p(r) => resp(r)
      }
      new SttpBackendStub[F, P](monad, matchers.orElse(m), fallback)
    }
  }
}

object SttpBackendStub {

  /**
    * Create a stub of a synchronous backend (which doesn't wrap results in any
    * container), without streaming.
    */
  def synchronous: SttpBackendStub[Identity, WebSockets] =
    new SttpBackendStub(
      IdMonad,
      PartialFunction.empty,
      None
    )

  /**
    * Create a stub of an asynchronous backend (which wraps results in Scala's
    * built-in [[Future]]), without streaming.
    */
  def asynchronousFuture: SttpBackendStub[Future, WebSockets] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    new SttpBackendStub(
      new FutureMonad(),
      PartialFunction.empty,
      None
    )
  }

  /**
    * Create a stub backend using the given response monad (which determines
    * how requests are wrapped), any stream type and any websocket handler.
    */
  def apply[F[_], P](responseMonad: MonadError[F]): SttpBackendStub[F, P] =
    new SttpBackendStub[F, P](
      responseMonad,
      PartialFunction.empty,
      None
    )

  /**
    * Create a stub backend which delegates send requests to the given fallback
    * backend, if the request doesn't match any of the specified predicates.
    */
  def withFallback[F[_], P0, P1 >: P0](
      fallback: SttpBackend[F, P0]
  ): SttpBackendStub[F, P1] =
    new SttpBackendStub[F, P1](
      fallback.responseMonad,
      PartialFunction.empty,
      Some(fallback)
    )

  private[client] def tryAdjustResponseType[DesiredRType, RType, M[_]](
      monad: MonadError[M],
      ra: ResponseAs[DesiredRType, _],
      m: M[Response[RType]]
  ): M[Response[DesiredRType]] = {
    monad.map[Response[RType], Response[DesiredRType]](m) { r =>
      val newBody: Any = tryAdjustResponseBody(ra, r.body, r).getOrElse(r.body)
      r.copy(body = newBody.asInstanceOf[DesiredRType])
    }
  }

  private[client] def tryAdjustResponseBody[T, U](ra: ResponseAs[T, _], b: U, meta: ResponseMetadata): Option[T] = {
    ra match {
      case IgnoreResponse => Some(())
      case ResponseAsByteArray =>
        b match {
          case s: String       => Some(s.getBytes(Utf8))
          case a: Array[Byte]  => Some(a)
          case is: InputStream => Some(toByteArray(is))
          case _               => None
        }
      case ResponseAsStream(_, _)    => None
      case ResponseAsStreamUnsafe(_) => None
      case ResponseAsFile(_) =>
        b match {
          case f: SttpFile => Some(f)
          case _           => None
        }
      case ResponseAsWebSocket(_)            => None
      case ResponseAsWebSocketUnsafe()       => None
      case ResponseAsWebSocketStream(_, _)   => None
      case MappedResponseAs(raw, g)          => tryAdjustResponseBody(raw, b, meta).map(g(_, meta))
      case rfm: ResponseAsFromMetadata[_, _] => tryAdjustResponseBody(rfm(meta), b, meta)
    }
  }
}

// TODO move to separate file
/**
  * A simple stub for websockets that uses a queue of responses which are returned when the client calls
  * [[WebSocket.receive]].
  *
  * New messages can be added to queue in reaction to [[WebSocket.send]] being invoked, by specifying the
  * behavior using one of the [[thenRespond]] variatns.
  *
  * For more complex cases, please provide your own implementation of [[WebSocket]].
  */
class WebSocketStub[S](
    initialResponses: List[Try[Either[WebSocketFrame.Close, WebSocketFrame.Incoming]]],
    initialState: S,
    makeNewResponses: (S, WebSocketFrame) => (S, List[Try[Either[WebSocketFrame.Close, WebSocketFrame.Incoming]]])
) {

  /**
    * Creates a stub that has the same initial responses, but replaces the function that adds messages to be
    * received using [[WebSocket.receive]], in reaction to [[WebSocket.send]] being invoked.
    */
  def thenRespond(addReceived: WebSocketFrame => List[WebSocketFrame.Incoming]): WebSocketStub[Unit] =
    thenRespondWith(
      addReceived.andThen(_.map(m => Success(Right(m): Either[WebSocketFrame.Close, WebSocketFrame.Incoming])))
    )

  /**
    * Creates a stub that has the same initial responses, but replaces the function that adds responses to be
    * received using [[WebSocket.receive]], in reaction to [[WebSocket.send]] being invoked.
    *
    * More powerful version of [[thenRespond]], as can result in the websocket to become closed.
    */
  def thenRespondWith(
      addReceived: WebSocketFrame => List[Try[Either[WebSocketFrame.Close, WebSocketFrame.Incoming]]]
  ): WebSocketStub[Unit] =
    new WebSocketStub(
      initialResponses,
      (),
      (_, frame) => ((), addReceived(frame))
    )

  /**
    * Creates a stub that has the same initial responses, but replaces the function that adds responses to be
    * received using [[WebSocket.receive]], in reaction to [[WebSocket.send]] being invoked.
    *
    * More powerful version of [[thenRespond]], as the given function can additionally use state and implement stateful
    * logic for computing response messages.
    */
  def thenRespondS[S2](initial: S2)(
      onSend: (S2, WebSocketFrame) => (S2, List[WebSocketFrame.Incoming])
  ): WebSocketStub[S2] =
    thenRespondWithS(initial)((state, frame) => {
      val (newState, messages) = onSend(state, frame)
      (newState, messages.map(m => Success(Right(m): Either[WebSocketFrame.Close, WebSocketFrame.Incoming])))
    })

  /**
    * Creates a stub that has the same initial responses, but replaces the function that adds responses to be
    * received using [[WebSocket.receive]], in reaction to [[WebSocket.send]] being invoked.
    *
    * More powerful version of [[thenRespond]], as the given function can additionally use state and implement stateful
    * logic for computing response messages, as well as result in the websocket to become closed.
    */
  def thenRespondWithS[S2](initial: S2)(
      onSend: (S2, WebSocketFrame) => (S2, List[Try[Either[WebSocketFrame.Close, WebSocketFrame.Incoming]]])
  ): WebSocketStub[S2] = new WebSocketStub(initialResponses, initial, onSend)

  def build[F[_]](implicit m: MonadError[F]): WebSocket[F] =
    new WebSocket[F] {

      private var state: S = initialState
      private var _isOpen: Boolean = true
      private var responses = initialResponses.toList

      override def monad: MonadError[F] = m
      override def isOpen(): F[Boolean] = monad.unit(_isOpen)

      override def receive(): F[WebSocketFrame] =
        synchronized {
          if (_isOpen) {
            responses.headOption match {
              case Some(Success(Right(response))) =>
                responses = responses.tail
                monad.unit(response)
              case Some(Success(Left(close))) =>
                _isOpen = false
                monad.unit(close)
              case Some(Failure(e)) =>
                _isOpen = false
                monad.error(e)
              case None =>
                monad.error(new Exception("Unexpected 'receive', no more prepared responses."))
            }
          } else {
            monad.error(new Exception("WebSocket is closed."))
          }
        }

      override def send(frame: WebSocketFrame, isContinuation: Boolean): F[Unit] =
        monad.flatten(monad.eval {
          synchronized {
            if (_isOpen) {
              val (newState, newResponses) = makeNewResponses(state, frame)
              responses = responses ++ newResponses
              state = newState
              monad.unit(())
            } else {
              monad.error(new Exception("WebSocket is closed."))
            }
          }
        })
    }
}

object WebSocketStub {

  /**
    * Creates a stub which will return the given responses when [[WebSocket.receive]] is called by the client.
    * More messages can be enqueued to be returned by the stub by subsequently calling one of the
    * [[WebSocketStub.thenRespond]] methods.
    */
  def withInitialResponses(
      events: List[Try[Either[WebSocketFrame.Close, WebSocketFrame.Incoming]]]
  ): WebSocketStub[Unit] = {
    new WebSocketStub(events, (), (_, _) => ((), List.empty))
  }

  /**
    * Creates a stub which will return the given messages when [[WebSocket.receive]] is called by the client.
    * More messages can be enqueued to be returned by the stub by subsequently calling one of the
    * [[WebSocketStub.thenRespond]] methods.
    */
  def withInitialIncoming(
      messages: List[WebSocketFrame.Incoming]
  ): WebSocketStub[Unit] = {
    withInitialResponses(messages.map(m => Success(Right(m): Either[WebSocketFrame.Close, WebSocketFrame.Incoming])))
  }

  /**
    * Creates a stub which won't return any initial responses when [[WebSocket.receive]] is called by the client.
    * Messages can be enqueued to be returned by the stub by subsequently calling one of the
    * [[WebSocketStub.thenRespond]] methods.
    */
  def withNoInitialResponses: WebSocketStub[Unit] = withInitialResponses(List.empty)

}
