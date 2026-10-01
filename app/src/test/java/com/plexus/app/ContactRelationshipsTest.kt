package com.plexus.app

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class ContactRelationshipsTest {
    private lateinit var db: PlexusDatabase
    private lateinit var relationships: ContactRelationships
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val key = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val peer = MessageDigest.getInstance("SHA-256").digest(keys.public.encoded).joinToString("") { "%02x".format(it) }
    private val localKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val local = MessageDigest.getInstance("SHA-256").digest(localKeys.public.encoded).joinToString("") { "%02x".format(it) }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PlexusDatabase::class.java)
            .allowMainThreadQueries().build()
        relationships = ContactRelationships(db)
    }
    @After fun close() { db.close() }

    private fun request(fromLocal: Boolean = false, requestId: String = UUID.randomUUID().toString()): ConnectionRequestPacket {
        val now = System.currentTimeMillis()
        val signer = if (fromLocal) localKeys else keys
        val signingKey = Base64.getEncoder().encodeToString(signer.public.encoded)
        val source = if (fromLocal) local else peer
        val destination = if (fromLocal) peer else local
        val packet = ConnectionRequestPacket(requestId, source, destination, "Peer", signingKey, signingKey, now, now + 60_000, "")
        val fields = listOf(packet.requestId, source, destination, "Peer", signingKey, signingKey, now.toString(), packet.expiresAt.toString())
        val canonical = "PLEXUS_CONNECTION_REQUEST_V1|" + fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it|" }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signer.private); update(canonical.toByteArray(Charsets.UTF_8)); sign()
        }
        return packet.copy(signature = Base64.getEncoder().encodeToString(signature))
    }

    private fun declinedResponse(request: ConnectionRequestPacket): ConnectionResponsePacket {
        val now = System.currentTimeMillis()
        val packet = ConnectionResponsePacket(request.requestId, UUID.randomUUID().toString(), local, peer,
            ConnectionResponsePacket.RESPONSE_DECLINED, "Peer", key, key, now, now + 60_000, "")
        val fields = listOf(packet.requestId, packet.responseId, local, peer, packet.response, "Peer", key, key,
            now.toString(), packet.expiresAt.toString())
        val canonical = "PLEXUS_CONNECTION_RESPONSE_V1|" + fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it|" }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(canonical.toByteArray(Charsets.UTF_8)); sign()
        }
        return packet.copy(signature = Base64.getEncoder().encodeToString(signature))
    }

    private suspend fun rescanAfterRemoteBlockUnblock(deliverClosure: Boolean) {
        val receiverDb = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PlexusDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val receiver = ContactRelationships(receiverDb)
            // Use the same identity parser, initiation guard, storage and response handler as QR import/runtime.
            val qr = org.json.JSONObject().put("version", 1).put("name", "Peer").put("nodeId", peer)
                .put("signingPublicKey", key).put("agreementPublicKey", key).toString()
            val imported = requireNotNull(IdentityManager.parseAndValidateContact(qr))
            val old = request(fromLocal = true)
            assertTrue(relationships.queueOutgoingRequest(old, imported))
            assertTrue(receiver.receiveRequest(requireNotNull(ConnectionRequestPacket.fromBytes(old.toBytes())), peer))
            val closed = receiver.block(local, peer)!!.toPacket()
            assertEquals(old.requestId, closed.requestId)
            val decline = requireNotNull(ConnectionResponsePacket.fromBytes(declinedResponse(closed).toBytes()))
            if (deliverClosure) {
                assertFalse(relationships.receiveResponse(decline.copy(signature = "AAAA"), local))
                assertTrue(relationships.receiveResponse(decline, local))
                assertEquals(ContactRelationshipState.DECLINED, db.contactDao().getContactById(peer)!!.relationshipState)
                assertNull(db.contactDao().getContactById(peer)!!.pendingRequestId)
            } else assertEquals(ContactRelationshipState.OUTGOING_PENDING, db.contactDao().getContactById(peer)!!.relationshipState)
            receiver.unblock(local)
            assertEquals(ContactRelationshipState.NONE, receiverDb.contactDao().getContactById(local)!!.relationshipState)
            assertFalse(receiver.receiveRequest(old, peer))
            assertTrue(ContactRelationshipState.canInitiateRequest(db.contactDao().getContactById(peer)!!.relationshipState))
            val fresh = request(fromLocal = true)
            assertNotEquals(old.requestId, fresh.requestId)
            assertTrue(relationships.queueOutgoingRequest(fresh, imported))
            assertFalse(relationships.queueOutgoingRequest(old, imported))
            assertFalse(relationships.receiveResponse(decline, local)) // Delayed closure cannot undo the NEW request.
            assertTrue(receiver.receiveRequest(requireNotNull(ConnectionRequestPacket.fromBytes(fresh.toBytes())), peer))
            assertFalse(receiver.receiveRequest(old, peer))
            assertEquals(fresh.requestId, db.contactDao().getContactById(peer)!!.pendingRequestId)
            assertEquals(ContactRelationshipState.OUTGOING_PENDING, db.contactDao().getContactById(peer)!!.relationshipState)
            assertEquals(fresh.requestId, receiverDb.contactDao().getContactById(local)!!.pendingRequestId)
            assertEquals(ContactRelationshipState.INCOMING_PENDING, receiverDb.contactDao().getContactById(local)!!.relationshipState)
            assertEquals(listOf(fresh.requestId), db.connectionRequestDao().getOutgoingRetryCandidates(local).map { it.requestId })
            assertNotNull(db.connectionRequestDao().getRequestById(old.requestId))
            assertNotNull(receiverDb.connectionRequestDao().getRequestById(old.requestId))
        } finally { receiverDb.close() }
    }

    @Test fun senderReceivesSignedBlockClosureThenQrRescanReachesUnblockedPeerWithNewId() = runBlocking {
        rescanAfterRemoteBlockUnblock(deliverClosure = true)
    }

    @Test fun senderQrRescanRecoversEvenWhenBlockClosureIsDelayed() = runBlocking {
        rescanAfterRemoteBlockUnblock(deliverClosure = false)
    }

    @Test fun declinePermitsFreshRequestButRejectsOldSignedReplay() = runBlocking {
        val old = request()
        assertTrue(relationships.receiveRequest(old, local))
        assertTrue(relationships.decideIncoming(peer, old.requestId, accepted = false))
        assertFalse(relationships.receiveRequest(old, local))
        val fresh = request()
        assertNotEquals(old.requestId, fresh.requestId)
        assertTrue(relationships.receiveRequest(fresh, local))
        val current = db.contactDao().getContactById(peer)!!
        assertEquals(ContactRelationshipState.INCOMING_PENDING, current.relationshipState)
        assertEquals(fresh.requestId, current.pendingRequestId)
    }

    @Test fun blockPendingRequesterIgnoresRequestsAndUnblockClearsActiveRelationship() = runBlocking {
        val old = request()
        assertTrue(relationships.receiveRequest(old, local))
        relationships.block(peer, local)
        assertFalse(relationships.receiveRequest(request(), local))
        assertEquals(1, db.contactDao().getBlockedContacts().first().size)
        relationships.unblock(peer)
        val cleared = db.contactDao().getContactById(peer)!!
        assertEquals(ContactRelationshipState.NONE, cleared.relationshipState)
        assertNull(cleared.pendingRequestId)
        assertTrue(db.contactDao().getAllContacts().first().isEmpty())
        assertFalse(relationships.receiveRequest(old, local))
        assertTrue(relationships.receiveRequest(request(), local))
    }

    @Test fun blockingConnectedContactPreservesHistoryAndSuppressesFutureMessages() = runBlocking {
        val packet = request()
        relationships.receiveRequest(packet, local)
        db.contactDao().updateRelationshipState(peer, ContactRelationshipState.CONNECTED, null)
        val old = MessageEntity("old", peer, "local history", false, System.currentTimeMillis())
        assertEquals(true, relationships.persistIncoming(old))
        relationships.block(peer, local)
        assertTrue(db.contactDao().getAllContacts().first().isEmpty())
        assertNull(relationships.persistIncoming(old.copy(messageId = "blocked-new")))
        assertEquals(listOf("old"), db.messageDao().getRecentMessagesForContact(peer, 10).map { it.messageId })
        db.contactDao().updateRelationshipState(peer, ContactRelationshipState.CONNECTED, packet.requestId)
        db.contactDao().markConnected(peer, "Renamed")
        db.contactDao().markDeclined(peer)
        assertEquals(ContactRelationshipState.BLOCKED_CONNECTED, db.contactDao().getContactById(peer)!!.relationshipState)
        relationships.block(peer, local) // Repeated Block cannot lose the connected provenance.
        relationships.unblock(peer)
        assertEquals(ContactRelationshipState.CONNECTED, db.contactDao().getContactById(peer)!!.relationshipState)
        assertTrue(db.conversationStateDao().getHiddenContactIds().first().contains(peer))
        assertEquals(true, relationships.persistIncoming(old.copy(messageId = "after-unblock")))
        assertFalse(db.conversationStateDao().getHiddenContactIds().first().contains(peer))
        assertEquals(2, db.messageDao().getRecentMessagesForContact(peer, 10).size)
    }

    @Test fun previouslyConnectedQrReopensExistingChatWithoutARequest() = runBlocking {
        val req = request()
        relationships.receiveRequest(req, local)
        relationships.decideIncoming(peer, req.requestId, accepted = true)
        relationships.block(peer, local); relationships.unblock(peer)
        val contact = db.contactDao().getContactById(peer)!!
        assertTrue(db.conversationStateDao().getHiddenContactIds().first().contains(peer))
        assertEquals(contact, relationships.reopenTrustedContact(contact))
        assertFalse(db.conversationStateDao().getHiddenContactIds().first().contains(peer))
        assertEquals(1, db.connectionRequestDao().countRequests())
        assertNull(relationships.reopenTrustedContact(contact.copy(agreementPublicKey = "wrong-key")))
    }

    @Test fun untrustedUnblockCannotUseTrustedQrShortcutButCanMakeFreshRequest() = runBlocking {
        val old = request()
        relationships.receiveRequest(old, local); relationships.block(peer, local); relationships.unblock(peer)
        val contact = db.contactDao().getContactById(peer)!!
        assertNull(relationships.reopenTrustedContact(contact))
        assertTrue(ContactRelationshipState.canRequest(contact.relationshipState))
        assertFalse(relationships.receiveRequest(old, local))
        assertTrue(relationships.receiveRequest(request(), local))
    }

    @Test fun scanningUntrustedUnblockedIdentityQueuesFreshSignedOutgoingRequest() = runBlocking {
        val old = request()
        relationships.receiveRequest(old, local); relationships.block(peer, local); relationships.unblock(peer)
        val imported = PlexusContactIdentity("Peer", peer, key, key)
        assertFalse(relationships.queueOutgoingRequest(request(fromLocal = true, requestId = old.requestId), imported))
        val fresh = request(fromLocal = true)
        assertTrue(relationships.queueOutgoingRequest(fresh, imported))
        val current = db.contactDao().getContactById(peer)!!
        assertEquals(ContactRelationshipState.OUTGOING_PENDING, current.relationshipState)
        assertEquals(fresh.requestId, current.pendingRequestId)
        assertEquals(listOf(fresh.requestId), db.connectionRequestDao().getOutgoingRetryCandidates(local).map { it.requestId })
    }

    @Test fun connectedPeerCanRenewFreshPinnedHandshakeWithoutBecomingPending() = runBlocking {
        val original = request()
        relationships.receiveRequest(original, local); relationships.decideIncoming(peer, original.requestId, accepted = true)
        val fresh = request()
        assertTrue(relationships.receiveRequest(fresh, local))
        val current = db.contactDao().getContactById(peer)!!
        assertEquals(ContactRelationshipState.CONNECTED, current.relationshipState)
        assertEquals(fresh.requestId, current.pendingRequestId) // Existing response retry sends a signed ACCEPTED.
        assertFalse(relationships.receiveRequest(fresh, local))
        relationships.block(peer, local)
        assertFalse(relationships.receiveRequest(request(), local))
    }

    @Test fun freshMessageAfterTrustedUnblockSupportsNormalSignedProofAndDedup() = runBlocking {
        val req = request()
        relationships.receiveRequest(req, local); relationships.decideIncoming(peer, req.requestId, accepted = true)
        relationships.block(peer, local); relationships.unblock(peer)
        val now = System.currentTimeMillis()
        val message = MessageEntity(UUID.randomUUID().toString(), peer, "test message", false, now)
        assertEquals(true, relationships.persistIncoming(message))
        assertEquals(false, relationships.persistIncoming(message))
        val expires = now + 86_400_000L
        val canonical = "PLEXUS_DELIVERY_V1|${message.messageId}|$peer|$local|$now|$expires"
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(localKeys.private); update(canonical.toByteArray(Charsets.UTF_8)); sign()
        }
        val proof = NearbyAntiPacket(message.messageId, peer, local,
            Base64.getEncoder().encodeToString(localKeys.public.encoded), now, expires,
            Base64.getEncoder().encodeToString(signature))
        val store = AntiPacketStore(db, local)
        assertEquals(AntiPacketStore.StoreResult.STORED, store.store(proof, locallyCreated = true))
        assertEquals(AntiPacketStore.StoreResult.DUPLICATE, store.store(proof, locallyCreated = true))
    }

    @Test fun messageRacingTrustedUnblockCanBeRetriedWithoutLossOrDuplication() = runBlocking {
        val req = request()
        relationships.receiveRequest(req, local); relationships.decideIncoming(peer, req.requestId, accepted = true)
        relationships.block(peer, local)
        val message = MessageEntity(UUID.randomUUID().toString(), peer, "race", false, System.currentTimeMillis())
        val ingress = async(Dispatchers.Default) { relationships.persistIncoming(message) }
        val unblock = async(Dispatchers.Default) { relationships.unblock(peer) }
        unblock.await(); ingress.await()
        assertNotNull(relationships.persistIncoming(message))
        assertEquals(listOf(message.messageId), db.messageDao().getRecentMessagesForContact(peer, 10).map { it.messageId })
        assertFalse(db.conversationStateDao().getHiddenContactIds().first().contains(peer))
    }

    @Test fun outgoingRetryRequiresCurrentRequestAndPendingRelationship() = runBlocking {
        val incoming = request()
        relationships.receiveRequest(incoming, local)
        val outgoing = ConnectionRequestEntity.fromPacket(incoming).copy(senderNodeId = local, recipientNodeId = peer)
        db.connectionRequestDao().deleteRequest(incoming.requestId)
        db.connectionRequestDao().insertRequest(outgoing)
        db.contactDao().updateRelationshipState(peer, ContactRelationshipState.OUTGOING_PENDING, outgoing.requestId)
        assertEquals(1, db.connectionRequestDao().getOutgoingRetryCandidates(local).size)
        db.contactDao().markDeclined(peer)
        assertTrue(db.connectionRequestDao().getOutgoingRetryCandidates(local).isEmpty())
        relationships.block(peer, local)
        assertTrue(db.connectionRequestDao().getOutgoingRetryCandidates(local).isEmpty())
    }

    @Test fun staleAcceptDeclineCannotUndoBlockOrDecideANewerRequest() = runBlocking {
        val old = request()
        relationships.receiveRequest(old, local)
        relationships.block(peer, local)
        assertFalse(relationships.decideIncoming(peer, old.requestId, accepted = true))
        assertFalse(relationships.decideIncoming(peer, old.requestId, accepted = false))
        relationships.unblock(peer)
        val fresh = request()
        relationships.receiveRequest(fresh, local)
        assertFalse(relationships.decideIncoming(peer, old.requestId, accepted = true))
        assertTrue(relationships.decideIncoming(peer, fresh.requestId, accepted = true, displayName = "Alias"))
        assertEquals("Alias", db.contactDao().getAllContacts().first().single().displayName)
    }

    @Test fun deliveredUpdateCountPreservesRecipientMineAndReadGuards() = runBlocking {
        val dao = db.messageDao()
        val outgoing = MessageEntity("outgoing", peer, "test", true, System.currentTimeMillis())
        val incoming = outgoing.copy(messageId = "incoming", isMine = false)
        val read = outgoing.copy(messageId = "read", deliveryStatus = MessageDeliveryStatus.READ)
        for (message in listOf(outgoing, incoming, read)) dao.insertMessage(message)
        assertEquals(0, dao.markMessageDelivered(outgoing.messageId, "other-recipient"))
        assertEquals(1, dao.markMessageDelivered(outgoing.messageId, peer))
        assertEquals(MessageDeliveryStatus.DELIVERED, dao.getMessageById(outgoing.messageId)!!.deliveryStatus)
        assertEquals(0, dao.markMessageDelivered(incoming.messageId, peer))
        assertEquals(0, dao.markMessageDelivered(read.messageId, peer))
        assertEquals(MessageDeliveryStatus.READ, dao.getMessageById(read.messageId)!!.deliveryStatus)
        assertNotNull(dao.getMessageById(incoming.messageId))
    }

    @Test fun forgedAndMismatchedPinnedAgreementKeysCannotReplaceRelationship() = runBlocking {
        val packet = request()
        assertFalse(relationships.receiveRequest(packet.copy(signature = "AAAA"), local))
        assertTrue(relationships.receiveRequest(packet, local))
        db.contactDao().markDeclined(peer)
        val contact = db.contactDao().getContactById(peer)!!
        db.contactDao().deleteContactById(peer)
        db.contactDao().insertContact(contact.copy(agreementPublicKey = "different-pinned-key"))
        assertFalse(relationships.receiveRequest(request(), local))
    }
}
