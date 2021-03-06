@file:Suppress("NOTHING_TO_INLINE")
package io.github.itsusinn.extension.forward.client

import io.ktor.client.call.*
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

fun DefaultClientWebSocketSession.warp():KtorWebsocket = KtorWebsocket(this)

val logger = KotlinLogging.logger {  }
private fun now() = System.currentTimeMillis()

private const val pingText = "[ping]"
private val pingBuffer = ByteBuffer.wrap(pingText.toByteArray())
private val pingFrame = Frame.Text(pingText)

class KtorWebsocket(
   private var session: DefaultClientWebSocketSession
):ClientWebSocketSession, DefaultWebSocketSession by session {
   override val call: HttpClientCall = session.call
   private val closeSignal = AtomicBoolean(false)
   fun isClosed():Boolean = closeSignal.get()

   private var aliveSignal = now()
   private fun isAlive() = now() - aliveSignal < 90_000
   private val aliveJob:Job
   private val receiveJob:Job
   init {
      aliveJob = launch {
         while (true){
            if (isClosed()) return@launch
            if (!isAlive()){
               logger.debug { "Websocket no longer alive" }
               close()
               break
            }
            send(pingFrame)
            delay(60_000)
         }
      }
      //launch a coroutine listening on receiving frames
      receiveJob = launch {
         try {
            for (frame in incoming){
               if (isClosed()) return@launch
               aliveSignal = now()
               when(frame){
                  is Frame.Text -> {
                     try {
                        if (frame.buffer.equals(pingBuffer)){
                           aliveSignal = now()
                           continue
                        }
                        _textFrameHandler?.invoke(frame)
                     }catch (e:Throwable){
                        _uncaughtErrorHandler?.invoke(e)
                     }
                  }
                  is Frame.Pong -> {
                     logger.info { "receiving pong frame " }
                  }
                  else -> { TODO("Maybe it is needed to impl") }
               }
            }
         } catch (e: ClosedReceiveChannelException) {
            close(CloseReason(CloseReason.Codes.GOING_AWAY,"Cannot receive frame"))
         } catch (e: EOFException){
            close(CloseReason(CloseReason.Codes.GOING_AWAY,"Cannot receive frame"))
         } catch (e: Throwable) {
            _uncaughtErrorHandler?.invoke(e)
         }
      }
   }
   override suspend fun send(frame: Frame) {
      if (isClosed()) return
      try {
         outgoing.send(frame)
      }catch (e:Throwable){
         close(CloseReason(CloseReason.Codes.GOING_AWAY,"Cannot send Frame"))
      }
   }

   var _textFrameHandler:(suspend (Frame.Text) -> Unit)? = null
   inline fun textFrameHandler(noinline handler:suspend (Frame.Text) -> Unit): KtorWebsocket {
      _textFrameHandler = handler
      return this
   }

   var _closeHandler:(suspend (CloseReason) -> Unit)? = null
   inline fun closeHandler(noinline handler: suspend (CloseReason) -> Unit): KtorWebsocket {
      _closeHandler = handler
      return this
   }

   suspend fun close(reason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, "")) {
      if (isClosed()) return
      try {
         closeSignal.set(true)
         _textFrameHandler = null
         _uncaughtErrorHandler = null
         _closeHandler?.invoke(reason)
         _closeHandler = null
         aliveJob.cancel()
         receiveJob.cancel()
         session.cancel()
      } catch (_: Throwable) { }
   }

   var _uncaughtErrorHandler:((Throwable) -> Unit)? = {
      logger.warn(it) { "uncaughtError \n"+it.stackTrace }
   }
   inline fun uncaughtErrorHandler(noinline handler:(Throwable) -> Unit){
      _uncaughtErrorHandler = {
         try {
            handler.invoke(it)
         }catch (e:Throwable){
            logger.warn(it) { "uncaughtError\n"+it.stackTrace }
         }
      }
   }
}