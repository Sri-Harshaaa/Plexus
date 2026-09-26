package com.plexus.app

import org.json.JSONObject
import java.util.UUID


data class ConnectionRequestPacket(

    val requestId: String,

    val senderNodeId: String,

    val recipientNodeId: String,

    val senderDisplayName: String,

    val senderSigningPublicKey: String,

    val senderAgreementPublicKey: String,

    val createdAt: Long,

    val expiresAt: Long,

    val signature: String
) {


    /*
     * =========================================================
     * SERIALIZE
     * =========================================================
     */

    fun toBytes(): ByteArray {


        val json =
            JSONObject()
                .put(
                    "type",
                    TYPE
                )
                .put(
                    "version",
                    VERSION
                )
                .put(
                    "requestId",
                    requestId
                )
                .put(
                    "senderNodeId",
                    senderNodeId
                )
                .put(
                    "recipientNodeId",
                    recipientNodeId
                )
                .put(
                    "senderDisplayName",
                    senderDisplayName
                )
                .put(
                    "senderSigningPublicKey",
                    senderSigningPublicKey
                )
                .put(
                    "senderAgreementPublicKey",
                    senderAgreementPublicKey
                )
                .put(
                    "createdAt",
                    createdAt
                )
                .put(
                    "expiresAt",
                    expiresAt
                )
                .put(
                    "signature",
                    signature
                )


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


        return expiresAt <=
                now
    }



    /*
     * =========================================================
     * VERIFY
     * =========================================================
     *
     * Verification means:
     *
     * 1. nodeId really belongs to senderSigningPublicKey
     *
     * 2. packet fields were signed by the private key belonging
     *    to that nodeId
     *
     * 3. packet timing is sane
     *
     * Therefore somebody cannot simply forge:
     *
     * "I am Harsha and I want to connect"
     *
     * without Harsha's Plexus signing private key.
     */

    fun verify(
        now: Long =
            System.currentTimeMillis()
    ): Boolean {


        /*
         * Basic structure.
         */
        if (
            requestId.isBlank() ||
            senderNodeId.isBlank() ||
            recipientNodeId.isBlank() ||
            senderSigningPublicKey.isBlank() ||
            senderAgreementPublicKey.isBlank()
        ) {

            return false
        }


        /*
         * Self-request makes no sense.
         */
        if (
            senderNodeId ==
            recipientNodeId
        ) {

            return false
        }


        /*
         * Display names are user-controlled metadata,
         * but keep them bounded.
         */
        val cleanName =
            senderDisplayName.trim()


        if (
            cleanName.isEmpty() ||
            cleanName.length >
            MAX_DISPLAY_NAME_LENGTH
        ) {

            return false
        }


        /*
         * requestId must actually be a UUID.
         */
        try {

            UUID.fromString(
                requestId
            )

        } catch (
            exception: Exception
        ) {

            return false
        }


        /*
         * createdAt cannot be ridiculously far in the future.
         */
        if (
            createdAt >
            now + MAX_FUTURE_CLOCK_SKEW_MILLIS
        ) {

            return false
        }


        /*
         * expiresAt must follow createdAt.
         */
        if (
            expiresAt <=
            createdAt
        ) {

            return false
        }


        /*
         * Do not accept packets claiming an arbitrarily
         * enormous lifetime.
         */
        if (
            expiresAt - createdAt >
            REQUEST_TTL_MILLIS
        ) {

            return false
        }


        if (
            isExpired(
                now
            )
        ) {

            return false
        }


        /*
         * The sender's nodeId is self-authenticating:
         *
         * nodeId =
         * SHA-256(signing public key)
         */
        val validIdentity =
            PeerIdentityVerifier
                .isValidIdentity(

                    nodeId =
                        senderNodeId,

                    signingPublicKey =
                        senderSigningPublicKey
                )


        if (
            !validIdentity
        ) {

            return false
        }


        /*
         * Finally verify that the live packet contents
         * were signed by that identity.
         */
        val signingData =
            buildSigningData(

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
                    expiresAt
            )


        return IdentityCrypto
            .verify(

                data =
                    signingData,

                signatureBase64 =
                    signature,

                publicKeyBase64 =
                    senderSigningPublicKey
            )
    }



    companion object {


        private const val TYPE =
            "connection_request"


        private const val VERSION =
            1


        /*
         * A request can remain alive for up to 30 days.
         *
         * It can therefore survive:
         *
         * - recipient being offline
         * - temporary lack of Nearby peers
         * - internet outages
         *
         * while still eventually expiring.
         */
        const val REQUEST_TTL_MILLIS =
            30L *
                    24L *
                    60L *
                    60L *
                    1000L


        /*
         * Same general clock-skew tolerance we already use
         * elsewhere in Plexus.
         */
        private const val MAX_FUTURE_CLOCK_SKEW_MILLIS =
            5L *
                    60L *
                    1000L


        private const val MAX_DISPLAY_NAME_LENGTH =
            64


        /*
         * Defensive limit against somebody feeding an
         * absurdly large BYTES payload into the parser.
         */
        private const val MAX_PACKET_BYTES =
            32 * 1024



        /*
         * =====================================================
         * CREATE
         * =====================================================
         */

        fun create(

            senderIdentity: PlexusIdentity,

            recipientNodeId: String,

            now: Long =
                System.currentTimeMillis()

        ): ConnectionRequestPacket {


            val cleanRecipientNodeId =
                recipientNodeId.trim()


            val cleanDisplayName =
                senderIdentity
                    .displayName
                    .trim()


            require(
                cleanRecipientNodeId.isNotEmpty()
            ) {

                "Recipient nodeId cannot be empty"
            }


            require(
                cleanRecipientNodeId !=
                        senderIdentity.nodeId
            ) {

                "Cannot create a connection request to self"
            }


            require(
                cleanDisplayName.isNotEmpty()
            ) {

                "Sender display name cannot be empty"
            }


            require(
                cleanDisplayName.length <=
                        MAX_DISPLAY_NAME_LENGTH
            ) {

                "Sender display name is too long"
            }


            /*
             * Sanity-check our own public identity before
             * creating anything that will leave the device.
             */
            require(

                PeerIdentityVerifier
                    .isValidIdentity(

                        nodeId =
                            senderIdentity.nodeId,

                        signingPublicKey =
                            senderIdentity.signingPublicKey
                    )

            ) {

                "Local Plexus identity is invalid"
            }


            val requestId =
                UUID
                    .randomUUID()
                    .toString()


            val expiresAt =
                now +
                        REQUEST_TTL_MILLIS


            val signingData =
                buildSigningData(

                    requestId =
                        requestId,

                    senderNodeId =
                        senderIdentity.nodeId,

                    recipientNodeId =
                        cleanRecipientNodeId,

                    senderDisplayName =
                        cleanDisplayName,

                    senderSigningPublicKey =
                        senderIdentity.signingPublicKey,

                    senderAgreementPublicKey =
                        senderIdentity.agreementPublicKey,

                    createdAt =
                        now,

                    expiresAt =
                        expiresAt
                )


            /*
             * IdentityCrypto locates our private signing key
             * in Android Keystore using senderNodeId.
             */
            val signature =
                IdentityCrypto
                    .sign(

                        data =
                            signingData,

                        responderNodeId =
                            senderIdentity.nodeId
                    )


            return ConnectionRequestPacket(

                requestId =
                    requestId,

                senderNodeId =
                    senderIdentity.nodeId,

                recipientNodeId =
                    cleanRecipientNodeId,

                senderDisplayName =
                    cleanDisplayName,

                senderSigningPublicKey =
                    senderIdentity.signingPublicKey,

                senderAgreementPublicKey =
                    senderIdentity.agreementPublicKey,

                createdAt =
                    now,

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
         *
         * Returns null for:
         *
         * - malformed JSON
         * - wrong packet type
         * - wrong protocol version
         * - invalid node/public-key binding
         * - bad signature
         * - expired request
         * - impossible timestamps
         */

        fun fromBytes(
            bytes: ByteArray
        ): ConnectionRequestPacket? {


            if (
                bytes.isEmpty() ||
                bytes.size >
                MAX_PACKET_BYTES
            ) {

                return null
            }


            return try {


                val json =
                    JSONObject(

                        bytes.toString(
                            Charsets.UTF_8
                        )
                    )


                if (
                    json.optString(
                        "type"
                    ) !=
                    TYPE
                ) {

                    return null
                }


                if (
                    json.optInt(
                        "version",
                        -1
                    ) !=
                    VERSION
                ) {

                    return null
                }


                val packet =
                    ConnectionRequestPacket(

                        requestId =
                            json
                                .getString(
                                    "requestId"
                                ),

                        senderNodeId =
                            json
                                .getString(
                                    "senderNodeId"
                                ),

                        recipientNodeId =
                            json
                                .getString(
                                    "recipientNodeId"
                                ),

                        senderDisplayName =
                            json
                                .getString(
                                    "senderDisplayName"
                                ),

                        senderSigningPublicKey =
                            json
                                .getString(
                                    "senderSigningPublicKey"
                                ),

                        senderAgreementPublicKey =
                            json
                                .getString(
                                    "senderAgreementPublicKey"
                                ),

                        createdAt =
                            json
                                .getLong(
                                    "createdAt"
                                ),

                        expiresAt =
                            json
                                .getLong(
                                    "expiresAt"
                                ),

                        signature =
                            json
                                .getString(
                                    "signature"
                                )
                    )


                if (
                    packet.verify()
                ) {

                    packet

                } else {

                    null
                }


            } catch (
                exception: Exception
            ) {


                null
            }
        }



        /*
         * =====================================================
         * SIGNING DATA
         * =====================================================
         *
         * Do NOT directly concatenate user-controlled values
         * with "|" separators.
         *
         * A display name itself could contain "|".
         *
         * Instead every string is length-prefixed:
         *
         * 6:Harsha
         *
         * That gives us an unambiguous canonical message.
         */

        private fun buildSigningData(

            requestId: String,

            senderNodeId: String,

            recipientNodeId: String,

            senderDisplayName: String,

            senderSigningPublicKey: String,

            senderAgreementPublicKey: String,

            createdAt: Long,

            expiresAt: Long

        ): ByteArray {


            val canonicalMessage =
                buildString {


                    append(
                        "PLEXUS_CONNECTION_REQUEST_V1|"
                    )


                    appendLengthPrefixed(
                        requestId
                    )


                    appendLengthPrefixed(
                        senderNodeId
                    )


                    appendLengthPrefixed(
                        recipientNodeId
                    )


                    appendLengthPrefixed(
                        senderDisplayName
                    )


                    appendLengthPrefixed(
                        senderSigningPublicKey
                    )


                    appendLengthPrefixed(
                        senderAgreementPublicKey
                    )


                    appendLengthPrefixed(
                        createdAt.toString()
                    )


                    appendLengthPrefixed(
                        expiresAt.toString()
                    )
                }


            return canonicalMessage
                .toByteArray(
                    Charsets.UTF_8
                )
        }



        /*
         * Length is measured in UTF-8 bytes rather than
         * Kotlin characters so Unicode names remain
         * canonical too.
         */
        private fun StringBuilder
                .appendLengthPrefixed(
            value: String
        ) {


            val byteLength =
                value
                    .toByteArray(
                        Charsets.UTF_8
                    )
                    .size


            append(
                byteLength
            )


            append(
                ":"
            )


            append(
                value
            )


            append(
                "|"
            )
        }
    }
}