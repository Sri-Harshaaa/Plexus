package com.plexus.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCooldownStateTest {

    private var mockTime = 1000L

    private val state = PeerCooldownState(
        initialCooldownMs = 5000L,
        maxCooldownMs = 60000L,
        timeProvider = { mockTime }
    )

    @Test
    fun `first failure enters cooldown`() {
        val delay = state.applyFailure("peerA")
        assertEquals(5000L, delay)
        assertTrue(state.isCoolingDown("peerA"))
    }

    @Test
    fun `repeated failure increases bounded backoff`() {
        assertEquals(5000L, state.applyFailure("peerA"))
        assertEquals(10000L, state.applyFailure("peerA"))
        assertEquals(20000L, state.applyFailure("peerA"))
        assertEquals(40000L, state.applyFailure("peerA"))
        assertEquals(60000L, state.applyFailure("peerA")) // Capped at max (60s)
        assertEquals(60000L, state.applyFailure("peerA"))
    }

    @Test
    fun `cooldown expiry allows retry`() {
        state.applyFailure("peerA") // 5s delay
        assertTrue(state.isCoolingDown("peerA"))
        
        mockTime += 5001L // Advance time past cooldown
        
        assertFalse(state.isCoolingDown("peerA"))
    }

    @Test
    fun `successful connection resets peer backoff`() {
        state.applyFailure("peerA") // 5s
        state.applyFailure("peerA") // 10s
        
        state.clear("peerA") // Reset on success
        
        val delay = state.applyFailure("peerA")
        assertEquals(5000L, delay) // Starts over
    }

    @Test
    fun `backoff max bound remains enforced`() {
        for (i in 1..20) {
            state.applyFailure("peerA")
        }
        val delay = state.applyFailure("peerA")
        assertEquals(60000L, delay)
    }
}
