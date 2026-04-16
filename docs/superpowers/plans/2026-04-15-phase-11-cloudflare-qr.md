# Phase 11 — Cloudflare Tunnel + QR Connect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Cloudflare quick-tunnel discovery (via QR scan), replace the TopAppBar text title with the GhostCrab logo, and add an animated splash screen.

**Architecture:** Purely additive. New `QrScanScreen` + `QrScanViewModel` slot into the existing connection flow: scan QR → validate URL → navigate to `ManualEntryScreen` with URL pre-filled. The `tunnel-qr` Docker/PyPI helper runs alongside `cloudflared` on the gateway and serves a QR page at `:19999`. No domain interface changes.

**Tech Stack:** Kotlin 2.0, CameraX 1.4.1, ML Kit barcode-scanning 17.3.0, Koin, Compose, Python 3.12/Alpine (tunnel-qr)

---

## File Map

| Action | Path |
|--------|------|
| Create | `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt` |
| Create | `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanScreen.kt` |
| Create | `app/src/test/kotlin/com/openclaw/ghostcrab/ui/QrScanViewModelTest.kt` |
| Create | `app/src/main/res/drawable/ic_splash_crab.xml` |
| Create | `app/src/main/res/animator/splash_crab_anim.xml` |
| Create | `app/src/main/res/drawable/animated_splash_crab.xml` |
| Create | `app/src/main/res/drawable-nodpi/logo_ghostcrab.png` (copy from repo root) |
| Create | `app/src/main/res/values-v31/themes.xml` |
| Create | `docker/tunnel-qr/serve.py` |
| Create | `docker/tunnel-qr/Dockerfile` |
| Create | `docker/docker-compose.example.yml` |
| Create | `docs/TUNNEL_SETUP.md` |
| Modify | `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerScreen.kt` |
| Modify | `app/src/main/kotlin/com/openclaw/ghostcrab/ui/navigation/NavGraph.kt` |
| Modify | `app/src/main/kotlin/com/openclaw/ghostcrab/di/UiModule.kt` |
| Modify | `app/src/main/AndroidManifest.xml` |
| Modify | `app/src/main/res/values/themes.xml` |
| Modify | `app/build.gradle.kts` |
| Modify | `gradle/libs.versions.toml` |
| Modify | `IMPLEMENTATION_PLAN.md` |

---

## Task 1: Dependencies + Manifest

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add CameraX + ML Kit versions to libs.versions.toml**

In the `[versions]` section, after the last existing version entry, add:
```toml
camerax = "1.4.1"
mlkit-barcode = "17.3.0"
```

In the `[libraries]` section, after the last existing library entry, add:
```toml
camerax-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camerax-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
mlkit-barcode-scanning = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkit-barcode" }
```

- [ ] **Step 2: Add dependencies to app/build.gradle.kts**

In the `dependencies` block, after the existing `implementation` lines and before `debugImplementation`:
```kotlin
// CameraX
implementation(libs.camerax.camera2)
implementation(libs.camerax.lifecycle)
implementation(libs.camerax.view)

// ML Kit QR decode (on-device, no network call)
implementation(libs.mlkit.barcode.scanning)
```

- [ ] **Step 3: Add CAMERA permission to AndroidManifest.xml**

After the existing `<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />` line, add:
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

- [ ] **Step 4: Verify build**

```bash
./gradlew assembleDebug --no-configuration-cache
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "chore: add CameraX 1.4.1 + ML Kit barcode-scanning 17.3.0 + CAMERA permission"
```

---

## Task 2: QrScanViewModel (TDD)

**Files:**
- Create: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/QrScanViewModelTest.kt`
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/UiModule.kt`

- [ ] **Step 1: Write the test file**

