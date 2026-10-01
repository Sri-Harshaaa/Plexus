package com.plexus.app

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the existing packet handlers and both live transports for one service lifetime.
 * Transport callbacks stay on Main, matching NearbyManager and InternetSession state confinement.
 */
internal class PlexusTransportRuntime(
    private val context: Context,
    private val identity: PlexusIdentity,
    private val database: PlexusDatabase
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contactDao = database.contactDao()
    private val contactRelationships = ContactRelationships(database)
    private val messageDao = database.messageDao()
    private val antiPacketDao = database.antiPacketDao()
    private val connectionRequestDao = database.connectionRequestDao()
    private val conversationStateDao = database.conversationStateDao()
    private val incomingMessageNotifier = IncomingMessageNotifier(context)
    private val meshRelayStore = MeshRelayStore(database, identity)
    private val antiPacketStore = AntiPacketStore(database, identity.nodeId)
    private val relationshipsHolder = arrayOf<List<ContactEntity>>(emptyList())
    private val contactsHolder = arrayOf<List<ContactEntity>>(emptyList())
    private val internetSyncJob = arrayOfNulls<Job>(1)
    private lateinit var internetManager: InternetManager
    private lateinit var nearbyManager: NearbyManager
    private var started = false
    private var networkParticipationStarted = false
    private var nearbyRetryJob: Job? = null
    private var internetRetryJob: Job? = null
    private val pendingSyncCallbacks = linkedMapOf<String, (Boolean) -> Unit>()
    private val proofPublicationOutbox by lazy {
        ProofPublicationOutbox(antiPacketDao, identity.nodeId,
            CoroutineScope(coroutineScope.coroutineContext + Dispatchers.Main.immediate)) { proof, background ->
            PlexusDiagnostics.proofOffer("INTERNET") { internetManager.sendAntiPacket(proof, background) }
        }
    }
    private val internetIngressJobs = mutableSetOf<Job>()
    private val peerPolicyVersions = mutableMapOf<String, Pair<Boolean, Long>>()
    private var initialized = false
    private var stopping = false
    @Volatile
    private var visibleChatContactNodeId: String? = null

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Transport state must be accessed on the main looper"
        }
    }

    fun setVisibleChat(contactNodeId: String?) {
        requireMainThread()
        visibleChatContactNodeId = contactNodeId
        if (contactNodeId != null) incomingMessageNotifier.cancelConversation(contactNodeId)
    }

    private suspend fun notifyIncomingMessageIfNeeded(
        contact: ContactEntity,
        messageInserted: Boolean
    ) {
        if (!messageInserted) return
        // Serialize posting with Block's Room transaction. If posting wins, Block cancels it;
        // if Block wins, no delayed ingress job may recreate the notification.
        database.withTransaction {
            if (!ContactRelationshipState.canReceiveMessages(contactDao.getContactById(contact.nodeId)?.relationshipState) ||
                !shouldNotifyIncomingMessage(visibleChatContactNodeId, contact.nodeId)) return@withTransaction
            incomingMessageNotifier.notifyConversation(
                contact,
                messageDao.getRecentMessagesForContact(contact.nodeId, 5).asReversed(),
                visibleChatContactNodeId
            )
        }
    }

    init {
                    val handleIncomingConnectionRequest:
                            suspend (
                                ConnectionRequestPacket,
                                String
                            ) -> Unit =
                        requestHandler@ { packet, transport ->


                            if (
                                packet.recipientNodeId !=
                                identity.nodeId
                            ) {

                                Log.e(
                                    "PlexusContact",
                                    "$transport REQUEST REJECTED - WRONG RECIPIENT -> " +
                                            packet.recipientNodeId.take(8)
                                )

                                return@requestHandler
                            }


                            /*
                         * nodeId is the relationship dedup key.
                         *
                         * A fresh request may restart a declined/cleared relationship.
                         * Pinned keys and durable request IDs still prevent replacement/replay.
                         */
                            if (!packet.verify()) return@requestHandler
                            if (ContactRelationshipState.isBlocked(contactDao.getContactById(packet.senderNodeId)?.relationshipState)) {
                                if (transport == "INTERNET") discardBlocked(packet.senderNodeId,
                                    "1#${packet.createdAt.toString().padStart(13, '0')}#CONNECTION_REQUEST#${packet.requestId}")
                                return@requestHandler
                            }
                            val stored = contactRelationships.receiveRequest(packet, identity.nodeId)
                            if (!stored) {
                                Log.d("PlexusContact", "$transport REQUEST DEFERRED - DUPLICATE OR PENDING LIMIT")
                                return@requestHandler
                            }
                            if (contactDao.getContactById(packet.senderNodeId)?.relationshipState == ContactRelationshipState.CONNECTED) {
                                try {
                                    val response = withContext(Dispatchers.IO) {
                                        ConnectionResponsePacket.createAccepted(packet, identity)
                                    }
                                    // Recheck after signing: a concurrent Block must still win.
                                    if (contactDao.getContactById(packet.senderNodeId)?.relationshipState == ContactRelationshipState.CONNECTED) {
                                        internetManager.sendConnectionResponse(response)
                                        if (networkParticipationStarted) nearbyManager.sendBytesToNode(
                                            packet.senderNodeId, response.toBytes(), NearbyManager.SendPriority.CONTROL)
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    Log.w("PlexusContact", "CONNECTION_RESPONSE_DEFERRED")
                                }
                            }

                            Log.d(
                                "PlexusContact",
                                "$transport CONNECTION REQUEST STORED ✅ -> " +
                                        packet.senderNodeId.take(8) +
                                        " | request=" +
                                        packet.requestId.take(8)
                            )
                        }


                    val handleIncomingConnectionResponse:
                            suspend (
                                ConnectionResponsePacket,
                                String
                            ) -> Unit =
                        responseHandler@ { packet, transport ->
                            val processed = contactRelationships.receiveResponse(packet, identity.nodeId)
                            Log.d("PlexusContact", if (processed) {
                                if (packet.isAccepted()) "CONNECTION_ACCEPTED" else "CONNECTION_DECLINED"
                            } else "CONNECTION_RESPONSE_IGNORED")
                        }


                    /*
                 * Callbacks need the already-created manager while the listener
                 * is being constructed.
                 */
                    val nearbyManagerHolder =
                        arrayOfNulls<NearbyManager>(
                                1
                            )


                    /*
                 * nodeId -> derived 32-byte AES key
                 *
                 * The key is derived only after the peer has passed the
                 * signed identity challenge. The current derivation uses
                 * long-term ECDH identity keys, so it can be deterministically
                 * regenerated when needed.
                 */
                    val peerEncryptionKeys =
                        mutableMapOf<String, ByteArray>()


                    /*
                 * Temporary Nearby endpoint -> announced Plexus identity.
                 *
                 * This is intentionally separate from Contacts:
                 *
                 * - saved contact: a person we explicitly added through QR/link
                 * - authenticated relay: a valid Plexus node that proved its
                 *   signing key, but is NOT automatically added as a contact
                 */
                    val announcedPeerIdentities =
                        mutableMapOf<String, NearbyIdentityPacket>()


                    /*
                 * In-memory fast replay/TTL guard.
                 *
                 * Room still gives us persistent duplicate protection through
                 * messageId, but this lets us reject stale/replayed packets
                 * before doing unnecessary decrypt/database work.
                 */
                    val packetReplayGuard =
                        PacketReplayGuard()


                    /*
                 * =============================================================
                 * INTERNET TRANSPORT - ENCRYPTED CHAT + ANTI-PACKETS
                 * =============================================================
                 *
                 * The internet path carries the SAME NearbyMeshPacket that the
                 * Nearby mesh carries. Encryption happens once on the sender.
                 *
                 * The Node server only routes ciphertext. The final recipient
                 * performs the same E2E AES-GCM verification/decryption here.
                 */
                    val internetManagerHolder =
                        arrayOfNulls<InternetManager>(
                                1
                            )


                    val internetSyncJob = this.internetSyncJob
                    val internetUploadOffset = intArrayOf(0)

                    val internetManager = InternetManager(

                                identity =
                                    identity,

                                serverUrl =
                                    BuildConfig.PLEXUS_WEBSOCKET_URL,

                                listener =
                                    object :
                                        InternetManager.Listener {


                                        override fun onConnected() {

                                            Log.d(
                                                "PlexusInternet",
                                                "SOCKET CONNECTED TO PLEXUS SERVER 🌐"
                                            )
                                        }


                                        override fun onAuthenticated() {
                                            proofPublicationOutbox.connected()

                                            Log.d(
                                                "PlexusInternet",
                                                "SERVER AUTH COMPLETE ✅ 🔐 🌐"
                                            )

                                            PlexusPushToken.get(context)?.let {
                                                internetManagerHolder[0]?.registerPushToken(it)
                                            }
                                            refreshPeerPolicy()
                                            onRetryPending()
                                        }

                                        override fun onRetryPending() {
                                            proofPublicationOutbox.retryPending()
                                            if (internetSyncJob[0]?.isActive == true) return


                                            /*
                                         * If this phone previously received messages through
                                         * Nearby while the internet path was unavailable, it
                                         * may already hold signed delivery anti-packets.
                                         *
                                         * Push only anti-packets signed by THIS device as the
                                         * final recipient. The server intentionally rejects
                                         * relayed anti-packets submitted by other nodes.
                                         */
                                            internetSyncJob[0]?.cancel()
                                            internetSyncJob[0] = coroutineScope.launch(Dispatchers.Main.immediate) {


                                                try {


                                                    /*
                                                 * =================================================
                                                 * RETRY LOCALLY QUEUED OUTGOING INTERNET PACKETS
                                                 * =================================================
                                                 *
                                                 * A message may have been created while the server
                                                 * was unreachable. Its encrypted mesh envelope is
                                                 * already durable in Room.
                                                 *
                                                 * Once server authentication succeeds again, offer
                                                 * signed local and foreign relay packets to the
                                                 * backend in bounded rotating windows.
                                                 *
                                                 * Never delete them merely because the relay accepts
                                                 * the upload. A valid signed delivery anti-packet
                                                 * remains the authority that kills the local copy.
                                                 */
                                                    var pendingInternetPackets =
                                                        meshRelayStore
                                                            .getInternetUploadCandidates(
                                                                localNodeId =
                                                                    identity.nodeId,
                                                                offset = internetUploadOffset[0]
                                                            )
                                                    if (pendingInternetPackets.isEmpty() && internetUploadOffset[0] > 0) {
                                                        internetUploadOffset[0] = 0
                                                        pendingInternetPackets = meshRelayStore.getInternetUploadCandidates(identity.nodeId)
                                                    }
                                                    internetUploadOffset[0] = if (pendingInternetPackets.size < 32) 0
                                                        else internetUploadOffset[0] + pendingInternetPackets.size
                                                    // Personal delivery continues after the user stops
                                                    // network participation; opaque third-party relay does not.
                                                    if (!networkParticipationStarted) {
                                                        pendingInternetPackets = pendingInternetPackets.filter {
                                                            it.sourceNodeId == identity.nodeId
                                                        }
                                                    }


                                                    var retriedPacketCount =
                                                        0


                                                    for (
                                                    meshPacket in
                                                    pendingInternetPackets
                                                    ) {
                                                        delay(75L)

                                                        if (!networkParticipationStarted &&
                                                            meshPacket.sourceNodeId != identity.nodeId) continue
                                                        PlexusDiagnostics.message("PENDING_INTERNET_RETRY", meshPacket.packetId)


                                                        /*
                                                     * Defensive cross-transport vaccine check.
                                                     *
                                                     * Normally AntiPacketStore already deletes the
                                                     * heavy relay copy when a vaccine is stored, but
                                                     * this prevents a stale packet from being
                                                     * resurrected if the two stores ever momentarily
                                                     * disagree.
                                                     */
                                                        if (
                                                            antiPacketStore
                                                                .hasActiveVaccineFor(
                                                                    meshPacket
                                                                ).also { found -> PlexusDiagnostics.message("VACCINE_FOUND_FOR_PENDING", meshPacket.packetId, "found=$found") }
                                                        ) {

                                                            meshRelayStore
                                                                .delete(
                                                                    meshPacket.packetId
                                                                )

                                                            continue
                                                        }


                                                        val sent =
                                                            internetManagerHolder[
                                                                0
                                                            ]
                                                                ?.sendMeshPacket(
                                                                    meshPacket
                                                                )
                                                                ?: false


                                                        if (
                                                            sent
                                                        ) {

                                                            retriedPacketCount +=
                                                                1

                                                        } else {

                                                            /*
                                                         * The socket is no longer usable.
                                                         * Leave this packet and the remaining packets
                                                         * durable in Room for the next authenticated
                                                         * reconnect.
                                                         */
                                                            break
                                                        }
                                                    }


                                                    if (
                                                        retriedPacketCount > 0
                                                    ) {

                                                        Log.d(
                                                            "PlexusInternet",
                                                            "LOCAL PENDING INTERNET PACKETS RETRIED 📦🌐 -> " +
                                                                    retriedPacketCount
                                                        )
                                                    }


                                                    /*
                                                 * =================================================
                                                 * RETRY OUTGOING CONNECTION REQUESTS
                                                 * =================================================
                                                 *
                                                 * A QR may have been scanned while the server was
                                                 * unavailable. Requests are durable in Room, so
                                                 * authentication is our retry trigger.
                                                 */
                                                    val pendingConnectionRequests =
                                                        connectionRequestDao
                                                            .getOutgoingRetryCandidates(
                                                                localNodeId =
                                                                    identity.nodeId
                                                            )


                                                    var retriedRequestCount =
                                                        0


                                                    for (
                                                    requestEntity in
                                                    pendingConnectionRequests
                                                    ) {
                                                        delay(75L)


                                                        val requestPacket =
                                                            requestEntity
                                                                .toPacket()


                                                        val sent =
                                                            internetManagerHolder[
                                                                0
                                                            ]
                                                                ?.sendConnectionRequest(
                                                                    requestPacket
                                                                )
                                                                ?: false


                                                        if (
                                                            sent
                                                        ) {

                                                            retriedRequestCount +=
                                                                1

                                                        } else {

                                                            break
                                                        }
                                                    }


                                                    if (
                                                        retriedRequestCount > 0
                                                    ) {

                                                        Log.d(
                                                            "PlexusInternet",
                                                            "OUTGOING CONNECTION REQUESTS RETRIED 🤝🌐 -> " +
                                                                    retriedRequestCount
                                                        )
                                                    }


                                                    /*
                                                 * =================================================
                                                 * RETRY PENDING CONNECTION RESPONSES
                                                 * =================================================
                                                 *
                                                 * Accept / decline is a user decision and must not
                                                 * disappear merely because both transports were
                                                 * unavailable at that exact moment.
                                                 *
                                                 * We deliberately keep the original incoming signed
                                                 * request plus pendingRequestId after the local state
                                                 * transition. That lets us deterministically create a
                                                 * fresh signed response whenever internet returns.
                                                 *
                                                 * The requester correlates by requestId, so receiving
                                                 * the same semantic response more than once is safe.
                                                 */
                                                    val pendingResponseRelationships =
                                                        relationshipsHolder[
                                                            0
                                                        ]
                                                            .filter { relationship ->

                                                                relationship.pendingRequestId !=
                                                                        null &&
                                                                        (
                                                                                relationship.relationshipState ==
                                                                                        ContactRelationshipState.CONNECTED ||
                                                                                        relationship.relationshipState ==
                                                                                        ContactRelationshipState.DECLINED ||
                                                                                        ContactRelationshipState.isBlocked(relationship.relationshipState)
                                                                                )
                                                            }


                                                    var retriedResponseCount =
                                                        0


                                                    for (
                                                    relationship in
                                                    pendingResponseRelationships
                                                    ) {
                                                        delay(75L)


                                                        val requestId =
                                                            relationship.pendingRequestId
                                                                ?: continue


                                                        val requestEntity =
                                                            connectionRequestDao
                                                                .getRequestById(
                                                                    requestId
                                                                )
                                                                ?: continue


                                                        val requestPacket =
                                                            requestEntity
                                                                .toPacket()


                                                        if (
                                                            !requestPacket.verify() ||
                                                            requestPacket.recipientNodeId !=
                                                            identity.nodeId ||
                                                            requestPacket.senderNodeId !=
                                                            relationship.nodeId
                                                        ) {

                                                            continue
                                                        }


                                                        val responsePacket =
                                                            if (
                                                                relationship.relationshipState ==
                                                                ContactRelationshipState.CONNECTED
                                                            ) {

                                                                ConnectionResponsePacket
                                                                    .createAccepted(
                                                                        request =
                                                                            requestPacket,

                                                                        responderIdentity =
                                                                            identity
                                                                    )

                                                            } else {

                                                                ConnectionResponsePacket
                                                                    .createDeclined(
                                                                        request =
                                                                            requestPacket,

                                                                        responderIdentity =
                                                                            identity
                                                                    )
                                                            }


                                                        val sent =
                                                            internetManagerHolder[
                                                                0
                                                            ]
                                                                ?.sendConnectionResponse(
                                                                    responsePacket
                                                                )
                                                                ?: false


                                                        if (
                                                            sent
                                                        ) {

                                                            retriedResponseCount +=
                                                                1

                                                        } else {

                                                            break
                                                        }
                                                    }


                                                    if (
                                                        retriedResponseCount > 0
                                                    ) {

                                                        Log.d(
                                                            "PlexusInternet",
                                                            "PENDING CONNECTION RESPONSES RETRIED 🤝🌐 -> " +
                                                                    retriedResponseCount
                                                        )
                                                    }




                                                } catch (
                                                    exception: Exception
                                                ) {


                                                    Log.e(
                                                        "PlexusInternet",
                                                        "Could not sync local delivery anti-packets",
                                                        exception
                                                    )
                                                }
                                            }
                                        }

                                        override fun onSyncComplete(busy: Boolean) {
                                            if (busy) { internetManager.retryPending(); return }
                                            val callbacks = pendingSyncCallbacks.toMap()
                                            val ingress = internetIngressJobs.toList()
                                            coroutineScope.launch(Dispatchers.Main.immediate) {
                                                ingress.forEach { it.join() }
                                                val published = if (callbacks.isEmpty()) true else proofPublicationOutbox.flush()
                                                Log.d("PlexusSync", "PENDING_SYNC_COMPLETE proofsPublished=$published")
                                                callbacks.forEach { (id, callback) ->
                                                    if (pendingSyncCallbacks.remove(id) != null) callback(published)
                                                }
                                            }
                                        }


                                        override fun onAntiPacketAcknowledged(packet: NearbyAntiPacket) {
                                            coroutineScope.launch(Dispatchers.Main.immediate) {
                                                proofPublicationOutbox.acknowledge(packet)
                                            }
                                        }

                                        override fun onDisconnected() {
                                            proofPublicationOutbox.disconnected()
                                            internetSyncJob[0]?.cancel()


                                            Log.d(
                                                "PlexusInternet",
                                                "SOCKET DISCONNECTED FROM PLEXUS SERVER"
                                            )
                                        }


                                        /*
                                     * =====================================================
                                     * RECEIVE ENCRYPTED MESH PACKET FROM INTERNET
                                     * =====================================================
                                     *
                                     * AWS authenticates the current WebSocket uploader. A packet
                                     * may originate from another Plexus node: AWS independently
                                     * verifies the origin nodeId/public-key binding and ECDSA
                                     * signature, so uploader and origin may legitimately differ.
                                     * We still do NOT trust plaintext from the server:
                                     *
                                     * - packet structure is validated by NearbyMeshPacket
                                     * - final sender must be a saved contact
                                     * - replay/TTL checks still run
                                     * - AES-GCM authentication must succeed
                                     *
                                     * Only after all of that do we store the message.
                                     */
                                        override fun onMeshPacketReceived(
                                            packet: NearbyMeshPacket
                                        ) {
                                            Log.d("PlexusSync", "PENDING_MESSAGE_RECEIVED")


                                            if (
                                                packet.isExpired()
                                            ) {


                                                launchInternetIngress launch@ {

                                                    meshRelayStore
                                                        .cleanupExpired()

                                                    antiPacketStore
                                                        .cleanupExpired()
                                                }


                                                Log.d(
                                                    "PlexusInternet",
                                                    "INTERNET PACKET DROPPED - TTL EXPIRED -> " +
                                                            packet.packetId.take(8)
                                                )

                                                return
                                            }


                                            /* The Internet relay routes to the final destination. */
                                            if (
                                                packet.destinationNodeId !=
                                                identity.nodeId
                                            ) {

                                                Log.e(
                                                    "PlexusInternet",
                                                    "INTERNET PACKET REJECTED - WRONG DESTINATION -> " +
                                                            packet.destinationNodeId.take(8)
                                                )

                                                return
                                            }


                                            launchInternetIngress launch@ {


                                                try {


                                                    /*
                                                 * A signed vaccine always wins over a stale
                                                 * ciphertext copy, regardless of transport.
                                                 */
                                                    if (
                                                        antiPacketStore
                                                            .hasActiveVaccineFor(
                                                                packet
                                                            )
                                                    ) {


                                                        meshRelayStore
                                                            .delete(
                                                                packet.packetId
                                                            )


                                                        antiPacketDao.getAntiPacket(packet.packetId)
                                                            ?.toNearbyAntiPacket()
                                                            ?.let { proofPublicationOutbox.offerFresh(it) }
                                                        Log.d(
                                                            "PlexusInternet",
                                                            "INTERNET PACKET KILLED BY VACCINE 💉 -> " +
                                                                    packet.packetId.take(8)
                                                        )

                                                        return@launch
                                                    }


                                                    val encryptedPacket =
                                                        packet
                                                            .extractEncryptedChatPacket()


                                                    if (
                                                        encryptedPacket == null
                                                    ) {

                                                        Log.e(
                                                            "PlexusInternet",
                                                            "INTERNET DELIVERY REJECTED - " +
                                                                    "INVALID INNER ENCRYPTED PACKET"
                                                        )

                                                        return@launch
                                                    }


                                                    if (
                                                        encryptedPacket.senderNodeId ==
                                                        identity.nodeId
                                                    ) {

                                                        return@launch
                                                    }


                                                    val relationship = contactDao.getContactById(encryptedPacket.senderNodeId)
                                                    if (ContactRelationshipState.isBlocked(relationship?.relationshipState)) {
                                                        discardBlockedMessage(packet)
                                                        return@launch
                                                    }
                                                    val knownContact = relationship?.takeIf { ContactRelationshipState.canReceiveMessages(it.relationshipState) }


                                                    if (
                                                        knownContact == null
                                                    ) {

                                                        Log.e(
                                                            "PlexusInternet",
                                                            "INTERNET DELIVERY REJECTED - " +
                                                                    "UNKNOWN CONTACT -> " +
                                                                    encryptedPacket.senderNodeId.take(8)
                                                        )

                                                        return@launch
                                                    }


                                                    /*
                                                 * Same replay/TTL gate used by Nearby.
                                                 *
                                                 * We intentionally do not mark the message ID
                                                 * as accepted until AES-GCM authentication
                                                 * succeeds.
                                                 */
                                                    when (
                                                        packetReplayGuard.check(

                                                            messageId =
                                                                encryptedPacket.messageId,

                                                            timestamp =
                                                                encryptedPacket.timestamp
                                                        )
                                                    ) {


                                                        PacketReplayGuard.CheckResult.ACCEPTABLE -> {

                                                            /*
                                                         * Continue.
                                                         */
                                                        }


                                                        PacketReplayGuard.CheckResult.DUPLICATE -> {


                                                            Log.d(
                                                                "PlexusInternet",
                                                                "INTERNET DELIVERY DROPPED - DUPLICATE -> " +
                                                                        encryptedPacket.messageId.take(8)
                                                            )

                                                            return@launch
                                                        }


                                                        PacketReplayGuard.CheckResult.EXPIRED -> {


                                                            Log.d(
                                                                "PlexusInternet",
                                                                "INTERNET DELIVERY DROPPED - TTL EXPIRED -> " +
                                                                        encryptedPacket.messageId.take(8)
                                                            )

                                                            return@launch
                                                        }


                                                        PacketReplayGuard.CheckResult.FROM_FUTURE -> {


                                                            Log.e(
                                                                "PlexusInternet",
                                                                "INTERNET DELIVERY DROPPED - " +
                                                                        "TIMESTAMP TOO FAR IN FUTURE -> " +
                                                                        encryptedPacket.messageId.take(8)
                                                            )

                                                            return@launch
                                                        }


                                                        PacketReplayGuard.CheckResult.INVALID -> {


                                                            Log.e(
                                                                "PlexusInternet",
                                                                "INTERNET DELIVERY DROPPED - " +
                                                                        "INVALID REPLAY METADATA"
                                                            )

                                                            return@launch
                                                        }
                                                    }


                                                    /*
                                                 * Internet delivery does not require a live
                                                 * Nearby connection to the original sender.
                                                 * The saved contact's long-term agreement key
                                                 * is enough to deterministically derive the
                                                 * current E2E AES key.
                                                 */
                                                    val sessionKey =
                                                        peerEncryptionKeys[
                                                            encryptedPacket.senderNodeId
                                                        ]
                                                            ?: try {


                                                                PlexusDiagnostics.timed("KEY_DERIVATION", "sessionKey") { PlexusCrypto
                                                                    .deriveSessionKey(

                                                                        localAgreementPublicKey =
                                                                            identity
                                                                                .agreementPublicKey,

                                                                        remoteAgreementPublicKey =
                                                                            knownContact
                                                                                .agreementPublicKey,

                                                                        localNodeId =
                                                                            identity.nodeId,

                                                                        remoteNodeId =
                                                                            knownContact.nodeId
                                                                    ) }
                                                                    .also { derivedKey ->

                                                                        peerEncryptionKeys[
                                                                            knownContact.nodeId
                                                                        ] =
                                                                            derivedKey
                                                                    }


                                                            } catch (
                                                                exception: Exception
                                                            ) {


                                                                Log.e(
                                                                    "PlexusInternet",
                                                                    "INTERNET DELIVERY REJECTED - " +
                                                                            "KEY DERIVATION FAILED -> " +
                                                                            encryptedPacket.senderNodeId.take(8),
                                                                    exception
                                                                )

                                                                return@launch
                                                            }


                                                    val plaintext =
                                                        PlexusDiagnostics.timed("DECRYPT", "encryptedChat") { encryptedPacket
                                                            .decrypt(
                                                                sessionKey =
                                                                    sessionKey
                                                            ) }


                                                    if (
                                                        plaintext == null
                                                    ) {

                                                        Log.e(
                                                            "PlexusInternet",
                                                            "INTERNET DELIVERY REJECTED - " +
                                                                    "AES-GCM AUTHENTICATION FAILED -> " +
                                                                    encryptedPacket.senderNodeId.take(8)
                                                        )

                                                        return@launch
                                                    }


                                                    val chatPayload =
                                                        ChatWirePayloadCodec
                                                            .decode(
                                                                plaintext
                                                            )


                                                    if (
                                                        chatPayload.isReadReceipt()
                                                    ) {


                                                        val readUpToTimestamp =
                                                            chatPayload.readUpToTimestamp


                                                        if (
                                                            readUpToTimestamp != null
                                                        ) {

                                                            messageDao
                                                                .markMessagesReadByPeer(

                                                                    contactNodeId =
                                                                        encryptedPacket.senderNodeId,

                                                                    readUpToTimestamp =
                                                                        readUpToTimestamp
                                                                )


                                                            Log.d(
                                                                "PlexusReadReceipt",
                                                                "READ RECEIPT RECEIVED ✅ -> " +
                                                                        encryptedPacket.senderNodeId.take(8) +
                                                                        " | upTo=" +
                                                                        readUpToTimestamp
                                                            )
                                                        }


                                                    } else {


                                                        val incomingMessage =
                                                            MessageEntity(

                                                                messageId =
                                                                    encryptedPacket.messageId,

                                                                contactNodeId =
                                                                    encryptedPacket.senderNodeId,

                                                                text =
                                                                    chatPayload.text,

                                                                isMine =
                                                                    false,

                                                                timestamp =
                                                                    encryptedPacket.timestamp,

                                                                isRead =
                                                                    false,

                                                                deliveryStatus =
                                                                    MessageDeliveryStatus.DELIVERED,

                                                                transportType =
                                                                    MessageTransport.CLOUD,

                                                                replyToMessageId =
                                                                    chatPayload.replyToMessageId
                                                            )


                                                        /*
                                                     * Store visible chat first, acknowledge second.
                                                     */
                                                        val messageInserted = contactRelationships.persistIncoming(incomingMessage)
                                                        if (messageInserted == null) return@launch

                                                        notifyIncomingMessageIfNeeded(
                                                            knownContact,
                                                            messageInserted
                                                        )
                                                    }


                                                    /*
                                                 * If this same packet was also being carried
                                                 * by our local mesh queue, delivery kills it.
                                                 */
                                                    meshRelayStore
                                                        .delete(
                                                            encryptedPacket.messageId
                                                        )


                                                    val deliveryAntiPacket =
                                                        try {


                                                            NearbyAntiPacket
                                                                .create(

                                                                    deliveredPacket =
                                                                        encryptedPacket,

                                                                    recipientIdentity =
                                                                        identity
                                                                ).also { PlexusDiagnostics.message("DELIVERY_PROOF_GENERATED", it.messageId) }


                                                        } catch (
                                                            exception: Exception
                                                        ) {


                                                            Log.e(
                                                                "PlexusInternet",
                                                                "INTERNET ANTI-PACKET CREATION FAILED -> " +
                                                                        encryptedPacket.messageId.take(8),
                                                                exception
                                                            )

                                                            null
                                                        }


                                                    if (
                                                        deliveryAntiPacket != null
                                                    ) {


                                                        when (
                                                            antiPacketStore
                                                                .store(
                                                                    deliveryAntiPacket,
                                                                    locallyCreated = true
                                                                )
                                                        ) {


                                                            AntiPacketStore.StoreResult.STORED,
                                                            AntiPacketStore.StoreResult.DUPLICATE -> {
                                                                packetReplayGuard.markAccepted(encryptedPacket.messageId)



                                                                /*
                                                             * Tell BOTH transports that this
                                                             * ciphertext is dead.
                                                             *
                                                             * - internet: server may delete queue
                                                             * - Nearby: peers may delete relay copies
                                                             */
                                                                proofPublicationOutbox.offerFresh(deliveryAntiPacket)


                                                                if (networkParticipationStarted) {
                                                                    PlexusDiagnostics.proofOffer("NEARBY", deliveryAntiPacket.messageId) { nearbyManagerHolder[0]?.broadcastBytes(
                                                                        deliveryAntiPacket.toBytes()
                                                                    ) }
                                                                }


                                                                Log.d(
                                                                    "PlexusInternet",
                                                                    "DELIVERY_PROOF_SENT"
                                                                )
                                                            }


                                                            AntiPacketStore.StoreResult.EXPIRED -> {


                                                                Log.e(
                                                                    "PlexusInternet",
                                                                    "INTERNET ANTI-PACKET REJECTED - EXPIRED -> " +
                                                                            encryptedPacket.messageId.take(8)
                                                                )
                                                            }


                                                            AntiPacketStore.StoreResult.INVALID -> {


                                                                Log.e(
                                                                    "PlexusInternet",
                                                                    "INTERNET ANTI-PACKET REJECTED - INVALID -> " +
                                                                            encryptedPacket.messageId.take(8)
                                                                )
                                                            }
                                                        }
                                                    }


                                                    Log.d(
                                                        "PlexusInternet",
                                                        "INTERNET MESSAGE DELIVERED ✅ 🔐 🌐 -> " +
                                                                encryptedPacket.messageId.take(8)
                                                    )


                                                } catch (
                                                    exception: Exception
                                                ) {


                                                    Log.e(
                                                        "PlexusInternet",
                                                        "INTERNET MESH PROCESSING FAILED -> " +
                                                                packet.packetId.take(8),
                                                        exception
                                                    )
                                                }
                                            }
                                        }


                                        /*
                                     * =====================================================
                                     * RECEIVE SIGNED DELIVERY ANTI-PACKET FROM INTERNET
                                     * =====================================================
                                     *
                                     * NearbyAntiPacket.fromBytes() already performs the
                                     * protocol's structural/cryptographic validation before
                                     * this callback receives the object.
                                     *
                                     * AntiPacketStore persists the vaccine and removes the
                                     * matching heavy relay copy.
                                     */
                                        override fun onAntiPacketReceived(
                                            packet: NearbyAntiPacket
                                        ) {


                                            launchInternetIngress launch@ {


                                                try {


                                                    when (
                                                        antiPacketStore
                                                            .store(
                                                                packet
                                                            )
                                                    ) {


                                                        AntiPacketStore.StoreResult.STORED -> {


                                                            nearbyManagerHolder[0]?.discardAcknowledged(packet)


                                                            PlexusDiagnostics.room("markMessageDelivered") { messageDao
                                                                .markMessageDelivered(
                                                                    packet.messageId,
                                                                    packet.recipientNodeId
                                                                ).also { rows -> PlexusDiagnostics.message("MARK_MESSAGE_DELIVERED", packet.messageId, "rowsUpdated=$rows") } }


                                                            /*
                                                         * Bridge the newly learned internet
                                                         * vaccine into the local Nearby mesh.
                                                         *
                                                         * Duplicate vaccines will not be
                                                         * rebroadcast when they come back.
                                                         */
                                                            if (networkParticipationStarted) {
                                                                PlexusDiagnostics.proofOffer("NEARBY", packet.messageId) { nearbyManagerHolder[0]?.broadcastBytes(packet.toBytes()) }
                                                            }


                                                            Log.d(
                                                                "PlexusInternet",
                                                                "INTERNET ANTI-PACKET STORED 💉✅ -> " +
                                                                        packet.messageId.take(8)
                                                            )
                                                        }


                                                        AntiPacketStore.StoreResult.DUPLICATE -> {


                                                            nearbyManagerHolder[0]?.discardAcknowledged(packet)


                                                            PlexusDiagnostics.room("markMessageDelivered") { messageDao
                                                                .markMessageDelivered(
                                                                    packet.messageId,
                                                                    packet.recipientNodeId
                                                                ).also { rows -> PlexusDiagnostics.message("MARK_MESSAGE_DELIVERED", packet.messageId, "rowsUpdated=$rows") } }


                                                            Log.d(
                                                                "PlexusInternet",
                                                                "INTERNET ANTI-PACKET DROPPED - DUPLICATE -> " +
                                                                        packet.messageId.take(8)
                                                            )
                                                        }


                                                        AntiPacketStore.StoreResult.EXPIRED -> {


                                                            Log.d(
                                                                "PlexusInternet",
                                                                "INTERNET ANTI-PACKET DROPPED - EXPIRED -> " +
                                                                        packet.messageId.take(8)
                                                            )
                                                        }


                                                        AntiPacketStore.StoreResult.INVALID -> {


                                                            Log.e(
                                                                "PlexusInternet",
                                                                "INTERNET ANTI-PACKET DROPPED - INVALID -> " +
                                                                        packet.messageId.take(8)
                                                            )
                                                        }
                                                    }


                                                } catch (
                                                    exception: Exception
                                                ) {


                                                    Log.e(
                                                        "PlexusInternet",
                                                        "INTERNET ANTI-PACKET STORE FAILED -> " +
                                                                packet.messageId.take(8),
                                                        exception
                                                    )
                                                }
                                            }
                                        }


                                        /*
                                     * =====================================================
                                     * RECEIVE CONNECTION REQUEST FROM INTERNET
                                     * =====================================================
                                     */
                                        override fun onConnectionRequestReceived(
                                            packet: ConnectionRequestPacket
                                        ) {


                                            launchInternetIngress launch@ {

                                                handleIncomingConnectionRequest(
                                                    packet,
                                                    "INTERNET"
                                                )
                                            }
                                        }


                                        /*
                                     * =====================================================
                                     * RECEIVE CONNECTION RESPONSE FROM INTERNET
                                     * =====================================================
                                     */
                                        override fun onConnectionResponseReceived(
                                            packet: ConnectionResponsePacket
                                        ) {


                                            launchInternetIngress launch@ {

                                                handleIncomingConnectionResponse(
                                                    packet,
                                                    "INTERNET"
                                                )
                                            }
                                        }


                                        override fun onError(
                                            message: String,
                                            exception: Exception?
                                        ) {
                                            if (message == "STALE_SYNC_RECOVERY" || message == "STALE_SOCKET_RECOVERY") return


                                            if (
                                                isTerminalPacketRejection(
                                                    message
                                                )
                                            ) {

                                                return
                                            }

                                            if (
                                                exception != null
                                            ) {

                                                Log.e(
                                                    "PlexusInternet",
                                                    message,
                                                    exception
                                                )

                                            } else {

                                                Log.e(
                                                    "PlexusInternet",
                                                    message
                                                )
                                            }
                                        }
                                    }
                            )


                    internetManagerHolder[
                        0
                    ] =
                        internetManager


                    val nearbyManager = NearbyManager(

                                context =
                                    context.applicationContext,

                                localEndpointName =
                                    NearbyEndpointName.create(identity.nodeId, identity.displayName),

                                listener =
                                    object :
                                        NearbyManager.Listener {


                                        override fun onAdvertisingStarted() {

                                            Log.d(
                                                "PlexusNearby",
                                                "Advertising started"
                                            )
                                        }


                                        override fun onDiscoveryStarted() {

                                            Log.d(
                                                "PlexusNearby",
                                                "Discovery started"
                                            )
                                        }


                                        override fun onEndpointFound(
                                            endpointId: String,
                                            endpointName: String
                                        ) {

                                            Log.d(
                                                "PlexusNearby",
                                                "FOUND -> $endpointName | $endpointId"
                                            )
                                        }


                                        override fun onEndpointLost(
                                            endpointId: String
                                        ) {

                                            Log.d(
                                                "PlexusNearby",
                                                "LOST -> $endpointId"
                                            )
                                        }


                                        override fun onConnectionInitiated(
                                            endpointId: String,
                                            endpointName: String,
                                            authenticationDigits: String
                                        ) {

                                            Log.d(
                                                "PlexusNearby",
                                                "CONNECTION INITIATED -> " +
                                                        "$endpointName | " +
                                                        "auth=$authenticationDigits"
                                            )
                                        }


                                        override fun onConnected(
                                            endpointId: String
                                        ) {

                                            Log.d(
                                                "PlexusNearby",
                                                "CONNECTED -> $endpointId"
                                            )


                                            /*
                                         * First protocol message after a
                                         * Nearby connection: tell the peer
                                         * which persistent Plexus identity
                                         * owns this temporary endpoint.
                                         */
                                            val identityPacket =
                                                NearbyIdentityPacket
                                                    .create(
                                                        identity
                                                    )


                                            nearbyManagerHolder[
                                                0
                                            ]
                                                ?.sendBytes(
                                                    endpointId =
                                                        endpointId,

                                                    bytes =
                                                        identityPacket
                                                            .toBytes()
                                                )


                                            Log.d(
                                                "PlexusNearby",
                                                "IDENTITY SENT -> " +
                                                        "$endpointId | " +
                                                        identity.nodeId.take(8)
                                            )
                                        }


                                        override fun onDisconnected(
                                            endpointId: String
                                        ) {


                                            val disconnectedIdentity =
                                                announcedPeerIdentities
                                                    .remove(
                                                        endpointId
                                                    )


                                            if (
                                                disconnectedIdentity != null
                                            ) {


                                                /*
                                             * A transport handoff can briefly create a new
                                             * Nearby endpoint (E2) for the same Plexus node
                                             * before the old WIFI_LAN endpoint (E1) reports
                                             * onDisconnected().
                                             *
                                             * NearbyManager removes E1's mapping before this
                                             * listener is called. If the node already maps to
                                             * another endpoint, that newer session owns the
                                             * same deterministic ECDH key and must not have
                                             * its cached key deleted by this late callback.
                                             */
                                                val replacementEndpoint =
                                                    nearbyManagerHolder[
                                                        0
                                                    ]
                                                        ?.getEndpointForNodeId(
                                                            disconnectedIdentity.nodeId
                                                        )


                                                if (
                                                    replacementEndpoint == null ||
                                                    replacementEndpoint ==
                                                    endpointId
                                                ) {


                                                    peerEncryptionKeys.remove(
                                                        disconnectedIdentity.nodeId
                                                    )


                                                    Log.d(
                                                        "PlexusNearby",
                                                        "PEER KEY CLEARED -> " +
                                                                disconnectedIdentity.nodeId.take(8)
                                                    )


                                                } else {


                                                    Log.d(
                                                        "PlexusNearby",
                                                        "STALE DISCONNECT IGNORED FOR KEY CACHE -> " +
                                                                disconnectedIdentity.nodeId.take(8) +
                                                                " | old=$endpointId" +
                                                                " | active=$replacementEndpoint"
                                                    )
                                                }
                                            }


                                            Log.d(
                                                "PlexusNearby",
                                                "DISCONNECTED -> $endpointId"
                                            )
                                        }


                                        override fun onBytesReceived(
                                            endpointId: String,
                                            bytes: ByteArray
                                        ) {


                                            /*
                                         * =================================================
                                         * CONNECTION REQUEST
                                         * =================================================
                                         *
                                         * ConnectionRequestPacket.fromBytes() verifies the
                                         * sender's node/key binding, signature and lifetime.
                                         */
                                            val connectionRequestPacket =
                                                PlexusDiagnostics.timed("PARSE", "ConnectionRequestPacket") { ConnectionRequestPacket
                                                    .fromBytes(
                                                        bytes
                                                    ) }


                                            if (
                                                connectionRequestPacket != null
                                            ) {


                                                if (
                                                    connectionRequestPacket.recipientNodeId !=
                                                    identity.nodeId
                                                ) {

                                                    return
                                                }


                                                PlexusDiagnostics.launch(coroutineScope) {

                                                    handleIncomingConnectionRequest(
                                                        connectionRequestPacket,
                                                        "NEARBY"
                                                    )
                                                }


                                                return
                                            }


                                            /*
                                         * =================================================
                                         * CONNECTION RESPONSE
                                         * =================================================
                                         */
                                            val connectionResponsePacket =
                                                PlexusDiagnostics.timed("PARSE", "ConnectionResponsePacket") { ConnectionResponsePacket
                                                    .fromBytes(
                                                        bytes
                                                    ) }


                                            if (
                                                connectionResponsePacket != null
                                            ) {


                                                if (
                                                    connectionResponsePacket.requesterNodeId !=
                                                    identity.nodeId
                                                ) {

                                                    return
                                                }


                                                PlexusDiagnostics.launch(coroutineScope) {

                                                    handleIncomingConnectionResponse(
                                                        connectionResponsePacket,
                                                        "NEARBY"
                                                    )
                                                }


                                                return
                                            }


                                            /*
                                         * =================================================
                                         * 1. IDENTITY PACKET
                                         * =================================================
                                         *
                                         * A Nearby peer does NOT need to be in Contacts
                                         * to participate in the Plexus mesh.
                                         *
                                         * Unknown peers may authenticate as relays.
                                         * Saved contacts receive an additional pinned-key
                                         * consistency check against the QR/link identity
                                         * we already stored.
                                         */

                                            val identityPacket =
                                                PlexusDiagnostics.timed("PARSE", "NearbyIdentityPacket") { NearbyIdentityPacket
                                                    .fromBytes(
                                                        bytes
                                                    ) }


                                            if (
                                                identityPacket != null
                                            ) {


                                                if (
                                                    identityPacket.nodeId ==
                                                    identity.nodeId
                                                ) {

                                                    Log.d(
                                                        "PlexusNearby",
                                                        "IGNORED SELF IDENTITY -> $endpointId"
                                                    )

                                                    return
                                                }


                                                /*
                                             * First verify the self-authenticating
                                             * identity binding:
                                             *
                                             * nodeId == SHA-256(signing public key)
                                             */
                                                val structurallyValidIdentity =
                                                    PeerIdentityVerifier
                                                        .isValidIdentity(

                                                            nodeId =
                                                                identityPacket.nodeId,

                                                            signingPublicKey =
                                                                identityPacket
                                                                    .signingPublicKey
                                                        )


                                                if (
                                                    !structurallyValidIdentity
                                                ) {

                                                    Log.e(
                                                        "PlexusNearby",
                                                        "IDENTITY REJECTED - " +
                                                                "NODE/PUBLIC-KEY MISMATCH -> " +
                                                                identityPacket.nodeId.take(8)
                                                    )

                                                    return
                                                }


                                                val knownRelationship =
                                                    relationshipsHolder[
                                                        0
                                                    ]
                                                        .firstOrNull { relationship ->

                                                            relationship.nodeId ==
                                                                    identityPacket.nodeId
                                                        }


                                                /*
                                             * Any QR/request relationship pins the peer's
                                             * public identity material, even before trust is
                                             * established for chat.
                                             */
                                                if (
                                                    knownRelationship != null &&
                                                    !PeerIdentityVerifier
                                                        .matchesSavedContact(

                                                            announcedIdentity =
                                                                identityPacket,

                                                            savedContact =
                                                                knownRelationship
                                                        )
                                                ) {

                                                    Log.e(
                                                        "PlexusNearby",
                                                        "IDENTITY REJECTED - " +
                                                                "SAVED CONTACT KEY MISMATCH -> " +
                                                                identityPacket.nodeId.take(8)
                                                    )

                                                    return
                                                }


                                                val manager =
                                                    nearbyManagerHolder[
                                                        0
                                                    ]
                                                        ?: return


                                                /*
                                             * Remember the public identity for the
                                             * upcoming signature proof.
                                             *
                                             * This does NOT insert the peer into Room
                                             * contacts.
                                             */
                                                announcedPeerIdentities[
                                                    endpointId
                                                ] =
                                                    identityPacket


                                                manager.registerPeerIdentity(

                                                    endpointId =
                                                        endpointId,

                                                    nodeId =
                                                        identityPacket.nodeId
                                                )


                                                if (
                                                    knownRelationship != null
                                                ) {

                                                    Log.d(
                                                        "PlexusNearby",
                                                        "CONTACT IDENTITY MAPPED -> " +
                                                                "$endpointId <-> " +
                                                                identityPacket.nodeId.take(8)
                                                    )

                                                } else {

                                                    Log.d(
                                                        "PlexusNearby",
                                                        "RELAY IDENTITY MAPPED -> " +
                                                                "$endpointId <-> " +
                                                                identityPacket.nodeId.take(8) +
                                                                " | not added to contacts"
                                                    )
                                                }


                                                /*
                                             * Public-key consistency only tells us which
                                             * key the nodeId represents.
                                             *
                                             * Now demand proof that the live peer actually
                                             * possesses the corresponding private key.
                                             */
                                                val authStarted =
                                                    manager.beginAuthentication(

                                                        endpointId =
                                                            endpointId,

                                                        challengerNodeId =
                                                            identity.nodeId,

                                                        responderNodeId =
                                                            identityPacket.nodeId
                                                    )


                                                if (
                                                    authStarted
                                                ) {

                                                    Log.d(
                                                        "PlexusNearby",
                                                        "AUTH STARTED 🔐 -> " +
                                                                identityPacket.nodeId.take(8)
                                                    )

                                                } else {

                                                    Log.e(
                                                        "PlexusNearby",
                                                        "AUTH COULD NOT START -> " +
                                                                identityPacket.nodeId.take(8)
                                                    )
                                                }


                                                return
                                            }



                                            /*
                                         * =================================================
                                         * 2. AUTHENTICATION PACKET
                                         * =================================================
                                         */

                                            val authPacket =
                                                PlexusDiagnostics.timed("PARSE", "NearbyAuthPacket") { NearbyAuthPacket
                                                    .fromBytes(
                                                        bytes
                                                    ) }


                                            if (
                                                authPacket != null
                                            ) {


                                                val manager =
                                                    nearbyManagerHolder[
                                                        0
                                                    ]
                                                        ?: return


                                                val mappedNodeId =
                                                    manager
                                                        .getNodeIdForEndpoint(
                                                            endpointId
                                                        )


                                                when (
                                                    authPacket.kind
                                                ) {


                                                    NearbyAuthPacket.Kind.CHALLENGE -> {


                                                        /*
                                                     * The peer challenging us must be
                                                     * exactly the identity already mapped
                                                     * to this physical Nearby endpoint.
                                                     */
                                                        if (
                                                            mappedNodeId == null ||
                                                            mappedNodeId !=
                                                            authPacket.challengerNodeId
                                                        ) {

                                                            Log.e(
                                                                "PlexusNearby",
                                                                "AUTH CHALLENGE REJECTED - " +
                                                                        "CHALLENGER MISMATCH -> " +
                                                                        endpointId
                                                            )

                                                            return
                                                        }


                                                        /*
                                                     * We only sign challenges explicitly
                                                     * addressed to our own node ID.
                                                     */
                                                        if (
                                                            authPacket.responderNodeId !=
                                                            identity.nodeId
                                                        ) {

                                                            Log.e(
                                                                "PlexusNearby",
                                                                "AUTH CHALLENGE REJECTED - " +
                                                                        "WRONG RESPONDER -> " +
                                                                        authPacket.responderNodeId.take(8)
                                                            )

                                                            return
                                                        }


                                                        try {


                                                            val proofPacket =
                                                                NearbyAuthPacket
                                                                    .createProof(
                                                                        authPacket
                                                                    )


                                                            manager.sendBytes(

                                                                endpointId =
                                                                    endpointId,

                                                                bytes =
                                                                    proofPacket
                                                                        .toBytes()
                                                            )


                                                            Log.d(
                                                                "PlexusNearby",
                                                                "AUTH PROOF SENT 🔐 -> " +
                                                                        "$endpointId | " +
                                                                        identity.nodeId.take(8)
                                                            )


                                                        } catch (
                                                            exception: Exception
                                                        ) {


                                                            Log.e(
                                                                "PlexusNearby",
                                                                "AUTH PROOF CREATION FAILED",
                                                                exception
                                                            )
                                                        }


                                                        return
                                                    }



                                                    NearbyAuthPacket.Kind.PROOF -> {


                                                        /*
                                                     * We only verify proofs for challenges
                                                     * created by this local identity.
                                                     */
                                                        if (
                                                            authPacket.challengerNodeId !=
                                                            identity.nodeId
                                                        ) {

                                                            Log.e(
                                                                "PlexusNearby",
                                                                "AUTH PROOF REJECTED - " +
                                                                        "WRONG CHALLENGER"
                                                            )

                                                            return
                                                        }


                                                        /*
                                                     * The endpoint returning the proof
                                                     * must still map to the responder that
                                                     * supposedly signed it.
                                                     */
                                                        if (
                                                            mappedNodeId == null ||
                                                            mappedNodeId !=
                                                            authPacket.responderNodeId
                                                        ) {

                                                            Log.e(
                                                                "PlexusNearby",
                                                                "AUTH PROOF REJECTED - " +
                                                                        "RESPONDER MISMATCH -> " +
                                                                        endpointId
                                                            )

                                                            return
                                                        }


                                                        /*
                                                     * Fetch the identity that THIS Nearby
                                                     * endpoint announced earlier.
                                                     *
                                                     * Unknown relays are allowed here; they
                                                     * simply are not present in Contacts.
                                                     */
                                                        val announcedIdentity =
                                                            announcedPeerIdentities[
                                                                endpointId
                                                            ]


                                                        if (
                                                            announcedIdentity == null ||
                                                            announcedIdentity.nodeId !=
                                                            authPacket.responderNodeId
                                                        ) {

                                                            Log.e(
                                                                "PlexusNearby",
                                                                "AUTH PROOF REJECTED - " +
                                                                        "NO MATCHING ANNOUNCED IDENTITY -> " +
                                                                        endpointId
                                                            )

                                                            return
                                                        }


                                                        val knownContact =
                                                            contactsHolder[
                                                                0
                                                            ]
                                                                .firstOrNull { contact ->

                                                                    contact.nodeId ==
                                                                            authPacket.responderNodeId
                                                                }


                                                        /*
                                                     * Saved contacts use the pinned key from
                                                     * Room. Unknown relays use the public key
                                                     * from their validated identity packet.
                                                     */
                                                        val verificationPublicKey =

                                                            if (
                                                                knownContact != null
                                                            ) {

                                                                knownContact
                                                                    .signingPublicKey

                                                            } else {

                                                                announcedIdentity
                                                                    .signingPublicKey
                                                            }


                                                        val verified =
                                                            manager
                                                                .verifyAuthenticationProof(

                                                                    endpointId =
                                                                        endpointId,

                                                                    proofPacket =
                                                                        authPacket,

                                                                    publicSigningKey =
                                                                        verificationPublicKey
                                                                )


                                                        if (
                                                            verified
                                                        ) {


                                                            if (
                                                                knownContact != null
                                                            ) {


                                                                Log.d(
                                                                    "PlexusNearby",
                                                                    "CONTACT VERIFIED ✅ 🔐 -> " +
                                                                            authPacket.responderNodeId.take(8)
                                                                )


                                                                /*
                                                             * Only saved contacts need a
                                                             * pairwise E2E message key.
                                                             *
                                                             * Relay-only peers never receive
                                                             * our chat AES key and therefore
                                                             * cannot decrypt mesh payloads.
                                                             */
                                                                try {


                                                                    val sessionKey =
                                                                        PlexusDiagnostics.timed("KEY_DERIVATION", "sessionKey") { PlexusCrypto
                                                                            .deriveSessionKey(

                                                                                localAgreementPublicKey =
                                                                                    identity
                                                                                        .agreementPublicKey,

                                                                                remoteAgreementPublicKey =
                                                                                    knownContact
                                                                                        .agreementPublicKey,

                                                                                localNodeId =
                                                                                    identity.nodeId,

                                                                                remoteNodeId =
                                                                                    knownContact.nodeId
                                                                            ) }


                                                                    peerEncryptionKeys[
                                                                        knownContact.nodeId
                                                                    ] =
                                                                        sessionKey


                                                                    Log.d(
                                                                        "PlexusNearby",
                                                                        "ENCRYPTION KEY READY ✅ 🔐 -> " +
                                                                                knownContact.nodeId.take(8)
                                                                    )


                                                                } catch (
                                                                    exception: Exception
                                                                ) {


                                                                    peerEncryptionKeys.remove(
                                                                        knownContact.nodeId
                                                                    )


                                                                    Log.e(
                                                                        "PlexusNearby",
                                                                        "SESSION KEY DERIVATION FAILED -> " +
                                                                                knownContact.nodeId.take(8),
                                                                        exception
                                                                    )
                                                                }


                                                            } else {


                                                                /*
                                                             * This peer proved that it owns a
                                                             * valid Plexus identity, but we did
                                                             * not add it to Contacts and we do
                                                             * not derive a chat key with it.
                                                             */
                                                                Log.d(
                                                                    "PlexusNearby",
                                                                    "RELAY VERIFIED ✅ 🔐 -> " +
                                                                            authPacket.responderNodeId.take(8) +
                                                                            " | no contact created | no chat key"
                                                                )
                                                            }


                                                            /*
                                                         * This peer is now cryptographically
                                                         * verified, whether it is a saved
                                                         * contact or a relay-only Plexus node.
                                                         *
                                                         * Offer it the still-live packets we
                                                         * are carrying. Destination packets
                                                         * are prioritized by MeshRelayStore.
                                                         *
                                                         * We do NOT delete packets after send
                                                         * yet. Signed delivery anti-packets
                                                         * will handle deletion in the next
                                                         * protocol stage.
                                                         */
                                                            PlexusDiagnostics.launch(coroutineScope) {


                                                                try {


                                                                    /*
                                                                 * If we previously scanned this peer while it was
                                                                 * not yet Nearby-authenticated, retry the durable
                                                                 * outgoing connection request now.
                                                                 */
                                                                    val outgoingRequests =
                                                                        connectionRequestDao
                                                                            .getOutgoingRetryCandidates(
                                                                                localNodeId =
                                                                                    identity.nodeId
                                                                            )
                                                                            .filter { requestEntity ->

                                                                                requestEntity.recipientNodeId ==
                                                                                        authPacket.responderNodeId
                                                                            }


                                                                    for (
                                                                    requestEntity in
                                                                    outgoingRequests
                                                                    ) {


                                                                        val sent =
                                                                            manager
                                                                                .sendBytesToNode(

                                                                                    nodeId =
                                                                                        authPacket
                                                                                            .responderNodeId,

                                                                                    bytes =
                                                                                        requestEntity
                                                                                            .toPacket()
                                                                                            .toBytes(),

                                                                                    priority =
                                                                                        NearbyManager.SendPriority.CONTROL
                                                                                )


                                                                        if (
                                                                            !sent
                                                                        ) {

                                                                            break
                                                                        }


                                                                        Log.d(
                                                                            "PlexusNearby",
                                                                            "OUTGOING CONNECTION REQUEST RETRIED 🤝 -> " +
                                                                                    authPacket.responderNodeId.take(8) +
                                                                                    " | request=" +
                                                                                    requestEntity.requestId.take(8)
                                                                        )
                                                                    }


                                                                    /*
                                                                 * If this peer previously sent us a request and we
                                                                 * already accepted/declined it while they were
                                                                 * unreachable, re-offer the signed response now.
                                                                 *
                                                                 * Keep the durable request locally; Nearby send() is
                                                                 * not our authoritative end-to-end receipt.
                                                                 */
                                                                    val pendingResponseRelationship =
                                                                        relationshipsHolder[
                                                                            0
                                                                        ]
                                                                            .firstOrNull { relationship ->

                                                                                relationship.nodeId ==
                                                                                        authPacket.responderNodeId &&
                                                                                        relationship.pendingRequestId !=
                                                                                        null &&
                                                                                        (
                                                                                                relationship.relationshipState ==
                                                                                                        ContactRelationshipState.CONNECTED ||
                                                                                                        relationship.relationshipState ==
                                                                                                        ContactRelationshipState.DECLINED ||
                                                                                                        ContactRelationshipState.isBlocked(relationship.relationshipState)
                                                                                                )
                                                                            }


                                                                    if (
                                                                        pendingResponseRelationship != null
                                                                    ) {


                                                                        val pendingRequestId =
                                                                            pendingResponseRelationship
                                                                                .pendingRequestId


                                                                        val requestEntity =
                                                                            pendingRequestId
                                                                                ?.let { requestId ->

                                                                                    connectionRequestDao
                                                                                        .getRequestById(
                                                                                            requestId
                                                                                        )
                                                                                }


                                                                        if (
                                                                            requestEntity != null
                                                                        ) {


                                                                            val requestPacket =
                                                                                requestEntity
                                                                                    .toPacket()


                                                                            if (
                                                                                requestPacket.recipientNodeId ==
                                                                                identity.nodeId &&
                                                                                requestPacket.senderNodeId ==
                                                                                authPacket.responderNodeId
                                                                            ) {


                                                                                val responsePacket =
                                                                                    if (
                                                                                        pendingResponseRelationship.relationshipState ==
                                                                                        ContactRelationshipState.CONNECTED
                                                                                    ) {

                                                                                        ConnectionResponsePacket
                                                                                            .createAccepted(
                                                                                                request =
                                                                                                    requestPacket,

                                                                                                responderIdentity =
                                                                                                    identity
                                                                                            )

                                                                                    } else {

                                                                                        ConnectionResponsePacket
                                                                                            .createDeclined(
                                                                                                request =
                                                                                                    requestPacket,

                                                                                                responderIdentity =
                                                                                                    identity
                                                                                            )
                                                                                    }


                                                                                val sent =
                                                                                    manager
                                                                                        .sendBytesToNode(

                                                                                            nodeId =
                                                                                                authPacket.responderNodeId,

                                                                                            bytes =
                                                                                                responsePacket
                                                                                                    .toBytes(),

                                                                                            priority =
                                                                                                NearbyManager.SendPriority.CONTROL
                                                                                        )


                                                                                if (
                                                                                    sent
                                                                                ) {

                                                                                    Log.d(
                                                                                        "PlexusNearby",
                                                                                        "CONNECTION RESPONSE RETRIED 🤝 -> " +
                                                                                                authPacket.responderNodeId.take(8) +
                                                                                                " | request=" +
                                                                                                requestPacket.requestId.take(8)
                                                                                    )
                                                                                }
                                                                            }
                                                                        }
                                                                    }


                                                                    retryNearbyPackets(manager, meshRelayStore, antiPacketStore, identity.nodeId)
                                                                } catch (
                                                                    exception: Exception
                                                                ) {


                                                                    Log.e(
                                                                        "PlexusNearby",
                                                                        "MESH QUEUE FORWARD FAILED -> " +
                                                                                authPacket.responderNodeId.take(8),
                                                                        exception
                                                                    )
                                                                }
                                                            }


                                                        } else {


                                                            peerEncryptionKeys.remove(
                                                                authPacket.responderNodeId
                                                            )


                                                            Log.e(
                                                                "PlexusNearby",
                                                                "PEER VERIFICATION FAILED ❌ -> " +
                                                                        authPacket.responderNodeId.take(8)
                                                            )
                                                        }


                                                        return
                                                    }
                                                }
                                            }



                                            /*
                                         * =================================================
                                         * 3. SIGNED DELIVERY ANTI-PACKET
                                         * =================================================
                                         *
                                         * Anti-packets are tiny signed tombstones.
                                         *
                                         * They mean:
                                         *
                                         * "message X reached its final recipient device"
                                         *
                                         * A valid anti-packet is persisted as a vaccine,
                                         * deletes any matching heavy relay payload, and is
                                         * propagated onward so stale copies die across the
                                         * opportunistic mesh.
                                         */

                                            val antiPacket =
                                                PlexusDiagnostics.timed("PARSE", "NearbyAntiPacket") { NearbyAntiPacket
                                                    .fromBytes(
                                                        bytes
                                                    ) }


                                            if (
                                                antiPacket != null
                                            ) {
                                                PlexusDiagnostics.message("DELIVERY_PROOF_RECEIVED", antiPacket.messageId, "source=NEARBY")


                                                val manager =
                                                    nearbyManagerHolder[
                                                        0
                                                    ]
                                                        ?: return


                                                val immediatePeerNodeId =
                                                    manager
                                                        .getNodeIdForEndpoint(
                                                            endpointId
                                                        )


                                                if (
                                                    immediatePeerNodeId == null
                                                ) {

                                                    Log.e(
                                                        "PlexusNearby",
                                                        "ANTI-PACKET REJECTED - " +
                                                                "ENDPOINT NOT IDENTIFIED -> " +
                                                                endpointId
                                                    )

                                                    return
                                                }


                                                if (
                                                    !manager
                                                        .isEndpointVerified(
                                                            endpointId
                                                        )
                                                ) {

                                                    Log.e(
                                                        "PlexusNearby",
                                                        "ANTI-PACKET REJECTED - " +
                                                                "IMMEDIATE PEER NOT VERIFIED 🔒 -> " +
                                                                endpointId
                                                    )

                                                    return
                                                }


                                                PlexusDiagnostics.launch(coroutineScope) {


                                                    try {


                                                        when (
                                                            antiPacketStore
                                                                .store(
                                                                    antiPacket
                                                                )
                                                        ) {


                                                            AntiPacketStore.StoreResult.STORED -> {


                                                                nearbyManagerHolder[0]?.discardAcknowledged(antiPacket)


                                                                PlexusDiagnostics.room("markMessageDelivered") { messageDao
                                                                    .markMessageDelivered(
                                                                        antiPacket.messageId,
                                                                        antiPacket.recipientNodeId
                                                                    ).also { rows -> PlexusDiagnostics.message("MARK_MESSAGE_DELIVERED", antiPacket.messageId, "rowsUpdated=$rows") } }


                                                                Log.d(
                                                                    "PlexusNearby",
                                                                    "ANTI-PACKET STORED 💉✅ -> " +
                                                                            antiPacket.messageId.take(8) +
                                                                            " | via=" +
                                                                            immediatePeerNodeId.take(8)
                                                                )


                                                                /*
                                                             * Epidemic vaccine propagation.
                                                             *
                                                             * Only a newly stored anti-packet
                                                             * is rebroadcast here. Duplicates
                                                             * are not rebroadcast, preventing a
                                                             * vaccine loop.
                                                             */
                                                                PlexusDiagnostics.proofOffer("NEARBY", antiPacket.messageId) { manager
                                                                    .broadcastBytes(
                                                                        antiPacket
                                                                            .toBytes()
                                                                    ) }
                                                            }


                                                            AntiPacketStore.StoreResult.DUPLICATE -> {


                                                                nearbyManagerHolder[0]?.discardAcknowledged(antiPacket)


                                                                PlexusDiagnostics.room("markMessageDelivered") { messageDao
                                                                    .markMessageDelivered(
                                                                        antiPacket.messageId,
                                                                        antiPacket.recipientNodeId
                                                                    ).also { rows -> PlexusDiagnostics.message("MARK_MESSAGE_DELIVERED", antiPacket.messageId, "rowsUpdated=$rows") } }


                                                                Log.d(
                                                                    "PlexusNearby",
                                                                    "ANTI-PACKET DROPPED - DUPLICATE -> " +
                                                                            antiPacket.messageId.take(8)
                                                                )
                                                            }


                                                            AntiPacketStore.StoreResult.EXPIRED -> {


                                                                Log.d(
                                                                    "PlexusNearby",
                                                                    "ANTI-PACKET DROPPED - EXPIRED -> " +
                                                                            antiPacket.messageId.take(8)
                                                                )
                                                            }


                                                            AntiPacketStore.StoreResult.INVALID -> {


                                                                Log.e(
                                                                    "PlexusNearby",
                                                                    "ANTI-PACKET DROPPED - INVALID -> " +
                                                                            antiPacket.messageId.take(8)
                                                                )
                                                            }
                                                        }


                                                    } catch (
                                                        exception: Exception
                                                    ) {


                                                        Log.e(
                                                            "PlexusNearby",
                                                            "ANTI-PACKET STORE FAILED -> " +
                                                                    antiPacket.messageId.take(8),
                                                            exception
                                                        )
                                                    }
                                                }


                                                return
                                            }



                                            /*
                                         * =================================================
                                         * 4. MESH ENVELOPE
                                         * =================================================
                                         *
                                         * Every new Plexus chat now travels inside this
                                         * outer store-and-forward envelope.
                                         *
                                         * The immediate Nearby sender may be:
                                         *
                                         * - the original sender
                                         * - an intermediate relay
                                         *
                                         * Therefore we authenticate the immediate Nearby
                                         * peer, but we do NOT require that peer's nodeId
                                         * to equal the message's original sourceNodeId.
                                         */

                                            val meshPacket =
                                                PlexusDiagnostics.timed("PARSE", "NearbyMeshPacket") { NearbyMeshPacket
                                                    .fromBytes(
                                                        bytes
                                                    ) }


                                            if (
                                                meshPacket != null
                                            ) {
                                                PlexusDiagnostics.message("RECEIVER_PACKET_INGRESS", meshPacket.packetId, "source=NEARBY")


                                                val manager =
                                                    nearbyManagerHolder[
                                                        0
                                                    ]
                                                        ?: return


                                                val immediatePeerNodeId =
                                                    manager
                                                        .getNodeIdForEndpoint(
                                                            endpointId
                                                        )


                                                if (
                                                    immediatePeerNodeId == null
                                                ) {

                                                    Log.e(
                                                        "PlexusNearby",
                                                        "MESH PACKET REJECTED - " +
                                                                "ENDPOINT NOT IDENTIFIED -> " +
                                                                endpointId
                                                    )

                                                    return
                                                }


                                                if (
                                                    !manager
                                                        .isEndpointVerified(
                                                            endpointId
                                                        )
                                                ) {

                                                    Log.e(
                                                        "PlexusNearby",
                                                        "MESH PACKET REJECTED - " +
                                                                "IMMEDIATE PEER NOT VERIFIED 🔒 -> " +
                                                                endpointId
                                                    )

                                                    return
                                                }


                                                if (
                                                    meshPacket.isExpired()
                                                ) {


                                                    PlexusDiagnostics.launch(coroutineScope) {

                                                        meshRelayStore
                                                            .cleanupExpired()

                                                        antiPacketStore
                                                            .cleanupExpired()
                                                    }


                                                    Log.d(
                                                        "PlexusNearby",
                                                        "MESH PACKET DROPPED - TTL EXPIRED -> " +
                                                                meshPacket.packetId.take(8)
                                                    )

                                                    return
                                                }


                                                /*
                                             * Persistent vaccine lookup requires Room, so
                                             * the rest of mesh processing continues inside
                                             * this coroutine instead of blocking Nearby's
                                             * callback thread.
                                             */
                                                PlexusDiagnostics.launch(coroutineScope) {


                                                    try {


                                                        /*
                                                     * If a signed delivery vaccine already
                                                     * exists for this exact source /
                                                     * destination / packet ID, this message
                                                     * is dead. Never resurrect or forward it.
                                                     */
                                                        if (
                                                            antiPacketStore
                                                                .hasActiveVaccineFor(
                                                                    meshPacket
                                                                )
                                                        ) {


                                                            meshRelayStore
                                                                .delete(
                                                                    meshPacket.packetId
                                                                )


                                                            antiPacketDao.getAntiPacket(meshPacket.packetId)
                                                                ?.toNearbyAntiPacket()
                                                                ?.let { proof ->
                                                                    PlexusDiagnostics.proofOffer("NEARBY", proof.messageId) { manager.sendBytesToNode(immediatePeerNodeId, proof.toBytes(),
                                                                        NearbyManager.SendPriority.MESSAGE) }
                                                                }
                                                            Log.d(
                                                                "PlexusNearby",
                                                                "MESH PACKET KILLED BY VACCINE 💉 -> " +
                                                                        meshPacket.packetId.take(8)
                                                            )

                                                            return@launch
                                                        }


                                                        /*
                                                     * =========================================
                                                     * FINAL DESTINATION: THIS PHONE
                                                     * =========================================
                                                     *
                                                     * Relays never decrypt. Only the final
                                                     * destination opens the encrypted payload.
                                                     */
                                                        if (
                                                            meshPacket.destinationNodeId ==
                                                            identity.nodeId
                                                        ) {


                                                            val encryptedPacket =
                                                                meshPacket
                                                                    .extractEncryptedChatPacket()


                                                            if (
                                                                encryptedPacket == null
                                                            ) {

                                                                Log.e(
                                                                    "PlexusNearby",
                                                                    "MESH DELIVERY REJECTED - " +
                                                                            "INVALID INNER ENCRYPTED PACKET"
                                                                )

                                                                return@launch
                                                            }


                                                            if (
                                                                encryptedPacket.senderNodeId ==
                                                                identity.nodeId
                                                            ) {

                                                                return@launch
                                                            }


                                                            val relationship = contactDao.getContactById(encryptedPacket.senderNodeId)
                                                    if (ContactRelationshipState.isBlocked(relationship?.relationshipState)) {
                                                        discardBlockedMessage(meshPacket)
                                                        return@launch
                                                    }
                                                    val knownContact = relationship?.takeIf { ContactRelationshipState.canReceiveMessages(it.relationshipState) }


                                                            if (
                                                                knownContact == null
                                                            ) {

                                                                Log.e(
                                                                    "PlexusNearby",
                                                                    "MESH DELIVERY REJECTED - " +
                                                                            "UNKNOWN CONTACT -> " +
                                                                            encryptedPacket.senderNodeId.take(8)
                                                                )

                                                                return@launch
                                                            }


                                                            /*
                                                         * Replay/TTL is based on the original
                                                         * authenticated message timestamp, not
                                                         * on relay hop metadata.
                                                         */
                                                            when (
                                                                packetReplayGuard.check(

                                                                    messageId =
                                                                        encryptedPacket.messageId,

                                                                    timestamp =
                                                                        encryptedPacket.timestamp
                                                                )
                                                            ) {


                                                                PacketReplayGuard.CheckResult.ACCEPTABLE -> {

                                                                    /*
                                                                 * Continue.
                                                                 */
                                                                }


                                                                PacketReplayGuard.CheckResult.DUPLICATE -> {


                                                                    Log.d(
                                                                        "PlexusNearby",
                                                                        "MESH DELIVERY DROPPED - DUPLICATE -> " +
                                                                                encryptedPacket.messageId.take(8)
                                                                    )

                                                                    return@launch
                                                                }


                                                                PacketReplayGuard.CheckResult.EXPIRED -> {


                                                                    Log.d(
                                                                        "PlexusNearby",
                                                                        "MESH DELIVERY DROPPED - TTL EXPIRED -> " +
                                                                                encryptedPacket.messageId.take(8)
                                                                    )

                                                                    return@launch
                                                                }


                                                                PacketReplayGuard.CheckResult.FROM_FUTURE -> {


                                                                    Log.e(
                                                                        "PlexusNearby",
                                                                        "MESH DELIVERY DROPPED - " +
                                                                                "TIMESTAMP TOO FAR IN FUTURE -> " +
                                                                                encryptedPacket.messageId.take(8)
                                                                    )

                                                                    return@launch
                                                                }


                                                                PacketReplayGuard.CheckResult.INVALID -> {


                                                                    Log.e(
                                                                        "PlexusNearby",
                                                                        "MESH DELIVERY DROPPED - " +
                                                                                "INVALID REPLAY METADATA"
                                                                    )

                                                                    return@launch
                                                                }
                                                            }


                                                            /*
                                                         * The original sender does not need
                                                         * to be directly connected. The saved
                                                         * contact identity is sufficient to
                                                         * derive the current long-term E2E key.
                                                         */
                                                            val sessionKey =
                                                                peerEncryptionKeys[
                                                                    encryptedPacket.senderNodeId
                                                                ]
                                                                    ?: try {


                                                                        PlexusDiagnostics.timed("KEY_DERIVATION", "sessionKey") { PlexusCrypto
                                                                            .deriveSessionKey(

                                                                                localAgreementPublicKey =
                                                                                    identity
                                                                                        .agreementPublicKey,

                                                                                remoteAgreementPublicKey =
                                                                                    knownContact
                                                                                        .agreementPublicKey,

                                                                                localNodeId =
                                                                                    identity.nodeId,

                                                                                remoteNodeId =
                                                                                    knownContact.nodeId
                                                                            ) }
                                                                            .also { derivedKey ->

                                                                                peerEncryptionKeys[
                                                                                    knownContact.nodeId
                                                                                ] =
                                                                                    derivedKey
                                                                            }


                                                                    } catch (
                                                                        exception: Exception
                                                                    ) {


                                                                        Log.e(
                                                                            "PlexusNearby",
                                                                            "MESH DELIVERY REJECTED - " +
                                                                                    "KEY DERIVATION FAILED -> " +
                                                                                    encryptedPacket.senderNodeId.take(8),
                                                                            exception
                                                                        )

                                                                        return@launch
                                                                    }


                                                            val plaintext =
                                                                PlexusDiagnostics.timed("DECRYPT", "encryptedChat") { encryptedPacket
                                                                    .decrypt(
                                                                        sessionKey =
                                                                            sessionKey
                                                                    ) }


                                                            if (
                                                                plaintext == null
                                                            ) {

                                                                Log.e(
                                                                    "PlexusNearby",
                                                                    "MESH DELIVERY REJECTED - " +
                                                                            "AES-GCM AUTHENTICATION FAILED -> " +
                                                                            encryptedPacket.senderNodeId.take(8)
                                                                )

                                                                return@launch
                                                            }


                                                            /*
                                                         * Only authenticated ciphertext reaches
                                                         * the replay cache.
                                                         */


                                                            val chatPayload =
                                                                ChatWirePayloadCodec
                                                                    .decode(
                                                                        plaintext
                                                                    )


                                                            if (
                                                                chatPayload.isReadReceipt()
                                                            ) {


                                                                val readUpToTimestamp =
                                                                    chatPayload.readUpToTimestamp


                                                                if (
                                                                    readUpToTimestamp != null
                                                                ) {

                                                                    messageDao
                                                                        .markMessagesReadByPeer(

                                                                            contactNodeId =
                                                                                encryptedPacket.senderNodeId,

                                                                            readUpToTimestamp =
                                                                                readUpToTimestamp
                                                                        )


                                                                    Log.d(
                                                                        "PlexusReadReceipt",
                                                                        "READ RECEIPT RECEIVED ✅ -> " +
                                                                                encryptedPacket.senderNodeId.take(8) +
                                                                                " | upTo=" +
                                                                                readUpToTimestamp
                                                                    )
                                                                }


                                                            } else {


                                                                val incomingMessage =
                                                                    MessageEntity(

                                                                        messageId =
                                                                            encryptedPacket.messageId,

                                                                        contactNodeId =
                                                                            encryptedPacket.senderNodeId,

                                                                        text =
                                                                            chatPayload.text,

                                                                        isMine =
                                                                            false,

                                                                        timestamp =
                                                                            encryptedPacket.timestamp,

                                                                        isRead =
                                                                            false,

                                                                        deliveryStatus =
                                                                            MessageDeliveryStatus.DELIVERED,

                                                                        transportType =
                                                                            MessageTransport.MESH,

                                                                        replyToMessageId =
                                                                            chatPayload.replyToMessageId
                                                                    )


                                                                /*
                                                             * Store visible chat first, acknowledge second.
                                                             */
                                                                val messageInserted = contactRelationships.persistIncoming(incomingMessage)
                                                                if (messageInserted == null) return@launch

                                                                notifyIncomingMessageIfNeeded(
                                                                    knownContact,
                                                                    messageInserted
                                                                )
                                                            }


                                                            /*
                                                         * We no longer need a local heavy relay
                                                         * copy after final delivery.
                                                         */
                                                            meshRelayStore
                                                                .delete(
                                                                    encryptedPacket.messageId
                                                                )


                                                            /*
                                                         * Create a signed anti-packet only after
                                                         * successful authenticated decryption and
                                                         * local message storage.
                                                         */
                                                            val deliveryAntiPacket =
                                                                try {


                                                                    NearbyAntiPacket
                                                                        .create(

                                                                            deliveredPacket =
                                                                                encryptedPacket,

                                                                            recipientIdentity =
                                                                                identity
                                                                        ).also { PlexusDiagnostics.message("DELIVERY_PROOF_GENERATED", it.messageId) }


                                                                } catch (
                                                                    exception: Exception
                                                                ) {


                                                                    Log.e(
                                                                        "PlexusNearby",
                                                                        "ANTI-PACKET CREATION FAILED -> " +
                                                                                encryptedPacket.messageId.take(8),
                                                                        exception
                                                                    )

                                                                    null
                                                                }


                                                            if (
                                                                deliveryAntiPacket != null
                                                            ) {


                                                                when (
                                                                    antiPacketStore
                                                                        .store(
                                                                            deliveryAntiPacket,
                                                                            locallyCreated = true
                                                                        )
                                                                ) {


                                                                    AntiPacketStore.StoreResult.STORED,
                                                                    AntiPacketStore.StoreResult.DUPLICATE -> {
                                                                        packetReplayGuard.markAccepted(encryptedPacket.messageId)



                                                                        /*
                                                                     * Send the signed delivery
                                                                     * vaccine into the mesh.
                                                                     *
                                                                     * Nodes that already have it
                                                                     * will drop the duplicate and
                                                                     * will not rebroadcast it.
                                                                     */
                                                                        PlexusDiagnostics.proofOffer("NEARBY", deliveryAntiPacket.messageId) { manager
                                                                            .broadcastBytes(
                                                                                bytes = deliveryAntiPacket
                                                                                    .toBytes(),
                                                                                priority = NearbyManager.SendPriority.MESSAGE
                                                                            ) }


                                                                        /*
                                                                     * Nearby may win the delivery race even though the
                                                                     * sender also uploaded the same ciphertext to the
                                                                     * internet server.
                                                                     *
                                                                     * Send the recipient-signed anti-packet through the
                                                                     * internet path too so the backend deletes its queued
                                                                     * copy instead of resurrecting an already-delivered
                                                                     * message later.
                                                                     */
                                                                        proofPublicationOutbox.offerFresh(deliveryAntiPacket)


                                                                        Log.d(
                                                                            "PlexusNearby",
                                                                            "DELIVERY ANTI-PACKET CREATED 💉✅ -> " +
                                                                                    encryptedPacket.messageId.take(8)
                                                                        )
                                                                    }


                                                                    AntiPacketStore.StoreResult.EXPIRED -> {


                                                                        Log.e(
                                                                            "PlexusNearby",
                                                                            "ANTI-PACKET CREATION REJECTED - EXPIRED -> " +
                                                                                    encryptedPacket.messageId.take(8)
                                                                        )
                                                                    }


                                                                    AntiPacketStore.StoreResult.INVALID -> {


                                                                        Log.e(
                                                                            "PlexusNearby",
                                                                            "ANTI-PACKET CREATION REJECTED - INVALID -> " +
                                                                                    encryptedPacket.messageId.take(8)
                                                                        )
                                                                    }
                                                                }
                                                            }


                                                            Log.d(
                                                                "PlexusNearby",
                                                                "MESH MESSAGE DELIVERED ✅ 🔐 -> " +
                                                                        encryptedPacket.messageId.take(8) +
                                                                        " | via=" +
                                                                        immediatePeerNodeId.take(8)
                                                            )


                                                            return@launch
                                                        }


                                                        /*
                                                     * =========================================
                                                     * NOT FOR US: STORE-FORWARD RELAY
                                                     * =========================================
                                                     *
                                                     * We never decrypt this packet.
                                                     */
                                                        if (meshPacket.sourceNodeId == identity.nodeId) return@launch
                                                        when (
                                                            meshRelayStore
                                                                .store(
                                                                    meshPacket
                                                                )
                                                        ) {


                                                            MeshRelayStore.StoreResult.STORED -> {

                                                                if (networkParticipationStarted) {
                                                                    internetManagerHolder[0]?.sendMeshPacket(meshPacket)
                                                                }


                                                                Log.d(
                                                                    "PlexusNearby",
                                                                    "MESH PACKET STORED 📦 -> " +
                                                                            meshPacket.packetId.take(8) +
                                                                            " | dst=" +
                                                                            meshPacket.destinationNodeId.take(8) +
                                                                            " | hop=" +
                                                                            meshPacket.hopCount
                                                                )


                                                                /*
                                                             * Epidemic forwarding foundation:
                                                             *
                                                             * immediately offer the newly stored
                                                             * opaque packet to currently connected
                                                             * peers within the independent TTL and hop limits.
                                                             */
                                                                val forwardedPacket =
                                                                    meshPacket
                                                                        .forwardedCopy()


                                                                if (
                                                                    forwardedPacket != null
                                                                ) {

                                                                    manager
                                                                        .broadcastBytes(
                                                                            forwardedPacket
                                                                                .toBytes(),
                                                                            excludeEndpointId = endpointId
                                                                        )
                                                                }
                                                            }


                                                            MeshRelayStore.StoreResult.DUPLICATE -> {


                                                                Log.d(
                                                                    "PlexusNearby",
                                                                    "MESH PACKET DROPPED - DUPLICATE -> " +
                                                                            meshPacket.packetId.take(8)
                                                                )
                                                            }


                                                            MeshRelayStore.StoreResult.EXPIRED -> {


                                                                Log.d(
                                                                    "PlexusNearby",
                                                                    "MESH PACKET DROPPED - TTL EXPIRED -> " +
                                                                            meshPacket.packetId.take(8)
                                                                )
                                                            }


                                                            MeshRelayStore.StoreResult.INVALID -> {


                                                                Log.e(
                                                                    "PlexusNearby",
                                                                    "MESH PACKET DROPPED - INVALID -> " +
                                                                            meshPacket.packetId.take(8)
                                                                )
                                                            }
                                                        }


                                                    } catch (
                                                        exception: Exception
                                                    ) {


                                                        Log.e(
                                                            "PlexusNearby",
                                                            "MESH PROCESSING FAILED -> " +
                                                                    meshPacket.packetId.take(8),
                                                            exception
                                                        )
                                                    }
                                                }


                                                return
                                            }



                                            Log.d("PlexusNearby", "UNSUPPORTED PACKET REJECTED")
                                        }


                                        override fun onError(
                                            message: String,
                                            exception: Exception?
                                        ) {

                                            if (
                                                exception != null
                                            ) {

                                                Log.e(
                                                    "PlexusNearby",
                                                    message,
                                                    exception
                                                )

                                            } else {

                                                Log.e(
                                                    "PlexusNearby",
                                                    message
                                                )
                                            }
                                        }
                                    }
                            )


                    /*
                 * Make the manager available to its callbacks before startup.
                 */
                    nearbyManagerHolder[
                        0
                    ] =
                        nearbyManager



        this.internetManager = internetManager
        this.nearbyManager = nearbyManager
    }

    fun startPersonalMessaging(onStarted: () -> Unit, onFailed: (Throwable) -> Unit) {
        requireMainThread()
        PlexusDiagnostics.printBuildOnce()
        if (started || stopping) return
        started = true
        coroutineScope.launch(Dispatchers.Main.immediate) {
            try {
                val relationships = contactDao.getAllRelationships().first()
                updateRelationships(relationships)
                initialized = true
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    try {
                        contactDao.getAllRelationships().collect(::updateRelationships)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        onFailed(failure)
                    }
                }
                internetManager.start()
                internetRetryJob = coroutineScope.launch(Dispatchers.Main.immediate) {
                    while (true) {
                        delay(30_000L)
                        if (!internetManager.isAuthenticated()) continue
                        refreshPeerPolicy()
                        try {
                            internetManager.retryPending()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (exception: Exception) {
                            Log.e("PlexusInternet", "DURABLE INTERNET RETRY FAILED", exception)
                        }
                    }
                }
                onStarted()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailed(failure)
            }
        }
    }

    private fun updateRelationships(relationships: List<ContactEntity>) {
        relationshipsHolder[0] = relationships
        if (initialized) refreshPeerPolicy()
        contactsHolder[0] = relationships.filter {
            it.relationshipState == ContactRelationshipState.CONNECTED ||
                it.relationshipState == ContactRelationshipState.ACCEPTED_NEEDS_NAME
        }
    }

    private fun launchInternetIngress(block: suspend CoroutineScope.() -> Unit) {
        val job = PlexusDiagnostics.launch(coroutineScope, source = "INTERNET", block = block)
        internetIngressJobs.add(job)
        job.invokeOnCompletion { internetIngressJobs.remove(job) }
    }

    fun cancelPersonalSync(requestId: String) { requireMainThread(); pendingSyncCallbacks.remove(requestId) }
    fun wakeForSync() { requireMainThread(); if (initialized && !stopping) internetManager.wakeForSync() }

    private var peerPolicyJob: Job? = null
    private val peerPolicyRevisionMutex = Mutex()
    private suspend fun nextPeerPolicyRevision(): Long = peerPolicyRevisionMutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("plexus_peer_policy", Context.MODE_PRIVATE)
            val revision = maxOf(System.currentTimeMillis(), prefs.getLong("revision", 0) + 1)
            check(prefs.edit().putLong("revision", revision).commit())
            revision
        }
    }
    fun refreshPeerPolicy() {
        requireMainThread()
        if (!initialized || stopping) return
        peerPolicyJob?.cancel()
        peerPolicyJob = coroutineScope.launch(Dispatchers.Main.immediate) {
            try {
            // Every known relationship is authoritative, even if NONE already became
            // OUTGOING_PENDING before a process restart could upload its unblock.
            for (contact in contactDao.getAllRelationships().first().sortedBy { it.relationshipState == ContactRelationshipState.CONNECTED }) {
                val blocked = ContactRelationshipState.isBlocked(contact.relationshipState)
                val previous = peerPolicyVersions[contact.nodeId]
                val version = if (previous?.first == blocked) previous else {
                    (blocked to nextPeerPolicyRevision()).also { peerPolicyVersions[contact.nodeId] = it }
                }
                if ((ContactRelationshipState.isBlocked(contactDao.getContactById(contact.nodeId)?.relationshipState)) != blocked) continue
                internetManager.setPeerBlocked(contact.nodeId, blocked, version.second,
                    background = !blocked && previous == null && contact.relationshipState == ContactRelationshipState.CONNECTED)
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w("PlexusInternet", "PEER_POLICY_RETRY_DEFERRED")
            }
        }
    }

    private fun discardBlocked(peer: String, key: String) {
        coroutineScope.launch(Dispatchers.Main.immediate) {
            try {
            if (!ContactRelationshipState.isBlocked(contactDao.getContactById(peer)?.relationshipState)) return@launch
            val revision = nextPeerPolicyRevision()
            if (!ContactRelationshipState.isBlocked(contactDao.getContactById(peer)?.relationshipState)) return@launch
            peerPolicyVersions[peer] = true to revision
            // Same authenticated session serializes the policy update before its discard offer.
            internetManager.setPeerBlocked(peer, true, revision)
            internetManager.discardBlockedPacket(key, peer)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w("PlexusInternet", "BLOCK_DISCARD_RETRY_DEFERRED")
            }
        }
    }

    private fun discardBlockedMessage(packet: NearbyMeshPacket) {
        discardBlocked(packet.sourceNodeId,
            "2#${packet.createdAt.toString().padStart(13, '0')}#MESSAGE#${packet.packetId}")
    }

    fun startNearbyIfPermitted() {
        requireMainThread()
        if (!initialized || stopping || !networkParticipationStarted || !NearbyPermissions.hasAllPermissions(context)) return
        nearbyManager.startAdvertising()
        nearbyManager.startDiscovery()
    }

    fun startNetworkParticipation() {
        requireMainThread()
        if (!initialized || stopping || networkParticipationStarted) return
        networkParticipationStarted = true
        PlexusDiagnostics.start(coroutineScope) { nearbyManager.diagnosticSnapshot() }
        startNearbyIfPermitted()
        nearbyRetryJob = coroutineScope.launch(Dispatchers.Main.immediate) {
            while (true) {
                delay(5_000L)
                try {
                    retryNearbyPackets(nearbyManager, meshRelayStore, antiPacketStore, identity.nodeId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.e("PlexusNearby", "DURABLE RETRY FAILED", exception)
                }
            }
        }
    }

    fun stopNetworkParticipation() {
        requireMainThread()
        if (!networkParticipationStarted) return
        networkParticipationStarted = false
        PlexusDiagnostics.stop()
        internetManager.stopGatewayOffers()
        nearbyRetryJob?.cancel()
        nearbyRetryJob = null
        nearbyManager.stopEverything()
    }

    fun sendOwnMeshPacket(packet: NearbyMeshPacket): Boolean {
        requireMainThread()
        return if (packet.sourceNodeId == identity.nodeId && initialized && !stopping) {
            internetManager.sendMeshPacket(packet)
        } else false
    }

    fun retryPersonalPending() {
        requireMainThread()
        if (initialized && !stopping) internetManager.retryPending()
    }

    fun syncPersonalPending(requestId: String, onComplete: (Boolean) -> Unit) {
        requireMainThread()
        if (!initialized || stopping) return
        pendingSyncCallbacks[requestId] = onComplete
        Log.d("PlexusSync", "PENDING_SYNC_REQUESTED")
        internetManager.wakeForSync()
        internetManager.retryPending()
    }

    fun registerPushToken(token: String): Boolean {
        requireMainThread()
        return initialized && !stopping && internetManager.registerPushToken(token)
    }

    fun sendConnectionRequest(packet: ConnectionRequestPacket): Boolean {
        requireMainThread()
        return if (initialized && !stopping) internetManager.sendConnectionRequest(packet) else false
    }

    fun sendConnectionResponse(packet: ConnectionResponsePacket): Boolean {
        requireMainThread()
        return if (initialized && !stopping) internetManager.sendConnectionResponse(packet) else false
    }

    fun isNodeVerified(nodeId: String): Boolean {
        requireMainThread()
        return initialized && !stopping && networkParticipationStarted && nearbyManager.isNodeVerified(nodeId)
    }

    fun sendBytesToNode(nodeId: String, bytes: ByteArray, priority: NearbyManager.SendPriority): Boolean {
        requireMainThread()
        return if (initialized && !stopping && networkParticipationStarted) nearbyManager.sendBytesToNode(nodeId, bytes, priority) else false
    }

    fun broadcastBytes(bytes: ByteArray, priority: NearbyManager.SendPriority) {
        requireMainThread()
        if (initialized && !stopping && networkParticipationStarted) nearbyManager.broadcastBytes(bytes, priority)
    }

    fun shutdown() {
        requireMainThread()
        if (stopping) return
        stopping = true
        PlexusDiagnostics.stop()
        initialized = false
        networkParticipationStarted = false
        internetSyncJob[0]?.cancel()
        nearbyRetryJob?.cancel()
        internetRetryJob?.cancel()
        pendingSyncCallbacks.clear()
        coroutineScope.cancel()
        try {
            nearbyManager.stopEverything()
        } finally {
            internetManager.stop()
        }
    }
}
