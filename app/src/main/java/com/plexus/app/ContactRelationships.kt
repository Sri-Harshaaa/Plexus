package com.plexus.app

import androidx.room.withTransaction

/** Atomic relationship decisions shared by ingress and UI; message history is never removed here. */
internal class ContactRelationships(private val database: PlexusDatabase) {
    private val contacts = database.contactDao()
    private val requests = database.connectionRequestDao()

    suspend fun receiveRequest(packet: ConnectionRequestPacket, localNodeId: String): Boolean {
        if (packet.recipientNodeId != localNodeId || !packet.verify()) return false
        return database.withTransaction {
            requests.deleteExpiredRequests()
            val existing = contacts.getContactById(packet.senderNodeId)
            val connected = existing?.relationshipState == ContactRelationshipState.CONNECTED
            if ((!connected && !ContactRelationshipState.canRequest(existing?.relationshipState)) ||
                (existing != null && (existing.signingPublicKey != packet.senderSigningPublicKey ||
                    existing.agreementPublicKey != packet.senderAgreementPublicKey)) ||
                (!connected && contacts.countIncomingPending() >= 256)) return@withTransaction false
            if (requests.insertRequest(ConnectionRequestEntity.fromPacket(packet)) == -1L) return@withTransaction false
            if (connected) {
                // The requester may have lost/cleared its relationship. Renew its signed handshake
                // against our existing pinned trust, without downgrading our contact to pending.
                contacts.updateRelationshipState(packet.senderNodeId, ContactRelationshipState.CONNECTED, packet.requestId)
                return@withTransaction true
            }
            if (existing == null) contacts.insertContact(ContactEntity(
                nodeId = packet.senderNodeId, displayName = packet.senderDisplayName,
                signingPublicKey = packet.senderSigningPublicKey, agreementPublicKey = packet.senderAgreementPublicKey,
                announcedDisplayName = packet.senderDisplayName,
                relationshipState = ContactRelationshipState.INCOMING_PENDING, pendingRequestId = packet.requestId
            )) else {
                contacts.updateAnnouncedDisplayName(packet.senderNodeId, packet.senderDisplayName)
                contacts.updateRelationshipState(packet.senderNodeId, ContactRelationshipState.INCOMING_PENDING, packet.requestId)
            }
            true
        }
    }

    suspend fun queueOutgoingRequest(packet: ConnectionRequestPacket, identity: PlexusContactIdentity): Boolean {
        if (packet.recipientNodeId != identity.nodeId || !packet.verify()) return false
        return database.withTransaction {
            val current = contacts.getContactById(identity.nodeId)
            if (!ContactRelationshipState.canInitiateRequest(current?.relationshipState) ||
                (current != null && (current.signingPublicKey != identity.signingPublicKey ||
                    current.agreementPublicKey != identity.agreementPublicKey))) return@withTransaction false
            if (requests.insertRequest(ConnectionRequestEntity.fromPacket(packet)) == -1L) return@withTransaction false
            if (current == null) contacts.insertContact(ContactEntity(
                nodeId = identity.nodeId, displayName = identity.displayName,
                signingPublicKey = identity.signingPublicKey, agreementPublicKey = identity.agreementPublicKey,
                announcedDisplayName = identity.displayName,
                relationshipState = ContactRelationshipState.OUTGOING_PENDING, pendingRequestId = packet.requestId
            )) else contacts.updateRelationshipState(current.nodeId, ContactRelationshipState.OUTGOING_PENDING, packet.requestId)
            true
        }
    }

    suspend fun receiveResponse(packet: ConnectionResponsePacket, localNodeId: String): Boolean {
        if (packet.requesterNodeId != localNodeId || !packet.verify()) return false
        return database.withTransaction {
            val original = requests.getRequestById(packet.requestId) ?: return@withTransaction false
            val current = contacts.getContactById(packet.responderNodeId) ?: return@withTransaction false
            if (original.senderNodeId != localNodeId || original.recipientNodeId != packet.responderNodeId ||
                current.relationshipState != ContactRelationshipState.OUTGOING_PENDING ||
                current.pendingRequestId != packet.requestId ||
                current.signingPublicKey != packet.responderSigningPublicKey ||
                current.agreementPublicKey != packet.responderAgreementPublicKey) return@withTransaction false
            if (packet.isAccepted()) {
                contacts.updateAnnouncedDisplayName(current.nodeId, packet.responderDisplayName)
                contacts.updateRelationshipState(current.nodeId, ContactRelationshipState.ACCEPTED_NEEDS_NAME, null)
            } else contacts.markDeclined(current.nodeId)
            // Keep the old request row for replay protection; only its active relationship is closed.
            true
        }
    }

    suspend fun decideIncoming(nodeId: String, requestId: String, accepted: Boolean, displayName: String? = null): Boolean = database.withTransaction {
        val current = contacts.getContactById(nodeId)
        if (current?.relationshipState != ContactRelationshipState.INCOMING_PENDING || current.pendingRequestId != requestId) return@withTransaction false
        if (accepted && displayName != null) contacts.updateDisplayName(nodeId, displayName)
        contacts.updateRelationshipState(nodeId,
            if (accepted) ContactRelationshipState.CONNECTED else ContactRelationshipState.DECLINED, requestId)
        true
    }

    suspend fun block(nodeId: String, localNodeId: String) = database.withTransaction {
        val current = contacts.getContactById(nodeId) ?: return@withTransaction null
        if (ContactRelationshipState.isBlocked(current.relationshipState)) return@withTransaction null
        if (current.relationshipState == ContactRelationshipState.CONNECTED) {
            database.conversationStateDao().setConversationHidden(nodeId, true)
        }
        // Close only the already-visible incoming request. Future requests stay ignored.
        val declinedRequestId = current.pendingRequestId?.takeIf { current.relationshipState == ContactRelationshipState.INCOMING_PENDING }
        contacts.blockContact(nodeId, declinedRequestId)
        database.relayPacketDao().deleteOwnPacketsForPeer(localNodeId, nodeId)
        declinedRequestId?.let { requests.getRequestById(it) }
    }

    suspend fun unblock(nodeId: String) { contacts.unblockContact(nodeId) }

    suspend fun reopenTrustedContact(contact: ContactEntity): ContactEntity? = database.withTransaction {
        val current = contacts.getContactById(contact.nodeId)
        if (current?.relationshipState != ContactRelationshipState.CONNECTED ||
            current.signingPublicKey != contact.signingPublicKey || current.agreementPublicKey != contact.agreementPublicKey) null
        else {
            database.conversationStateDao().setConversationHidden(current.nodeId, false)
            current
        }
    }

    /** null means suppressed, false means an already persisted valid copy. */
    suspend fun persistIncoming(message: MessageEntity): Boolean? = database.withTransaction {
        if (message.isMine || !ContactRelationshipState.canReceiveMessages(contacts.getContactById(message.contactNodeId)?.relationshipState)) null
        else {
            val inserted = database.messageDao().insertMessage(message) != -1L
            if (inserted) database.conversationStateDao().unhideConversation(message.contactNodeId)
            inserted
        }
    }
}
