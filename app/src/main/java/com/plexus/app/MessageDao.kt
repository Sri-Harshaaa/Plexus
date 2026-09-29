package com.plexus.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow


/*
 * One lightweight Home-screen row of message metadata.
 *
 * Contacts themselves still come from ContactDao. This projection only
 * describes the messaging activity for a contact.
 */
data class ConversationSummary(

    val contactNodeId: String,

    val latestMessageText: String,

    val latestMessageIsMine: Boolean,

    val latestMessageTimestamp: Long,

    val unreadCount: Long
)


@Dao
interface MessageDao {


    /*
     * Insert one message.
     *
     * IGNORE means:
     * if the same messageId already exists,
     * don't insert a duplicate.
     *
     * This is important for mesh/network deduplication.
     */
    @Insert(
        onConflict = OnConflictStrategy.IGNORE
    )
    suspend fun insertMessage(
        message: MessageEntity
    ): Long


    @Query(
        """
        SELECT *
        FROM messages
        WHERE contactNodeId = :contactNodeId
        ORDER BY timestamp DESC, rowid DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentMessagesForContact(
        contactNodeId: String,
        limit: Int
    ): List<MessageEntity>


    /*
     * Observe all messages belonging
     * to one contact.
     *
     * Room emits a new list whenever
     * this conversation changes.
     */
    @Query(
        """
        SELECT *
        FROM messages
        WHERE contactNodeId = :contactNodeId
        ORDER BY timestamp ASC
        """
    )
    fun getMessagesForContact(
        contactNodeId: String
    ): Flow<List<MessageEntity>>


    /*
     * Home-screen metadata for every conversation that has messages.
     *
     * For each contact this returns:
     * - latest message text
     * - latest message timestamp
     * - unread incoming-message count
     *
     * Rows are already ordered by newest activity first.
     * Contacts with zero messages are intentionally absent here;
     * MainActivity appends those contacts after active conversations.
     */
    @Query(
        """
        SELECT
            m.contactNodeId AS contactNodeId,

            (
                SELECT latest.text
                FROM messages AS latest
                WHERE latest.contactNodeId = m.contactNodeId
                ORDER BY latest.timestamp DESC, latest.rowid DESC
                LIMIT 1
            ) AS latestMessageText,

            (
                SELECT latest.isMine
                FROM messages AS latest
                WHERE latest.contactNodeId = m.contactNodeId
                ORDER BY latest.timestamp DESC, latest.rowid DESC
                LIMIT 1
            ) AS latestMessageIsMine,

            MAX(m.timestamp) AS latestMessageTimestamp,

            SUM(
                CASE
                    WHEN m.isMine = 0 AND m.isRead = 0
                    THEN 1
                    ELSE 0
                END
            ) AS unreadCount

        FROM messages AS m
        GROUP BY m.contactNodeId
        ORDER BY latestMessageTimestamp DESC
        """
    )
    fun getConversationSummaries():
            Flow<List<ConversationSummary>>


    /*
     * Opening a chat clears only unread incoming messages.
     * Outgoing messages are already read locally.
     */
    @Query(
        """
        UPDATE messages
        SET isRead = 1
        WHERE contactNodeId = :contactNodeId
          AND isMine = 0
          AND isRead = 0
        """
    )
    suspend fun markConversationRead(
        contactNodeId: String
    )


    /*
     * A valid signed anti-packet is the final-recipient delivery proof.
     *
     * Only our own outgoing row is updated. If this phone is merely
     * relaying another user's anti-packet, no visible message changes.
     */
    @Query(
        """
        UPDATE messages
        SET deliveryStatus = 'DELIVERED'
        WHERE messageId = :messageId
          AND isMine = 1
          AND contactNodeId = :recipientNodeId
          AND deliveryStatus != 'READ'
        """
    )
    suspend fun markMessageDelivered(
        messageId: String,
        recipientNodeId: String
    )


    /*
     * A genuine read receipt is sent only when the remote user opens
     * the conversation. It is stronger than device delivery, so this
     * moves every one of our outgoing messages up to that timestamp to READ.
     *
     * A later delivery anti-packet must never downgrade READ back to DELIVERED;
     * markMessageDelivered() therefore explicitly ignores READ rows.
     */
    @Query(
        """
        UPDATE messages
        SET deliveryStatus = 'READ'
        WHERE contactNodeId = :contactNodeId
          AND isMine = 1
          AND timestamp <= :readUpToTimestamp
        """
    )
    suspend fun markMessagesReadByPeer(
        contactNodeId: String,
        readUpToTimestamp: Long
    )


    /*
     * Persist the route icon after an outgoing send attempt.
     *
     * Incoming messages already know their ingress route at insert time.
     */
    @Query(
        """
        UPDATE messages
        SET transportType = :transportType
        WHERE messageId = :messageId
        """
    )
    suspend fun updateMessageTransport(
        messageId: String,
        transportType: String
    )


    /*
     * Get one specific message.
     *
     * Useful for:
     * deduplication,
     * delivery acknowledgements,
     * relay logic,
     * reply resolution.
     */
    @Query(
        """
        SELECT *
        FROM messages
        WHERE messageId = :messageId
        LIMIT 1
        """
    )
    suspend fun getMessageById(
        messageId: String
    ): MessageEntity?


    /*
     * Delete one local message only.
     *
     * This intentionally does NOT cancel a relay packet, recall anything from
     * the peer, or send a network command. It is strictly "Delete for me".
     * Replies that pointed at this row will naturally render
     * "Original message unavailable."
     */
    @Query(
        """
        DELETE FROM messages
        WHERE messageId = :messageId
        """
    )
    suspend fun deleteMessageById(
        messageId: String
    )


    /*
     * Delete all messages
     * from one conversation.
     */
    @Query(
        """
        DELETE FROM messages
        WHERE contactNodeId = :contactNodeId
        """
    )
    suspend fun deleteMessagesForContact(
        contactNodeId: String
    )


    /*
     * Debug/reset helper.
     */
    @Query(
        "DELETE FROM messages"
    )
    suspend fun deleteAllMessages()
}
