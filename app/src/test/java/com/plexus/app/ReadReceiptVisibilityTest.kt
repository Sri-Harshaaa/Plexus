package com.plexus.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadReceiptVisibilityTest {
    @Test
    fun receiptRequiresResumedExactOpenChat() {
        assertTrue(
            shouldSendReadReceipt(
                activityResumed = true,
                currentScreen = PlexusScreen.CHAT,
                selectedContactNodeId = "contact-c",
                senderNodeId = "contact-c"
            )
        )
        assertFalse(
            shouldSendReadReceipt(
                activityResumed = false,
                currentScreen = PlexusScreen.CHAT,
                selectedContactNodeId = "contact-c",
                senderNodeId = "contact-c"
            )
        )
        assertFalse(
            shouldSendReadReceipt(
                activityResumed = true,
                currentScreen = PlexusScreen.HOME,
                selectedContactNodeId = "contact-c",
                senderNodeId = "contact-c"
            )
        )
        assertFalse(
            shouldSendReadReceipt(
                activityResumed = true,
                currentScreen = PlexusScreen.CHAT,
                selectedContactNodeId = "contact-b",
                senderNodeId = "contact-c"
            )
        )
    }
}
