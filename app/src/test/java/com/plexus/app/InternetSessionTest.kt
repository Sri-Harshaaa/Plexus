package com.plexus.app

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class InternetSessionTest {
    private class Clock : InternetSession.Scheduler {
        var now = 1_800_000_000_000L
        private val work = mutableListOf<Pair<Long, Runnable>>()
        override fun post(task: Runnable) = later(task, 0)
        @Synchronized override fun later(task: Runnable, delay: Long) { work.add(now + delay to task) }
        @Synchronized override fun cancel(task: Runnable) { work.removeAll { it.second === task } }
        fun run() {
            while (true) {
                val next = synchronized(this) { work.filter { it.first <= now }.minByOrNull { it.first }?.also { work.remove(it) } }
                    ?: return
                next.second.run()
            }
        }
        fun advance(delay: Long) { now += delay; run() }
        fun await(condition: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000
            while (!condition() && System.nanoTime() < end) { advance(5); Thread.sleep(5) }
            run(); assertTrue("Timed out", condition())
        }
    }
    private class Peer(private val node: String, private val key: java.security.PublicKey) : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        var socket: WebSocket? = null
        var acknowledge = true
        var validSignature = false
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text); received.add(json)
            when (json.getString("action")) {
                "authInit" -> event("auth:challenge", JSONObject().put("challengeId", CHALLENGE_ID).put("challenge", CHALLENGE))
                "authProof" -> {
                    validSignature = Signature.getInstance("SHA256withECDSA").run {
                        initVerify(key); update(InternetSession.authenticationBytes(node, CHALLENGE_ID, CHALLENGE))
                        verify(Base64.getDecoder().decode(json.getString("signature")))
                    }
                    event("auth:success", JSONObject().put("nodeId", node)); event("sync:complete", JSONObject())
                }
                "sendPacket", "deliveryProof" -> if (acknowledge) ack(json.getString("requestId"))
                "sync" -> event("sync:complete", JSONObject())
            }
        }
        fun ack(id: String) { socket!!.send(JSONObject().put("event", "internet:ack").put("requestId", id).put("data", JSONObject().put("accepted", true)).toString()) }
        fun event(name: String, data: JSONObject) { socket!!.send(JSONObject().put("event", name).put("data", data).toString()) }
    }
    private fun scenario(block: (InternetSession, Clock, Peer, MockWebServer, MutableList<String>) -> Unit) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val node = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded).joinToString("") { "%02x".format(it) }
        val clock = Clock(); val peer = Peer(node, pair.public); val events = mutableListOf<String>()
        val server = MockWebServer(); server.enqueue(MockResponse().withWebSocketUpgrade(peer)); server.start()
        val session = InternetSession(server.url("/").toString(), node, Base64.getEncoder().encodeToString(pair.public.encoded), {
            Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(it); sign() })
        }, clock, object : InternetSession.Observer {
            override fun authenticated() { events.add("authenticated") }
            override fun disconnected() { events.add("disconnected") }
            override fun event(name: String, packet: JSONObject) { events.add(name) }
        }, now = { clock.now })
        try { session.start(); clock.await { session.authenticated }; block(session, clock, peer, server, events) }
        finally { session.stop(); server.close() }
    }
    @Test fun standardWebSocketAuthenticatesWithExistingCanonicalEcdsaFormat() = scenario { _, _, peer, _, events ->
        assertTrue(peer.validSignature); assertEquals(1, events.count { it == "authenticated" })
        assertEquals("PLEXUS_SERVER_AUTH_V1|node|id|challenge", String(InternetSession.authenticationBytes("node", "id", "challenge")))
    }
    @Test fun persistenceAckDoesNotEmitDeliveryAndDuplicateOfferKeepsStableId() = scenario { session, clock, peer, _, events ->
        val packet = JSONObject().put("packetId", "stable-id")
        assertTrue(session.offer("message:id", "sendPacket", packet, clock.now + 100000))
        clock.await { peer.received.any { it.optString("action") == "sendPacket" } }
        clock.run()
        assertTrue(session.offer("message:id", "sendPacket", packet, clock.now + 100000))
        assertEquals(1, peer.received.count { it.optString("action") == "sendPacket" })
        assertEquals(listOf("authenticated"), events)
    }
    @Test fun missingPersistenceAckReconnectsAndRetainsSameLogicalPacket() = scenario { session, clock, peer, server, events ->
        peer.acknowledge = false
        session.offer("message:id", "sendPacket", JSONObject().put("packetId", "stable-id"), clock.now + 100000)
        clock.await { peer.received.any { it.optString("action") == "sendPacket" } }
        val original = peer.received.last().getString("requestId")
        // Second handshake uses the same test peer, simulating a new API Gateway connection.
        server.enqueue(MockResponse().withWebSocketUpgrade(peer)); peer.acknowledge = true
        clock.advance(20001); assertFalse(session.authenticated)
        clock.advance(2000); clock.await { events.count { it == "authenticated" } == 2 }
        clock.await { peer.received.count { it.optString("action") == "sendPacket" } == 2 }
        assertEquals(original, peer.received.last { it.optString("action") == "sendPacket" }.getString("requestId"))
    }
    @Test fun stopCancelsRetryAndLateCallbacksCannotResurrectSession() = scenario { session, clock, _, server, events ->
        session.stop(); val requests = server.requestCount; clock.advance(120000)
        assertFalse(session.connected); assertFalse(session.authenticated); assertEquals(requests, server.requestCount)
        assertEquals(1, events.count { it == "authenticated" })
    }
    @Test fun boundedQueueAndExpiredPacketRejectLeaveDurabilityToRoom() = scenario { session, clock, peer, _, _ ->
        peer.acknowledge = false
        assertFalse(session.offer("expired", "sendPacket", JSONObject(), clock.now))
        for (i in 0 until 256) assertTrue(session.offer("m:$i", "sendPacket", JSONObject().put("packetId", i), clock.now + 100000))
        assertFalse(session.offer("overflow", "sendPacket", JSONObject(), clock.now + 100000))
    }
    @Test fun syncContinuationAndIncomingDeliveryProofAreSeparateFromAcks() = scenario { session, clock, peer, _, events ->
        peer.event("sync:complete", JSONObject().put("cursor", "test-cursor"))
        clock.await { peer.received.any { it.optString("cursor") == "test-cursor" } }
        peer.event("internet:anti_packet", JSONObject().put("messageId", "proof-id"))
        clock.await { events.contains("internet:anti_packet") }
        assertTrue(session.authenticated)
    }
    @Test fun existingCanonicalControlAndProofBytesMatchLambdaVectors() {
        fun canonical(companion: Any, name: String, vararg args: Any): String {
            val method = companion.javaClass.declaredMethods.single { it.name == name }
            method.isAccessible = true
            return String(method.invoke(companion, *args) as ByteArray, Charsets.UTF_8)
        }
        assertEquals("PLEXUS_CONNECTION_REQUEST_V1|2:id|1:a|1:b|7:é|🪷|4:sign|5:agree|4:1000|4:2000|",
            canonical(ConnectionRequestPacket.Companion, "buildSigningData", "id", "a", "b", "é|🪷", "sign", "agree", 1000L, 2000L))
        assertEquals("PLEXUS_CONNECTION_RESPONSE_V1|2:id|5:reply|1:a|1:b|8:ACCEPTED|7:é|🪷|4:sign|5:agree|4:1000|4:2000|",
            canonical(ConnectionResponsePacket.Companion, "buildSigningData", "id", "reply", "a", "b", "ACCEPTED", "é|🪷", "sign", "agree", 1000L, 2000L))
        assertEquals("PLEXUS_DELIVERY_V1|id|a|b|1000|2000",
            canonical(NearbyAntiPacket.Companion, "buildSigningPayload", "id", "a", "b", 1000L, 2000L))
    }
    @Test fun proofsAndControlOutrankQueuedMessagesAndStaleAckCannotClearNewOffer() = scenario { session, clock, peer, _, events ->
        peer.acknowledge = false
        fun offer(key: String, type: String, action: String = "sendPacket") =
            session.offer(key, action, JSONObject().put("type", type).put("packetId", key), clock.now + 100000)
        offer("first", "mesh_message")
        clock.await { peer.received.count { it.optString("action") == "sendPacket" } == 1 }
        val firstId = peer.received.last().getString("requestId")
        offer("message", "mesh_message"); offer("control", "connection_request"); offer("proof", "delivery_anti_packet", "deliveryProof")
        peer.ack(firstId)
        clock.await { peer.received.any { it.optString("action") == "deliveryProof" } }
        val proofId = peer.received.last { it.optString("action") == "deliveryProof" }.getString("requestId")
        peer.ack(firstId); peer.event("test:marker", JSONObject())
        clock.await { events.contains("test:marker") }
        assertEquals(2, peer.received.count { it.has("packet") })
        peer.ack(proofId)
        clock.await { peer.received.count { it.has("packet") } == 3 }
        assertEquals("connection_request", peer.received.last().getJSONObject("packet").getString("type"))
    }
    @Test fun maximumCurrentChatTextFitsGatewayFrameIncludingEncryptionAndEnvelope() {
        // JSON escaping of control characters is larger than ordinary Unicode text.
        val plaintext = ChatWirePayloadCodec.encode("\u0000".repeat(2000), "00000000-0000-4000-8000-000000000000")
        val inner = JSONObject().put("version", 1).put("type", "encrypted_chat")
            .put("messageId", "00000000-0000-4000-8000-000000000000")
            .put("senderNodeId", "a".repeat(64)).put("recipientNodeId", "b".repeat(64)).put("timestamp", 1800000000000L)
            .put("nonce", Base64.getEncoder().encodeToString(ByteArray(12)))
            .put("ciphertext", Base64.getEncoder().encodeToString(ByteArray(plaintext.toByteArray().size + 16)))
        val packet = JSONObject().put("version", 1).put("type", "mesh_message").put("hopCount", 0)
            .put("packetId", inner.getString("messageId")).put("sourceNodeId", "a".repeat(64)).put("destinationNodeId", "b".repeat(64))
            .put("createdAt", 1800000000000L).put("expiresAt", 1800086400000L)
            .put("encryptedPayload", Base64.getEncoder().encodeToString(inner.toString().toByteArray()))
        val wire = JSONObject().put("action", "sendPacket").put("requestId", "x".repeat(36)).put("packet", packet)
        assertTrue(wire.toString().toByteArray().size < InternetSession.MAX_WIRE_BYTES - 512)
    }
    @Test fun historicalProofsAreBoundedAndDoNotStarveCurrentMessage() = scenario { session, clock, peer, _, _ ->
        peer.acknowledge = false
        for (i in 0 until 32) assertTrue(session.offer("history:$i", "deliveryProof", JSONObject(), clock.now + 100000, background = true))
        assertFalse(session.offer("overflow-history", "deliveryProof", JSONObject(), clock.now + 100000, background = true))
        assertTrue(session.offer("new-message", "sendPacket", JSONObject().put("type", "mesh_message"), clock.now + 100000))
        clock.await { peer.received.any { it.optString("action") == "deliveryProof" } }
        peer.ack(peer.received.last { it.optString("action") == "deliveryProof" }.getString("requestId"))
        clock.await { peer.received.any { it.optString("action") == "sendPacket" } }
        assertEquals(1, peer.received.count { it.optString("action") == "deliveryProof" })
    }
    companion object {
        private const val CHALLENGE_ID = "00000000-0000-4000-8000-000000000001"
        private val CHALLENGE = "A".repeat(43)
    }
}