```kotlin
// app/src/test/kotlin/com/openclaw/ghostcrab/ui/QrScanViewModelTest.kt
package com.openclaw.ghostcrab.ui

import app.cash.turbine.test
import com.openclaw.ghostcrab.ui.connection.QrScanEvent
import com.openclaw.ghostcrab.ui.connection.QrScanUiState
import com.openclaw.ghostcrab.ui.connection.QrScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QrScanViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `onPermissionGranted sets Scanning state`() {
        val vm = QrScanViewModel()
        vm.onPermissionGranted()
        assertInstanceOf(QrScanUiState.Scanning::class.java, vm.uiState.value)
    }

    @Test
    fun `onPermissionDenied permanent sets PermissionDenied state`() {
        val vm = QrScanViewModel()
        vm.onPermissionDenied(isPermanent = true)
        assertInstanceOf(QrScanUiState.PermissionDenied::class.java, vm.uiState.value)
    }

    @Test
    fun `onPermissionDenied not permanent sets PermissionRequired state`() {
        val vm = QrScanViewModel()
        vm.onPermissionDenied(isPermanent = false)
        assertInstanceOf(QrScanUiState.PermissionRequired::class.java, vm.uiState.value)
    }

    @Test
    fun `onQrDecoded valid https url emits NavigateToManualEntry with url`() = runTest {
        val vm = QrScanViewModel()
        vm.events.test {
            vm.onQrDecoded("https://abc-xyz.trycloudflare.com")
            val event = awaitItem() as QrScanEvent.NavigateToManualEntry
            assertEquals("https://abc-xyz.trycloudflare.com", event.url)
        }
    }

    @Test
    fun `onQrDecoded valid http url with port emits NavigateToManualEntry`() = runTest {
        val vm = QrScanViewModel()
        vm.events.test {
            vm.onQrDecoded("http://192.168.1.50:18789")
            assertEquals("http://192.168.1.50:18789", (awaitItem() as QrScanEvent.NavigateToManualEntry).url)
        }
    }

    @Test
    fun `onQrDecoded blank string sets InvalidQr state`() {
        val vm = QrScanViewModel()
        vm.onQrDecoded("   ")
        assertInstanceOf(QrScanUiState.InvalidQr::class.java, vm.uiState.value)
    }

    @Test
    fun `onQrDecoded non-url text sets InvalidQr state`() {
        val vm = QrScanViewModel()
        vm.onQrDecoded("just some random text")
        assertInstanceOf(QrScanUiState.InvalidQr::class.java, vm.uiState.value)
    }

    @Test
    fun `onScanAgain resets to Scanning after InvalidQr`() {
        val vm = QrScanViewModel()
        vm.onQrDecoded("not a url")
        assertInstanceOf(QrScanUiState.InvalidQr::class.java, vm.uiState.value)
        vm.onScanAgain()
        assertInstanceOf(QrScanUiState.Scanning::class.java, vm.uiState.value)
    }
}
```

- [ ] **Step 2: Run test — verify it fails to compile**

```bash
./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.QrScanViewModelTest" --no-configuration-cache 2>&1 | grep -E "(error:|FAILED|BUILD)" | head -10
```

Expected: compilation error — `QrScanViewModel`, `QrScanUiState`, `QrScanEvent` not found.

- [ ] **Step 3: Write the ViewModel**

