package com.plexus.app

import org.json.JSONObject


data class NearbyIdentityPacket(

    val nodeId: String,

    val displayName: String,

    val signingPublicKey: String,

    val agreementPublicKey: String,

    val timestamp: Long

) {


    fun toBytes(): ByteArray {

        val json =
            JSONObject().apply {

                put(
                    "version",
                    1
                )

                put(
                    "type",
                    "identity"
                )

                put(
                    "nodeId",
                    nodeId
                )

                put(
                    "displayName",
                    displayName
                )

                put(
                    "signingPublicKey",
                    signingPublicKey
                )

                put(
                    "agreementPublicKey",
                    agreementPublicKey
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


    companion object {


        fun create(
            identity: PlexusIdentity
        ): NearbyIdentityPacket {

            return NearbyIdentityPacket(

                nodeId =
                    identity.nodeId,

                displayName =
                    identity.displayName,

                signingPublicKey =
                    identity.signingPublicKey,

                agreementPublicKey =
                    identity.agreementPublicKey,

                timestamp =
                    System.currentTimeMillis()
            )
        }


        fun fromBytes(
            bytes: ByteArray
        ): NearbyIdentityPacket? {

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
                    ) != "identity"
                ) {

                    return null
                }


                val nodeId =
                    json.optString(
                        "nodeId"
                    )


                val displayName =
                    json.optString(
                        "displayName"
                    )


                val signingPublicKey =
                    json.optString(
                        "signingPublicKey"
                    )


                val agreementPublicKey =
                    json.optString(
                        "agreementPublicKey"
                    )


                val timestamp =
                    json.optLong(
                        "timestamp"
                    )


                if (
                    !nodeId.matches(Regex("[0-9a-f]{64}")) ||
                    displayName.isBlank() || displayName.length > 64 ||
                    signingPublicKey.isBlank() || signingPublicKey.length > 512 ||
                    agreementPublicKey.isBlank() || agreementPublicKey.length > 512 ||
                    timestamp <= 0L
                ) {

                    return null
                }


                NearbyIdentityPacket(

                    nodeId =
                        nodeId,

                    displayName =
                        displayName,

                    signingPublicKey =
                        signingPublicKey,

                    agreementPublicKey =
                        agreementPublicKey,

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
