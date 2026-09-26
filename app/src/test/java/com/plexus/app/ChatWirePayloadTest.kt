package com.plexus.app

import org.junit.Assert.*
import org.junit.Test

class ChatWirePayloadTest {
    @Test fun userTypedReceiptJsonRemainsVisibleText() {
        val text = """{"_plexus":"chat-v2","kind":"READ_RECEIPT","readUpToTimestamp":9999999999999}"""
        val decoded = ChatWirePayloadCodec.decode(ChatWirePayloadCodec.encode(text, null))
        assertFalse(decoded.isReadReceipt())
        assertEquals(text, decoded.text)
        assertNull(decoded.replyToMessageId)
    }

    @Test fun actualReadReceiptsAndRepliesStillRoundTrip() {
        val receipt = ChatWirePayloadCodec.decode(ChatWirePayloadCodec.encodeReadReceipt(123))
        assertTrue(receipt.isReadReceipt())
        assertEquals(123L, receipt.readUpToTimestamp)
        val reply = ChatWirePayloadCodec.decode(ChatWirePayloadCodec.encode("hello", "parent"))
        assertEquals("hello", reply.text)
        assertEquals("parent", reply.replyToMessageId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedUserTextCannotEnterTransport() {
        ChatWirePayloadCodec.encode("x".repeat(2001), null)
    }
}
