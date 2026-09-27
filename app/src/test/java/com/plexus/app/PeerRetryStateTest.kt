package com.plexus.app

import org.junit.Assert.*
import org.junit.Test

class PeerRetryStateTest {
    private var now = 0L
    private val state = PeerRetryState { now }
    private fun found(endpoint: String = "A", name: String = "Harsha-12345678") = state.found(endpoint, name)!!

    @Test fun failurePlansRetryWithoutAnotherFound() {
        val candidate = found()
        assertTrue(state.begin(candidate))
        val retry = state.failed(candidate)!!
        assertFalse(state.ready(retry))
        now = retry.deadline
        assertTrue(state.ready(retry))
        assertTrue(state.begin(retry.candidate))
    }
    @Test fun actualExpiryRetrySequenceRetainsHistory() {
        val candidate = found()
        for (delay in listOf(5000L, 10000L, 20000L, 40000L, 60000L, 60000L)) {
            assertTrue(state.begin(candidate))
            val retry = state.failed(candidate)!!
            assertEquals(delay, retry.deadline - now)
            now = retry.deadline
            assertTrue(state.ready(retry))
            assertNull(state.cooldown.getExpireAt(candidate.key))
        }
    }
    @Test fun authenticatedSuccessResetsFailuresAndSuppressesRetry() {
        val candidate = found()
        val retry = state.failed(candidate)!!
        state.authenticated(candidate)
        now = retry.deadline
        assertFalse(state.ready(retry))
        assertEquals(5000L, state.failed(candidate)!!.deadline - now)
    }
    @Test fun duplicateFoundCannotDuplicatePendingAttempt() {
        assertTrue(state.begin(found()))
        assertFalse(state.begin(found()))
    }
    @Test fun lostCandidateInvalidatesTimer() {
        val retry = state.failed(found())!!
        state.lost("A")
        now = retry.deadline
        assertFalse(state.ready(retry))
    }
    @Test fun newEndpointSupersedesOldTimer() {
        val retry = state.failed(found())!!
        val replacement = found("B")
        now = retry.deadline
        assertFalse(state.ready(retry))
        assertTrue(state.begin(replacement))
    }
    @Test fun badPeerDoesNotBlockHealthyPeer() {
        val a = found()
        state.begin(a)
        state.failed(a)
        assertTrue(state.begin(found("B", "Harsha-87654321")))
    }
    @Test fun shutdownInvalidatesAllTimers() {
        val retry = state.failed(found())!!
        state.clear()
        now = retry.deadline
        assertFalse(state.ready(retry))
        assertTrue(state.begin(found()))
    }
    @Test fun renameChangesLabelNotStableKeyOrInitiator() {
        val node = "1".repeat(64)
        val before = NearbyEndpointName.create(node, "Harsha")
        val after = NearbyEndpointName.create(node, "Arshad")
        val other = NearbyEndpointName.create("2".repeat(64), "Harsha")
        assertNotEquals(before, after)
        assertEquals(node, NearbyEndpointName.key(after))
        assertEquals(NearbyEndpointName.key(before), NearbyEndpointName.key(after))
        assertTrue(NearbyEndpointName.initiates(before, other))
        assertTrue(NearbyEndpointName.initiates(after, other))
        assertFalse(NearbyEndpointName.initiates(other, after))
    }
    @Test fun legacyRoleOrderingRemainsSymmetric() {
        val local = NearbyEndpointName.create("1".repeat(64), "Name")
        val legacy = "Vishnu-8348c1de"
        assertEquals(local < legacy, NearbyEndpointName.initiates(local, legacy))
    }
}
