package com.plexus.app

import org.junit.Assert.*
import org.junit.Test

class PlexusSyncWorkTest {
    @Test fun burstCoalescesIntoOneWorkerAndOneFollowUpPass() {
        val wakes = PendingSyncWakes()
        assertTrue(wakes.wake("first"))
        assertTrue(wakes.begin("first"))
        val firstQuery = wakes.requested
        repeat(100) { assertFalse(wakes.wake("wake-$it")) }
        assertTrue(wakes.finishPass("first", firstQuery))
        val followUpQuery = wakes.requested
        assertFalse(wakes.finishPass("first", followUpQuery))
        assertEquals(wakes.requested, wakes.completed)
    }

    @Test fun queuedWakeBurstKeepsOneWorkerAndCapturesLatestGeneration() {
        val wakes = PendingSyncWakes()
        assertTrue(wakes.wake("queued"))
        repeat(100) { assertFalse(wakes.wake("burst-$it")) }
        assertTrue(wakes.begin("queued"))
        assertFalse(wakes.finishPass("queued", wakes.requested))
        assertEquals(101L, wakes.completed)
    }

    @Test fun cancellationRetainsDirtyWakeAndDoesNotReleaseANewerWorker() {
        val wakes = PendingSyncWakes()
        wakes.wake("old"); wakes.begin("old")
        val queried = wakes.requested
        assertFalse(wakes.wake("during-query"))
        wakes.release("old")
        assertTrue(wakes.requested > queried)
        assertEquals(0L, wakes.completed)
        assertTrue(wakes.wake("recovery"))
        assertTrue(wakes.begin("recovery"))
        wakes.release("old")
        assertFalse(wakes.begin("duplicate"))
        assertFalse(wakes.finishPass("recovery", wakes.requested))
    }

    @Test fun wakeAtWorkerCompletionSchedulesFreshWorkerInsteadOfBeingLost() {
        val wakes = PendingSyncWakes()
        wakes.wake("first"); wakes.begin("first")
        assertFalse(wakes.finishPass("first", wakes.requested))
        assertTrue(wakes.wake("second"))
        wakes.release("first")
        assertTrue(wakes.begin("second"))
        assertFalse(wakes.finishPass("second", wakes.requested))
    }

    @Test fun freshWakeReplacesRetryBackoffWithoutAWorkerChain() {
        val wakes = PendingSyncWakes()
        wakes.wake("stale"); wakes.begin("stale")
        assertFalse(wakes.failedPass("stale", wakes.requested))
        assertTrue(wakes.wake("fresh"))
        assertFalse(wakes.begin("stale"))
        assertTrue(wakes.begin("fresh"))
        assertFalse(wakes.finishPass("fresh", wakes.requested))
    }

    @Test fun wakeDuringFailedPassRetriesImmediatelyInsteadOfEnteringBackoff() {
        val wakes = PendingSyncWakes()
        wakes.wake("active"); wakes.begin("active")
        val query = wakes.requested
        assertFalse(wakes.wake("fresh"))
        assertTrue(wakes.failedPass("active", query))
        assertFalse(wakes.finishPass("active", wakes.requested))
    }

    @Test fun processDeathRetainsDirtyGenerationAndAllowsWorkManagerRecovery() {
        val previous = PendingSyncWakes()
        previous.wake("old"); previous.begin("old"); previous.wake("new")
        val restored = PendingSyncWakes(previous.requested, previous.completed)
        assertTrue(restored.begin("old"))
        assertFalse(restored.finishPass("old", restored.requested))
        assertEquals(restored.requested, restored.completed)
    }
}
