package dev.otherworld.budget.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otherworld.budget.R
import dev.otherworld.budget.data.local.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The app's home screen: photograph a receipt (or pick an existing image) and it is queued
 * for the user's own Nextcloud to extract. Capture stays enabled even when
 * [CaptureUiState.ocrAvailable] is false -- the photo still reaches Review, where the amount
 * can be typed by hand, so disabling the shutter would waste it.
 *
 * [onOpenQuickAdd] and [onOpenSettings] are this screen's own way in and out of Quick Add and
 * Settings; neither is a tab (see [dev.otherworld.budget.ui.nav.TOP_LEVEL_ROUTES]), so both stay
 * on this top bar rather than moving to the bottom bar Task 7 added. Recent's old top-bar entry
 * point is gone -- reaching it (as the Activity tab, once Task 9 lands) is the bottom bar's job
 * now, not this screen's.
 *
 * [onOpenReview] is Capture's own reliable route into Review, via [AwaitingReviewBanner] below:
 * capture itself never navigates there synchronously (extraction is asynchronous, so there is no
 * receipt id yet at the moment of a shutter press or gallery pick), and the "ready for review"
 * notification is not a dependable substitute -- POST_NOTIFICATIONS is opportunistic and often
 * declined, yet AWAITING_REVIEW is reached on OCR success *and* on failure, quota exhaustion, and
 * not-configured (see [dev.otherworld.budget.data.repo.ReceiptRepository.extractNext]), exactly
 * the cases where the user most needs a way in. The banner reflects live queue state instead, so
 * it stays correct regardless of notification permission or delivery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onOpenQuickAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReview: (Long) -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Constructed directly rather than via Hilt: PhotoStore only wraps a Context and a
    // filesDir path, so there is nothing DI buys here that a plain instance doesn't.
    val photoStore = remember(context) { PhotoStore(context.applicationContext) }

    val activity = remember(context) { context.findActivity() }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // Set when a denial is permanent ("don't ask again"): the system dialog will no longer appear,
    // so re-requesting is a dead end and the rationale offers an Open settings route instead.
    var cameraPermanentlyDenied by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            cameraPermanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Capture is the start destination and is never popped, so `init`-only loading would leave a
    // first launch made offline (or before the user added an account) stuck on that answer for
    // the whole process. Re-asking on resume is also what makes returning from Settings, or from
    // the browser after granting access, put the screen right without a restart -- including
    // re-checking the camera grant, so enabling it in system Settings takes effect on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        hasCameraPermission = granted
        if (granted) cameraPermanentlyDenied = false
        viewModel.refresh()
    }

    // Requested here, the first time a capture actually happens, not at screen launch -- at
    // launch it has no context and is more likely to be denied.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    // Set when CameraX fails to bind; without surfacing it the shutter just stays greyed forever
    // with no explanation, when the gallery path is still perfectly usable.
    var cameraBindFailed by remember { mutableStateOf(false) }

    // Drives the shutter flash: a quick black blink over the whole screen when a photo is taken.
    val flashAlpha = remember { Animatable(0f) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) pick@{ uri ->
        if (uri == null) return@pick
        requestNotificationPermissionIfNeeded(context, notificationPermissionLauncher)
        scope.launch(Dispatchers.IO) {
            val destination = photoStore.newPhotoFile()
            // The picked content:// URI's read grant is transient, so it is copied into app
            // storage right here, before the queue is told about it at all -- a background
            // worker may not reach this photo for hours, long after the grant is gone.
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use(input::copyTo)
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            if (copied) {
                // A picked screenshot or download is frequently larger than a phone-camera
                // photo, so this needs the same shrink the camera path gets -- see
                // PhotoStore.downscale's KDoc. downscale writes via a temp file and renames, so
                // a failure here leaves the full-size copy intact rather than a truncated one:
                // it must not stop the photo being queued.
                runCatching { photoStore.downscale(destination) }
                viewModel.onPhotoCaptured(destination)
            } else {
                // The copy failed (an unreadable URI, no space). Without this the pick simply
                // vanished: no queue row, no message, nothing to distinguish it from success.
                destination.delete()
                viewModel.onCaptureFailed()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // An icon rather than a second text button: "add" is the one action here with
                    // an unambiguous standard glyph, so it doesn't need Settings' text label to
                    // stay legible.
                    IconButton(onClick = onOpenQuickAdd) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.capture_add_transaction))
                    }
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_title)) }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        val previewView = PreviewView(viewContext)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(viewContext)
                        cameraProviderFuture.addListener(
                            {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val capture = ImageCapture.Builder().build()
                                runCatching {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        capture,
                                    )
                                }.onSuccess {
                                    imageCapture = capture
                                    cameraBindFailed = false
                                }.onFailure { cameraBindFailed = true }
                            },
                            ContextCompat.getMainExecutor(viewContext),
                        )
                        previewView
                    },
                )
            } else {
                CameraPermissionRationale(
                    permanentlyDenied = cameraPermanentlyDenied,
                    onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        activity?.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val oldestAwaitingReviewId = uiState.oldestAwaitingReviewId
                if (uiState.awaitingReviewCount > 0 && oldestAwaitingReviewId != null) {
                    AwaitingReviewBanner(
                        count = uiState.awaitingReviewCount,
                        onClick = { onOpenReview(oldestAwaitingReviewId) },
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (uiState.pendingCount > 0) {
                    PendingQueueBanner(uiState.pendingCount)
                    Spacer(Modifier.height(12.dp))
                }

                CaptureMessageArea(uiState.message, onRetry = viewModel::refresh)
                Spacer(Modifier.height(12.dp))

                if (hasCameraPermission && cameraBindFailed) {
                    Text(
                        stringResource(R.string.capture_camera_start_failed),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // CameraX binds asynchronously, so for the first moments after this screen
                // appears there is nothing to take a photo with. Greying the button out says so;
                // the previous version looked ready and silently did nothing when pressed.
                val cameraReady = imageCapture != null
                FloatingActionButton(
                    onClick = {
                        val capture = imageCapture ?: return@FloatingActionButton
                        // Immediate visual feedback on the press itself: the photo is extracted
                        // later on a background worker, off-screen, so without this a shutter press
                        // had no on-screen response at all. Runs concurrently with the capture below.
                        scope.launch {
                            flashAlpha.snapTo(1f)
                            flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 220))
                        }
                        requestNotificationPermissionIfNeeded(context, notificationPermissionLauncher)
                        val destination = photoStore.newPhotoFile()
                        scope.launch {
                            CameraXReceiptSource(capture, context, photoStore).capture(destination)
                                .onSuccess { file -> viewModel.onPhotoCaptured(file) }
                                // ImageCaptureException was discarded here: a failed shutter
                                // press produced no photo, no queue row and no feedback at all.
                                .onFailure { viewModel.onCaptureFailed() }
                        }
                    },
                    containerColor = if (cameraReady) {
                        FloatingActionButtonDefaults.containerColor
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (cameraReady) {
                        contentColorFor(FloatingActionButtonDefaults.containerColor)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                    modifier = if (cameraReady) Modifier else Modifier.semantics { disabled() },
                ) {
                    Text(stringResource(R.string.capture_shutter))
                }

                TextButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) {
                    Text(stringResource(R.string.capture_choose_gallery))
                }
            }

            // Shutter flash: a quick black blink over the whole screen the moment a photo is taken.
            // Drawn last so it covers the preview and the controls; only present while animating.
            if (flashAlpha.value > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = flashAlpha.value)),
                )
            }
        }
    }
}

