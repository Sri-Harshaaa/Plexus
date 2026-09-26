package com.plexus.app

import androidx.room.Entity
import androidx.room.PrimaryKey


/*
 * Durable Room representation of a signed
 * ConnectionRequestPacket.
 *
 * We persist BOTH:
 *
 * - outgoing requests we created
 * - incoming requests we received
 *
 * This allows the request lifecycle to survive:
 *
 * - app restart
 * - phone restart
 * - temporary lack of internet
 * - temporary lack of Nearby peers
 */
@Entity(
    tableName = "connection_requests"
)
data class ConnectionRequestEntity(


    /*
     * Globally unique request identifier.
     *
     * Also gives Room persistent deduplication:
     *
     * the exact same request packet cannot be stored twice.
     */
    @PrimaryKey
    val requestId: String,


    /*
     * Plexus identity that created the request.
     */
    val senderNodeId: String,


    /*
     * Plexus identity the request is addressed to.
     */
    val recipientNodeId: String,


    /*
     * Sender's self-chosen Plexus profile name
     * at the time the request was created.
     *
     * This is NOT necessarily the local alias the
     * recipient will eventually save.
     */
    val senderDisplayName: String,


    /*
     * Sender's public signing key.
     *
     * senderNodeId must bind to this key.
     */
    val senderSigningPublicKey: String,


    /*
     * Sender's ECDH agreement public key.
     *
     * This becomes part of the trusted contact identity
     * if the request is accepted.
     */
    val senderAgreementPublicKey: String,


    /*
     * Original protocol timestamps.
     */
    val createdAt: Long,

    val expiresAt: Long,


    /*
     * Sender's ECDSA signature over the canonical
     * ConnectionRequestPacket contents.
     */
    val signature: String,


    /*
     * Local database bookkeeping timestamp.
     *
     * This is NOT signed and is NOT part of the protocol.
     */
    val storedAt: Long =
        System.currentTimeMillis()
) {


    /*
     * Convert the durable Room row back into the
     * cryptographically verifiable protocol packet.
     */
    fun toPacket():
            ConnectionRequestPacket {


        return ConnectionRequestPacket(

            requestId =
                requestId,

            senderNodeId =
                senderNodeId,

            recipientNodeId =
                recipientNodeId,

            senderDisplayName =
                senderDisplayName,

            senderSigningPublicKey =
                senderSigningPublicKey,

            senderAgreementPublicKey =
                senderAgreementPublicKey,

            createdAt =
                createdAt,

            expiresAt =
                expiresAt,

            signature =
                signature
        )
    }


    companion object {


        /*
         * Convert a verified protocol packet into a
         * persistent Room row.
         *
         * Callers should only persist packets returned by:
         *
         * ConnectionRequestPacket.fromBytes()
         *
         * or packets created locally through:
         *
         * ConnectionRequestPacket.create()
         */
        fun fromPacket(
            packet:
            ConnectionRequestPacket
        ): ConnectionRequestEntity {


            return ConnectionRequestEntity(

                requestId =
                    packet.requestId,

                senderNodeId =
                    packet.senderNodeId,

                recipientNodeId =
                    packet.recipientNodeId,

                senderDisplayName =
                    packet.senderDisplayName,

                senderSigningPublicKey =
                    packet.senderSigningPublicKey,

                senderAgreementPublicKey =
                    packet.senderAgreementPublicKey,

                createdAt =
                    packet.createdAt,

                expiresAt =
                    packet.expiresAt,

                signature =
                    packet.signature
            )
        }
    }
}