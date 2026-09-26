package com.plexus.app

import org.json.JSONObject


data class NearbyAuthPacket(

    val kind: Kind,

    val challengerNodeId: String,

    val responderNodeId: String,

    val challenge: String,

    val signature: String?,

    val timestamp: Long

) {


    enum class Kind {

        CHALLENGE,

        PROOF
    }



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
                    when (
                        kind
                    ) {

                        Kind.CHALLENGE ->
                            "auth_challenge"

                        Kind.PROOF ->
                            "auth_proof"
                    }
                )


                put(
                    "challengerNodeId",
                    challengerNodeId
                )


                put(
                    "responderNodeId",
                    responderNodeId
                )


                put(
                    "challenge",
                    challenge
                )


                put(
                    "timestamp",
                    timestamp
                )


                if (
                    signature != null
                ) {

                    put(
                        "signature",
                        signature
                    )
                }
            }


        return json
            .toString()
            .toByteArray(
                Charsets.UTF_8
            )
    }



    companion object {


        /*
         * =====================================================
         * CREATE CHALLENGE
         * =====================================================
         */

        fun createChallenge(

            challengerNodeId: String,

            responderNodeId: String

        ): NearbyAuthPacket {


            return NearbyAuthPacket(

                kind =
                    Kind.CHALLENGE,

                challengerNodeId =
                    challengerNodeId,

                responderNodeId =
                    responderNodeId,

                challenge =
                    IdentityCrypto
                        .generateChallenge(),

                signature =
                    null,

                timestamp =
                    System.currentTimeMillis()
            )
        }



        /*
         * =====================================================
         * CREATE PROOF
         * =====================================================
         */

        fun createProof(

            challengePacket:
            NearbyAuthPacket

        ): NearbyAuthPacket {


            require(
                challengePacket.kind ==
                        Kind.CHALLENGE
            ) {

                "Proof can only be created from an auth challenge"
            }



            val dataToSign =
                IdentityCrypto
                    .buildAuthenticationMessage(

                        challengerNodeId =
                            challengePacket
                                .challengerNodeId,

                        responderNodeId =
                            challengePacket
                                .responderNodeId,

                        challenge =
                            challengePacket
                                .challenge
                    )



            val signature =
                IdentityCrypto
                    .sign(

                        data =
                            dataToSign,

                        responderNodeId =
                            challengePacket
                                .responderNodeId
                    )



            return NearbyAuthPacket(

                kind =
                    Kind.PROOF,

                challengerNodeId =
                    challengePacket
                        .challengerNodeId,

                responderNodeId =
                    challengePacket
                        .responderNodeId,

                challenge =
                    challengePacket
                        .challenge,

                signature =
                    signature,

                timestamp =
                    System.currentTimeMillis()
            )
        }



        /*
         * =====================================================
         * PARSE
         * =====================================================
         */

        fun fromBytes(
            bytes: ByteArray
        ): NearbyAuthPacket? {


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



                val kind =
                    when (
                        json.optString(
                            "type"
                        )
                    ) {


                        "auth_challenge" -> {

                            Kind.CHALLENGE
                        }


                        "auth_proof" -> {

                            Kind.PROOF
                        }


                        else -> {

                            return null
                        }
                    }



                val challengerNodeId =
                    json.optString(
                        "challengerNodeId"
                    )



                val responderNodeId =
                    json.optString(
                        "responderNodeId"
                    )



                val challenge =
                    json.optString(
                        "challenge"
                    )



                val timestamp =
                    json.optLong(
                        "timestamp"
                    )



                val signature =

                    if (
                        kind ==
                        Kind.PROOF
                    ) {

                        json.optString(
                            "signature"
                        )

                    } else {

                        null
                    }



                if (
                    challengerNodeId.isBlank() ||
                    responderNodeId.isBlank() ||
                    challenge.isBlank() ||
                    timestamp <= 0L
                ) {

                    return null
                }



                if (
                    kind ==
                    Kind.PROOF &&
                    signature.isNullOrBlank()
                ) {

                    return null
                }



                NearbyAuthPacket(

                    kind =
                        kind,

                    challengerNodeId =
                        challengerNodeId,

                    responderNodeId =
                        responderNodeId,

                    challenge =
                        challenge,

                    signature =
                        signature,

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