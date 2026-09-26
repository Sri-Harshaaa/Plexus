package com.plexus.app

import org.junit.Assert.*
import org.junit.Test

class MeshPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun originAndLastHopMayBeDeliveredButBoundaryCannotRelay() {
        assertTrue(MeshPolicy.validLifetime(now, now + 1000, 0, now))
        assertTrue(MeshPolicy.validLifetime(now, now + 1000, 8, now))
        assertEquals(8L, MeshPolicy.nextHop(7, now + 1000, now))
        assertNull(MeshPolicy.nextHop(8, now + 1000, now))
    }

    @Test fun malformedHopsAndExpiredPacketsCannotForward() {
        for (hop in listOf(-1L, 9L, Long.MAX_VALUE)) {
            assertFalse(MeshPolicy.validLifetime(now, now + 1000, hop, now))
            assertNull(MeshPolicy.nextHop(hop, now + 1000, now))
        }
        assertNull(MeshPolicy.nextHop(0, now, now))
        assertFalse(MeshPolicy.validLifetime(now - 1000, now, 0, now))
    }

    @Test fun ttlAndClockSkewAreIndependentlyBounded() {
        assertFalse(MeshPolicy.validLifetime(now, now + PacketReplayGuard.MESSAGE_TTL_MS + 1, 0, now))
        assertFalse(MeshPolicy.validLifetime(now + 300001, now + 400000, 0, now))
    }

    @Test fun threeNodeCycleTerminatesEvenWithoutDedup() {
        var hop = 0L
        repeat(8) { hop = MeshPolicy.nextHop(hop, now + 1000, now)!! }
        assertNull(MeshPolicy.nextHop(hop, now + 1000, now))
    }
}
