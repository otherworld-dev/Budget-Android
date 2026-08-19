package dev.otherworld.budget.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.otherworld.budget.RobolectricTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// See PendingReceiptDaoTest for why sdk is pinned per-class, and RobolectricTestApplication's
// KDoc for why application is overridden, rather than either being set globally.
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class PhotoStoreTest {

    private val store = PhotoStore(ApplicationProvider.getApplicationContext())

    @Test fun `creates files inside app-private storage`() {
        val file = store.newPhotoFile()
        assertTrue(file.absolutePath.contains("receipts"))
    }

    @Test fun `newPhotoFile never collides`() {
        assertTrue(store.newPhotoFile().name != store.newPhotoFile().name)
    }

    @Test fun `delete removes the file and tolerates a missing one`() {
        val file = store.newPhotoFile().apply { writeBytes(byteArrayOf(1)) }
        store.delete(file.absolutePath)
        assertFalse(file.exists())
        store.delete(file.absolutePath)   // must not throw
    }

    /**
     * Shared by every image entry point (camera, gallery pick, share target) -- see
     * [PhotoStore.downscale]'s KDoc for why it lives here rather than on any one of them.
     */
    @Test fun `downscale reduces an oversized image to the target long edge`() {
        val file = store.newPhotoFile().apply { writeJpeg(width = 4032, height = 3024) }

        store.downscale(file)

        val (width, height) = file.decodedSize()
        assertEquals(2048, maxOf(width, height))
    }

    @Test fun `downscale does not upscale an already-small image`() {
        val file = store.newPhotoFile().apply { writeJpeg(width = 800, height = 600) }

        store.downscale(file)

        val (width, height) = file.decodedSize()
        assertEquals(800, width)
        assertEquals(600, height)
    }

    @Test fun `downscale preserves aspect ratio`() {
        // A non-4:3 ratio, so a bug that scaled one dimension differently from the other
        // wouldn't pass by accident: 4000x2000 is 2:1, so a 2048 long edge should land
        // exactly on 2048x1024, not merely "some size no bigger than 2048".
        val file = store.newPhotoFile().apply { writeJpeg(width = 4000, height = 2000) }

        store.downscale(file)

        val (width, height) = file.decodedSize()
        assertEquals(2048, width)
        assertEquals(1024, height)
    }

    @Test fun `downscale writes through a temp file and leaves none behind`() {
        // downscale() no longer opens the source for writing before it has anything to write:
        // a throw mid-compress used to leave a truncated JPEG that was then uploaded, while
        // every caller's "a downscale failure is harmless" comment assumed the opposite. The
        // temp file is the mechanism, so this asserts it is both used and cleaned up.
        val file = store.newPhotoFile().apply { writeJpeg(width = 4032, height = 3024) }

        store.downscale(file)

        assertTrue(file.exists())
        assertFalse(File("${file.absolutePath}.tmp").exists())
        // Still a decodable JPEG, i.e. the rename produced a complete file.
        assertEquals(2048, maxOf(file.decodedSize().first, file.decodedSize().second))
    }

    private fun File.writeJpeg(width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out) }
        bitmap.recycle()
    }

    private fun File.decodedSize(): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(absolutePath, options)
        return options.outWidth to options.outHeight
    }
}
