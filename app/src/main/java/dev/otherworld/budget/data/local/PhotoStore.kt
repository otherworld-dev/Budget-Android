package dev.otherworld.budget.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Receipt photos live in app-private storage so they are not indexed by the
 * gallery and disappear on uninstall. Nothing is written to shared media.
 */
class PhotoStore @Inject constructor(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "receipts").apply { mkdirs() }

    fun newPhotoFile(): File = File(dir, "${UUID.randomUUID()}.jpg")

    fun delete(path: String) { runCatching { File(path).delete() } }

    /**
     * Deletes only *orphaned* photo files: files no queue row references. Referenced photos are
     * never deleted here, whatever their age -- the rows they belong to are the user's
     * financial records, and the queue's prune pass surfaces old rows for manual resolution
     * rather than destroying them (spec §7). The age bound still applies to the orphans: a
     * freshly minted file legitimately has no row for the moment between [newPhotoFile] and
     * the enqueue that records it (longer on the share/gallery paths, which copy bytes first),
     * so only files that are both unreferenced and old are certainly abandoned.
     */
    fun pruneOrphans(
        referencedPaths: Set<String>,
        maxAgeMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): Int = dir.listFiles().orEmpty().count { file ->
        file.absolutePath !in referencedPaths &&
            file.lastModified() < now - maxAgeMillis &&
            file.delete()
    }

    /**
     * Rewrites [file] in place: decoded at a sample size close to the target so a large
     * original is never fully decoded at full resolution, rotated upright per its EXIF
     * orientation tag (BitmapFactory ignores it and re-encoding would otherwise discard it,
     * leaving a sideways photo with no tag left to correct it), scaled so its longer edge is
     * [longEdgePx] -- never upscaled -- then re-encoded as JPEG at [quality].
     *
     * Shared by every entry point that can hand this app an image -- the camera
     * ([longEdgePx]/[quality] match what a phone-camera JPEG needs), a gallery pick, and a
     * share target -- because a screenshot or download is frequently *larger* than a
     * phone-camera photo, and the point ("a big original is a slow upload and the server
     * gains nothing from it") applies just as much there. Callers are expected to invoke this
     * off the main thread; it is a plain blocking call, not a suspend function, matching the
     * rest of this class.
     *
     * "Rewrites in place" is achieved by compressing into a sibling temp file and renaming over
     * [file], never by opening [file] for writing first. Every caller treats a downscale failure
     * as non-fatal and queues the photo anyway, which is only sound if a failure leaves the
     * original intact: truncating the source and then throwing would hand the upload a corrupt
     * JPEG, and the receipt the user photographed would be gone.
     */
    fun downscale(file: File, longEdgePx: Int = DEFAULT_LONG_EDGE_PX, quality: Int = DEFAULT_JPEG_QUALITY) {
        val orientation = ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val rotationDegrees = rotationDegreesFor(orientation)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sourceLongEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (sourceLongEdge <= 0) return

        val sampleSize = Integer.highestOneBit((sourceLongEdge / longEdgePx).coerceAtLeast(1))
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return

        val rotated = if (rotationDegrees == 0f) {
            decoded
        } else {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }

        // Computed explicitly (rather than folding scale into the rotation matrix above) so the
        // long edge lands on exactly longEdgePx, not whatever createBitmap's own rounding of a
        // transformed bounding box happens to produce.
        val rotatedLongEdge = maxOf(rotated.width, rotated.height)
        val scale = (longEdgePx.toFloat() / rotatedLongEdge).coerceAtMost(1f)
        val targetWidth = (rotated.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (rotated.height * scale).roundToInt().coerceAtLeast(1)

        val scaled = if (scale == 1f) {
            rotated
        } else {
            Bitmap.createScaledBitmap(rotated, targetWidth, targetHeight, true)
                .also { if (it !== rotated) rotated.recycle() }
        }

        val temp = File("${file.absolutePath}.tmp")
        val compressed = try {
            temp.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, quality, out) }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        } finally {
            scaled.recycle()
        }

        // A failed compress or a failed move both leave `file` exactly as it was -- larger than
        // we wanted, but a complete, uploadable image. Files.move rather than File.renameTo:
        // renameTo does not replace an existing destination on every platform (notably not on
        // the JVM these tests run on), and silently returning false there would leave every
        // photo un-downscaled. Available since API 26, which is this app's minSdk.
        if (compressed) {
            runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
        temp.delete()   // no-op once the move has consumed it
    }

    private fun rotationDegreesFor(exifOrientation: Int): Float = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    companion object {
        const val DEFAULT_LONG_EDGE_PX = 2048
        const val DEFAULT_JPEG_QUALITY = 85
    }
}
