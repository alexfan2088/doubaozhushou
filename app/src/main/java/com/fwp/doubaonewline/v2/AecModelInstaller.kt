package com.fwp.doubaonewline.v2

import android.content.Context
import java.io.File

/**
 * The speech SDK requires an ordinary filesystem path; it cannot read the AEC
 * model directly from the APK assets directory.
 */
object AecModelInstaller {
    private const val ASSET_NAME = "aec.model"

    fun install(context: Context): Result<File> = runCatching {
        val directory = File(context.filesDir, "speechengine").apply { mkdirs() }
        val destination = File(directory, ASSET_NAME)

        if (!destination.isFile || destination.length() == 0L) {
            val temporary = File(directory, "$ASSET_NAME.tmp")
            context.assets.open(ASSET_NAME).use { input ->
                temporary.outputStream().use(input::copyTo)
            }
            check(temporary.length() > 0L) { "AEC model copy is incomplete" }
            if (destination.exists()) {
                check(destination.delete()) { "Unable to replace the old AEC model" }
            }
            check(temporary.renameTo(destination)) { "Unable to install AEC model" }
        }
        destination
    }
}
