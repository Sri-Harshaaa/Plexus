package com.plexus.app

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(
    tableName = "relay_packets"
)
data class RelayPacketEntity(


    /*
     * Same ID as:
     *
     * NearbyEncryptedChatPacket.messageId
     *
     * This being the primary key gives us persistent
     * deduplication even across app/process restarts.
     */
    @PrimaryKey
    val packetId: String,


    /*
     * Original sender of the message.
     *
     * NOT the most recent relay.
     */
    val sourceNodeId: String,


    /*
     * Final intended recipient.
     */
    val destinationNodeId: String,


    /*
     * Original message creation time.
     */
    val createdAt: Long,


    /*
     * Absolute expiry time.
     *
     * Once current time > expiresAt,
     * the relay packet can be deleted permanently.
     */
    val expiresAt: Long,


    /*
     * Informational only.
     *
     * MeshPolicy bounds hop count independently of expiration.
     *
     * We never delete or reject a packet merely because
     * this value becomes large.
     */
    val hopCount: Long,


    /*
     * Base64 encoded NearbyEncryptedChatPacket.
     *
     * Relay nodes store this opaque value without having
     * the AES key required to decrypt the message body.
     */
    val encryptedPayload: String,


    /*
     * When THIS device first stored the packet.
     *
     * Useful later for debugging / queue inspection /
     * relay analytics.
     */
    val storedAt: Long
) {


    /*
     * =========================================================
     * CONVERT BACK TO NETWORK PACKET
     * =========================================================
     */

    fun toMeshPacket(): NearbyMeshPacket {


        return NearbyMeshPacket(

            packetId =
                packetId,

            sourceNodeId =
                sourceNodeId,

            destinationNodeId =
                destinationNodeId,

            createdAt =
                createdAt,

            expiresAt =
                expiresAt,

            hopCount =
                hopCount,

            encryptedPayload =
                encryptedPayload
        )
    }



    companion object {


        /*
         * =====================================================
         * CREATE ROOM ENTITY FROM RECEIVED MESH PACKET
         * =====================================================
         */

        fun fromMeshPacket(
            packet: NearbyMeshPacket
        ): RelayPacketEntity {


            return RelayPacketEntity(

                packetId =
                    packet.packetId,

                sourceNodeId =
                    packet.sourceNodeId,

                destinationNodeId =
                    packet.destinationNodeId,

                createdAt =
                    packet.createdAt,

                expiresAt =
                    packet.expiresAt,

                hopCount =
                    packet.hopCount,

                encryptedPayload =
                    packet.encryptedPayload,

                storedAt =
                    System.currentTimeMillis()
            )
        }
    }
}
