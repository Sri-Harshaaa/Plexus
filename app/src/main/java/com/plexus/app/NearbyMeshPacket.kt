package com.plexus.app

import android.util.Base64
import org.json.JSONObject


data class NearbyMeshPacket(

    val packetId: String,

    val sourceNodeId: String,

    val destinationNodeId: String,

    val createdAt: Long,

    val expiresAt: Long,

    // Number of relay hops already traversed; destination may accept the boundary hop.
    val hopCount: Long,

    /*
     * Base64-encoded NearbyEncryptedChatPacket.
     *
     * Relays never decrypt this.
     */
    val encryptedPayload: String,
    val originSigningPublicKey: String,
    val originSignature: String

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
                    "mesh_message"
                )


                put(
                    "packetId",
                    packetId
                )


                put(
                    "sourceNodeId",
                    sourceNodeId
                )


                put(
                    "destinationNodeId",
                    destinationNodeId
                )


                put(
                    "createdAt",
                    createdAt
                )


                put(
                    "expiresAt",
                    expiresAt
                )


                put(
                    "hopCount",
                    hopCount
                )


                put(
                    "encryptedPayload",
                    encryptedPayload
                )
                put("originSigningPublicKey", originSigningPublicKey)
                put("originSignature", originSignature)
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
     *
     * TTL and hop count independently bound propagation.
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
     * FORWARD
     * =========================================================
     *
     * A relay does NOT:
     *
     * decrypt
     * modify the encrypted message
     * change authenticated routing metadata
     *
     * It only increments hopCount metadata.
     */

    fun forwardedCopy(now: Long = System.currentTimeMillis()): NearbyMeshPacket? {
        val next = MeshPolicy.nextHop(hopCount, expiresAt, now) ?: return null
        return copy(hopCount = next)
    }

    internal fun originBytes(): ByteArray {
        val fields = listOf(packetId, sourceNodeId, destinationNodeId,
            createdAt.toString(), expiresAt.toString(), encryptedPayload)
        return ("PLEXUS_MESH_ORIGIN_V1|" + fields.joinToString("") {
            "${it.toByteArray(Charsets.UTF_8).size}:$it|"
        }).toByteArray(Charsets.UTF_8)
    }

    fun hasValidOrigin(): Boolean = originSigningPublicKey.length <= 512 &&
        originSignature.length <= 256 &&
        PeerIdentityVerifier.isValidIdentity(sourceNodeId, originSigningPublicKey) &&
        IdentityCrypto.verify(originBytes(), originSignature, originSigningPublicKey)

    fun signAs(identity: PlexusIdentity): NearbyMeshPacket {
        require(sourceNodeId == identity.nodeId)
        val unsigned = copy(originSigningPublicKey = identity.signingPublicKey, originSignature = "")
        return unsigned.copy(originSignature = IdentityCrypto.sign(unsigned.originBytes(), identity.nodeId))
    }


    /*
     * =========================================================
     * EXTRACT INNER ENCRYPTED CHAT PACKET
     * =========================================================
     */

    fun extractEncryptedChatPacket():
            NearbyEncryptedChatPacket? {


        return try {


            val bytes =
                Base64.decode(

                    encryptedPayload,

                    Base64.NO_WRAP
                )


            NearbyEncryptedChatPacket
                .fromBytes(
                    bytes
                )


        } catch (
            exception: Exception
        ) {


            null
        }
    }



    companion object {


        /*
         * =====================================================
         * CREATE MESH PACKET
         * =====================================================
         */

        fun create(

            encryptedChatPacket:
            NearbyEncryptedChatPacket,

            identity: PlexusIdentity,

            lifetimeMs: Long =
                PacketReplayGuard.MESSAGE_TTL_MS

        ): NearbyMeshPacket {


            require(
                lifetimeMs > 0L
            ) {

                "Mesh packet lifetime must be positive"
            }



            val createdAt =
                encryptedChatPacket
                    .timestamp



            val expiresAt =

                if (
                    Long.MAX_VALUE -
                    createdAt <
                    lifetimeMs
                ) {

                    Long.MAX_VALUE

                } else {

                    createdAt +
                            lifetimeMs
                }



            val encodedPayload =
                Base64.encodeToString(

                    encryptedChatPacket
                        .toBytes(),

                    Base64.NO_WRAP
                )



            return NearbyMeshPacket(

                /*
                 * Same logical message keeps the same ID across:
                 *
                 * encryption
                 * mesh forwarding
                 * deduplication
                 * Room storage
                 */
                packetId =
                    encryptedChatPacket
                        .messageId,

                sourceNodeId =
                    encryptedChatPacket
                        .senderNodeId,

                destinationNodeId =
                    encryptedChatPacket
                        .recipientNodeId,

                createdAt =
                    createdAt,

                expiresAt =
                    expiresAt,

                /*
                 * Sender creates the packet.
                 *
                 * It has passed through zero relay nodes yet.
                 */
                hopCount =
                    0L,

                encryptedPayload = encodedPayload,
                originSigningPublicKey = "",
                originSignature = ""
            ).signAs(identity)
        }



        /*
         * =====================================================
         * PARSE
         * =====================================================
         */

        fun fromBytes(
            bytes: ByteArray
        ): NearbyMeshPacket? {
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
                    ) != "mesh_message"
                ) {

                    return null
                }



                val packetId =
                    json.optString(
                        "packetId"
                    )



                val sourceNodeId =
                    json.optString(
                        "sourceNodeId"
                    )



                val destinationNodeId =
                    json.optString(
                        "destinationNodeId"
                    )



                val createdAt =
                    json.optLong(
                        "createdAt"
                    )



                val expiresAt =
                    json.optLong(
                        "expiresAt"
                    )



                val hopCount =
                    json.optLong(
                        "hopCount",
                        -1L
                    )
                val rawHop = json.opt("hopCount")
                if (rawHop !is Int && rawHop !is Long) return null



                val encryptedPayload =
                    json.optString(
                        "encryptedPayload"
                    )
                val originSigningPublicKey = json.optString("originSigningPublicKey")
                val originSignature = json.optString("originSignature")



                // Reject malformed routing metadata before decoding inner ciphertext.
                if (
                    packetId.length !in 1..128 ||
                    !sourceNodeId.matches(Regex("[0-9a-f]{64}")) ||
                    !destinationNodeId.matches(Regex("[0-9a-f]{64}")) ||
                    !MeshPolicy.validLifetime(createdAt, expiresAt, hopCount, System.currentTimeMillis()) ||
                    sourceNodeId.isBlank() ||
                    destinationNodeId.isBlank() ||
                    createdAt <= 0L ||
                    expiresAt <= createdAt ||
                    hopCount !in 0..MeshPolicy.MAX_MESH_HOPS ||
                    encryptedPayload.isBlank() ||
                    originSigningPublicKey.isBlank() || originSignature.isBlank()
                ) {

                    return null
                }



                val packet =
                    NearbyMeshPacket(

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

                        encryptedPayload = encryptedPayload,
                        originSigningPublicKey = originSigningPublicKey,
                        originSignature = originSignature
                    )



                /*
                 * =================================================
                 * INNER / OUTER CONSISTENCY
                 * =================================================
                 *
                 * Relays cannot decrypt the message body.
                 *
                 * But the inner encrypted-chat packet contains
                 * visible routing metadata which must agree with
                 * the mesh envelope.
                 */

                val inner =
                    packet
                        .extractEncryptedChatPacket()
                        ?: return null



                if (
                    inner.messageId !=
                    packet.packetId
                ) {

                    return null
                }



                if (
                    inner.senderNodeId !=
                    packet.sourceNodeId
                ) {

                    return null
                }



                if (
                    inner.recipientNodeId !=
                    packet.destinationNodeId
                ) {

                    return null
                }



                if (
                    inner.timestamp !=
                    packet.createdAt
                ) {

                    return null
                }



                if (!packet.hasValidOrigin()) return null
                packet


            } catch (
                exception: Exception
            ) {


                null
            }
        }
    }
}
