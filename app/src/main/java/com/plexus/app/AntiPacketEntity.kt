package com.plexus.app

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(
    tableName = "anti_packets"
)
data class AntiPacketEntity(


    /*
     * =========================================================
     * ORIGINAL MESSAGE ID
     * =========================================================
     *
     * This is the same ID as:
     *
     * NearbyEncryptedChatPacket.messageId
     * NearbyMeshPacket.packetId
     *
     * Once an authenticated anti-packet exists for this ID,
     * this device knows that the original message reached its
     * final destination.
     *
     * Therefore:
     *
     * messageId = primary key
     *
     * One delivered message needs only one vaccine record.
     */

    @PrimaryKey
    val messageId: String,



    /*
     * =========================================================
     * ORIGINAL SOURCE
     * =========================================================
     */

    val sourceNodeId: String,



    /*
     * =========================================================
     * FINAL RECIPIENT
     * =========================================================
     *
     * This is the node that actually received the encrypted
     * message and created the signed anti-packet.
     */

    val recipientNodeId: String,



    /*
     * =========================================================
     * RECIPIENT SIGNING PUBLIC KEY
     * =========================================================
     *
     * Stored with the vaccine so the original signed evidence
     * is retained.
     *
     * The nodeId/public-key binding was already verified before
     * this entity should ever be inserted.
     */

    val recipientSigningPublicKey: String,



    /*
     * =========================================================
     * DELIVERY TIME
     * =========================================================
     *
     * This means delivery to the recipient DEVICE.
     *
     * It does NOT mean:
     *
     * human opened message
     * human read message
     */

    val deliveredAt: Long,



    /*
     * =========================================================
     * VACCINE EXPIRY
     * =========================================================
     *
     * Anti-packets live longer than their corresponding
     * message packets.
     *
     * Once this timestamp passes, the vaccine itself may be
     * cleaned from Room.
     */

    val expiresAt: Long,



    /*
     * =========================================================
     * RECIPIENT SIGNATURE
     * =========================================================
     *
     * ECDSA signature proving that the final recipient created
     * this delivery acknowledgement.
     */

    val signature: String,



    /*
     * =========================================================
     * LOCAL STORAGE TIME
     * =========================================================
     *
     * Not part of the cryptographic signature.
     *
     * This is only local metadata useful for:
     *
     * diagnostics
     * cleanup
     * debugging
     */

    val storedAt: Long,

    // Local Internet publication metadata; never serialized into the signed proof.
    val internetPublishedAt: Long? = null
) {


    /*
     * =========================================================
     * CONVERT BACK TO NETWORK ANTI-PACKET
     * =========================================================
     *
     * Useful when this node encounters another authenticated
     * Plexus relay later and wants to spread the vaccine.
     */

    fun toNearbyAntiPacket():
            NearbyAntiPacket {


        return NearbyAntiPacket(

            messageId =
                messageId,

            sourceNodeId =
                sourceNodeId,

            recipientNodeId =
                recipientNodeId,

            recipientSigningPublicKey =
                recipientSigningPublicKey,

            deliveredAt =
                deliveredAt,

            expiresAt =
                expiresAt,

            signature =
                signature
        )
    }



    companion object {


        /*
         * =====================================================
         * CREATE DATABASE ENTITY
         * =====================================================
         *
         * IMPORTANT:
         *
         * NearbyAntiPacket.fromBytes() already performs:
         *
         * nodeId/public-key verification
         * +
         * ECDSA signature verification
         *
         * So normally only cryptographically valid packets
         * should reach this conversion.
         */

        fun fromNearbyAntiPacket(
            packet: NearbyAntiPacket,
            storedAt: Long =
                System.currentTimeMillis()
        ): AntiPacketEntity {


            return AntiPacketEntity(

                messageId =
                    packet.messageId,

                sourceNodeId =
                    packet.sourceNodeId,

                recipientNodeId =
                    packet.recipientNodeId,

                recipientSigningPublicKey =
                    packet.recipientSigningPublicKey,

                deliveredAt =
                    packet.deliveredAt,

                expiresAt =
                    packet.expiresAt,

                signature =
                    packet.signature,

                storedAt =
                    storedAt
            )
        }
    }
}