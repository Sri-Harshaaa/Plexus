package com.plexus.app

import org.junit.Assert.*
import org.junit.Test

class StartAttemptGateTest {
    @Test fun successConsumesTokenBeforeStaleTimeout() {
        val gate = StartAttemptGate()
        val token = gate.begin()
        assertTrue(gate.complete(token))
        assertFalse(gate.complete(token))
    }
    @Test fun timeoutThenLateSuccessCannotTouchNextAttempt() {
        val gate = StartAttemptGate()
        val old = gate.begin()
        assertTrue(gate.complete(old))
        gate.invalidate()
        assertFalse(gate.complete(old))
        val fresh = gate.begin()
        assertFalse(gate.complete(old))
        assertTrue(gate.complete(fresh))
    }
    @Test fun shutdownInvalidatesOutstandingOperation() {
        val gate = StartAttemptGate()
        val token = gate.begin()
        gate.invalidate()
        assertFalse(gate.complete(token))
        assertTrue(gate.complete(gate.begin()))
    }
}
