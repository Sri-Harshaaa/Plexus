package com.plexus.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private val ScannerBlack = Color(0xFF050505)
private val ScannerWhite = Color(0xFFFFFFFF)
private val ScannerOverlay = Color(0xCC000000)

@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasCameraPermission = granted
        }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScannerBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScannerHeader(onBack = onBack)

        if (hasCameraPermission) {
            ScannerCameraSection(
                onQrScanned = onQrScanned
            )
        } else {
            ScannerPermissionFallback(
                onGrantPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            )
        }
    }
}

@Composable
private fun ScannerHeader(
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            ScannerBackArrow(
                color = ScannerWhite,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "Scan Plexus QR",
            color = ScannerWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ScannerCameraSection(
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor()
    }

    var previewView by remember {
        mutableStateOf<PreviewView?>(null)
    }

    var hasScanned by remember {
        mutableStateOf(false)
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val view = previewView
        var active = true
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        if (view != null) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (active) {
                    try {
                        val cameraProvider = future.get()
                        provider = cameraProvider
                        val cameraPreview = Preview.Builder().build().also {
                            it.surfaceProvider = view.surfaceProvider
                        }
                        preview = cameraPreview
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis = imageAnalysis
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processQrFrame(imageProxy, scanner) { rawValue ->
                                if (active && !hasScanned && rawValue.length <= 8192) {
                                    hasScanned = true
                                    onQrScanned(rawValue)
                                }
                            }
                        }
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, imageAnalysis
                        )
                    } catch (exception: Exception) {
                        android.util.Log.e("PlexusQr", "Camera setup failed", exception)
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            active = false
            analysis?.clearAnalyzer()
            val owned = listOfNotNull(preview, analysis).toTypedArray()
            if (owned.isNotEmpty()) provider?.unbind(*owned)
            scanner.close()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { createdPreviewView ->
                    createdPreviewView.scaleType =
                        PreviewView.ScaleType.FILL_CENTER
                    previewView = createdPreviewView
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        ScannerOverlayMask()

        Text(
            text = "Align a Plexus QR inside the frame",
            color = ScannerWhite.copy(alpha = 0.92f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 38.dp)
        )
    }
}

@Composable
private fun ScannerOverlayMask() {
    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val frameWidth = size.width * 0.76f
        val frameHeight = frameWidth
        val left = (size.width - frameWidth) / 2f
        val top = (size.height - frameHeight) / 2f - 40.dp.toPx()
        val cornerRadius = 28.dp.toPx()

        val outerPath = Path().apply {
            addRect(
                Rect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height
                )
            )
        }

        val cutoutPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(
                        left = left,
                        top = top,
                        right = left + frameWidth,
                        bottom = top + frameHeight
                    ),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                )
            )
        }

        val overlayPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addPath(outerPath)
            addPath(cutoutPath)
        }

        drawPath(
            path = overlayPath,
            color = ScannerOverlay
        )

        drawRoundRect(
            color = ScannerWhite.copy(alpha = 0.98f),
            topLeft = Offset(left, top),
            size = Size(frameWidth, frameHeight),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

@Composable
private fun ScannerPermissionFallback(
    onGrantPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScannerBlack)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Camera permission is required",
            color = ScannerWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Allow camera access to scan Plexus QR codes.",
            color = ScannerWhite.copy(alpha = 0.75f),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(22.dp))

        Button(
            onClick = onGrantPermission
        ) {
            Text("Grant permission")
        }
    }
}

@Composable
private fun ScannerBackArrow(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
    ) {
        val stroke = 2.6.dp.toPx()

        drawLine(
            color = color,
            start = Offset(size.width * 0.18f, size.height * 0.50f),
            end = Offset(size.width * 0.86f, size.height * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        drawLine(
            color = color,
            start = Offset(size.width * 0.18f, size.height * 0.50f),
            end = Offset(size.width * 0.43f, size.height * 0.24f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        drawLine(
            color = color,
            start = Offset(size.width * 0.18f, size.height * 0.50f),
            end = Offset(size.width * 0.43f, size.height * 0.76f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processQrFrame(
    imageProxy: ImageProxy,
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onQrScanned: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val inputImage =
        InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

    barcodeScanner
        .process(inputImage)
        .addOnSuccessListener { barcodes ->
            val value =
                barcodes
                    .firstOrNull { it.rawValue != null }
                    ?.rawValue

            if (!value.isNullOrBlank()) {
                onQrScanned(value)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