```kotlin
// app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt
package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface QrScanUiState {
    /** Initial state before permission is checked. */
    data object Idle : QrScanUiState
    /** Camera is active and scanning. */
    data object Scanning : QrScanUiState
    /** Permission denied once — can request again. */
    data object PermissionRequired : QrScanUiState
    /** Permission permanently denied — must open Settings. */
    data object PermissionDenied : QrScanUiState
    /** A QR was decoded but its content is not a valid gateway URL. */
    data class InvalidQr(val message: String) : QrScanUiState
}

sealed interface QrScanEvent {
    /** Emitted when a valid URL is decoded. Navigate to ManualEntry with this URL. */
    data class NavigateToManualEntry(val url: String) : QrScanEvent
}

/**
 * ViewModel for [QrScanScreen].
 *
 * All camera permission checks and CameraX lifecycle management live in the screen;
 * this ViewModel only manages state and validates decoded URLs.
 */
class QrScanViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<QrScanUiState>(QrScanUiState.Idle)
    val uiState: StateFlow<QrScanUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<QrScanEvent>()
    val events: SharedFlow<QrScanEvent> = _events.asSharedFlow()

    /** Call when the system grants camera permission. */
    fun onPermissionGranted() {
        _uiState.value = QrScanUiState.Scanning
    }

    /**
     * Call when the system denies camera permission.
     *
     * @param isPermanent `true` when "Don't ask again" was selected.
     */
    fun onPermissionDenied(isPermanent: Boolean) {
        _uiState.value = if (isPermanent) QrScanUiState.PermissionDenied
                          else QrScanUiState.PermissionRequired
    }

    /**
     * Call when ML Kit decodes a barcode.
     *
     * Validates [raw] is a valid `http://` or `https://` URL. On success emits
     * [QrScanEvent.NavigateToManualEntry]; on failure sets [QrScanUiState.InvalidQr].
     *
     * @param raw The raw string value from the decoded QR barcode.
     */
    fun onQrDecoded(raw: String) {
        val url = raw.trim()
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            _uiState.value = QrScanUiState.InvalidQr("QR code doesn't contain a valid gateway URL")
            return
        }
        runCatching { java.net.URI(url) }.onFailure {
            _uiState.value = QrScanUiState.InvalidQr("QR code doesn't contain a valid gateway URL")
            return
        }
        viewModelScope.launch { _events.emit(QrScanEvent.NavigateToManualEntry(url)) }
    }

    /** Re-activate the scanner after an [QrScanUiState.InvalidQr] state. */
    fun onScanAgain() {
        _uiState.value = QrScanUiState.Scanning
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.QrScanViewModelTest" --no-configuration-cache 2>&1 | grep -E "(tests completed|FAILED|BUILD)"
```

Expected: `8 tests completed, 0 failed` · `BUILD SUCCESSFUL`.

- [ ] **Step 5: Register in UiModule**

In `app/src/main/kotlin/com/openclaw/ghostcrab/di/UiModule.kt`, add the import and a viewModel entry after the last `viewModel { ... }` line:

```kotlin
import com.openclaw.ghostcrab.ui.connection.QrScanViewModel
```

```kotlin
viewModel { QrScanViewModel() }
```

- [ ] **Step 6: Commit**

```bash
git add app/src/test/kotlin/com/openclaw/ghostcrab/ui/QrScanViewModelTest.kt \
        app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt \
        app/src/main/kotlin/com/openclaw/ghostcrab/di/UiModule.kt
git commit -m "feat: QrScanViewModel — 8 tests (permission states, URL validation, navigation event)"
```

---

## Task 3: QrScanScreen

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanScreen.kt`

- [ ] **Step 1: Write QrScanScreen.kt**

```kotlin
// app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanScreen.kt
package com.openclaw.ghostcrab.ui.connection

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
        val alreadyGranted = ContextCompat.checkPermission(
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
        topBar = {
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
        },
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

        // Dim overlay (with hole cleared for the viewfinder box)
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(boxSize, boxSize),
            blendMode = BlendMode.Clear,
        )

        // Cyan corner brackets
        listOf(
            Offset(left, top)                to Offset(left + arm, top),
            Offset(left, top)                to Offset(left, top + arm),
            Offset(left + boxSize, top)      to Offset(left + boxSize - arm, top),
            Offset(left + boxSize, top)      to Offset(left + boxSize, top + arm),
            Offset(left, top + boxSize)      to Offset(left + arm, top + boxSize),
            Offset(left, top + boxSize)      to Offset(left, top + boxSize - arm),
            Offset(left + boxSize, top + boxSize) to Offset(left + boxSize - arm, top + boxSize),
            Offset(left + boxSize, top + boxSize) to Offset(left + boxSize, top + boxSize - arm),
        ).forEach { (start, end) ->
            drawLine(Color(cyan.value), start, end, strokeWidth = strokeW, cap = StrokeCap.Round)
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
```

- [ ] **Step 2: Verify build**

```bash
./gradlew assembleDebug --no-configuration-cache 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanScreen.kt
git commit -m "feat: QrScanScreen — CameraX viewfinder, ML Kit QR decode, permission handling"
```

---

## Task 4: NavGraph + ConnectionPickerScreen (logo + QR entry point)

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerScreen.kt`
- Create: `app/src/main/res/drawable-nodpi/logo_ghostcrab.png`

- [ ] **Step 1: Copy logo asset to drawable-nodpi**

```bash
mkdir -p app/src/main/res/drawable-nodpi
cp logo_cropped.png app/src/main/res/drawable-nodpi/logo_ghostcrab.png
```

- [ ] **Step 2: Add qr_scan route to NavGraph.kt**

In `NavGraph.kt`, add this import at the top:
```kotlin
import com.openclaw.ghostcrab.ui.connection.QrScanScreen
```

Add this composable block after the `composable("connection_picker") { ... }` block (before the `composable("manual_entry?...")` block):

```kotlin
composable("qr_scan") {
    QrScanScreen(
        onNavigateToManualEntry = { url ->
            val encoded = android.net.Uri.encode(url)
            navController.navigate("manual_entry?prefillUrl=$encoded") {
                popUpTo("qr_scan") { inclusive = true }
            }
        },
        onNavigateBack = { navController.popBackStack() },
    )
}
```

Also update the `connection_picker` composable to pass the new `onNavigateToQrScan` callback:

```kotlin
composable("connection_picker") {
    ConnectionPickerScreen(
        onNavigateToManualEntry = { navController.navigate("manual_entry") },
        onNavigateToScan = { navController.navigate("scan") },
        onNavigateToQrScan = { navController.navigate("qr_scan") },
        onNavigateToDashboard = {
            navController.navigate("dashboard") {
                popUpTo("connection_picker") { inclusive = true }
            }
        },
        onNavigateToOnboarding = {
            navController.navigate("onboarding") {
                popUpTo("connection_picker") { inclusive = false }
            }
        },
    )
}
```

- [ ] **Step 3: Replace ConnectionPickerScreen.kt**

Replace the entire file content with:

```kotlin
package com.openclaw.ghostcrab.ui.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionPickerScreen(
    onNavigateToManualEntry: () -> Unit,
    onNavigateToScan: () -> Unit,
    onNavigateToQrScan: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onNavigateToOnboarding: () -> Unit = {},
    viewModel: ConnectionPickerViewModel = koinViewModel(),
) {
    val profiles by viewModel.profiles.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var connectingId by remember { mutableStateOf<String?>(null) }

    val showOnboarding by viewModel.showOnboarding.collectAsState()
    LaunchedEffect(showOnboarding) {
        if (showOnboarding) {
            onNavigateToOnboarding()
            viewModel.onOnboardingNavigated()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.logo_ghostcrab),
                        contentDescription = "GhostCrab",
                        modifier = Modifier.height(38.dp),
                        contentScale = ContentScale.Fit,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandTokens.colorAbyss,
                ),
                actions = {
                    IconButton(onClick = onNavigateToQrScan) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR Code",
                            tint = BrandTokens.colorCyanPrimary,
                        )
                    }
                    IconButton(onClick = onNavigateToScan) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Scan LAN",
                            tint = BrandTokens.colorTextSecondary,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (profiles.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onNavigateToManualEntry,
                    containerColor = BrandTokens.colorCyanPrimary,
                    contentColor = BrandTokens.colorAbyss,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add gateway")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BrandTokens.colorAbyss,
    ) { innerPadding ->
        if (profiles.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onScanQr = onNavigateToQrScan,
                onScanLan = onNavigateToScan,
                onManualEntry = onNavigateToManualEntry,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isConnecting = connectingId == profile.id,
                        onConnect = {
                            connectingId = profile.id
                            viewModel.connect(profile) { result ->
                                connectingId = null
                                result.onSuccess { onNavigateToDashboard() }
                                    .onFailure { e ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                e.message ?: "Connection failed"
                                            )
                                        }
                                    }
                            }
                        },
                        onDelete = { viewModel.delete(profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ConnectionProfile,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting, onClick = onConnect),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = BrandTokens.colorTextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = profile.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = BrandTokens.colorTextSecondary,
                )
                if (profile.hasToken) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "TOKEN AUTH",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = BrandTokens.colorCyanPulse,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(Spacing.sm),
                    color = BrandTokens.colorCyanPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete profile",
                        tint = BrandTokens.colorTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    onScanQr: () -> Unit,
    onScanLan: () -> Unit,
    onManualEntry: () -> Unit,
) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // QR hero card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(BrandTokens.colorCyanPrimary.copy(alpha = 0.06f))
                .border(
                    width = 1.dp,
                    color = BrandTokens.colorCyanPrimary.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(Spacing.xl),
            contentAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = BrandTokens.colorCyanPrimary,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "Scan QR to connect",
                    style = MaterialTheme.typography.titleMedium,
                    color = BrandTokens.colorCyanPrimary,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Open your gateway's connect page in a browser:\nhttp://<gateway-ip>:19999",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = BrandTokens.colorTextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = onScanQr,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandTokens.colorCyanPrimary,
                        contentColor = BrandTokens.colorAbyss,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Open Camera")
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedButton(onClick = onScanLan, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Scan LAN")
            }
            OutlinedButton(onClick = onManualEntry, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Manual")
            }
        }
    }
}
```

**Note:** The `androidx.compose.foundation.Image` import is the standard Compose Image composable. If it conflicts with Coil's `AsyncImage`, use `import androidx.compose.foundation.Image` explicitly or just `Image(painter = painterResource(...), ...)` — both work for local drawable resources.

- [ ] **Step 4: Verify build**

```bash
./gradlew assembleDebug --no-configuration-cache 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable-nodpi/logo_ghostcrab.png \
        app/src/main/kotlin/com/openclaw/ghostcrab/ui/navigation/NavGraph.kt \
        app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerScreen.kt
