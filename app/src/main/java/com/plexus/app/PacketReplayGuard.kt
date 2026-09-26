package com.plexus.app

import java.util.LinkedHashMap


class PacketReplayGuard {


    companion object {


        /*
         * Temporary protocol lifetime.
         *
         * For now:
         *
         *     encrypted chat packet lives for 24 hours
         *
         * Later, when store-and-forward mesh is fully built,
         * we can tune this or make different packet classes
         * use different lifetimes.
         */
        const val MESSAGE_TTL_MS =
            24L * 60L * 60L * 1000L


        /*
         * Allow small clock differences between phones.
         *
         * A packet claiming to come from much further in the
         * future is suspicious and is rejected.
         */
        const val MAX_FUTURE_CLOCK_SKEW_MS =
            5L * 60L * 1000L


        /*
         * Bound memory usage.
         *
         * We do NOT want an attacker to force us to remember
         * unlimited fake message IDs.
         */
        private const val MAX_SEEN_MESSAGE_IDS =
            10_000
    }



    enum class CheckResult {

        ACCEPTABLE,

        DUPLICATE,

        EXPIRED,

        FROM_FUTURE,

        INVALID
    }



    /*
     * messageId -> time at which we accepted it locally
     *
     * accessOrder=true means recently-used entries move toward
     * the end of the map.
     */
    private val seenMessageIds =
        LinkedHashMap<String, Long>(
            128,
            0.75f,
            true
        )



    /*
     * =========================================================
     * PRE-DECRYPTION CHECK
     * =========================================================
     *
     * IMPORTANT:
     *
     * This does NOT mark the packet as seen.
     *
     * We only mark a message after AES-GCM authentication has
     * successfully completed.
     *
     * Otherwise an attacker could send:
     *
     *     legitimateMessageId + garbage ciphertext
     *
     * first, causing us to permanently reject the real packet.
     */

    @Synchronized
    fun check(

        messageId: String,

        timestamp: Long,

        now: Long =
            System.currentTimeMillis()

    ): CheckResult {


        if (
            messageId.isBlank() ||
            timestamp <= 0L
        ) {

            return CheckResult.INVALID
        }



        cleanup(
            now
        )



        if (
            seenMessageIds.containsKey(
                messageId
            )
        ) {

            return CheckResult.DUPLICATE
        }



        val age =
            now -
                    timestamp



        /*
         * Packet timestamp is significantly ahead of our
         * local clock.
         */
        if (
            age <
            -MAX_FUTURE_CLOCK_SKEW_MS
        ) {

            return CheckResult.FROM_FUTURE
        }



        /*
         * Message lifetime expired.
         */
        if (
            age >
            MESSAGE_TTL_MS
        ) {

            return CheckResult.EXPIRED
        }



        return CheckResult.ACCEPTABLE
    }



    /*
     * =========================================================
     * MARK AUTHENTICATED PACKET
     * =========================================================
     *
     * Call this ONLY AFTER:
     *
     *     sender verified
     *     AES-GCM decrypt succeeds
     *
     * At that point messageId becomes replay-protected.
     */

    @Synchronized
    fun markAccepted(

        messageId: String,

        now: Long =
            System.currentTimeMillis()

    ) {


        if (
            messageId.isBlank()
        ) {

            return
        }



        cleanup(
            now
        )



        seenMessageIds[
            messageId
        ] =
            now



        trimToMaximumSize()
    }



    /*
     * =========================================================
     * QUERY
     * =========================================================
     */

    @Synchronized
    fun hasSeen(
        messageId: String
    ): Boolean {


        return seenMessageIds
            .containsKey(
                messageId
            )
    }



    /*
     * =========================================================
     * REMOVE OLD CACHE ENTRIES
     * =========================================================
     *
     * Once a remembered message is older than the maximum
     * packet lifetime, retaining the ID serves no purpose.
     */

    @Synchronized
    private fun cleanup(
        now: Long
    ) {


        val iterator =
            seenMessageIds
                .entries
                .iterator()



        while (
            iterator.hasNext()
        ) {


            val entry =
                iterator.next()



            val rememberedAge =
                now -
                        entry.value



            if (
                rememberedAge >
                MESSAGE_TTL_MS
            ) {

                iterator.remove()
            }
        }
    }



    /*
     * =========================================================
     * MEMORY BOUND
     * =========================================================
     */

    @Synchronized
    private fun trimToMaximumSize() {


        while (
            seenMessageIds.size >
            MAX_SEEN_MESSAGE_IDS
        ) {


            val oldestKey =
                seenMessageIds
                    .entries
                    .firstOrNull()
                    ?.key
                    ?: return



            seenMessageIds.remove(
                oldestKey
            )
        }
    }



    /*
     * =========================================================
     * RESET
     * =========================================================
     *
     * Mainly useful during development/testing.
     */

    @Synchronized
    fun clear() {


        seenMessageIds.clear()
    }
}