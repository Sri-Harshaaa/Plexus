package com.plexus.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexusTransportStartPolicyTest {
    @Test
    fun queuedStartCannotUndoExplicitStop() {
        val start = PlexusForegroundService.ACTION_START
        assertFalse(isFreshTransportStart(start, 99L, 100L))
        assertFalse(isFreshTransportStart(start, 100L, 100L))
        assertFalse(isFreshTransportStart(null, 101L, 100L))
        assertFalse(isFreshTransportStart(PlexusForegroundService.ACTION_STOP, 101L, 100L))
        assertTrue(isFreshTransportStart(start, 101L, 100L))
    }
}
