package com.plexus.app

import org.junit.Assert.*
import org.junit.Test

class PacketReplayGuardTest {
    @Test fun unauthenticatedCheckDoesNotPoisonReplayState() {
        val guard = PacketReplayGuard()
        assertEquals(PacketReplayGuard.CheckResult.ACCEPTABLE, guard.check("id", 1000, 1000))
        assertEquals(PacketReplayGuard.CheckResult.ACCEPTABLE, guard.check("id", 1000, 1000))
        guard.markAccepted("id", 1000)
        assertEquals(PacketReplayGuard.CheckResult.DUPLICATE, guard.check("id", 1000, 1000))
    }

    @Test fun boundedCacheEvictionOnlyRemovesFastHint() {
        val guard = PacketReplayGuard()
        repeat(10001) { guard.markAccepted("id$it", 1000) }
        assertFalse(guard.hasSeen("id0"))
        assertTrue(guard.hasSeen("id10000"))
    }
}
