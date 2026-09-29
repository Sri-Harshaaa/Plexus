package com.plexus.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.util.Log

internal fun isTerminalPacketRejection(code: String): Boolean =
    code.startsWith("PACKET_REJECTED_")

internal fun terminalPacketRejectionCode(serverCode: String): String =
    when {
        serverCode.startsWith("INVALID_") -> "PACKET_REJECTED_INVALID"
        serverCode.endsWith("_MISMATCH") -> "PACKET_REJECTED_MISMATCH"
        serverCode.endsWith("_CONFLICT") -> "PACKET_REJECTED_CONFLICT"
        serverCode.endsWith("_EXPIRED") -> "PACKET_REJECTED_EXPIRED"
        else -> "PACKET_REJECTED"
    }

/** All state is confined to Scheduler. Room remains the durable outbound owner. */
internal class InternetSession(
    private val url: String,
    private val nodeId: String,
    private val publicKey: String,
    private val sign: (ByteArray) -> String,
    private val scheduler: Scheduler,
    private val observer: Observer,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).pingInterval(5, TimeUnit.MINUTES).build(),
    private val now: () -> Long = System::currentTimeMillis
) {
    interface Scheduler { fun post(task: Runnable); fun later(task: Runnable, delay: Long); fun cancel(task: Runnable) }
    interface Observer {
        fun connected() {}
        fun authenticated() {}
        fun disconnected() {}
        fun event(name: String, packet: JSONObject) {}
        fun error(code: String) {}
    }
    private data class Offer(val key: String, val action: String, val packet: JSONObject,
                             val expiresAt: Long, val background: Boolean = false,
                             val id: String = UUID.randomUUID().toString()) {
        val priority get() = when {
            background -> 3
            action == "deliveryProof" -> 0
            packet.optString("type") == "mesh_message" -> 2
            else -> 1
        }
    }
    private var running = false
    private var generation = 0L
    private var failures = 0
    private var openedAt = 0L
    private var socket: WebSocket? = null
    private var timer: Runnable? = null
    private var syncTimer: Runnable? = null
    private var syncing = false
    private var inFlight: Offer? = null
    private val offers = linkedMapOf<String, Offer>()
    private val accepted = linkedMapOf<String, Long>()
    var connected = false; private set
    var authenticated = false; private set

    fun start() {
        if (running) return
        running = true
        connect()
    }
    private fun connect() {
        if (!running) return
        val token = ++generation
        arm(15_000) { fail(token, "CONNECTION_AUTH_TIMEOUT") }
        try {
            socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                private fun current(task: () -> Unit) = scheduler.post(Runnable {
                    if (running && generation == token) task()
                })
                override fun onOpen(webSocket: WebSocket, response: Response) = current {
                    socket = webSocket
                    connected = true
                    openedAt = now()
                    observer.connected()
                    write(JSONObject().put("action", "authInit").put("nodeId", nodeId)
                        .put("signingPublicKey", publicKey))
                }
                override fun onMessage(webSocket: WebSocket, text: String) = current {

                    if (text.toByteArray(Charsets.UTF_8).size > MAX_WIRE_BYTES) {

                        fail(token, "OVERSIZED_SERVER_FRAME")

                    } else {

                        try {

                            val envelope =
                                JSONObject(text)

                            if (!envelope.has("event")) {

                                Log.e(
                                    "PlexusInternet",
                                    "NON_PLEXUS_SERVER_FRAME"
                                )

                                return@current
                            }

                            receive(envelope)

                        } catch (e: Exception) {

                            Log.e(
                                "PlexusInternet",
                                "SERVER EVENT PROCESSING FAILED -> " +
                                        "${e.javaClass.simpleName}: ${e.message}",
                                e
                            )

                            fail(
                                token,
                                "INVALID_SERVER_EVENT"
                            )
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = current {
                    webSocket.close(code, null)
                    fail(token, "WEBSOCKET_CLOSED")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = current {
                    fail(token, "WEBSOCKET_CLOSED")
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = current {
                    // Never pass the exception/response URL to application logs.
                    fail(token, "WEBSOCKET_UNAVAILABLE")
                }
            })
        } catch (_: Exception) { fail(token, "WEBSOCKET_CONFIGURATION_INVALID") }
    }
    private fun receive(envelope: JSONObject) {
        val event = envelope.getString("event")
        val data = envelope.optJSONObject("data") ?: JSONObject()
        when (event) {
            "auth:challenge" -> {
                if (authenticated) return
                val id = data.getString("challengeId")
                val challenge = data.getString("challenge")
                require(id.matches(Regex("[0-9a-fA-F-]{36}")) && challenge.matches(Regex("[A-Za-z0-9_-]{43}")))
                write(JSONObject().put("action", "authProof").put("challengeId", id)
                    .put("signature", sign(authenticationBytes(nodeId, id, challenge))))
            }
            "auth:success" -> {
                require(data.getString("nodeId") == nodeId)
                if (authenticated) return
                cancelTimer()
                authenticated = true
                // Server automatically sends the first sync page to this exact connection.
                syncing = true
                armSyncTimeout()
                observer.authenticated()
                drain()
            }
            "internet:ack" -> {
                val offer = inFlight ?: return
                if (envelope.optString("requestId") != offer.id) return
                require(data.optBoolean("accepted", false))
                cancelTimer()
                inFlight = null
                offers.remove(offer.key)
                accepted[offer.key] = minOf(offer.expiresAt, now() + 300_000)
                while (accepted.size > 4096) accepted.remove(accepted.keys.first())
                // This is relay persistence acknowledgement, never destination delivery.
                drain()
            }
            "sync:complete" -> if (authenticated) {
                syncTimer?.let(scheduler::cancel)
                syncTimer = null
                syncing = false
                val cursor = data.optString("cursor")
                if (cursor.isNotEmpty()) requestSync(cursor)
            }
            "internet:error" -> {
                val code = data.optString("error", "SERVER_ERROR").take(80)
                val offer = inFlight
                if (authenticated && offer != null && envelope.optString("requestId") == offer.id &&
                    (code.startsWith("INVALID_") || code.endsWith("_MISMATCH") || code.endsWith("_CONFLICT") || code.endsWith("_EXPIRED"))) {
                    cancelTimer(); inFlight = null; offers.remove(offer.key)
                    observer.error(terminalPacketRejectionCode(code))
                    drain()
                } else fail(generation, "SERVER_RETRY_REQUIRED")
            }
            else -> if (authenticated) observer.event(event, data)
        }
    }
    fun offer(key: String, action: String, packet: JSONObject, expiresAt: Long, background: Boolean = false): Boolean {
        if (!authenticated || expiresAt <= now()) return false
        accepted.entries.removeAll { it.value <= now() }
        if (accepted.containsKey(key)) return true
        offers[key]?.let {
            if (!background && it.background) offers[key] = it.copy(background = false)
            return true
        }
        if (offers.size >= 256 || (background && offers.values.count { it.background } >= 32)) return false
        val offer = Offer(key, action, packet, expiresAt, background)
        if (wire(offer).toString().toByteArray(Charsets.UTF_8).size > MAX_WIRE_BYTES) return false
        offers[key] = offer
        drain()
        return true
    }
    private fun wire(offer: Offer) = JSONObject().put("action", offer.action)
        .put("requestId", offer.id).put("packet", offer.packet)
    private fun drain() {
        if (!authenticated || inFlight != null) return
        offers.entries.removeAll { it.value.expiresAt <= now() }
        val offer = offers.values.minByOrNull { it.priority } ?: return
        inFlight = offer
        val token = generation
        arm(20_000) { fail(token, "PERSISTENCE_ACK_TIMEOUT") }
        if (!write(wire(offer))) fail(token, "WEBSOCKET_BACKPRESSURE")
    }
    fun requestSync(cursor: String = "") {
        if (!authenticated || syncing) return
        syncing = true
        val token = generation
        val task = Runnable {
            if (running && authenticated && generation == token) {
                write(JSONObject().put("action", "sync").put("cursor", cursor))
                armSyncTimeout()
            }
        }
        syncTimer = task
        scheduler.later(task, 200)
    }
    private fun armSyncTimeout() {
        syncTimer?.let(scheduler::cancel)
        val token = generation
        syncTimer = Runnable { if (generation == token) fail(token, "SYNC_TIMEOUT") }
            .also { scheduler.later(it, 20_000) }
    }
    private fun write(json: JSONObject) = socket?.send(json.toString()) == true
    private fun arm(delay: Long, action: () -> Unit) {
        cancelTimer()
        timer = Runnable(action).also { scheduler.later(it, delay) }
    }
    private fun cancelTimer() { timer?.let(scheduler::cancel); timer = null }
    private fun fail(token: Long, code: String) {
        if (!running || token != generation) return
        ++generation // Invalidate callbacks before cancellation can report another failure.
        if (authenticated && now() - openedAt >= 60_000) failures = 0
        authenticated = false; connected = false; syncing = false
        inFlight = null
        syncTimer?.let(scheduler::cancel); syncTimer = null
        socket?.cancel(); socket = null
        observer.disconnected()
        observer.error(code)
        if (!running) return
        val delay = (1_000L shl minOf(failures++, 6)).coerceAtMost(60_000L)
        arm((delay + (Math.random() * delay / 4).toLong()).coerceAtMost(60_000)) { connect() }
    }
    fun stop() {
        running = false; ++generation
        cancelTimer(); syncTimer?.let(scheduler::cancel); syncTimer = null
        connected = false; authenticated = false; syncing = false
        offers.clear(); accepted.clear(); inFlight = null
        socket?.cancel(); socket = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
    companion object {
        // Leave room under API Gateway's 32 KiB frame limit for the event envelope.
        const val MAX_WIRE_BYTES = 32 * 1024
        fun authenticationBytes(node: String, id: String, challenge: String) =
            "PLEXUS_SERVER_AUTH_V1|$node|$id|$challenge".toByteArray(Charsets.UTF_8)
    }
}
