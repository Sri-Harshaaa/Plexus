package com.plexus.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexusPersonalMessagingTest {
    @Test fun startupRuntimeIsNotReportedReadyUntilInitializationCompletes() {
        assertFalse(isPersonalMessagingReady(starting = true, runtimePresent = true))
        assertFalse(isPersonalMessagingReady(starting = false, runtimePresent = false))
        assertTrue(isPersonalMessagingReady(starting = false, runtimePresent = true))
    }

    @Test fun stopDuringStartupCancelsDeferredParticipationButAllowsFreshStart() {
        val requests = NetworkParticipationRequests()
        var starts = 0
        val pendingStartupCompletion = requests.startWhenReady { starts++ }

        requests.stop()
        pendingStartupCompletion()
        assertEquals(0, starts)

        requests.startWhenReady { starts++ }()
        assertEquals(1, starts)
    }
}
