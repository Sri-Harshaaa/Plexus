package com.plexus.app

import androidx.room.withTransaction


class AntiPacketStore(private val database: PlexusDatabase) {
    private val antiPacketDao = database.antiPacketDao()
    private val relayPacketDao = database.relayPacketDao()


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
     * STORE SIGNED ANTI-PACKET
     * =========================================================
     *
     * A valid anti-packet means:
     *
     * "message X reached its final recipient device"
     *
     * Once accepted:
     *
     * 1. store the tiny signed vaccine
     * 2. delete our heavy encrypted relay copy
     *
     * IMPORTANT:
     *
     * We DO NOT trust messageId alone.
     *
     * If we still possess the original relay packet, the
     * anti-packet must also agree with:
     *
     * sourceNodeId
     * destinationNodeId
     *
     * This prevents an unrelated signed anti-packet from
     * deleting a message merely because it claims the same ID.
     */

    suspend fun store(
        packet: NearbyAntiPacket,
        now: Long = System.currentTimeMillis(),
        locallyCreated: Boolean = false
    ): StoreResult = database.withTransaction {
        antiPacketDao.deleteExpiredAntiPackets(now)
        val original = database.messageDao().getMessageById(packet.messageId)
        if (original != null && original.isMine && original.contactNodeId != packet.recipientNodeId) {
            return@withTransaction StoreResult.INVALID
        }
        if (!locallyCreated && original == null &&
            antiPacketDao.getAntiPacket(packet.messageId) == null &&
            antiPacketDao.countActiveAntiPackets(now) >= 8192) {
            return@withTransaction StoreResult.INVALID
        }
        storeInsideTransaction(packet, now)
    }

    private suspend fun storeInsideTransaction(
        packet: NearbyAntiPacket,
        now: Long =
            System.currentTimeMillis()
    ): StoreResult {


        /*
         * Basic structural sanity.
         */
        if (
            packet.messageId.isBlank() ||
            packet.sourceNodeId.isBlank() ||
            packet.recipientNodeId.isBlank() ||
            packet.recipientSigningPublicKey.isBlank() ||
            packet.deliveredAt <= 0L ||
            packet.expiresAt <=
            packet.deliveredAt ||
            packet.signature.isBlank()
        ) {

            return StoreResult.INVALID
        }



        /*
         * Don't preserve already-dead vaccines.
         */
        if (
            packet.isExpired(
                now
            )
        ) {

            return StoreResult.EXPIRED
        }



        /*
         * Verify:
         *
         * SHA256(signingPublicKey) == recipientNodeId
         *
         * +
         *
         * recipient ECDSA signature
         */
        if (
            !packet
                .isCryptographicallyValid()
        ) {

            return StoreResult.INVALID
        }



        /*
         * Remove expired vaccines before considering primary-key
         * conflicts.
         */
        antiPacketDao
            .deleteExpiredAntiPackets(
                now
            )



        /*
         * Do we still carry the corresponding encrypted message?
         */
        val relayPacket =
            relayPacketDao
                .getPacket(
                    packet.messageId
                )



        if (
            relayPacket != null
        ) {


            /*
             * The recipient signing the anti-packet MUST be the
             * final destination recorded in the original mesh
             * packet.
             *
             * Likewise the claimed source must be the original
             * message source.
             */
            val matchesOriginalMessage =

                relayPacket.sourceNodeId ==
                        packet.sourceNodeId &&

                        relayPacket.destinationNodeId ==
                        packet.recipientNodeId



            if (
                !matchesOriginalMessage
            ) {

                /*
                 * Valid signature, but NOT a valid delivery
                 * acknowledgement for the message we are
                 * carrying.
                 */
                return StoreResult.INVALID
            }
        }



        /*
         * Check whether we already have a vaccine for this
         * messageId.
         */
        val existing =
            antiPacketDao
                .getAntiPacket(
                    packet.messageId
                )



        if (
            existing != null &&
            existing.expiresAt > now
        ) {


            val sameDeliveryIdentity =

                existing.sourceNodeId ==
                        packet.sourceNodeId &&

                        existing.recipientNodeId ==
                        packet.recipientNodeId



            if (
                sameDeliveryIdentity
            ) {


                /*
                 * Even if our relay payload somehow still exists,
                 * a valid active vaccine means we can safely
                 * remove it now.
                 */
                relayPacketDao
                    .deletePacket(
                        packet.messageId
                    )


                return StoreResult.DUPLICATE
            }



            /*
             * Extremely defensive case:
             *
             * same messageId
             * but different source / recipient.
             *
             * If we possess the original relay packet and the
             * NEW anti-packet matches that original packet, then
             * the old vaccine is the conflicting one.
             *
             * Replace the conflicting vaccine.
             */
            if (
                relayPacket != null
            ) {


                antiPacketDao
                    .deleteAntiPacket(
                        packet.messageId
                    )


            } else {


                /*
                 * We have no original packet available to tell
                 * which conflicting anti-packet corresponds to
                 * the real message.
                 *
                 * Safest behavior:
                 *
                 * don't overwrite anything
                 * don't delete anything
                 */
                return StoreResult.INVALID
            }
        }



        val entity =
            AntiPacketEntity
                .fromNearbyAntiPacket(
                    packet =
                        packet,

                    storedAt =
                        now
                )



        val rowId =
            antiPacketDao
                .insertAntiPacket(
                    entity
                )



        if (
            rowId == -1L
        ) {

            return StoreResult.DUPLICATE
        }



        /*
         * The vaccine is now durably stored.
         *
         * Only NOW do we remove the heavy relay payload.
         */
        relayPacketDao
            .deletePacket(
                packet.messageId
            )



        return StoreResult.STORED
    }



