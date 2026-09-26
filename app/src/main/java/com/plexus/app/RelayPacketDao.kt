package com.plexus.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


@Dao
interface RelayPacketDao {


    /*
     * =========================================================
     * INSERT
     * =========================================================
     *
     * packetId is the primary key.
     *
     * If the same mesh packet reaches us through multiple
     * neighbors, Room simply ignores the duplicate.
     *
     * Return value:
     *
     * >= 0  -> newly inserted
     * -1    -> packet already existed
     */

    @Insert(
        onConflict =
            OnConflictStrategy.IGNORE
    )
    suspend fun insertPacket(
        packet: RelayPacketEntity
    ): Long



    /*
     * =========================================================
     * LOOKUP BY PACKET ID
     * =========================================================
     */

    @Query(
        """
        SELECT *
        FROM relay_packets
        WHERE packetId = :packetId
        LIMIT 1
        """
    )
    suspend fun getPacket(
        packetId: String
    ): RelayPacketEntity?



    /*
     * =========================================================
     * DUPLICATE CHECK
     * =========================================================
     *
     * Useful before doing unnecessary relay processing.
     */

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM relay_packets
            WHERE packetId = :packetId
        )
        """
    )
    suspend fun containsPacket(
        packetId: String
    ): Boolean



    /*
     * =========================================================
     * GET ACTIVE RELAY PACKETS
     * =========================================================
     *
     * These are packets that are still alive and may be
     * forwarded to newly encountered peers.
     */

    @Query(
        """
        SELECT *
        FROM relay_packets
        WHERE expiresAt > :now
        ORDER BY storedAt ASC
        """
    )
    suspend fun getActivePackets(
        now: Long =
            System.currentTimeMillis()
    ): List<RelayPacketEntity>



    /*
     * =========================================================
     * GET ACTIVE PACKETS FOR DESTINATION
     * =========================================================
     *
     * Important optimization:
     *
     * if Vishnu suddenly connects directly, we can immediately
     * query packets whose final destination is Vishnu.
     */

    @Query(
        """
        SELECT *
        FROM relay_packets
        WHERE destinationNodeId = :destinationNodeId
          AND expiresAt > :now
        ORDER BY storedAt ASC
        """
    )
    suspend fun getActivePacketsForDestination(

        destinationNodeId: String,

        now: Long =
            System.currentTimeMillis()

    ): List<RelayPacketEntity>



    /*
     * =========================================================
     * ACTIVE PACKET COUNT
     * =========================================================
     *
     * Useful later for:
     *
     * diagnostics
     * UI/debug screen
     * storage monitoring
     */

    @Query(
        """
        SELECT COUNT(*)
        FROM relay_packets
        WHERE expiresAt > :now
        """
    )
    suspend fun countActivePackets(
        now: Long =
            System.currentTimeMillis()
    ): Int



    /*
     * =========================================================
     * DELETE ONE PACKET
     * =========================================================
     *
     * Later the anti-packet / signed delivery receipt system
     * will call this when we know the message reached its final
     * recipient.
     */

    @Query(
        """
        DELETE FROM relay_packets
        WHERE packetId = :packetId
        """
    )
    suspend fun deletePacket(
        packetId: String
    ): Int



    /*
     * =========================================================
     * DELETE EXPIRED PACKETS
     * =========================================================
     *
     * TTL is the REAL lifetime limiter in Plexus.
     *
     * Hop count is NOT used here.
     */

    @Query(
        """
        DELETE FROM relay_packets
        WHERE expiresAt <= :now
        """
    )
    suspend fun deleteExpiredPackets(
        now: Long =
            System.currentTimeMillis()
    ): Int



    /*
     * =========================================================
     * DELETE EVERYTHING
     * =========================================================
     *
     * Mainly useful during development/testing.
     */

    @Query(
        """
        DELETE FROM relay_packets
        """
    )
    suspend fun deleteAllPackets(): Int
}