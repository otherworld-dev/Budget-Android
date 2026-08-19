package dev.otherworld.budget.ui.capture

import java.io.File

/** Keeps every test off a physical camera. */
interface ReceiptSource {
    suspend fun capture(destination: File): Result<File>
}
