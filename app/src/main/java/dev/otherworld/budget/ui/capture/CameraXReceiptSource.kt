package dev.otherworld.budget.ui.capture

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import dev.otherworld.budget.data.local.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Real [ReceiptSource], bound in [CaptureScreen] to the [ImageCapture] use case CameraX has
 * attached to the preview. Every automated test uses [ReceiptSource] instead -- this class
 * touches a physical camera and is exercised manually.
 */
class CameraXReceiptSource(
    private val imageCapture: ImageCapture,
    private val context: Context,
    private val photoStore: PhotoStore,
) : ReceiptSource {

    override suspend fun capture(destination: File): Result<File> {
        val saved = suspendCancellableCoroutine<Result<Unit>> { continuation ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(destination).build()
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        continuation.resume(Result.success(Unit))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resume(Result.failure(exception))
                    }
                },
            )
        }
        if (saved.isFailure) return Result.failure(saved.exceptionOrNull()!!)

        // A 12MP original is a slow upload on a phone connection and the server gains
        // nothing from it, so shrink before this ever reaches the queue. Off the main thread:
        // onImageSaved above runs on it, and decoding/re-encoding a full-resolution photo
        // there would jank or ANR the capture UI. The downscale itself lives on PhotoStore,
        // shared with the gallery-pick and share-target paths -- see its KDoc.
        return withContext(Dispatchers.Default) {
            runCatching {
                photoStore.downscale(destination)
                destination
            }
        }
    }
}