git commit -m "feat: QR scan entry point + GhostCrab logo in TopAppBar + QR hero empty state"
```

---

## Task 5: Animated Splash Screen

**Files:**
- Create: `app/src/main/res/drawable/ic_splash_crab.xml`
- Create: `app/src/main/res/animator/splash_crab_anim.xml`
- Create: `app/src/main/res/drawable/animated_splash_crab.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values-v31/themes.xml`

- [ ] **Step 1: Create the crab VectorDrawable**

```bash
mkdir -p app/src/main/res/drawable
```

Create `app/src/main/res/drawable/ic_splash_crab.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <group
        android:name="crab"
        android:pivotX="54"
        android:pivotY="54">

        <!-- Body oval -->
        <path
            android:pathData="M54,70 A18,18 0 1 1 54.01,70 Z"
            android:fillColor="#00000000"
            android:strokeColor="#5BE9FF"
            android:strokeWidth="3.5" />

        <!-- Left eye stalk -->
        <path
            android:pathData="M46,36 C44,29 41,25 38,20"
            android:fillColor="#00000000"
            android:strokeColor="#5BE9FF"
            android:strokeWidth="3"
            android:strokeLineCap="round" />

        <!-- Left eye -->
        <path
            android:pathData="M38,18 m-3,0 a3,3 0 1 0 6,0 a3,3 0 1 0 -6,0"
            android:fillColor="#5BE9FF" />

        <!-- Right eye stalk -->
        <path
            android:pathData="M62,36 C64,29 67,25 70,20"
            android:fillColor="#00000000"
            android:strokeColor="#5BE9FF"
            android:strokeWidth="3"
            android:strokeLineCap="round" />

        <!-- Right eye -->
        <path
            android:pathData="M70,18 m-3,0 a3,3 0 1 0 6,0 a3,3 0 1 0 -6,0"
            android:fillColor="#5BE9FF" />

        <!-- Left claw -->
        <path
            android:pathData="M36,52 C24,46 14,50 12,58 C10,66 18,72 26,67"
            android:fillColor="#00000000"
            android:strokeColor="#5BE9FF"
            android:strokeWidth="3.5"
            android:strokeLineCap="round" />

        <!-- Right claw -->
        <path
            android:pathData="M72,52 C84,46 94,50 96,58 C98,66 90,72 82,67"
            android:fillColor="#00000000"
            android:strokeColor="#5BE9FF"
            android:strokeWidth="3.5"
            android:strokeLineCap="round" />

        <!-- Left leg -->
        <path
            android:pathData="M40,64 C32,68 28,74 30,80"
            android:fillColor="#00000000"
            android:strokeColor="#5BE9FF"
            android:strokeWidth="2.5"
            android:strokeLineCap="round" />

        <!-- Right leg -->
        <path
            android:pathData="M68,64 C76,68 80,74 78,80"
            android:fillColor="#00000000"
            android:strokeColor="#5BE9FF"
            android:strokeWidth="2.5"
            android:strokeLineCap="round" />

    </group>
