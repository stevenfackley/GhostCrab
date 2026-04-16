package com.openclaw.ghostcrab.ui.connection

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onNavigateToManualEntry: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: QrScanViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            val isPermanent = !ActivityCompat.shouldShowRequestPermissionRationale(
                context as Activity, Manifest.permission.CAMERA
            )
            viewModel.onPermissionDenied(isPermanent)
        }
    }

    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PermissionChecker.PERMISSION_GRANTED
        if (alreadyGranted) viewModel.onPermissionGranted()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is QrScanEvent.NavigateToManualEntry -> onNavigateToManualEntry(event.url)
            }
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is QrScanUiState.InvalidQr) {
            scope.launch {
                snackbarHostState.showSnackbar((uiState as QrScanUiState.InvalidQr).message)
                viewModel.onScanAgain()
            }
        }
    }

    if (uiState == QrScanUiState.PermissionDenied) {
        PermissionDeniedDialog(
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            },
            onDismiss = onNavigateBack,
        )
    }

    Scaffold(
        topBar = { QrTopBar(onNavigateBack = onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BrandTokens.colorAbyss,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                uiState == QrScanUiState.Scanning || uiState is QrScanUiState.InvalidQr -> {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        onQrDecoded = viewModel::onQrDecoded,
                    )
                    ViewfinderOverlay(modifier = Modifier.fillMaxSize())
                }
                uiState == QrScanUiState.PermissionRequired -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            "Camera permission is required to scan QR codes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BrandTokens.colorTextSecondary,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant Permission")
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrTopBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = { Text("Scan QR Code", color = BrandTokens.colorTextPrimary) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = BrandTokens.colorTextPrimary,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = BrandTokens.colorAbyss,
        ),
    )
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(
    modifier: Modifier,
    onQrDecoded: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).also { previewView ->
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy -> analyzeProxy(proxy, scanner, onQrDecoded) }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
    )
}

@OptIn(ExperimentalGetImage::class)
private fun analyzeProxy(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onQrDecoded: (String) -> Unit,
) {
    val mediaImage = proxy.image ?: run { proxy.close(); return }
    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes -> barcodes.firstOrNull()?.rawValue?.let(onQrDecoded) }
        .addOnCompleteListener { proxy.close() }
}

@Composable
private fun ViewfinderOverlay(modifier: Modifier) {
    val cyan = BrandTokens.colorCyanPrimary
    Canvas(modifier = modifier) {
        val boxSize = minOf(size.width, size.height) * 0.65f
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2f
        val arm = boxSize * 0.12f
        val strokeW = 4.dp.toPx()

        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(boxSize, boxSize),
            blendMode = BlendMode.Clear,
        )

        listOf(
            Offset(left, top)                     to Offset(left + arm, top),
            Offset(left, top)                     to Offset(left, top + arm),
            Offset(left + boxSize, top)           to Offset(left + boxSize - arm, top),
            Offset(left + boxSize, top)           to Offset(left + boxSize, top + arm),
            Offset(left, top + boxSize)           to Offset(left + arm, top + boxSize),
            Offset(left, top + boxSize)           to Offset(left, top + boxSize - arm),
            Offset(left + boxSize, top + boxSize) to Offset(left + boxSize - arm, top + boxSize),
            Offset(left + boxSize, top + boxSize) to Offset(left + boxSize, top + boxSize - arm),
        ).forEach { (start, end) ->
            drawLine(cyan, start, end, strokeWidth = strokeW, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun PermissionDeniedDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera Permission Required") },
        text = {
            Text(
                "GhostCrab needs camera access to scan QR codes. " +
                    "Enable it in Settings → App Permissions.",
                color = BrandTokens.colorTextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("Open Settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
