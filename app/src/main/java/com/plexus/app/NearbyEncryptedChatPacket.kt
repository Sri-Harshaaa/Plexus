package com.plexus.app

import org.json.JSONObject
import java.util.UUID


data class NearbyEncryptedChatPacket(

    val messageId: String,

    val senderNodeId: String,

    val recipientNodeId: String,

    val ciphertext: String,

    val nonce: String,

    val timestamp: Long

) {


    /*
     * =========================================================
     * SERIALIZE
     * =========================================================
     *
     * IMPORTANT:
     *
     * There is intentionally NO plaintext "text" field here.
     *
     * Wire packet contains only:
     *
     * metadata
     * nonce
     * encrypted ciphertext
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
                    "encrypted_chat"
                )


                put(
                    "messageId",
                    messageId
                )


                put(
                    "senderNodeId",
                    senderNodeId
                )


                put(
                    "recipientNodeId",
                    recipientNodeId
                )


                put(
                    "ciphertext",
                    ciphertext
                )


                put(
                    "nonce",
                    nonce
                )


                put(
                    "timestamp",
                    timestamp
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
     * DECRYPT
     * =========================================================
     */

    fun decrypt(
        sessionKey: ByteArray
    ): String? {


        return try {


            val aad =
                PlexusCrypto
                    .buildMessageAad(

                        senderNodeId =
                            senderNodeId,

                        recipientNodeId =
                            recipientNodeId,

                        messageId =
                            messageId,

                        timestamp =
                            timestamp
                    )



            val plaintextBytes =
                PlexusCrypto
                    .decrypt(

                        ciphertextBase64 =
                            ciphertext,

                        nonceBase64 =
                            nonce,

                        sessionKey =
                            sessionKey,

                        aad =
                            aad
                    )



            val plaintext =
                plaintextBytes.toString(
                    Charsets.UTF_8
                )



            if (
                plaintext.isBlank()
            ) {

                null

            } else {

                plaintext
            }


        } catch (
            exception: Exception
        ) {


            null
        }
    }



    companion object {


        /*
         * =====================================================
         * CREATE ENCRYPTED CHAT PACKET
         * =====================================================
         */

        fun create(

            senderNodeId: String,

            recipientNodeId: String,

            text: String,

            sessionKey: ByteArray

        ): NearbyEncryptedChatPacket {


            require(
                senderNodeId.isNotBlank()
            ) {

                "Sender node ID cannot be blank"
            }


            require(
                recipientNodeId.isNotBlank()
            ) {

                "Recipient node ID cannot be blank"
            }


            require(
                text.isNotBlank()
            ) {

                "Message text cannot be blank"
            }



            val messageId =
                UUID
                    .randomUUID()
                    .toString()



            val timestamp =
                System
                    .currentTimeMillis()



            val aad =
                PlexusCrypto
                    .buildMessageAad(

                        senderNodeId =
                            senderNodeId,

                        recipientNodeId =
                            recipientNodeId,

                        messageId =
                            messageId,

                        timestamp =
                            timestamp
                    )



            val encrypted =
                PlexusCrypto
                    .encrypt(

                        plaintext =
                            text.toByteArray(
                                Charsets.UTF_8
                            ),

                        sessionKey =
                            sessionKey,

                        aad =
                            aad
                    )



            return NearbyEncryptedChatPacket(

                messageId =
                    messageId,

                senderNodeId =
                    senderNodeId,

                recipientNodeId =
                    recipientNodeId,

                ciphertext =
                    encrypted.ciphertext,

                nonce =
                    encrypted.nonce,

                timestamp =
                    timestamp
            )
        }



        /*
         * =====================================================
         * PARSE
         * =====================================================
         */

        fun fromBytes(
            bytes: ByteArray
        ): NearbyEncryptedChatPacket? {
            if (bytes.size > MeshPolicy.MAX_PACKET_BYTES) return null


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
                    ) != "encrypted_chat"
                ) {

                    return null
                }



                val messageId =
                    json.optString(
                        "messageId"
                    )



                val senderNodeId =
                    json.optString(
                        "senderNodeId"
                    )



                val recipientNodeId =
                    json.optString(
                        "recipientNodeId"
                    )



                val ciphertext =
                    json.optString(
                        "ciphertext"
                    )



                val nonce =
                    json.optString(
                        "nonce"
                    )



                val timestamp =
                    json.optLong(
                        "timestamp"
                    )



                if (
                    messageId.length !in 1..128 ||
                    senderNodeId.isBlank() ||
                    recipientNodeId.isBlank() ||
                    ciphertext.isBlank() ||
                    nonce.length != 16 ||
                    ciphertext.length > 24_000 ||
                    timestamp <= 0L
                ) {

                    return null
                }



                /*
                 * Current UI converts messageId using
                 * UUID.fromString(), so reject malformed IDs.
                 */
                try {


                    UUID.fromString(
                        messageId
                    )


                } catch (
                    exception: Exception
                ) {


                    return null
                }



                NearbyEncryptedChatPacket(

                    messageId =
                        messageId,

                    senderNodeId =
                        senderNodeId,

                    recipientNodeId =
                        recipientNodeId,

                    ciphertext =
                        ciphertext,

                    nonce =
                        nonce,

                    timestamp =
                        timestamp
                )


            } catch (
                exception: Exception
            ) {


                null
            }
        }
    }
}
