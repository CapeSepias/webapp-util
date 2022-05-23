package japgolly.webapputil.websocket

import japgolly.scalajs.react.{AsyncCallback, Callback, CallbackTo}
import japgolly.univeq._
import japgolly.webapputil.binary._
import japgolly.webapputil.general.JsExt._
import japgolly.webapputil.general._
import japgolly.webapputil.websocket.WebSocket
import japgolly.webapputil.websocket.WebSocket.ReadyState
import japgolly.webapputil.websocket.WebSocketShared._
import org.scalajs.dom.{CloseEvent, Event, MessageEvent}
import scala.annotation.{elidable, nowarn}
import scala.scalajs.js
import scala.scalajs.js.timers.SetTimeoutHandle
import scala.scalajs.js.typedarray.ArrayBuffer
import scala.util.{Failure, Success, Try}

trait WebSocketClient[Codec[_], ReqRes <: Protocol.RequestResponse[Codec]] {
  val readyState: CallbackTo[Option[ReadyState]]
  val keepAlive: Callback
  def send(p: ReqRes)(request: p.RequestType): CallbackTo[AsyncCallback[p.ResponseType]]
  def asyncFunction(p: ReqRes): AsyncFunction[p.RequestType, ErrorMsg, p.ResponseType]
  def connect: Callback
  val close: Callback
}

object WebSocketClient {

  type OnDecodeError = CallbackTo[Option[CloseReason]]

  trait Builder[Codec[_], ReqRes <: Protocol.RequestResponse[Codec], Push] {
    def build(reauthorise  : AsyncCallback[Permission],
              onServerPush : Push => Callback,
              onStateChange: WebSocketClient[Codec, ReqRes] => State => Callback,
              retries      : Retries,
              sessionExpiry: VarJs[Boolean],
              timers       : TimersJs,
              logger       : LoggerJs): WebSocketClient[Codec, ReqRes]
  }

  object Builder {

    def apply[Codec[_]](protocol     : Protocol.WebSocket.ClientReqServerPush[Codec])
                       (mkProtocolCS : Codec[protocol.Req] => Protocol.Of[Codec, ClientToServer[protocol.Req]],
                        mkProtocolSC : (ReqId => Option[Protocol[Codec]], Codec[protocol.Push]) => Protocol.Of[Codec, ServerToClient[Codec, protocol.Push]],
                        createWS     : CallbackTo[WebSocket],
                        codecEngine  : CodecEngine[Codec, OnDecodeError],
                       ): Builder[Codec, protocol.ReqRes, protocol.Push] =
      new Builder[Codec, protocol.ReqRes, protocol.Push] {
        override def build(reauthorise  : AsyncCallback[Permission],
                           onServerPush : protocol.Push => Callback,
                           onStateChange: WebSocketClient[Codec, protocol.ReqRes] => State => Callback,
                           retries      : Retries,
                           sessionExpiry: VarJs[Boolean],
                           timers       : TimersJs,
                           logger       : LoggerJs) =
          new Impl[Codec, protocol.Req, protocol.ReqRes, protocol.Push](
            createWS,
            retries,
            reauthorise,
            onStateChange,
            codecEngine,
            mkProtocolCS(protocol.req.codec),
            mkProtocolSC(_, protocol.push.codec),
            onServerPush,
            timers,
            sessionExpiry,
            logger)
      }
  }

  object CloseReasons {
    val parseError = CloseReason(CloseCode.protocolError, CloseReasonPhrase("Failed to parse server response"))
  }

  sealed trait State
  object State {
    case object Unauthorised extends State
    final case class Authorised(readyState: ReadyState) extends State

    implicit def univEq: UnivEq[State] = UnivEq.derive
  }

  // ===================================================================================================================

