package com.plexus.app

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PlexusSyncWorkTest {
    @Test fun wakeDuringRunningSyncEnqueuesSuccessorWithoutCancellingCurrentSync() {
        val requests = mutableListOf<Pair<String, ExistingWorkPolicy>>()
        val enqueue = { name: String, policy: ExistingWorkPolicy ->
            requests.add(name to policy)
            Unit
        }

        PlexusSyncWork.enqueue(enqueue)
        val runningRequest = requests.removeAt(0)
        // The running worker has already queried AWS; a new packet now triggers a wake.
        PlexusSyncWork.enqueue(enqueue)

        assertEquals(1, requests.size)
        assertEquals(runningRequest.first, requests.single().first)
        // WorkManager appends behind RUNNING work with this policy, rather than
        // discarding the wake (KEEP) or cancelling the running worker (REPLACE).
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, requests.single().second)
    }
}
