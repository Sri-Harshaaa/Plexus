package com.plexus.app

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow


@Dao
interface ConversationStateDao {

    /*
     * Home uses this to omit chats that the local user explicitly deleted.
     * The contact itself remains CONNECTED in ContactDao.
     */
    @Query(
        """
        SELECT contactNodeId
        FROM conversation_states
        WHERE isHidden = 1
        """
    )
    fun getHiddenContactIds(): Flow<List<String>>


    /*
     * INSERT OR REPLACE means this also creates state for an existing trusted
     * contact the first time the user chooses Delete chat.
     */
    @Query(
        """
        INSERT OR REPLACE INTO conversation_states (
            contactNodeId,
            isHidden
        )
        VALUES (
            :contactNodeId,
            :hidden
        )
        """
    )
    suspend fun setConversationHidden(
        contactNodeId: String,
        hidden: Boolean
    )


    /*
     * Receiving a NEW visible message resurrects a locally deleted chat.
     * If no state row exists, doing nothing is correct because the chat was
     * never hidden in the first place.
     */
    @Query(
        """
        UPDATE conversation_states
        SET isHidden = 0
        WHERE contactNodeId = :contactNodeId
        """
    )
    suspend fun unhideConversation(
        contactNodeId: String
    )
}
