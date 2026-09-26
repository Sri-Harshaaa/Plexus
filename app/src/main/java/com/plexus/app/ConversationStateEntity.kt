package com.plexus.app

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey


/*
 * Local-only presentation state for one trusted conversation.
 *
 * Hiding/deleting a chat MUST NOT destroy the contact relationship or keys.
 * The other person can still send us a new encrypted message, at which point
 * Plexus unhides the conversation and it reappears with only the new history.
 */
@Entity(
    tableName = "conversation_states"
)
data class ConversationStateEntity(

    @PrimaryKey
    val contactNodeId: String,

    @ColumnInfo(
        defaultValue = "0"
    )
    val isHidden: Boolean = false
)
