package com.plexus.app

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow


@Dao
interface ContactDao {
    @Query("SELECT COUNT(*) FROM contacts WHERE relationshipState = 'INCOMING_PENDING'")
    suspend fun countIncomingPending(): Int


    /*
     * Insert a new pinned identity. Concurrent imports must never replace existing trust.
     *
     * nodeId is the primary key, so one Plexus identity
     * can only have one relationship row on this device.
     */
    @Insert(
        onConflict = OnConflictStrategy.IGNORE
    )
    suspend fun insertContact(
        contact: ContactEntity
    )


    /*
     * NORMAL CHAT CONTACTS ONLY.
     *
     * MainActivity currently calls this method to populate
     * the Home chat list.
     *
     * Therefore pending requests must NOT appear here.
     *
     * Only fully connected contacts are returned.
     */
    @Query(
        """
        SELECT *
        FROM contacts
        WHERE relationshipState = 'CONNECTED'
        ORDER BY displayName ASC
        """
    )
    fun getAllContacts():
            Flow<List<ContactEntity>>


    /*
     * Every relationship row, regardless of state.
     *
     * Used by the connection-request system when it needs
     * to inspect pending / accepted / declined identities.
     */
    @Query(
        """
        SELECT *
        FROM contacts
        ORDER BY displayName ASC
        """
    )
    fun getAllRelationships():
            Flow<List<ContactEntity>>


    /*
     * Requests somebody else sent TO us.
     *
     * These will eventually power the:
     *
     * "Harsha wants to connect"
     *
     * UI.
     */
    @Query(
        """
        SELECT *
        FROM contacts
        WHERE relationshipState = 'INCOMING_PENDING'
        ORDER BY displayName ASC
        """
    )
    fun getIncomingPendingRequests():
            Flow<List<ContactEntity>>


    /*
     * Requests WE sent and are still waiting on.
     */
    @Query(
        """
        SELECT *
        FROM contacts
        WHERE relationshipState = 'OUTGOING_PENDING'
        ORDER BY displayName ASC
        """
    )
    fun getOutgoingPendingRequests():
            Flow<List<ContactEntity>>


    /*
     * The remote user has accepted our request.
     *
     * Cryptographic trust already exists, but we have not
     * yet confirmed the local name we want to save them as.
     */
    @Query(
        """
        SELECT *
        FROM contacts
        WHERE relationshipState = 'ACCEPTED_NEEDS_NAME'
        ORDER BY displayName ASC
        """
    )
    fun getAcceptedContactsNeedingName():
            Flow<List<ContactEntity>>


    /*
     * Find any relationship/contact using nodeId.
     *
     * Unlike getAllContacts(), this deliberately does NOT
     * require CONNECTED state.
     */
    @Query(
        """
        SELECT *
        FROM contacts
        WHERE nodeId = :nodeId
        LIMIT 1
        """
    )
    suspend fun getContactById(
        nodeId: String
    ): ContactEntity?


    /*
     * Change relationship state and optionally associate
     * it with a connection request.
     *
     * Examples:
     *
     * OUTGOING_PENDING
     * INCOMING_PENDING
     * ACCEPTED_NEEDS_NAME
     * CONNECTED
     * DECLINED
     */
    @Query(
        """
        UPDATE contacts
        SET relationshipState = :relationshipState,
            pendingRequestId = :pendingRequestId
        WHERE nodeId = :nodeId AND relationshipState NOT IN ('BLOCKED', 'BLOCKED_CONNECTED')
        """
    )
    suspend fun updateRelationshipState(
        nodeId: String,
        relationshipState: String,
        pendingRequestId: String?
    )


    /*
     * Rename a contact LOCALLY.
     *
     * This must never change the other person's announced
     * Plexus display name.
     */
    @Query(
        """
        UPDATE contacts
        SET displayName = :displayName
        WHERE nodeId = :nodeId
        """
    )
    suspend fun updateDisplayName(
        nodeId: String,
        displayName: String
    )


    /*
     * Update the name the remote identity announced.
     *
     * This does NOT overwrite our locally chosen alias.
     */
    @Query(
        """
        UPDATE contacts
        SET announcedDisplayName = :announcedDisplayName
        WHERE nodeId = :nodeId
        """
    )
    suspend fun updateAnnouncedDisplayName(
        nodeId: String,
        announcedDisplayName: String
    )


    /*
     * Finalize the relationship after the local user has
     * chosen/confirmed their saved name.
     *
     * Clear pendingRequestId because the request lifecycle
     * is finished.
     */
    @Query(
        """
        UPDATE contacts
        SET displayName = :displayName,
            relationshipState = 'CONNECTED',
            pendingRequestId = NULL
        WHERE nodeId = :nodeId AND relationshipState = 'ACCEPTED_NEEDS_NAME'
        """
    )
    suspend fun markConnected(
        nodeId: String,
        displayName: String
    )


    /*
     * Mark a request as declined.
     *
     * Declined is NOT blocked. A future connection request
     * may still create a new relationship attempt.
     */
    @Query(
        """
        UPDATE contacts
        SET relationshipState = 'DECLINED',
            pendingRequestId = NULL
        WHERE nodeId = :nodeId AND relationshipState NOT IN ('BLOCKED', 'BLOCKED_CONNECTED')
        """
    )
    suspend fun markDeclined(
        nodeId: String
    )


    @Query("SELECT * FROM contacts WHERE relationshipState IN ('BLOCKED', 'BLOCKED_CONNECTED') ORDER BY displayName ASC")
    fun getBlockedContacts(): Flow<List<ContactEntity>>

    @Query("UPDATE contacts SET relationshipState = CASE WHEN relationshipState IN ('CONNECTED', 'BLOCKED_CONNECTED') THEN 'BLOCKED_CONNECTED' ELSE 'BLOCKED' END, pendingRequestId = :declinedRequestId WHERE nodeId = :nodeId")
    suspend fun blockContact(nodeId: String, declinedRequestId: String? = null)

    @Query("UPDATE contacts SET relationshipState = CASE WHEN relationshipState = 'BLOCKED_CONNECTED' THEN 'CONNECTED' ELSE 'NONE' END, pendingRequestId = NULL WHERE nodeId = :nodeId AND relationshipState IN ('BLOCKED', 'BLOCKED_CONNECTED')")
    suspend fun unblockContact(nodeId: String)

    /*
     * Delete one relationship/contact.
     */
    @Delete
    suspend fun deleteContact(
        contact: ContactEntity
    )


    /*
     * Useful when Remove Contact only has the node ID.
     */
    @Query(
        """
        DELETE FROM contacts
        WHERE nodeId = :nodeId
        """
    )
    suspend fun deleteContactById(
        nodeId: String
    )


    /*
     * Debug/reset helper.
     */
    @Query(
        "DELETE FROM contacts"
    )
    suspend fun deleteAllContacts()
}
