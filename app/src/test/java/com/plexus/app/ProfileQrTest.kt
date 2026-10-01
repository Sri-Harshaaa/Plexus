package com.plexus.app

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class ProfileQrTest {
    @Test fun generationRunsOffMainCachesOnePayloadAndInvalidatesChanges() = runBlocking {
        var generations = 0
        val cache = ProfileQrCache { _, size ->
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            generations++
            Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        }
        val first = cache.get("first")
        assertSame(first, cache.get("first"))
        val changed = cache.get("changed")
        assertNotSame(first, changed)
        assertSame(changed, cache.get("changed"))
        assertNotSame(first, cache.get("first")) // Earlier payload was evicted, not accumulated.
        assertEquals(3, generations)
    }

    @Test fun bulkGeneratedBitmapRoundTripsUnicodeIdentityData() = runBlocking {
        val payload = "{\"displayName\":\"Peer 🪷\",\"publicTestIdentity\":\"test\"}"
        val bitmap = ProfileQrCache().get(payload)
        assertEquals(900, bitmap.width)
        assertEquals(900, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
            RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        )))
        assertEquals(payload, decoded.text)
    }
}
