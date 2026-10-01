package com.plexus.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow


@Dao
interface ConnectionRequestDao {


    /*
     * Store a request exactly once.
     *
     * requestId is the primary key, so the same signed
     * request arriving again through Nearby + internet
     * will not create duplicate rows.
     *
     * INSERT IGNORE also makes transport first-wins
     * behavior safe.
     */
    @Insert(
        onConflict = OnConflictStrategy.IGNORE
    )
    suspend fun insertRequest(
        request: ConnectionRequestEntity
    ): Long


    /*
     * Find one request by its requestId.
     *
     * Used when:
     *
     * - accepting a request
     * - declining a request
     * - processing a response
     */
    @Query(
        """
        SELECT *
        FROM connection_requests
        WHERE requestId = :requestId
        LIMIT 1
        """
    )
    suspend fun getRequestById(
        requestId: String
    ): ConnectionRequestEntity?


    /*
     * Check persistent deduplication without loading
     * the whole row.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM connection_requests
            WHERE requestId = :requestId
        )
        """
    )
    suspend fun containsRequest(
        requestId: String
    ): Boolean


    /*
     * Requests addressed TO this device.
     *
     * These power the incoming-request UI.
     */
    @Query(
        """
        SELECT *
        FROM connection_requests
        WHERE recipientNodeId = :localNodeId
          AND expiresAt > :now
        ORDER BY createdAt DESC
        """
    )
    fun getIncomingRequests(
        localNodeId: String,
        now: Long =
            System.currentTimeMillis()
    ): Flow<List<ConnectionRequestEntity>>


    /*
     * Requests created BY this device.
     *
     * Useful for:
     *
     * - outgoing pending UI
     * - retry after reconnect
     * - correlating a later acceptance/decline
     */
    @Query(
        """
        SELECT *
        FROM connection_requests
        WHERE senderNodeId = :localNodeId
          AND expiresAt > :now
        ORDER BY createdAt DESC
        """
    )
    fun getOutgoingRequests(
        localNodeId: String,
        now: Long =
            System.currentTimeMillis()
    ): Flow<List<ConnectionRequestEntity>>


    /*
     * Durable outgoing requests that may need to be
     * offered again after internet / Nearby reconnect.
     *
     * We return only requests originally created by
     * this local identity.
     */
    @Query(
        """
        SELECT *
        FROM connection_requests
        WHERE senderNodeId = :localNodeId
          AND expiresAt > :now
          AND EXISTS (SELECT 1 FROM contacts c WHERE c.nodeId = recipientNodeId
              AND c.relationshipState = 'OUTGOING_PENDING' AND c.pendingRequestId = requestId)
        ORDER BY storedAt ASC
        """
    )
    suspend fun getOutgoingRetryCandidates(
        localNodeId: String,
        now: Long =
            System.currentTimeMillis()
    ): List<ConnectionRequestEntity>


    /*
     * Remove one request when its lifecycle is finished.
     *
     * Example:
     *
     * response processed successfully
     * or request deliberately discarded.
     */
    @Query(
        """
        DELETE FROM connection_requests
        WHERE requestId = :requestId
        """
    )
    suspend fun deleteRequest(
        requestId: String
    )


    /*
     * Garbage-collect expired request packets.
     */
    @Query(
        """
        DELETE FROM connection_requests
        WHERE expiresAt <= :now
        """
    )
    suspend fun deleteExpiredRequests(
        now: Long =
            System.currentTimeMillis()
    )


    /*
     * Helpful for diagnostics / debugging.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM connection_requests
        """
    )
    suspend fun countRequests():
            Int


    /*
     * Full reset helper.
     */
    @Query(
        """
        DELETE FROM connection_requests
        """
    )
    suspend fun deleteAllRequests()
}