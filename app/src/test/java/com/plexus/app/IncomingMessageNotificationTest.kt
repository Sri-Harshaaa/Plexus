package com.plexus.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessageNotificationTest {
    @Test
    fun exactVisibleChatSuppressesOnlyItsConversation() {
        assertFalse(shouldNotifyIncomingMessage("contact-c", "contact-c"))
        assertTrue(shouldNotifyIncomingMessage(null, "contact-c"))
        assertTrue(shouldNotifyIncomingMessage("contact-b", "contact-c"))
    }

    @Test
    fun notificationIntentTargetsOnlyTheRequestedConversation() {
        assertEquals("contact-c", conversationContactId(ACTION_OPEN_CONVERSATION, "contact-c"))
        assertEquals(null, conversationContactId("other", "contact-c"))
    }

    @Test
    fun notificationIdentityUsesTheStableContactId() {
        assertEquals("contact-a", conversationNotificationTag("contact-a"))
        assertEquals("contact-b", conversationNotificationTag("contact-b"))
    }

    @Test
    fun notificationPermissionDenialSkipsPosting() {
        assertFalse(canPostIncomingMessage(false, null, "contact-c"))
        assertTrue(canPostIncomingMessage(true, null, "contact-c"))
    }
}