  final class Impl[
      Codec[_],
      Req,
      ReqRes <: Protocol.RequestResponse[Codec] { type PreparedRequestType = Req },
      Push](
      createWS         : CallbackTo[WebSocket],
      connectionRetries: Retries,
      reauthorise      : AsyncCallback[Permission],
      onStateChange    : WebSocketClient[Codec, ReqRes] => State => Callback,
      codecEngine      : CodecEngine[Codec, OnDecodeError],
      protocolCS       : Protocol.Of[Codec, ClientToServer[Req]],
      mkProtocolSC     : (ReqId => Option[Protocol[Codec]]) => Protocol.Of[Codec, ServerToClient[Codec, Push]],
      recvPush         : Push => Callback,
      timers           : TimersJs,
      sessionExpiry    : VarJs[Boolean],
      logger           : LoggerJs) extends WebSocketClient[Codec, ReqRes] { self =>

    private val requestManager: RequestManager[ReqId, Protocol.AndValue[Codec], Request[Codec, ReqRes]] =
      RequestManager.arrayStore

    private val protocolSC: Protocol.Of[Codec, ServerToClient[Codec, Push]] =
      mkProtocolSC(requestManager.get(_).runNow().map(_.state.prep.response))

    /** All state for the WebSocket client.
      *
      * This is impure because of .instance.
      *
      * Unfortunately splitting this into multiple, clearer cases isn't an option. Even if you just had Connected
      * and Disconnected as cases, Connected holds a WebSocket instance which has it's own mutable state, and can change
      * to Closed without gives us a chance to change the state over to Disconnected.
      *
      * Correctness, confidence and sanity are instead achieved by formal specification in `websocket_client.tla`.
      *
      * @param authorised Whether we think we're logged in or not.
      * @param instance An optional WebSocket instance that may or may not be connected. See it's readyState.
      * @param retries Remaining retries when attempting to reconnect.
      * @param scheduled A scheduled task that will attempt to (re)connect when it eventually executes.
      * @param prevState The last [[State]] used to notify readyState-change listeners. Used to prevent sending
      *                  consecutive, identical notifications.
      */
    private case class InternalState(authorised: Boolean,
                                     instance  : Option[Instance],
                                     retries   : Retries,
                                     scheduled : Option[SetTimeoutHandle],
                                     prevState : Option[State])

    private var state = InternalState(
      authorised = true,
      instance   = None,
      retries    = connectionRetries,
      scheduled  = None,
      prevState  = None)

    private var queueOldestToNewest = Vector.empty[(ReqId, BinaryData)]

    private val readyStateCB: CallbackTo[Option[ReadyState]] =
      CallbackTo(state.instance.map(_.readyState()))

    private def setPublicState(newState: State): Callback =
      Callback {
        if (!state.prevState.exists(_ ==* newState)) {
          state = state.copy(prevState = Some(newState))
          onReadyStateChange2(newState).runNow()
        }
      }

    override lazy val connect: Callback = {
      val attemptConnect: Callback =
        Callback {
          state.scheduled.foreach(timers.clearTimeout)
          val i = unsafeNewInstance()
          state = state.copy(instance = i, scheduled = None)
          i match {
            case Some(_) =>
              state = state.copy(retries = connectionRetries)
              for (e <- requestManager.getAll.runNow())
                resend(e.reqId, e.state).runNow()
            case None =>
              unsafeScheduleReconnect()
          }
        }

      val reauthoriseAndReconnect: Callback =
        reauthorise.attempt.flatMap {

          case Right(Allow) =>
            AsyncCallback.delay {
              state = state.copy(authorised = true)
            } >> connect.asAsyncCallback

          case Right(Deny) | Left(_) =>
            AsyncCallback.delay {
              unsafeFailQueued(errorUnauthorised)
            }

        }.toCallback

      readyStateCB.flatMap {
        case None | Some(ReadyState.Closed) =>

          // Check if re-authorisation has occurred in a different tab
          if (!state.authorised) {
            val sessionExpired = sessionExpiry.unsafeGet()
            if (!sessionExpired)
              state = state.copy(authorised = true)
          }

          if (state.authorised)
            attemptConnect
          else
            reauthoriseAndReconnect

        case Some(ReadyState.Connecting | ReadyState.Open | ReadyState.Closing) =>
          Callback.empty
      }
    }

    private def unsafeNewInstance(): Option[Instance] = {
      // console.info("Connecting to server...")
      createWS.map(new Instance(_)).attempt.runNow() match {
        case Right(i) => Some(i)
        case Left(e) =>
          logger(_.warn(s"Failed to create WebSocket instance."))
          LoggerJs.exception(e)
          None
      }
    }

    private def unsafeScheduleReconnect(): Unit =
      state.retries.pop match {
        case Some((retry, nextRetries)) =>
          logger(_.info(s"WebSocketClient: retry connection in ${retry.toMillis} ms..."))
          val h = timers.setTimeout(retry.toMillis.toDouble) {
            // This bit here is Schedule in websocket_client.tla
            val i = unsafeNewInstance()
            state = state.copy(instance = i, scheduled = None)
            if (i.isEmpty)
              unsafeScheduleReconnect()
          }
          state = state.copy(retries = nextRetries, scheduled = Some(h))

        case None =>
          logger(_.info("WebSocketClient: out of retries. Leaving disconnected."))
          state = state.copy(scheduled = None)
          unsafeFailQueued(errorClosed)
      }

    private val onReadyStateChange2 = onStateChange(this)

    private class Instance(val ws: WebSocket) {
      ws.binaryType.unsafeSet(WebSocket.BinaryType.ArrayBuffer)
      ws.onOpen    .unsafeSet(onOpen _)
      ws.onClose   .unsafeSet(onClose _)
      ws.onMessage .unsafeSet(onMessage _)
      ws.onError   .unsafeSet(onError _)

      private var opened = false

      ws.readyState() match {
        case ReadyState.Open =>
          onOpened()

        case ReadyState.Closed =>
          // Sometimes (like when the security policy blocks the connection), the WebSocket starts in a closed state
          // without sending an onClose event. Catch that here so that the normal retry process kicks in.
          onClosed(None)

          case ReadyState.Closing
             | ReadyState.Connecting => ()
        }

      @elidable(elidable.INFO)
      override def toString = s"WebSocketClient.Instance(${readyState()})"

      def readyState(): ReadyState =
        ws.readyState()

      def isOpen(): Boolean =
        ws.readyState() == ReadyState.Open

      private def runReadyStateChange(): Unit = {
        val newState = State.Authorised(readyState())
        setPublicState(newState).runNow()
      }

      @nowarn("cat=unused")
      private def onOpen(e: Event): Unit =
        onOpened()

      private def onOpened(): Unit = {
        opened = true
        state = state.copy(retries = connectionRetries) // reset retry counter
        runReadyStateChange()
        processQueue()
      }

      def processQueue(): Unit = {
        while (queueOldestToNewest.nonEmpty) {
          val (reqId, payload) = queueOldestToNewest.head

          if (requestManager.get(reqId).runNow().isDefined)
            try
              ws.send(payload.toArrayBuffer)
            catch {
              case t: Throwable =>
                LoggerJs.exception(t)
                logger(_.warn(s"WebSocket.send($payload) failed"))
                throw t
            }

          queueOldestToNewest = queueOldestToNewest.tail
        }
      }

      private def onMessage(e: MessageEvent): Unit = {
        // console.log(s"[${ws.readyState}] onmessage: ", e.data.asInstanceOf[ArrayBuffer])
        def msg = e.data.asInstanceOf[ArrayBuffer]

        val decode = CallbackTo(codecEngine.decode(BinaryData.unsafeFromArrayBuffer(msg))(protocolSC.codec))

        val handler: Callback =
          decode.attempt.flatMap {
            case Right(Right(Right((id, null)))) =>
              logger.pure(_.debug(s"Unable to decode response to req #${id.value}; request has been removed")) >>
                requestManager.remove(id)

            case Right(Right(Right((id, res)))) =>
              logger.pure(_.debug(s"WebSocketClient received response to req #${id.value}: ${res.value}")) >>
                requestManager.complete(id, Success(res))

            case Right(Right(Left(push))) =>
              logger.pure(_.debug(s"WebSocketClient received push: $push")) >>
                recvPush(push)

            case Right(Left(err)) =>
              logger.pure(_.error(s"WebSocketClient failed to process msg: ${BinaryData.fromArrayBuffer(msg)}\n$err")) >>
                err.map(_.foreach(ws.close))

            case Left(err) =>
              logger.pure(_.error(s"WebSocketClient failed to process msg: ${BinaryData.fromArrayBuffer(msg)}\n$err")) >>
                onException(err)
          }
        handler.runNow()
      }

      private def onException(err: Throwable): Callback =
        Callback {
          LoggerJs.exception(err)
          val message = Option(err.getMessage)
          unsafeCloseDueToError(message)
        }

      private def onError(e: Event): Unit = {
        val message = e.asInstanceOf[js.Dynamic].message.asInstanceOf[js.UndefOr[String]]
        unsafeCloseDueToError(message.toOption)
      }

      private def unsafeCloseDueToError(msg: Option[String]): Unit = {
        val desc = s"WebSocket error occurred.${msg.fold("")(" " + _)}"
        //console.error(desc, e)
        val reason = CloseReason(CloseCode.unhandledException, CloseReasonPhrase(desc))
        ws.close(reason)
      }

      private def onClose(e: CloseEvent): Unit = {
        onClosed(Some(CloseCode(e.code)))
      }

      private def onClosed(code: Option[CloseCode]): Unit =
        code match {
          case Some(CloseCode.`unauthorised`) =>
            sessionExpiry.unsafeSet(true)
            state = state.copy(authorised = false)
            setPublicState(State.Unauthorised).runNow()
            connect.runNow()

          case _ =>
            runReadyStateChange()
            unsafeFailQueued(if (opened) errorClosed else errorFailed)
            unsafeScheduleReconnect()
        }
    }

    private val processQueue: Callback =
      Callback {
        for (i <- state.instance)
          if (i.isOpen())
            i.processQueue()
      }

    private def unsafeFailQueued(error: Throwable): Unit =
      requestManager.completeAll(Failure(error))
        .map(_.foreach(_.printStackTrace()))
        .runNow()

    override val close: Callback =
      Callback {
        state = state.copy(retries = Retries.none)
        for (i <- state.instance)
          if (i.isOpen())
            i.ws.close(CloseReason.normalClosure)
      }

    override val readyState: CallbackTo[Option[ReadyState]] =
      CallbackTo(state.instance.map(_.readyState()))

    /** If a connection is open, send an empty message to prevent server-side timeout and keep the connection alive. */
    override val keepAlive: Callback = {
      val ab = new ArrayBuffer(0)
      Callback {
        for (i <- state.instance)
          if (i.isOpen())
            i.ws.send(ab)
      }
    }

    override def send(p: ReqRes)(request: p.RequestType): CallbackTo[AsyncCallback[p.ResponseType]] =
      _send(Request[Codec, ReqRes](p)(request))

    private def _send(request: Request[Codec, ReqRes]): CallbackTo[AsyncCallback[request.p.ResponseType]] =
      _sendGeneric(request) {
        requestManager.newRequest(request).flatMap { case (reqId, promise) =>
          _addToQueue(reqId, request)(promise)
        }
      }

    private def resend(reqId: ReqId, request: Request[Codec, ReqRes]): CallbackTo[AsyncCallback[request.p.ResponseType]] =
      _sendGeneric(request) {
        requestManager.get(reqId).flatMap {
          case Some(e) => _addToQueue(reqId, request)(e.promise)
          case None    => _send(request)
        }
      }

    private def _sendGeneric(request: Request[Codec, ReqRes])
                            (addToQueue: => CallbackTo[AsyncCallback[request.p.ResponseType]]): CallbackTo[AsyncCallback[request.p.ResponseType]] = {
      type R = CallbackTo[AsyncCallback[request.p.ResponseType]]

      def rejectImmediately(error: Throwable): R =
        CallbackTo.pure(AsyncCallback.throwException(error))

      CallbackTo.suspend {
        state.instance.map(_.readyState()) match {
          case Some(ReadyState.Connecting) => addToQueue
          case Some(ReadyState.Open)       => addToQueue <* processQueue
          case Some(ReadyState.Closing)    => rejectImmediately(errorClosing)
          case None
             | Some(ReadyState.Closed)     => addToQueue <* connect
        }
      }
    }

    private def _addToQueue(reqId  : ReqId,
                            request: Request[Codec, ReqRes])
                           (promise: AsyncCallback[Protocol.AndValue[Codec]]): CallbackTo[AsyncCallback[request.p.ResponseType]] =
      CallbackTo {
        val msgValue = (reqId, request.prep.request)
        val msgBin   = codecEngine.encode(msgValue)(protocolCS.codec)
        queueOldestToNewest :+= ((reqId, msgBin))
        promise.map(_.unsafeForceType[request.p.ResponseType].value)
      }

    override def asyncFunction(p: ReqRes): AsyncFunction[p.RequestType, ErrorMsg, p.ResponseType] =
      AsyncFunction.fromSimple(send(p)(_))
  }

