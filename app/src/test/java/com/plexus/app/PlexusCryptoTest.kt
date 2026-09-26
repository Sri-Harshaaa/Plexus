package com.plexus.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlexusCryptoTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun hkdfMatchesRfc5869Sha256TestCaseOne() {
        val method = PlexusCrypto::class.java.getDeclaredMethod(
            "hkdfSha256", ByteArray::class.java, ByteArray::class.java,
            ByteArray::class.java, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val result = method.invoke(PlexusCrypto, ByteArray(22) { 0x0b },
            hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42) as ByteArray
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), result)
    }

    @Test fun authenticatedMetadataBindsBothIdentitiesMessageAndTimestamp() {
        val original = PlexusCrypto.buildMessageAad("a", "b", "id", 1)
        for (changed in listOf(
            PlexusCrypto.buildMessageAad("x", "b", "id", 1),
            PlexusCrypto.buildMessageAad("a", "x", "id", 1),
            PlexusCrypto.buildMessageAad("a", "b", "other", 1),
            PlexusCrypto.buildMessageAad("a", "b", "id", 2)
        )) assertFalse(original.contentEquals(changed))
    }
}