private fun requestNotificationPermissionIfNeeded(
    context: Context,
    launcher: ActivityResultLauncher<String>,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun CameraPermissionRationale(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (permanentlyDenied) {
                stringResource(R.string.capture_camera_denied_permanent)
            } else {
                stringResource(R.string.capture_camera_rationale)
            },
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        // Once a denial is permanent, re-requesting shows nothing -- send the user to Settings,
        // the only place the grant can now be changed, rather than to a button that does nothing.
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.capture_open_settings)) }
        } else {
            Button(onClick = onRequestPermission) { Text(stringResource(R.string.capture_grant_camera)) }
        }
    }
}

/** Unwraps the Activity from a Compose [Context] so permission-rationale and settings intents work. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * "transaction", not "receipt". This banner counts every row that is not AWAITING_REVIEW (see
 * [CaptureViewModel]), which since Quick Add includes photo-less manual entries -- a FAILED
 * Quick Add row appears here and in Settings' queue counts (which use the same partition and
 * the same "queued" word), and nowhere else. Calling it a receipt told the one user who needs
 * this number that the thing they are looking for is not in it.
 */
@Composable
private fun PendingQueueBanner(pendingCount: Int) {
    val label = pluralStringResource(R.plurals.pending_queued, pendingCount, pendingCount)
    Surface(tonalElevation = 2.dp) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/**
 * Tappable and visually distinct from [PendingQueueBanner] (still-processing, inert) -- this is
 * an action, not just status: something is waiting on the user, not on the server.
 *
 * "transaction" for the same reason as [PendingQueueBanner]: a Quick Add entry whose post ended
 * ambiguously is parked in AWAITING_REVIEW and is counted here, with no receipt behind it.
 */
@Composable
private fun AwaitingReviewBanner(count: Int, onClick: () -> Unit) {
    val label = pluralStringResource(R.plurals.awaiting_review, count, count)
    Surface(onClick = onClick, tonalElevation = 4.dp) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/**
 * [CaptureMessage.OcrUnavailable] renders the fixed, verbatim copy required by Google Play's
 * anti-steering rules for this app: no button, no link, no further affordance pointing the
 * user anywhere -- not even to Nextcloud's own settings beyond naming where to look.
 *
 * [CaptureMessage.Offline] is the one case with an action, because it is the one case the user
 * can do something about from here; note it never claims accounts are missing, which is what
 * showing the [CaptureMessage.NoAccounts] copy for any failed fetch used to do.
 */
@Composable
private fun CaptureMessageArea(message: CaptureMessage?, onRetry: () -> Unit) {
    when (message) {
        null -> Unit
        CaptureMessage.OcrUnavailable -> Text(
            stringResource(R.string.capture_ocr_unavailable),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        // Word-for-word QuickAddUiState.accountsMessage's no-accounts copy: with no account,
        // neither a captured receipt nor a manual entry can be saved, and the two screens
        // explaining the same blockage in two different vocabularies helps nobody.
        CaptureMessage.NoAccounts -> Text(
            stringResource(R.string.capture_no_accounts),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        CaptureMessage.Offline -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.capture_offline),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_try_again)) }
        }
        CaptureMessage.CaptureFailed -> Text(
            stringResource(R.string.capture_failed),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        // Not emitted by CaptureViewModel today -- refresh() never produces it, and the pending
        // count has its own banner. Handled for exhaustiveness against the full sealed interface.
        is CaptureMessage.Queued -> Unit
    }
}
