package com.plexus.app

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One cached bitmap, without Activity references; bitmap construction always runs on Default. */
internal class ProfileQrCache(private val generate: (String, Int) -> Bitmap = ::generateQrCode) {
    private val mutex = Mutex()
    private var cached: Pair<String, Bitmap>? = null

    suspend fun get(payload: String): Bitmap = withContext(Dispatchers.Default) {
        mutex.withLock {
            cached?.takeIf { it.first == payload }?.second ?: generate(payload, 900).also {
                cached = payload to it
            }
        }
    }
}

private val profileQrCache = ProfileQrCache()

@Composable
internal fun PlexusIdentityQr(payload: String) {
    val failed = remember { androidx.compose.runtime.mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, payload) {
        try {
            value = profileQrCache.get(payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed.value = true
            Log.w("PlexusProfile", "QR_GENERATION_FAILED")
        }
    }
    val ready = bitmap
    if (ready == null) {
        if (failed.value) Text("QR unavailable", color = Color.DarkGray)
        else CircularProgressIndicator(Modifier.size(28.dp), color = Color(0xFF146B31), strokeWidth = 2.dp)
        return
    }
    val image = remember(ready) { ready.asImageBitmap() }
    val reveal = remember(ready) { Animatable(if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f) }
    LaunchedEffect(ready) {
        if (ValueAnimator.areAnimatorsEnabled()) reveal.animateTo(1f, tween(400))
        else reveal.snapTo(1f)
    }
    Image(image, contentDescription = "Your Plexus identity QR", modifier = Modifier.fillMaxSize().printerQrReveal { reveal.value })
}

/** Two clipped draws at most, using the existing bitmap and 32 visual bands, never bitmap rows. */
internal fun Modifier.printerQrReveal(progress: () -> Float): Modifier = drawWithContent {
    val sweptBands = progress().coerceIn(0f, 1f) * 32
    val completed = sweptBands.toInt()
    val bandHeight = size.height / 32
    val bottom = completed * bandHeight
    if (completed > 0) clipRect(bottom = bottom) { this@drawWithContent.drawContent() }
    val fraction = sweptBands - completed
    if (completed < 32 && fraction > 0f) {
        clipRect(top = bottom, bottom = bottom + bandHeight, right = size.width * fraction) {
            this@drawWithContent.drawContent()
        }
    }
}

fun generateQrCode(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}
