package com.plexus.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


@Dao
interface AntiPacketDao {


    /*
     * =========================================================
     * INSERT ANTI-PACKET
     * =========================================================
     *
     * messageId is the primary key.
     *
     * If the same signed anti-packet reaches us through many
     * different relays, Room keeps only one copy.
     *
     * Return value:
     *
     * >= 0  -> newly inserted
     * -1    -> already existed
     */

    @Insert(
        onConflict =
            OnConflictStrategy.IGNORE
    )
    suspend fun insertAntiPacket(
        packet: AntiPacketEntity
    ): Long



    /*
     * =========================================================
     * LOOKUP BY MESSAGE ID
     * =========================================================
     */

    @Query(
        """
        SELECT *
        FROM anti_packets
        WHERE messageId = :messageId
        LIMIT 1
        """
    )
    suspend fun getAntiPacket(
        messageId: String
    ): AntiPacketEntity?



    /*
     * =========================================================
     * VACCINE CHECK
     * =========================================================
     *
     * This is the important fast check used when an old mesh
     * packet reappears.
     *
     * If this returns true:
     *
     * message already reached destination
     * -> do not store heavy packet
     * -> do not forward heavy packet
     */

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM anti_packets
            WHERE messageId = :messageId
              AND expiresAt > :now
        )
        """
    )
    suspend fun containsActiveAntiPacket(

        messageId: String,

        now: Long =
            System.currentTimeMillis()

    ): Boolean



    /*
     * =========================================================
     * GET ALL ACTIVE ANTI-PACKETS
     * =========================================================
     *
     * These are the vaccines we may spread to newly encountered
     * authenticated Plexus peers.
     */

    @Query(
        """
        SELECT *
        FROM anti_packets
        WHERE expiresAt > :now
        ORDER BY storedAt ASC
        """
    )
    suspend fun getActiveAntiPackets(
        now: Long =
            System.currentTimeMillis()
    ): List<AntiPacketEntity>

    @Query("SELECT * FROM anti_packets WHERE expiresAt > :now ORDER BY storedAt ASC LIMIT :limit OFFSET :offset")
    suspend fun getActiveAntiPacketWindow(now: Long, limit: Int, offset: Int): List<AntiPacketEntity>



    /*
     * =========================================================
     * COUNT ACTIVE ANTI-PACKETS
     * =========================================================
     *
     * Useful later for diagnostics and debugging.
     */

    @Query(
        """
        SELECT COUNT(*)
        FROM anti_packets
        WHERE expiresAt > :now
        """
    )
    suspend fun countActiveAntiPackets(
        now: Long =
            System.currentTimeMillis()
    ): Int



    /*
     * =========================================================
     * DELETE EXPIRED VACCINES
     * =========================================================
     *
     * Anti-packets themselves do not live forever.
     *
     * They survive longer than normal messages, but after their
     * expiry they can be removed.
     */

    @Query(
        """
        DELETE FROM anti_packets
        WHERE expiresAt <= :now
        """
    )
    suspend fun deleteExpiredAntiPackets(
        now: Long =
            System.currentTimeMillis()
    ): Int



    /*
     * =========================================================
     * DELETE ONE ANTI-PACKET
     * =========================================================
     *
     * Mostly useful during development/testing.
     */

    @Query(
        """
        DELETE FROM anti_packets
        WHERE messageId = :messageId
        """
    )
    suspend fun deleteAntiPacket(
        messageId: String
    ): Int



    /*
     * =========================================================
     * DELETE EVERYTHING
     * =========================================================
     *
     * Development/debug utility.
     */

    @Query(
        """
        DELETE FROM anti_packets
        """
    )
    suspend fun deleteAllAntiPackets(): Int
}