</vector>
```

- [ ] **Step 2: Create the animator**

```bash
mkdir -p app/src/main/res/animator
```

Create `app/src/main/res/animator/splash_crab_anim.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android"
    android:ordering="together">

    <objectAnimator
        android:propertyName="scaleX"
        android:valueFrom="0.6"
        android:valueTo="1.0"
        android:duration="600"
        android:interpolator="@android:interpolator/fast_out_slow_in" />

    <objectAnimator
        android:propertyName="scaleY"
        android:valueFrom="0.6"
        android:valueTo="1.0"
        android:duration="600"
        android:interpolator="@android:interpolator/fast_out_slow_in" />

    <objectAnimator
        android:propertyName="alpha"
        android:valueFrom="0.0"
        android:valueTo="1.0"
        android:duration="400"
        android:interpolator="@android:interpolator/fast_out_slow_in" />

</set>
```

- [ ] **Step 3: Create the AnimatedVectorDrawable**

Create `app/src/main/res/drawable/animated_splash_crab.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<animated-vector
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:drawable="@drawable/ic_splash_crab">

    <target
        android:name="crab"
        android:animation="@animator/splash_crab_anim" />

</animated-vector>
```

- [ ] **Step 4: Update themes.xml**

Replace the entire `app/src/main/res/values/themes.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.GhostCrab" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">#0F1115</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/animated_splash_crab</item>
        <item name="windowSplashScreenAnimationDuration">600</item>
        <item name="postSplashScreenTheme">@style/Theme.GhostCrab.Main</item>
    </style>
    <style name="Theme.GhostCrab.Main" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 5: Create values-v31/themes.xml (branding image for API 31+)**

```bash
mkdir -p app/src/main/res/values-v31
```

Create `app/src/main/res/values-v31/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.GhostCrab" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">#0F1115</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/animated_splash_crab</item>
        <item name="windowSplashScreenAnimationDuration">600</item>
        <item name="windowSplashScreenBrandingImage">@drawable/logo_ghostcrab</item>
        <item name="postSplashScreenTheme">@style/Theme.GhostCrab.Main</item>
    </style>
</resources>
```