  private val errorClosing      = js.JavaScriptException("Connection is closing.")
  private val errorClosed       = js.JavaScriptException("Connection is closed.")
  private val errorFailed       = js.JavaScriptException("Failed to connect to server.")
  private val errorUnauthorised = js.JavaScriptException("Session expired. You must login again.")

  // ===================================================================================================================

  private sealed trait Request[Codec[_], ReqRes <: Protocol.RequestResponse[Codec]] {
    val p: ReqRes
    val req: p.RequestType

    final lazy val prep = p.prepareSend(req)
  }

  private object Request {
    type Aux[Codec[_], ReqRes <: Protocol.RequestResponse[Codec], P <: ReqRes] = Request[Codec, ReqRes] { val p: P }

    def apply[Codec[_], ReqRes <: Protocol.RequestResponse[Codec]](p: ReqRes)(req: p.RequestType): Aux[Codec, ReqRes, p.type] = {
      val _p: p.type = p
      val _req = req
      new Request[Codec, ReqRes] {
        override val p: _p.type = _p
        override val req = _req
      }
    }
  }

  // ===================================================================================================================

  private trait RequestManager[Id, A, S] {
    def newRequest(state: S): CallbackTo[(Id, AsyncCallback[A])]
    def get(id: Id): CallbackTo[Option[RequestManager.StateEntry[A, S]]]
    def getAll: CallbackTo[List[RequestManager.StateEntry[A, S]]]
    def complete(id: Id, a: Try[A]): Callback
    def completeAll(t: Try[A]): CallbackTo[List[Throwable]]
    def remove(id: Id): Callback
  }

