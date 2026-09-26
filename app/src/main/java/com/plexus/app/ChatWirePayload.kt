package com.plexus.app

import org.json.JSONObject


/*
 * Application-layer plaintext carried inside NearbyEncryptedChatPacket.
 *
 * Two payload kinds exist:
 *
 * MESSAGE
 *   Normal visible chat text, optionally replying to another message.
 *
 * READ_RECEIPT
 *   Hidden control payload sent only after the recipient actually opens
 *   the conversation. The sender uses it to turn delivered double ticks
 *   into blue/read double ticks.
 *
 * The outer encrypted packet format stays unchanged. Both visible messages
 * and read receipts remain end-to-end encrypted with the existing pairwise
 * session key.
 */
object ChatWireKind {

    const val MESSAGE =
        "MESSAGE"

    const val READ_RECEIPT =
        "READ_RECEIPT"
}


data class ChatWirePayload(

    val kind: String,

    val text: String,

    val replyToMessageId: String?,

    val readUpToTimestamp: Long?
) {

    fun isReadReceipt(): Boolean {

        return kind ==
                ChatWireKind.READ_RECEIPT
    }
}


object ChatWirePayloadCodec {

    private const val MAGIC_KEY =
        "_plexus"

    private const val MESSAGE_V1 =
        "chat-v1"

    private const val MESSAGE_V2 =
        "chat-v2"

    private const val KIND_KEY =
        "kind"

    private const val TEXT_KEY =
        "text"

    private const val REPLY_TO_KEY =
        "replyToMessageId"

    private const val READ_UP_TO_KEY =
        "readUpToTimestamp"


    // Always wrap user text so JSON-looking chat cannot masquerade as a read receipt.
    fun encode(
        text: String,
        replyToMessageId: String?
    ): String {
        require(text.length <= 2_000) { "Message exceeds 2000 characters" }

        return JSONObject()
            .put(
                MAGIC_KEY,
                MESSAGE_V1
            )
            .put(
                TEXT_KEY,
                text
            )
            .put(
                REPLY_TO_KEY,
                replyToMessageId
            )
            .toString()
    }


    /*
     * Hidden encrypted read receipt.
     *
     * readUpToTimestamp is the newest incoming message timestamp the user has
     * actually opened in the conversation. The sender can therefore mark all
     * of their outgoing rows up to that point as READ.
     */
    fun encodeReadReceipt(
        readUpToTimestamp: Long
    ): String {

        return JSONObject()
            .put(
                MAGIC_KEY,
                MESSAGE_V2
            )
            .put(
                KIND_KEY,
                ChatWireKind.READ_RECEIPT
            )
            .put(
                READ_UP_TO_KEY,
                readUpToTimestamp
            )
            .toString()
    }


    fun decode(
        plaintext: String
    ): ChatWirePayload {

        return try {

            val json =
                JSONObject(
                    plaintext
                )


            when (
                json.optString(
                    MAGIC_KEY
                )
            ) {

                MESSAGE_V2 -> {

                    val kind =
                        json.optString(
                            KIND_KEY
                        )


                    if (
                        kind ==
                        ChatWireKind.READ_RECEIPT
                    ) {

                        val readUpTo =
                            json.optLong(
                                READ_UP_TO_KEY,
                                -1L
                            )


                        if (
                            readUpTo > 0L
                        ) {

                            ChatWirePayload(
                                kind =
                                    ChatWireKind.READ_RECEIPT,

                                text =
                                    "",

                                replyToMessageId =
                                    null,

                                readUpToTimestamp =
                                    readUpTo
                            )

                        } else {

                            legacyMessage(
                                plaintext
                            )
                        }

                    } else {

                        legacyMessage(
                            plaintext
                        )
                    }
                }


                MESSAGE_V1 -> {

                    if (
                        !json.has(
                            TEXT_KEY
                        )
                    ) {

                        legacyMessage(
                            plaintext
                        )

                    } else {

                        val replyId =
                            json
                                .optString(
                                    REPLY_TO_KEY
                                )
                                .trim()
                                .takeIf {
                                    it.isNotEmpty()
                                }


                        ChatWirePayload(
                            kind =
                                ChatWireKind.MESSAGE,

                            text =
                                json.getString(
                                    TEXT_KEY
                                ),

                            replyToMessageId =
                                replyId,

                            readUpToTimestamp =
                                null
                        )
                    }
                }


                else -> {

                    legacyMessage(
                        plaintext
                    )
                }
            }


        } catch (
            ignored: Exception
        ) {

            legacyMessage(
                plaintext
            )
        }
    }


    private fun legacyMessage(
        plaintext: String
    ): ChatWirePayload {

        return ChatWirePayload(
            kind =
                ChatWireKind.MESSAGE,

            text =
                plaintext,

            replyToMessageId =
                null,

            readUpToTimestamp =
                null
        )
    }
}