- [ ] **Step 6: Verify build**

```bash
./gradlew assembleDebug --no-configuration-cache 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/drawable/ic_splash_crab.xml \
        app/src/main/res/animator/splash_crab_anim.xml \
        app/src/main/res/drawable/animated_splash_crab.xml \
        app/src/main/res/values/themes.xml \
        app/src/main/res/values-v31/themes.xml
git commit -m "feat: animated splash screen — crab VectorDrawable, scale+fade AVD, branding image API 31+"
```

---

## Task 6: tunnel-qr Helper + Docs

**Files:**
- Create: `docker/tunnel-qr/serve.py`
- Create: `docker/tunnel-qr/Dockerfile`
- Create: `docker/docker-compose.example.yml`
- Create: `docs/TUNNEL_SETUP.md`

- [ ] **Step 1: Create docker/tunnel-qr/serve.py**

```bash
mkdir -p docker/tunnel-qr
```

```python
#!/usr/bin/env python3
"""tunnel-qr: Reads the Cloudflare quick-tunnel URL from cloudflared log output
and serves a QR code page on LAN port 19999.

Environment variables:
  TUNNEL_LOG   Path to the file where cloudflared stdout/stderr is tee'd.
               Default: /shared/tunnel.log
  QR_PORT      HTTP port for the QR page. Default: 19999
"""
import base64
import http.server
import io
import os
import re
import threading
import time

import qrcode

TUNNEL_LOG = os.environ.get("TUNNEL_LOG", "/shared/tunnel.log")
QR_PORT = int(os.environ.get("QR_PORT", "19999"))
_URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com", re.IGNORECASE)

_tunnel_url: str | None = None
_lock = threading.Lock()


def _watch_log() -> None:
    """Background thread: scan TUNNEL_LOG line by line until the tunnel URL is found."""
    global _tunnel_url
    while True:
        try:
            with open(TUNNEL_LOG, encoding="utf-8", errors="replace") as f:
                for line in f:
                    m = _URL_RE.search(line)
                    if m:
                        with _lock:
                            _tunnel_url = m.group(0)
                        print(f"[tunnel-qr] Tunnel URL: {_tunnel_url}", flush=True)
                        return
        except FileNotFoundError:
            pass
        time.sleep(2)


def _qr_data_uri(url: str) -> str:
    img = qrcode.make(url)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


_HTML_WAITING = """<!doctype html>
<html><head><meta charset="utf-8"><meta http-equiv="refresh" content="3">
<title>GhostCrab — Waiting for tunnel…</title>
<style>
body{background:#0F1115;color:#ccc;font-family:sans-serif;
     display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}
.box{text-align:center} h1{color:#5BE9FF;font-size:1.3em} p{color:#888;font-size:.9em}
</style></head>
<body><div class="box">
<h1>Waiting for tunnel…</h1>
<p>Cloudflare is starting up. This page refreshes every 3 seconds.</p>
</div></body></html>"""


def _html_ready(url: str) -> str:
    qr = _qr_data_uri(url)
    return f"""<!doctype html>
<html><head><meta charset="utf-8">
<title>GhostCrab — Scan to Connect</title>
<style>
body{{background:#0F1115;color:#ccc;font-family:sans-serif;
     display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}}
.box{{text-align:center}}
h1{{color:#5BE9FF;font-size:1.5em;font-weight:bold;margin-bottom:4px}}
p.sub{{color:#888;font-size:.9em;margin-bottom:24px}}
img{{border:2px solid #5BE9FF;border-radius:8px;width:220px;height:220px}}
p.url{{color:#7BD8FF;font-family:monospace;font-size:.85em;margin-top:16px;word-break:break-all}}
</style></head>
<body><div class="box">
<h1>GhostCrab</h1>
<p class="sub">Open the GhostCrab app and scan this QR code</p>
<img src="{qr}" alt="QR code">
<p class="url">{url}</p>
</div></body></html>"""


class _Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self) -> None:  # noqa: N802
        with _lock:
            url = _tunnel_url

        if self.path == "/url":
            body = (url or "").encode()
            code = 200 if url else 404
            ct = "text/plain; charset=utf-8"
        else:
            body = (_html_ready(url) if url else _HTML_WAITING).encode()
            code = 200
            ct = "text/html; charset=utf-8"

        self.send_response(code)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    threading.Thread(target=_watch_log, daemon=True).start()
    server = http.server.HTTPServer(("0.0.0.0", QR_PORT), _Handler)
    print(f"[tunnel-qr] Listening on :{QR_PORT} — watching {TUNNEL_LOG}", flush=True)
    server.serve_forever()
```

