package com.plexus.app

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey


/*
 * Persistent delivery states for locally stored chat messages.
 *
 * SENT:
 *   The outgoing encrypted packet was accepted into Plexus' durable
 *   local relay/send pipeline. This maps to a single tick in the UI.
 *
 * DELIVERED:
 *   A signed delivery anti-packet from the final recipient was observed.
 *   This maps to muted double ticks in the UI.
 *
 * READ:
 *   The recipient opened this conversation and sent an encrypted read receipt.
 *   This maps to blue double ticks in the UI.
 */
object MessageDeliveryStatus {

    const val SENT =
        "SENT"

    const val DELIVERED =
        "DELIVERED"

    const val READ =
        "READ"
}


/*
 * Transport label shown beside the message timestamp.
 *
 * CLOUD:
 *   The internet path is the selected/observed route.
 *
 * MESH:
 *   Nearby/direct/mesh is the selected/observed route.
 *
 * UNKNOWN:
 *   Historical messages created before transport metadata existed.
 */
object MessageTransport {

    const val CLOUD =
        "CLOUD"

    const val MESH =
        "MESH"

    const val UNKNOWN =
        "UNKNOWN"
}


@Entity(
    tableName = "messages"
)
data class MessageEntity(

    @PrimaryKey
    val messageId: String,

    /*
     * Which contact/conversation
     * this message belongs to.
     */
    val contactNodeId: String,

    /*
     * Actual user-visible message body.
     *
     * Reply metadata is stored separately, so Home previews remain clean
     * and never expose the internal encrypted wire envelope.
     */
    val text: String,

    /*
     * true  -> sent by us
     * false -> received from them
     */
    val isMine: Boolean,

    /*
     * Unix timestamp in milliseconds.
     *
     * Used for:
     * ordering,
     * Home conversation preview/time,
     * date separators,
     * delivery state,
     * sync,
     * relay expiry, etc.
     */
    val timestamp: Long,

    /*
     * Persistent read state.
     *
     * Outgoing messages are always read locally.
     * Incoming messages are inserted unread and are marked read
     * when that conversation is opened.
     */
    @ColumnInfo(
        defaultValue = "1"
    )
    val isRead: Boolean = true,

    /*
     * SENT / DELIVERED / READ.
     *
     * Only outgoing messages render ticks, but keeping the state on every
     * row makes the model simple and migration-safe.
     */
    @ColumnInfo(
        defaultValue = "'SENT'"
    )
    val deliveryStatus: String =
        MessageDeliveryStatus.SENT,

    /*
     * CLOUD / MESH / UNKNOWN.
     *
     * New incoming messages record the actual ingress path.
     * New outgoing messages record Plexus' selected primary path after
     * the send attempt. Old rows migrate as UNKNOWN because their route
     * cannot be reconstructed honestly.
     */
    @ColumnInfo(
        defaultValue = "'UNKNOWN'"
    )
    val transportType: String =
        MessageTransport.UNKNOWN,

    /*
     * Stable messageId of the quoted/original message.
     *
     * Null means this is a normal message.
     *
     * We intentionally do NOT duplicate the quoted text in the database.
     * The quote is resolved from the local conversation by messageId.
     * If that original is later deleted locally, the UI can truthfully
     * show "Original message unavailable."
     */
    @ColumnInfo(
        defaultValue = "NULL"
    )
    val replyToMessageId: String? =
        null
)
