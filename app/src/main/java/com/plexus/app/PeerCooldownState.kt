package com.plexus.app

class PeerCooldownState(
    private val initialCooldownMs: Long = 5_000L,
    private val maxCooldownMs: Long = 60_000L,
    private val timeProvider: () -> Long
) {
    private val failures = mutableMapOf<String, Int>()
    private val cooldowns = mutableMapOf<String, Long>()

    fun applyFailure(peerKey: String): Long {
        val current = failures.getOrDefault(peerKey, 0)
        val next = minOf(current + 1, 10)
        failures[peerKey] = next
        
        val backoffBase = initialCooldownMs * (1L shl (next - 1))
        val actualBackoff = backoffBase.coerceAtMost(maxCooldownMs)
        
        cooldowns[peerKey] = timeProvider() + actualBackoff
        
        trim()
        return actualBackoff
    }

    fun isCoolingDown(peerKey: String): Boolean {
        val expireAt = cooldowns[peerKey] ?: return false
        if (timeProvider() < expireAt) {
            return true
        } else {
            cooldowns.remove(peerKey)
            return false
        }
    }

    fun getExpireAt(peerKey: String): Long? {
        val expireAt = cooldowns[peerKey] ?: return null
        if (timeProvider() >= expireAt) {
            cooldowns.remove(peerKey)
            return null
        }
        return expireAt
    }

    fun clear(peerKey: String) {
        failures.remove(peerKey)
        cooldowns.remove(peerKey)
    }

    fun failureCount(peerKey: String): Int = failures[peerKey] ?: 0

    fun clearAll() {
        failures.clear()
        cooldowns.clear()
    }

    private fun trim() {
        while (failures.size > 200) {
            val oldest = failures.keys.firstOrNull()
            if (oldest != null) {
                clear(oldest)
            } else {
                break
            }
        }
    }
}