- [ ] **Step 2: Create docker/tunnel-qr/Dockerfile**

```dockerfile
FROM python:3.12-alpine

RUN pip install --no-cache-dir "qrcode[pil]==8.0"

WORKDIR /app
COPY serve.py .

EXPOSE 19999

CMD ["python", "serve.py"]
```

- [ ] **Step 3: Create docker/docker-compose.example.yml**

```yaml
# GhostCrab — Cloudflare Tunnel sidecar additions
# ──────────────────────────────────────────────────────────────────────────────
# Add the services below to your existing docker-compose.yml.
# Replace <gateway-service> with the name of your OpenClaw gateway service
# and adjust the port (18789) if you changed it.
#
# How it works:
#   cloudflared creates a quick tunnel and logs the URL to /shared/tunnel.log
#   tunnel-qr watches that file, generates a QR code, and serves it at :19999
#
# To connect: open http://<your-server-LAN-ip>:19999 in a browser on your
#             phone, then tap "Open Camera" in GhostCrab.
#
# Named tunnel (permanent URL):
#   Set CLOUDFLARE_TUNNEL_TOKEN in cloudflared's environment to use a named
#   tunnel instead of a quick tunnel. tunnel-qr works identically.
# ──────────────────────────────────────────────────────────────────────────────

services:

  cloudflared:
    image: cloudflare/cloudflared:latest
    restart: unless-stopped
    # Tee stdout+stderr to shared volume so tunnel-qr can read the URL
    entrypoint: ["/bin/sh", "-c"]
    command:
      - >
        cloudflared tunnel --url http://<gateway-service>:18789
        --no-autoupdate 2>&1 | tee /shared/tunnel.log
    volumes:
      - tunnel-data:/shared
    networks:
      - internal   # adjust to match your existing network name

  tunnel-qr:
    image: ghcr.io/openclaw/tunnel-qr:latest
    restart: unless-stopped
    environment:
      TUNNEL_LOG: /shared/tunnel.log
      QR_PORT: "19999"
    ports:
      - "19999:19999"
    volumes:
      - tunnel-data:/shared
    depends_on:
      - cloudflared
    networks:
      - internal

volumes:
  tunnel-data:
```

- [ ] **Step 4: Create docs/TUNNEL_SETUP.md**

```markdown
# Cloudflare Tunnel Setup for GhostCrab

Connect to your OpenClaw Gateway from anywhere — no port forwarding required.

## How it works

1. `cloudflared` creates a secure tunnel from your server to Cloudflare's network
2. Cloudflare assigns a public URL (e.g. `https://abc-xyz.trycloudflare.com`)
3. `tunnel-qr` reads that URL and serves a QR code page at LAN port 19999
4. Open `http://<your-server-ip>:19999` in a browser on your phone → tap **Open Camera** in GhostCrab → done

The QR code contains only the gateway URL. Bearer tokens are added in-app after scanning.

---

## Option A — Docker Compose (recommended)

### Prerequisites
- Docker + Docker Compose installed on your gateway host
- Your gateway already running with a `docker-compose.yml`

### Steps

1. Copy the two service blocks from [`docker/docker-compose.example.yml`](../docker/docker-compose.example.yml) into your existing `docker-compose.yml`.

2. Replace `<gateway-service>` with the name of your gateway service (the service name in your compose file, not a hostname).

3. Apply the change:
   ```bash
   docker compose up -d cloudflared tunnel-qr
   ```

4. Wait ~15 seconds, then open `http://<server-LAN-ip>:19999` in your phone browser. You should see the QR code.

### Named tunnel (permanent URL — optional)

Quick tunnels get a new URL on every restart. For a permanent URL:

1. Create a free Cloudflare account and create a named tunnel in the Cloudflare dashboard
2. Copy the tunnel token
3. Add to the `cloudflared` service in your compose file:
   ```yaml
   environment:
     CLOUDFLARE_TUNNEL_TOKEN: "your-token-here"
   command: tunnel run
   ```
4. `tunnel-qr` works identically — the QR page will show your permanent URL

---

## Option B — Direct Install (Linux / macOS / Windows)

### Step 1: Install cloudflared

**Linux (Debian/Ubuntu):**
```bash
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb -o cloudflared.deb
sudo dpkg -i cloudflared.deb
```

**macOS:**
```bash
brew install cloudflare/cloudflare/cloudflared
```

