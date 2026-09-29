package com.plexus.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetSessionRejectionTest {
    @Test
    fun terminalServerRejectionsUseBoundedCategories() {
        assertEquals("PACKET_REJECTED_INVALID", terminalPacketRejectionCode("INVALID_PACKET"))
        assertEquals("PACKET_REJECTED_MISMATCH", terminalPacketRejectionCode("PROOF_MISMATCH"))
        assertEquals("PACKET_REJECTED_CONFLICT", terminalPacketRejectionCode("MESSAGE_ID_CONFLICT"))
        assertEquals("PACKET_REJECTED_EXPIRED", terminalPacketRejectionCode("PACKET_EXPIRED"))
        assertTrue(isTerminalPacketRejection("PACKET_REJECTED_CONFLICT"))
        assertFalse(isTerminalPacketRejection("SERVER_RETRY_REQUIRED"))
    }
}
