package com.plexus.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/** Internet-only adapter; original sender identity is verified by the signed mesh envelope. */
class InternetManager(
    private val identity: PlexusIdentity,
    private val serverUrl: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected() {}
        fun onAuthenticated() {}
        fun onRetryPending() {}
        fun onSyncComplete(busy: Boolean) {}
        fun onDisconnected() {}
        fun onMeshPacketReceived(packet: NearbyMeshPacket) {}
        fun onAntiPacketReceived(packet: NearbyAntiPacket) {}
        fun onAntiPacketAcknowledged(packet: NearbyAntiPacket) {}
        fun onConnectionRequestReceived(packet: ConnectionRequestPacket) {}
        fun onConnectionResponseReceived(packet: ConnectionResponsePacket) {}
        fun onError(message: String, exception: Exception? = null) {}
    }
    private val handler = Handler(Looper.getMainLooper())
    private val session = InternetSession(serverUrl, identity.nodeId, identity.signingPublicKey,
        { IdentityCrypto.sign(it, identity.nodeId) }, object : InternetSession.Scheduler {
            override fun post(task: Runnable) { handler.post(task) }
            override fun later(task: Runnable, delay: Long) { handler.postDelayed(task, delay) }
            override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
        }, object : InternetSession.Observer {
            override fun connected() { listener.onConnected() }
            override fun authenticated() { listener.onAuthenticated() }
            override fun disconnected() { listener.onDisconnected() }
            override fun error(code: String) {
                if (code == "STALE_SYNC_RECOVERY" || code == "STALE_SOCKET_RECOVERY") Log.d(TAG, code)
                else Log.w(TAG, code)
                listener.onError(code)
            }
            override fun syncComplete(busy: Boolean) { listener.onSyncComplete(busy) }
            override fun proofAcknowledged(packet: JSONObject) {
                NearbyAntiPacket.fromBytes(packet.toString().toByteArray(Charsets.UTF_8))
                    ?.let(listener::onAntiPacketAcknowledged)
            }
            override fun event(name: String, packet: JSONObject) {
                val bytes = packet.toString().toByteArray(Charsets.UTF_8)
                when (name) {
                    "internet:packet" -> NearbyMeshPacket.fromBytes(bytes)?.takeIf {
                        it.destinationNodeId == identity.nodeId && !it.isExpired()
                    }?.let {
                        listener.onMeshPacketReceived(it)
                    }
                    "internet:anti_packet" -> NearbyAntiPacket.fromBytes(bytes)?.takeIf {
                        !it.isExpired()
                    }?.let {
                        listener.onAntiPacketReceived(it)
                    }
                    "internet:connection_request" -> ConnectionRequestPacket.fromBytes(bytes)?.takeIf {
                        it.recipientNodeId == identity.nodeId
                    }?.let(listener::onConnectionRequestReceived)
                    "internet:connection_response" -> ConnectionResponsePacket.fromBytes(bytes)?.takeIf {
                        it.requesterNodeId == identity.nodeId
                    }?.let(listener::onConnectionResponseReceived)
                }
            }
        })
    fun start() {
        if (!serverUrl.startsWith("wss://")) {
            listener.onError("Secure Internet endpoint is not configured")
            return
        }
        session.start()
    }
    fun sendMeshPacket(packet: NearbyMeshPacket): Boolean = (!packet.isExpired() && packet.originSignature.isNotBlank() &&
        offer("message:${packet.packetId}", "sendPacket", packet.toBytes(), packet.expiresAt))
    fun sendAntiPacket(packet: NearbyAntiPacket, background: Boolean = false): Boolean = (packet.recipientNodeId == identity.nodeId &&
        offer("proof:${packet.messageId}", "deliveryProof", packet.toBytes(), packet.expiresAt, background))
    fun sendConnectionRequest(packet: ConnectionRequestPacket): Boolean =
        packet.senderNodeId == identity.nodeId && packet.verify() &&
            offer("request:${packet.requestId}", "sendPacket", packet.toBytes(), packet.expiresAt)
    fun sendConnectionResponse(packet: ConnectionResponsePacket): Boolean =
        packet.responderNodeId == identity.nodeId && packet.verify() &&
            offer("response:${packet.requestId}:${packet.response}", "sendPacket", packet.toBytes(), packet.expiresAt)
    private fun offer(key: String, action: String, bytes: ByteArray, expires: Long, background: Boolean = false): Boolean =
        session.offer(key, action, JSONObject(bytes.toString(Charsets.UTF_8)), expires, background)
    fun isConnected() = session.connected
    fun isAuthenticated() = session.authenticated
    fun retryPending() {
        if (!session.authenticated) return
        listener.onRetryPending()
        session.requestSync()
    }
    fun stopGatewayOffers() { session.stopGatewayOffers() }
    fun wakeForSync() { session.wakeForSync() }
    fun setPeerBlocked(peer: String, blocked: Boolean, revision: Long, background: Boolean = false): Boolean = session.setPeerBlocked(peer, blocked, revision, background)
    fun discardBlockedPacket(key: String, peer: String): Boolean = session.discardBlockedPacket(key, peer)
    fun registerPushToken(token: String): Boolean = session.registerPushToken(token)
    fun stop() { session.stop(); handler.removeCallbacksAndMessages(null) }
    companion object { private const val TAG = "PlexusInternet" }
}
