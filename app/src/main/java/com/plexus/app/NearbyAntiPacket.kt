package com.plexus.app

import org.json.JSONObject


data class NearbyAntiPacket(

    /*
     * The original encrypted message ID.
     */
    val messageId: String,


    /*
     * Original message sender.
     */
    val sourceNodeId: String,


    /*
     * The final recipient who successfully received
     * and authenticated the message.
     */
    val recipientNodeId: String,


    /*
     * Recipient's signing public key.
     *
     * Relays need this because they may NOT have the
     * recipient saved as a contact.
     *
     * They verify:
     *
     * SHA256(publicKey) == recipientNodeId
     *
     * before trusting the signature.
     */
    val recipientSigningPublicKey: String,


    /*
     * When the final recipient accepted the message.
     *
     * This means DEVICE DELIVERY.
     *
     * It does NOT mean the human opened/read it.
     */
    val deliveredAt: Long,


    /*
     * Anti-packets live longer than normal messages
     * so they have enough time to clean stale copies
     * throughout the mesh.
     */
    val expiresAt: Long,


    /*
     * ECDSA signature created by the final recipient.
     */
    val signature: String

) {


    /*
     * =========================================================
     * SERIALIZE
     * =========================================================
     */

    fun toBytes(): ByteArray {


        val json =
            JSONObject().apply {


                put(
                    "version",
                    1
                )


                put(
                    "type",
                    "delivery_anti_packet"
                )


                put(
                    "messageId",
                    messageId
                )


                put(
                    "sourceNodeId",
                    sourceNodeId
                )


                put(
                    "recipientNodeId",
                    recipientNodeId
                )


                put(
                    "recipientSigningPublicKey",
                    recipientSigningPublicKey
                )


                put(
                    "deliveredAt",
                    deliveredAt
                )


                put(
                    "expiresAt",
                    expiresAt
                )


                put(
                    "signature",
                    signature
                )
            }


        return json
            .toString()
            .toByteArray(
                Charsets.UTF_8
            )
    }



    /*
     * =========================================================
     * EXPIRY
     * =========================================================
     */

    fun isExpired(
        now: Long =
            System.currentTimeMillis()
    ): Boolean {


        return now >=
                expiresAt
    }



    /*
     * =========================================================
     * CRYPTOGRAPHIC VERIFICATION
     * =========================================================
     *
     * Any Plexus relay can verify this anti-packet.
     *
     * It does NOT need to know the recipient beforehand.
     */

    fun isCryptographicallyValid(): Boolean {
        if (messageId.length !in 1..128 ||
            !sourceNodeId.matches(Regex("[0-9a-f]{64}")) ||
            !recipientNodeId.matches(Regex("[0-9a-f]{64}")) ||
            recipientSigningPublicKey.length > 512 || signature.length > 256 ||
            deliveredAt <= 0 || expiresAt <= deliveredAt ||
            expiresAt - deliveredAt > ANTI_PACKET_TTL_MS ||
            deliveredAt > System.currentTimeMillis() + PacketReplayGuard.MAX_FUTURE_CLOCK_SKEW_MS
        ) return false


        /*
         * First prove that this public key actually corresponds
         * to recipientNodeId.
         */
        val identityBindingValid =
            PeerIdentityVerifier
                .isValidIdentity(

                    nodeId =
                        recipientNodeId,

                    signingPublicKey =
                        recipientSigningPublicKey
                )


        if (
            !identityBindingValid
        ) {

            return false
        }



        val signedData =
            buildSigningPayload(

                messageId =
                    messageId,

                sourceNodeId =
                    sourceNodeId,

                recipientNodeId =
                    recipientNodeId,

                deliveredAt =
                    deliveredAt,

                expiresAt =
                    expiresAt
            )



        return IdentityCrypto
            .verify(

                data =
                    signedData,

                signatureBase64 =
                    signature,

                publicKeyBase64 =
                    recipientSigningPublicKey
            )
    }



    companion object {


        /*
         * Anti-packets deliberately survive longer than
         * normal message packets.
         *
         * Current message TTL:
         *
         * 24 hours
         *
         * Current anti-packet TTL:
         *
         * 48 hours
         */
        const val ANTI_PACKET_TTL_MS =
            PacketReplayGuard.MESSAGE_TTL_MS * 2L



        /*
         * =====================================================
         * CREATE SIGNED ANTI-PACKET
         * =====================================================
         *
         * IMPORTANT:
         *
         * Call this only AFTER:
         *
         * AES-GCM authentication succeeds
         * AND
         * the message is accepted/stored locally.
         *
         * This is a DELIVERY confirmation,
         * not a read receipt.
         */

        fun create(

            deliveredPacket:
            NearbyEncryptedChatPacket,

            recipientIdentity:
            PlexusIdentity,

            deliveredAt: Long =
                System.currentTimeMillis()

        ): NearbyAntiPacket {


            require(
                deliveredPacket.recipientNodeId ==
                        recipientIdentity.nodeId
            ) {

                "Cannot create anti-packet for a message " +
                        "addressed to another node"
            }



            require(
                deliveredPacket.senderNodeId !=
                        recipientIdentity.nodeId
            ) {

                "Recipient cannot also be the original sender"
            }



            val expiresAt =

                if (
                    Long.MAX_VALUE -
                    deliveredAt <
                    ANTI_PACKET_TTL_MS
                ) {

                    Long.MAX_VALUE

                } else {

                    deliveredAt +
                            ANTI_PACKET_TTL_MS
                }



            val signedData =
                buildSigningPayload(

                    messageId =
                        deliveredPacket.messageId,

                    sourceNodeId =
                        deliveredPacket.senderNodeId,

                    recipientNodeId =
                        recipientIdentity.nodeId,

                    deliveredAt =
                        deliveredAt,

                    expiresAt =
                        expiresAt
                )



            /*
             * IdentityCrypto finds the recipient's signing
             * private key inside Android Keystore using nodeId.
             */
            val signature =
                IdentityCrypto
                    .sign(

                        data =
                            signedData,

                        responderNodeId =
                            recipientIdentity.nodeId
                    )



            return NearbyAntiPacket(

                messageId =
                    deliveredPacket.messageId,

                sourceNodeId =
                    deliveredPacket.senderNodeId,

                recipientNodeId =
                    recipientIdentity.nodeId,

                recipientSigningPublicKey =
                    recipientIdentity.signingPublicKey,

                deliveredAt =
                    deliveredAt,

                expiresAt =
                    expiresAt,

                signature =
                    signature
            )
        }



        /*
         * =====================================================
         * PARSE
         * =====================================================
         */

        fun fromBytes(
            bytes: ByteArray
        ): NearbyAntiPacket? {
            if (bytes.size > 4096) return null


            return try {


                val json =
                    JSONObject(

                        bytes.toString(
                            Charsets.UTF_8
                        )
                    )



                if (
                    json.optInt(
                        "version"
                    ) != 1
                ) {

                    return null
                }



                if (
                    json.optString(
                        "type"
                    ) !=
                    "delivery_anti_packet"
                ) {

                    return null
                }



                val messageId =
                    json.optString(
                        "messageId"
                    )



                val sourceNodeId =
                    json.optString(
                        "sourceNodeId"
                    )



                val recipientNodeId =
                    json.optString(
                        "recipientNodeId"
                    )



                val recipientSigningPublicKey =
                    json.optString(
                        "recipientSigningPublicKey"
                    )



                val deliveredAt =
                    json.optLong(
                        "deliveredAt"
                    )



                val expiresAt =
                    json.optLong(
                        "expiresAt"
                    )



                val signature =
                    json.optString(
                        "signature"
                    )



                /*
                 * Structural validation.
                 */
                if (
                    messageId.isBlank() ||
                    sourceNodeId.isBlank() ||
                    recipientNodeId.isBlank() ||
                    recipientSigningPublicKey.isBlank() ||
                    deliveredAt <= 0L ||
                    expiresAt <= deliveredAt ||
                    signature.isBlank()
                ) {

                    return null
                }



                val packet =
                    NearbyAntiPacket(

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



                /*
                 * Don't even expose forged anti-packets to the
                 * rest of the app.
                 */
                if (
                    !packet
                        .isCryptographicallyValid()
                ) {

                    return null
                }



                packet


            } catch (
                exception: Exception
            ) {


                null
            }
        }



        /*
         * =====================================================
         * CANONICAL SIGNED DATA
         * =====================================================
         *
         * Every field that matters to delivery confirmation is
         * included in the signature.
         *
         * A relay cannot change:
         *
         * messageId
         * source
         * recipient
         * delivery time
         * expiry
         *
         * without invalidating the signature.
         */

        private fun buildSigningPayload(

            messageId: String,

            sourceNodeId: String,

            recipientNodeId: String,

            deliveredAt: Long,

            expiresAt: Long

        ): ByteArray {


            val canonical =
                "PLEXUS_DELIVERY_V1|" +
                        messageId +
                        "|" +
                        sourceNodeId +
                        "|" +
                        recipientNodeId +
                        "|" +
                        deliveredAt +
                        "|" +
                        expiresAt


            return canonical
                .toByteArray(
                    Charsets.UTF_8
                )
        }
    }
}
