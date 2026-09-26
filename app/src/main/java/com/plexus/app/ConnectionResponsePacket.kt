package com.plexus.app

import org.json.JSONObject
import java.util.UUID


data class ConnectionResponsePacket(

    /*
     * The original request this response belongs to.
     */
    val requestId: String,


    /*
     * Unique ID for THIS response packet.
     *
     * Useful for deduplication later.
     */
    val responseId: String,


    /*
     * Person who originally sent the connection request.
     */
    val requesterNodeId: String,


    /*
     * Person accepting / declining the request.
     */
    val responderNodeId: String,


    /*
     * ACCEPTED or DECLINED.
     */
    val response: String,


    /*
     * Responder's own Plexus profile name.
     *
     * This is metadata only.
     *
     * The requester can still save this person locally
     * under any alias they want.
     */
    val responderDisplayName: String,


    /*
     * Public identity material of the responder.
     *
     * Including these makes this packet independently
     * verifiable and lets us compare them against the
     * identity originally scanned from the QR/link.
     */
    val responderSigningPublicKey: String,

    val responderAgreementPublicKey: String,


    val createdAt: Long,

    val expiresAt: Long,


    /*
     * ECDSA signature made by responderNodeId's private key.
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
                    "responseId",
                    responseId
                )
                .put(
                    "requesterNodeId",
                    requesterNodeId
                )
                .put(
                    "responderNodeId",
                    responderNodeId
                )
                .put(
                    "response",
                    response
                )
                .put(
                    "responderDisplayName",
                    responderDisplayName
                )
                .put(
                    "responderSigningPublicKey",
                    responderSigningPublicKey
                )
                .put(
                    "responderAgreementPublicKey",
                    responderAgreementPublicKey
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
     * RESPONSE TYPE HELPERS
     * =========================================================
     */

    fun isAccepted(): Boolean {


        return response ==
                RESPONSE_ACCEPTED
    }


    fun isDeclined(): Boolean {


        return response ==
                RESPONSE_DECLINED
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
     */

    fun verify(
        now: Long =
            System.currentTimeMillis()
    ): Boolean {


        /*
         * Basic required values.
         */
        if (
            requestId.isBlank() ||
            responseId.isBlank() ||
            requesterNodeId.isBlank() ||
            responderNodeId.isBlank() ||
            responderSigningPublicKey.isBlank() ||
            responderAgreementPublicKey.isBlank()
        ) {

            return false
        }


        /*
         * Nobody should respond to themselves.
         */
        if (
            requesterNodeId ==
            responderNodeId
        ) {

            return false
        }


        /*
         * Only protocol-supported responses are accepted.
         */
        if (
            response !=
            RESPONSE_ACCEPTED &&
            response !=
            RESPONSE_DECLINED
        ) {

            return false
        }


        /*
         * Both IDs must actually be UUIDs.
         */
        try {


            UUID.fromString(
                requestId
            )


            UUID.fromString(
                responseId
            )


        } catch (
            exception: Exception
        ) {


            return false
        }


        val cleanName =
            responderDisplayName.trim()


        /*
         * Even a decline packet includes the responder's
         * current public display metadata.
         */
        if (
            cleanName.isEmpty() ||
            cleanName.length >
            MAX_DISPLAY_NAME_LENGTH
        ) {

            return false
        }


        /*
         * Reject timestamps too far ahead of our clock.
         */
        if (
            createdAt >
            now +
            MAX_FUTURE_CLOCK_SKEW_MILLIS
        ) {

            return false
        }


        if (
            expiresAt <=
            createdAt
        ) {

            return false
        }


        /*
         * Prevent someone from constructing a response
         * with an effectively infinite lifetime.
         */
        if (
            expiresAt - createdAt >
            RESPONSE_TTL_MILLIS
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
         * Verify:
         *
         * responderNodeId ==
         * SHA-256(responderSigningPublicKey)
         */
        val validIdentity =
            PeerIdentityVerifier
                .isValidIdentity(

                    nodeId =
                        responderNodeId,

                    signingPublicKey =
                        responderSigningPublicKey
                )


        if (
            !validIdentity
        ) {

            return false
        }


        val signingData =
            buildSigningData(

                requestId =
                    requestId,

                responseId =
                    responseId,

                requesterNodeId =
                    requesterNodeId,

                responderNodeId =
                    responderNodeId,

                response =
                    response,

                responderDisplayName =
                    responderDisplayName,

                responderSigningPublicKey =
                    responderSigningPublicKey,

                responderAgreementPublicKey =
                    responderAgreementPublicKey,

                createdAt =
                    createdAt,

                expiresAt =
                    expiresAt
            )


        /*
         * Cryptographically prove that the responder
         * owns responderNodeId's private signing key.
         */
        return IdentityCrypto
            .verify(

                data =
                    signingData,

                signatureBase64 =
                    signature,

                publicKeyBase64 =
                    responderSigningPublicKey
            )
    }



    companion object {


        private const val TYPE =
            "connection_response"


        private const val VERSION =
            1


        const val RESPONSE_ACCEPTED =
            "ACCEPTED"


        const val RESPONSE_DECLINED =
            "DECLINED"


        const val RESPONSE_TTL_MILLIS =
            30L *
                    24L *
                    60L *
                    60L *
                    1000L


        private const val MAX_FUTURE_CLOCK_SKEW_MILLIS =
            5L *
                    60L *
                    1000L


        private const val MAX_DISPLAY_NAME_LENGTH =
            64


        private const val MAX_PACKET_BYTES =
            32 * 1024



        /*
         * =====================================================
         * CREATE ACCEPTANCE
         * =====================================================
         */

        fun createAccepted(

            request:
            ConnectionRequestPacket,

            responderIdentity:
            PlexusIdentity,

            now: Long =
                System.currentTimeMillis()

        ): ConnectionResponsePacket {


            return create(

                request =
                    request,

                responderIdentity =
                    responderIdentity,

                response =
                    RESPONSE_ACCEPTED,

                now =
                    now
            )
        }



        /*
         * =====================================================
         * CREATE DECLINE
         * =====================================================
         */

        fun createDeclined(

            request:
            ConnectionRequestPacket,

            responderIdentity:
            PlexusIdentity,

            now: Long =
                System.currentTimeMillis()

        ): ConnectionResponsePacket {


            return create(

                request =
                    request,

                responderIdentity =
                    responderIdentity,

                response =
                    RESPONSE_DECLINED,

                now =
                    now
            )
        }



        /*
         * =====================================================
         * INTERNAL CREATE
         * =====================================================
         */

        private fun create(

            request:
            ConnectionRequestPacket,

            responderIdentity:
            PlexusIdentity,

            response: String,

            now: Long

        ): ConnectionResponsePacket {


            require(
                request.verify(
                    now
                )
            ) {

                "Cannot respond to an invalid connection request"
            }


            /*
             * This device must actually be the intended
             * recipient of the original request.
             */
            require(
                request.recipientNodeId ==
                        responderIdentity.nodeId
            ) {

                "Connection request is not addressed to this identity"
            }


            require(
                response ==
                        RESPONSE_ACCEPTED ||
                        response ==
                        RESPONSE_DECLINED
            )


            val cleanDisplayName =
                responderIdentity
                    .displayName
                    .trim()


            require(
                cleanDisplayName.isNotEmpty()
            ) {

                "Responder display name cannot be empty"
            }


            require(
                cleanDisplayName.length <=
                        MAX_DISPLAY_NAME_LENGTH
            ) {

                "Responder display name is too long"
            }


            /*
             * Sanity-check our own identity before signing.
             */
            require(

                PeerIdentityVerifier
                    .isValidIdentity(

                        nodeId =
                            responderIdentity.nodeId,

                        signingPublicKey =
                            responderIdentity.signingPublicKey
                    )

            ) {

                "Local responder identity is invalid"
            }


            val responseId =
                UUID
                    .randomUUID()
                    .toString()


            val expiresAt =
                now +
                        RESPONSE_TTL_MILLIS


            val signingData =
                buildSigningData(

                    requestId =
                        request.requestId,

                    responseId =
                        responseId,

                    requesterNodeId =
                        request.senderNodeId,

                    responderNodeId =
                        responderIdentity.nodeId,

                    response =
                        response,

                    responderDisplayName =
                        cleanDisplayName,

                    responderSigningPublicKey =
                        responderIdentity.signingPublicKey,

                    responderAgreementPublicKey =
                        responderIdentity.agreementPublicKey,

                    createdAt =
                        now,

                    expiresAt =
                        expiresAt
                )


            val signature =
                IdentityCrypto
                    .sign(

                        data =
                            signingData,

                        responderNodeId =
                            responderIdentity.nodeId
                    )


            return ConnectionResponsePacket(

                requestId =
                    request.requestId,

                responseId =
                    responseId,

                requesterNodeId =
                    request.senderNodeId,

                responderNodeId =
                    responderIdentity.nodeId,

                response =
                    response,

                responderDisplayName =
                    cleanDisplayName,

                responderSigningPublicKey =
                    responderIdentity.signingPublicKey,

                responderAgreementPublicKey =
                    responderIdentity.agreementPublicKey,

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
         */

        fun fromBytes(
            bytes: ByteArray
        ): ConnectionResponsePacket? {


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
                    ConnectionResponsePacket(

                        requestId =
                            json.getString(
                                "requestId"
                            ),

                        responseId =
                            json.getString(
                                "responseId"
                            ),

                        requesterNodeId =
                            json.getString(
                                "requesterNodeId"
                            ),

                        responderNodeId =
                            json.getString(
                                "responderNodeId"
                            ),

                        response =
                            json.getString(
                                "response"
                            ),

                        responderDisplayName =
                            json.getString(
                                "responderDisplayName"
                            ),

                        responderSigningPublicKey =
                            json.getString(
                                "responderSigningPublicKey"
                            ),

                        responderAgreementPublicKey =
                            json.getString(
                                "responderAgreementPublicKey"
                            ),

                        createdAt =
                            json.getLong(
                                "createdAt"
                            ),

                        expiresAt =
                            json.getLong(
                                "expiresAt"
                            ),

                        signature =
                            json.getString(
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
         */

        private fun buildSigningData(

            requestId: String,

            responseId: String,

            requesterNodeId: String,

            responderNodeId: String,

            response: String,

            responderDisplayName: String,

            responderSigningPublicKey: String,

            responderAgreementPublicKey: String,

            createdAt: Long,

            expiresAt: Long

        ): ByteArray {


            val canonicalMessage =
                buildString {


                    append(
                        "PLEXUS_CONNECTION_RESPONSE_V1|"
                    )


                    appendLengthPrefixed(
                        requestId
                    )


                    appendLengthPrefixed(
                        responseId
                    )


                    appendLengthPrefixed(
                        requesterNodeId
                    )


                    appendLengthPrefixed(
                        responderNodeId
                    )


                    appendLengthPrefixed(
                        response
                    )


                    appendLengthPrefixed(
                        responderDisplayName
                    )


                    appendLengthPrefixed(
                        responderSigningPublicKey
                    )


                    appendLengthPrefixed(
                        responderAgreementPublicKey
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