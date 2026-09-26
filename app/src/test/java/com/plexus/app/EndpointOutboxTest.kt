package com.plexus.app

import org.junit.Assert.*
import org.junit.Test

class EndpointOutboxTest {
    private fun offer(box: EndpointOutbox, id: String, priority: Int = 2) =
        box.offer(id, byteArrayOf(1), priority, 0)

    @Test fun messagesAndControlOvertakeHistoricalBackground() {
        val box = EndpointOutbox(1)
        repeat(64) { assertTrue(offer(box, "b$it")) }
        assertFalse(offer(box, "overflow"))
        assertTrue(offer(box, "message", 1))
        assertTrue(offer(box, "auth", 0))
        assertEquals("auth", box.poll()!!.key)
        assertEquals("message", box.poll()!!.key)
        assertEquals("b0", box.poll()!!.key)
    }

    @Test fun duplicateQueuedAndInFlightPacketsAreCoalesced() {
        val box = EndpointOutbox(1)
        assertTrue(offer(box, "id"))
        assertTrue(offer(box, "id", 1))
        assertEquals(1, box.size)
        val item = box.poll()!!
        assertEquals(1, item.priority)
        box.start(10, item)
        assertTrue(offer(box, "id"))
        assertEquals(0, box.size)
        assertNull(box.poll())
    }

    @Test fun lateCallbackAndOldWatchdogCannotFinishNewTransfer() {
        val box = EndpointOutbox(1)
        offer(box, "one")
        val old = box.start(1, box.poll()!!)
        assertTrue(box.finish(old, 0))
        offer(box, "two")
        val current = box.start(2, box.poll()!!)
        assertFalse(box.finish(old, 1))
        assertTrue(box.matches(current))
        assertFalse(box.matches(old))
    }

    @Test fun reconnectRejectsOldSessionEvenWithSamePayloadId() {
        val before = EndpointOutbox(1)
        offer(before, "old")
        val old = before.start(5, before.poll()!!)
        val after = EndpointOutbox(2)
        offer(after, "new")
        val current = after.start(5, after.poll()!!)
        assertFalse(after.finish(old, 0))
        assertTrue(after.matches(current))
    }

    @Test fun successfulTransportIsEligibleForRetryWithoutDeliveryProof() {
        val box = EndpointOutbox(1)
        offer(box, "pending")
        val transfer = box.start(1, box.poll()!!)
        assertTrue(box.finish(transfer, 0))
        assertTrue(offer(box, "pending"))
        assertNull(box.poll())
        assertTrue(box.offer("pending", byteArrayOf(1), 1, EndpointOutbox.RETRY_SUPPRESSION_MS))
        assertNotNull(box.poll())
    }

    @Test fun byteBudgetCannotConsumeReservedControlCapacity() {
        val box = EndpointOutbox(1)
        repeat(8) { assertTrue(box.offer("large$it", ByteArray(32768), 2, 0)) }
        assertFalse(box.offer("overflow", ByteArray(32768), 2, 0))
        assertTrue(offer(box, "auth", 0))
        assertFalse(box.offer("oversized", ByteArray(32769), 0, 0))
    }

    @Test fun acknowledgedCiphertextIsRemovedWithoutDisturbingInFlightControl() {
        val box = EndpointOutbox(1)
        offer(box, "auth", 0)
        val transfer = box.start(1, box.poll()!!)
        offer(box, "mesh:a:b:delivered:hash", 1)
        offer(box, "mesh:a:b:pending:hash", 1)
        box.discardQueued("mesh:a:b:delivered:")
        assertTrue(box.matches(transfer))
        box.finish(transfer, 1)
        assertEquals("mesh:a:b:pending:hash", box.poll()!!.key)
    }

    @Test fun explicitProofResponseBypassesHistoricalSyncSuppression() {
        val box = EndpointOutbox(1)
        offer(box, "anti:proof")
        box.finish(box.start(1, box.poll()!!), 0)
        offer(box, "anti:proof")
        assertNull(box.poll())
        offer(box, "anti:proof", 1)
        assertNotNull(box.poll())
    }

    @Test fun continuousControlCannotStarveMessages() {
        val box = EndpointOutbox(1)
        repeat(16) { offer(box, "auth$it", 0) }
        offer(box, "chat", 1)
        repeat(8) { assertEquals(0, box.poll()!!.priority) }
        assertEquals("chat", box.poll()!!.key)
    }

    @Test fun priorityPromotionCannotBypassCapacity() {
        val box = EndpointOutbox(1)
        repeat(32) { offer(box, "auth$it", 0) }
        repeat(64) { offer(box, "background$it", 2) }
        repeat(64) { offer(box, "background$it", 0) }
        assertFalse(offer(box, "additional", 2))
        assertEquals(96, box.size)
    }
}
