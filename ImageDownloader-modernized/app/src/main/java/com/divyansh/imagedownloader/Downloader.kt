package com.divyansh.imagedownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import java.util.UUID

/**
 * Wraps [DownloadManager] to save a single image to the app's external
 * downloads directory (no storage permission needed on API 29+, see
 * AndroidManifest.xml).
 *
 * Use [Downloader.enqueue] rather than the constructor directly -- it
 * validates the URL first and reports success/failure instead of
 * crashing on a null or malformed URI.
 */
class Downloader private constructor(uri: String, context: Context) {

    init {
        val fileName = buildFileName(uri)
        val mimeType = guessMimeType(fileName)

        val request = DownloadManager.Request(Uri.parse(uri))
            .setTitle("Image Download")
            .setDescription("Downloading an image")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .apply { mimeType?.let { setMimeType(it) } }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
    }

    companion object {
        private val KNOWN_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif"
        )

        /**
         * Validates [uri] and enqueues the download.
         *
         * @return true if the download was queued, false if [uri] was null,
         * blank, used an unsupported scheme, or DownloadManager rejected it.
         */
        fun enqueue(uri: String?, context: Context): Boolean {
            if (uri.isNullOrBlank()) return false

            val parsed = Uri.parse(uri)
            if (parsed.scheme != "http" && parsed.scheme != "https") return false

            return try {
                Downloader(uri, context)
                true
            } catch (e: IllegalArgumentException) {
                // Malformed URI that Uri.parse accepted but DownloadManager rejected.
                false
            } catch (e: SecurityException) {
                // Destination not writable / DownloadManager unavailable.
                false
            }
        }

        /** file-<uuid>.<ext> -- UUID guarantees uniqueness without the
         * spaces/colons a raw Date().toString() would put in a filename. */
        private fun buildFileName(uri: String): String {
            val extension = Uri.parse(uri).lastPathSegment
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
                ?.takeIf { it in KNOWN_IMAGE_EXTENSIONS }
                ?: "jpg" // sensible default so the file is still recognized as an image

            return "image-${UUID.randomUUID()}.$extension"
        }

        private fun guessMimeType(fileName: String): String? {
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
    }
}