  private object RequestManager {

    final case class StateEntry[A, S](reqId   : ReqId,
                                      state   : S,
                                      complete: Try[A] => Callback,
                                      promise : AsyncCallback[A])

    private final class ArrayStore[A, S] extends RequestManager[ReqId, A, S] {
      private var prevId = 0
      private var size = 0
      private var state = new js.Array[js.UndefOr[StateEntry[A, S]]]

      private def unsafeGet(id: ReqId): Option[StateEntry[A, S]] = {
        // console.log("GET: ", state.asInstanceOf[js.Array[js.Any]])
        val e = state(id.value)
        e.toOption
      }

      override def get(id: ReqId) =
        CallbackTo(unsafeGet(id))

      override val getAll =
        CallbackTo(state.iterator.flatMap(_.toList).toList)

      override def newRequest(s: S): CallbackTo[(ReqId, AsyncCallback[A])] =
        CallbackTo {
          prevId += 1
          val id = prevId
          val reqId = ReqId(id)
          val (ac, tryToCb) = AsyncCallback.promise[A].runNow()
          state(id) = StateEntry(reqId, s, tryToCb, ac)
          size += 1
          (reqId, ac)
        }

      private def unsafeGetAndRemove(reqId: ReqId): Option[StateEntry[A, S]] = {
        val r = unsafeGet(reqId)
        if (r.isDefined) {
          assert(size > 0, s"WebSocketClient.RequestManager: size=$size, reqId=${reqId.value}, state=$state")
          size -= 1
          if (size == 0)
            state = new js.Array
          else
            js.special.delete(state, reqId.value)
        }
        r
      }

      override def complete(reqId: ReqId, a: Try[A]): Callback =
        Callback {
          for (e <- unsafeGetAndRemove(reqId))
            e.complete(a).runNow()
        }

      override def completeAll(t: Try[A]): CallbackTo[List[Throwable]] =
        CallbackTo {
          // Extract handlers
          var l = List.empty[Try[A] => Callback]
          state.forEachJs(_.foreach(l ::= _.complete))

          // Clear state
          state = new js.Array
          size = 0

          // Call handlers
          l.flatMap(_(t).attempt.runNow().left.toSeq)
        }

      override def remove(reqId: ReqId): Callback =
        Callback {
          unsafeGetAndRemove(reqId)
          ()
        }
    }

    def arrayStore[A, S]: RequestManager[ReqId, A, S] =
      new ArrayStore[A, S]
  }
}