    /*
     * =========================================================
     * DOES THIS MESH PACKET HAVE AN ACTIVE VACCINE?
     * =========================================================
     *
     * This is called BEFORE accepting/storing/forwarding an old
     * mesh packet.
     *
     * IMPORTANT:
     *
     * We check:
     *
     * messageId
     * sourceNodeId
     * destinationNodeId
     *
     * NOT messageId alone.
     */

    suspend fun hasActiveVaccineFor(
        packet: NearbyMeshPacket,
        now: Long =
            System.currentTimeMillis()
    ): Boolean {


        antiPacketDao
            .deleteExpiredAntiPackets(
                now
            )



        val vaccine =
            antiPacketDao
                .getAntiPacket(
                    packet.packetId
                )
                ?: return false



        if (
            vaccine.expiresAt <= now
        ) {

            return false
        }



        if (
            vaccine.sourceNodeId !=
            packet.sourceNodeId
        ) {

            return false
        }



        if (
            vaccine.recipientNodeId !=
            packet.destinationNodeId
        ) {

            return false
        }



        /*
         * It was verified when stored.
         *
         * Re-verifying here gives us an additional safety layer
         * in case the database is ever corrupted or manually
         * modified during development.
         */
        if (
            !vaccine
                .toNearbyAntiPacket()
                .isCryptographicallyValid()
        ) {

            return false
        }



        return true
    }



    /*
     * =========================================================
     * ACTIVE ANTI-PACKETS TO PROPAGATE
     * =========================================================
     *
     * Whenever another authenticated Plexus peer appears,
     * these small vaccines can be forwarded to it.
     */

    suspend fun getPropagationCandidates(
        now: Long =
            System.currentTimeMillis()
    ): List<NearbyAntiPacket> {


        antiPacketDao
            .deleteExpiredAntiPackets(
                now
            )



        return antiPacketDao
            .getActiveAntiPackets(
                now
            )
            .map { entity ->

                entity
                    .toNearbyAntiPacket()
            }
    }

    suspend fun getPropagationWindow(offset: Int, limit: Int, now: Long = System.currentTimeMillis()): List<NearbyAntiPacket> {
        return antiPacketDao.getActiveAntiPacketWindow(now, limit, offset)
            .map { it.toNearbyAntiPacket() }
    }



    /*
     * =========================================================
     * CLEAN EXPIRED ANTI-PACKETS
     * =========================================================
     */

    suspend fun cleanupExpired(
        now: Long =
            System.currentTimeMillis()
    ): Int {


        return antiPacketDao
            .deleteExpiredAntiPackets(
                now
            )
    }



    /*
     * =========================================================
     * ACTIVE VACCINE COUNT
     * =========================================================
     */

    suspend fun activeAntiPacketCount(
        now: Long =
            System.currentTimeMillis()
    ): Int {


        antiPacketDao
            .deleteExpiredAntiPackets(
                now
            )


        return antiPacketDao
            .countActiveAntiPackets(
                now
            )
    }
}
