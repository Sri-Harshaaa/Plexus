package com.plexus.app

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class MeshRelayStore(private val database: PlexusDatabase, private val identity: PlexusIdentity) {
    private val relayPacketDao = database.relayPacketDao()
    private suspend fun cleanup(now: Long) = relayPacketDao.deleteUnusablePackets(now, identity.nodeId)

    private suspend fun packetForRetry(entity: RelayPacketEntity): NearbyMeshPacket? {
        val packet = entity.toMeshPacket()
        if (packet.originSignature.isNotEmpty()) return packet
        // Existing local Room packets predate origin signatures. Only their owner can upgrade them.
        if (packet.sourceNodeId != identity.nodeId) return null // Unsigned foreign rows are purged by cleanup().
        val signed = withContext(Dispatchers.IO) { packet.signAs(identity) }
        val updated = relayPacketDao.signLegacyLocal(packet.packetId, identity.nodeId,
            signed.originSigningPublicKey, signed.originSignature)
        return resolvedLegacyPacket(signed, updated,
            if (updated == 0) relayPacketDao.getPacket(packet.packetId) else null)
    }


    /*
     * =========================================================
     * STORE RESULT
     * =========================================================
     */

    enum class StoreResult {

        STORED,

        DUPLICATE,

        EXPIRED,

        INVALID
    }



    /*
     * =========================================================
     * STORE A RECEIVED MESH PACKET
     * =========================================================
     *
     * Called when this phone receives an encrypted mesh packet
     * that is NOT meant for this phone.
     *
     * The relay does not decrypt it.
     *
     * It simply:
     *
     * validate lifetime
     * deduplicate
     * persist ciphertext
     */

    suspend fun store(
        packet: NearbyMeshPacket,
        now: Long = System.currentTimeMillis(),
        locallyCreated: Boolean = false
    ): StoreResult =
        database.withTransaction {
            cleanup(now)
            if (!locallyCreated && !relayPacketDao.containsPacket(packet.packetId) &&
                relayPacketDao.countActivePackets(now) >= 2048) return@withTransaction StoreResult.INVALID
            val vaccine = database.antiPacketDao().getAntiPacket(packet.packetId)
            if (vaccine != null && vaccine.expiresAt > now &&
                vaccine.sourceNodeId == packet.sourceNodeId &&
                vaccine.recipientNodeId == packet.destinationNodeId
            ) StoreResult.DUPLICATE else storeInsideTransaction(packet, now)
        }

    private suspend fun storeInsideTransaction(
        packet: NearbyMeshPacket,
        now: Long =
            System.currentTimeMillis()
    ): StoreResult {


        /*
         * Basic structural sanity.
         */
        if (
            packet.packetId.isBlank() ||
            packet.sourceNodeId.isBlank() ||
            packet.destinationNodeId.isBlank() ||
            packet.createdAt <= 0L ||
            packet.expiresAt <=
            packet.createdAt ||
            packet.hopCount !in 0..MeshPolicy.MAX_MESH_HOPS ||
            packet.expiresAt - packet.createdAt > PacketReplayGuard.MESSAGE_TTL_MS ||
            packet.createdAt > now + PacketReplayGuard.MAX_FUTURE_CLOCK_SKEW_MS ||
            packet.encryptedPayload.isBlank() || !packet.hasValidOrigin()
        ) {

            return StoreResult.INVALID
        }



        /*
         * TTL is the real lifetime limiter.
         *
         * Hop count independently bounds relay propagation.
         */
        if (
            packet.isExpired(
                now
            )
        ) {

            return StoreResult.EXPIRED
        }



        /*
         * Remove dead packets while we are already touching
         * the relay database.
         */
        cleanup(now)



        /*
         * Fast persistent duplicate check.
         */
        if (
            relayPacketDao
                .containsPacket(
                    packet.packetId
                )
        ) {

            return StoreResult.DUPLICATE
        }



        val entity =
            RelayPacketEntity
                .fromMeshPacket(
                    packet
                )



        /*
         * insertPacket() uses INSERT IGNORE.
         *
         * So even if two coroutines race and both pass the
         * containsPacket() check, Room's primary key still
         * guarantees only one copy survives.
         */
        val rowId =
            relayPacketDao
                .insertPacket(
                    entity
                )



        return if (
            rowId == -1L
        ) {

            StoreResult.DUPLICATE

        } else {

            StoreResult.STORED
        }
    }



    /*
     * =========================================================
     * GET PACKETS TO FORWARD TO A PEER
     * =========================================================
     *
     * Plexus currently uses opportunistic / epidemic-style
     * forwarding.
     *
     * We do not yet know the complete network topology.
     *
     * So when an authenticated Plexus peer appears, we can offer
     * it packets we are carrying.
     *
     * Important optimization:
     *
     * packets whose FINAL destination is this peer come first.
     */

    suspend fun getForwardCandidates(
        peerNodeId: String,
        localNodeId: String? = null,
        now: Long =
            System.currentTimeMillis()
    ): List<NearbyMeshPacket> {


        if (
            peerNodeId.isBlank()
        ) {

            return emptyList()
        }



        cleanup(now)



        val activePackets =
            relayPacketDao
                .getActivePackets(
                    now
                )



        return activePackets

            /*
             * Don't immediately hand a packet back to the node
             * that originally created it.
             *
             * Even if we did, its packetId dedup would protect
             * it, but avoiding the obvious echo saves bandwidth.
             */
            .filter { entity ->

                entity.sourceNodeId !=
                        peerNodeId
            }


            /*
             * Destination packets first.
             *
             * Everything else can still be carried onward.
             */
            .sortedWith(

                compareByDescending<RelayPacketEntity> { entity ->

                    entity.destinationNodeId ==
                            peerNodeId

                }.thenBy { entity ->

                    entity.storedAt
                }
            )


            .mapNotNull { entity ->
                val packet = packetForRetry(entity) ?: return@mapNotNull null
                // Retransmission by the original sender is still hop zero.
                if (packet.sourceNodeId == localNodeId) packet else packet.forwardedCopy(now)
            }
    }



    // Every authenticated Internet-capable node may upload its durable signed relay copies.

    suspend fun getInternetUploadCandidates(
        localNodeId: String,
        offset: Int = 0,
        limit: Int = 32,
        now: Long =
            System.currentTimeMillis()
    ): List<NearbyMeshPacket> {


        if (
            localNodeId.isBlank()
        ) {

            return emptyList()
        }



        cleanup(now)



        return relayPacketDao.getInternetUploadPage(localNodeId, now, limit, offset)
            .mapNotNull { packetForRetry(it) }
    }



    /*
     * =========================================================
     * GET PACKETS WHOSE DESTINATION JUST APPEARED
     * =========================================================
     *
     * If the actual recipient connects directly, these should
     * get highest priority.
     */

    suspend fun getPacketsForDestination(
        destinationNodeId: String,
        now: Long =
            System.currentTimeMillis()
    ): List<NearbyMeshPacket> {


        if (
            destinationNodeId.isBlank()
        ) {

            return emptyList()
        }



        cleanup(now)



        return relayPacketDao
            .getActivePacketsForDestination(

                destinationNodeId =
                    destinationNodeId,

                now =
                    now
            )
            .mapNotNull { entity ->
                packetForRetry(entity)?.forwardedCopy(now)
            }
    }



    /*
     * =========================================================
     * DELETE ONE PACKET
     * =========================================================
     *
     * Later:
     *
     * signed delivery ACK / anti-packet arrives
     *                  ↓
     * delete(packetId)
     *
     * Until then, packets remain until TTL expiry.
     */

    suspend fun delete(
        packetId: String
    ): Boolean {


        if (
            packetId.isBlank()
        ) {

            return false
        }



        return relayPacketDao
            .deletePacket(
                packetId
            ) > 0
    }



    /*
     * =========================================================
     * CLEAN EXPIRED PACKETS
     * =========================================================
     */

    suspend fun cleanupExpired(
        now: Long =
            System.currentTimeMillis()
    ): Int {


        return cleanup(now)
    }



    /*
     * =========================================================
     * DEBUG / DIAGNOSTICS
     * =========================================================
     */

    suspend fun activePacketCount(
        now: Long =
            System.currentTimeMillis()
    ): Int {


        cleanup(now)


        return relayPacketDao
            .countActivePackets(
                now
            )
    }
}

internal fun resolvedLegacyPacket(signed: NearbyMeshPacket, updated: Int,
                                  persisted: RelayPacketEntity?): NearbyMeshPacket? =
    if (updated == 1) signed else persisted?.toMeshPacket()?.takeIf {
        it.originSignature.isNotEmpty() && it.originBytes().contentEquals(signed.originBytes())
    }