**Windows:** Download the MSI from https://github.com/cloudflare/cloudflared/releases/latest

### Step 2: Start the tunnel

```bash
# Linux/macOS — tee output to a shared log file
cloudflared tunnel --url http://localhost:18789 --no-autoupdate 2>&1 | tee /tmp/cloudflared.log &
```

```powershell
# Windows PowerShell
Start-Process cloudflared -ArgumentList "tunnel --url http://localhost:18789 --no-autoupdate" -RedirectStandardOutput "$env:TEMP\cloudflared.log" -RedirectStandardError "$env:TEMP\cloudflared.log"
```

### Step 3: Install and run tunnel-qr

**Option 1 — pip:**
```bash
pip install openclaw-tunnel-qr
TUNNEL_LOG=/tmp/cloudflared.log openclaw-tunnel-qr
```

**Option 2 — Docker standalone:**
```bash
docker run -p 19999:19999 \
  -v /tmp/cloudflared.log:/shared/tunnel.log:ro \
  ghcr.io/openclaw/tunnel-qr:latest
```

### Step 4: Scan and connect

Open `http://<your-machine-ip>:19999` in your phone browser. Scan the QR code with GhostCrab.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| QR page shows "Waiting for tunnel…" | cloudflared is still starting (takes ~10s). Refresh the page. |
| QR page is not reachable at :19999 | Make sure your server firewall allows inbound TCP 19999 on LAN. Port 19999 should NOT be open to the internet. |
| GhostCrab shows "doesn't contain a valid gateway URL" | The QR code is not from GhostCrab's tunnel-qr page. Use the correct URL. |
| Tunnel URL changes after restart | Use a named tunnel (see Option A above) for a permanent URL. |
```

- [ ] **Step 5: Commit**

```bash
git add docker/ docs/TUNNEL_SETUP.md
git commit -m "feat: tunnel-qr helper — Docker image + compose example + setup docs (Docker + direct install)"
```

---

## Task 7: Full Verification + Progress Ledger

**Files:**
- Modify: `IMPLEMENTATION_PLAN.md`

- [ ] **Step 1: Run the full test suite**

```bash
./gradlew testDebugUnitTest --no-configuration-cache 2>&1 | grep -E "(tests completed|FAILED|BUILD)"
```

Expected: all tests pass (the 8 new QrScanViewModel tests + all prior tests).

- [ ] **Step 2: Detekt baseline update (if needed)**

```bash
./gradlew detekt --no-configuration-cache 2>&1 | tail -10
```

If detekt reports new findings in files you created, update the baseline:
```bash
./gradlew detektBaseline --no-configuration-cache
git add config/detekt-baseline.xml
```

- [ ] **Step 3: Release build**

```bash
./gradlew assembleRelease --no-configuration-cache 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Update IMPLEMENTATION_PLAN.md progress ledger**

Find the Phase 11 row (add it if it doesn't exist yet, after the Phase 10 row):

```
| 11 | Cloudflare Tunnel + QR Connect + Logo + Splash | 🟢 Done | 2026-04-15 | 2026-04-15 | — | Sonnet 4.6 | QrScanScreen (CameraX + ML Kit), QrScanViewModel (8 tests), tunnel-qr helper (Docker + PyPI), GhostCrab logo in TopAppBar, animated splash (AVD scale+fade), QR hero empty state |
```

- [ ] **Step 5: Final commit**

```bash
git add IMPLEMENTATION_PLAN.md
git commit -m "chore: Phase 11 complete — Cloudflare tunnel QR connect, logo, animated splash"
```

---

## Acceptance Criteria

- [ ] `./gradlew testDebugUnitTest` passes — 8 new QrScanViewModel tests + all prior tests green
- [ ] `./gradlew assembleRelease` BUILD SUCCESSFUL
- [ ] `./gradlew lint detekt` clean (or baseline updated)
- [ ] Scanning a valid URL QR navigates to ManualEntry with the URL pre-filled
- [ ] Scanning a non-URL QR shows snackbar "doesn't contain a valid gateway URL", camera stays open
- [ ] Camera permission permanently denied shows AlertDialog with "Open Settings" button
- [ ] Connection Picker empty state shows QR hero card with "Open Camera" button
- [ ] Connection Picker with profiles shows GhostCrab logo in TopAppBar + QR icon in actions
- [ ] Animated splash: crab scales in + fades in on app launch (visible on device/emulator)
- [ ] `docker/tunnel-qr/serve.py` starts, reads log, serves QR HTML at `:19999/`, URL text at `:19999/url`
- [ ] `docs/TUNNEL_SETUP.md` covers both Docker and direct install paths
