package io.github.bl3xand.apkcloner.sources.net

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.CancellationSignal
import io.github.bl3xand.apkcloner.sources.core.HttpStatusError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.sha256Hex
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** percent is 0..100 (null when finished); sizes are null when unknown. */
typealias ProgressListener = (percent: Double?, received: Long?, total: Long?) -> Unit

object Downloader {
    private const val DEFAULT_RETRIES = 3
    private const val RETRY_DELAY_MS = 5_000L
    private const val PARTIAL_HASH_START = 1024
    private const val PARTIAL_HASH_LOWER_LIMIT = 128
    private const val PARTIAL_HASH_DECREMENT = 256
    private const val MAX_DOWNLOAD_POLLS = 43
    private const val DOWNLOAD_POLL_INTERVAL_MS = 7_000L
    private const val PROGRESS_INTERVAL_MS = 500L
    private const val BUFFER_SIZE = 32 * 1024
    private const val PROGRESS_FALLBACK = 30.0

    /** Transport failures, timeouts and 429/5xx answers are retried; a cancellation never is. */
    fun downloadFileWithRetry(
        url: String,
        fileName: String,
        fileNameHasExt: Boolean,
        onProgress: ProgressListener?,
        destDir: File,
        options: RequestOptions,
        useExisting: Boolean = true,
        headers: Map<String, String>? = null,
        retries: Int = DEFAULT_RETRIES,
        cancellationToken: CancellationToken? = null,
    ): File {
        var remaining = retries
        while (true) {
            try {
                return downloadFile(
                    url, fileName, fileNameHasExt, onProgress, destDir, options, useExisting, headers,
                    cancellationToken,
                )
            } catch (e: Exception) {
                val retryableStatus = e is HttpStatusError && (e.statusCode == 429 || e.statusCode >= 500)
                if (remaining > 0 && (e is IOException || retryableStatus)) {
                    remaining--
                    Thread.sleep(RETRY_DELAY_MS)
                } else {
                    throw e
                }
            }
        }
    }

    /** Waits while another download of the same file is still growing its ".part" file. */
    private fun waitForConcurrentDownload(tempFile: File, finalFile: File): File? {
        var currentSize = tempFile.length()
        var polls = 0
        while (polls < MAX_DOWNLOAD_POLLS) {
            polls++
            Thread.sleep(DOWNLOAD_POLL_INTERVAL_MS)
            if (!tempFile.exists()) return finalFile.takeIf { it.exists() }
            val newSize = tempFile.length()
            if (newSize > currentSize) currentSize = newSize else break
        }
        return finalFile.takeIf { it.exists() }
    }

    /**
     * Downloads [url] into [destDir], resuming a partial file when the server supports ranges.
     * The extension comes from the response unless [fileNameHasExt].
     */
    fun downloadFile(
        url: String,
        fileNameIn: String,
        fileNameHasExt: Boolean,
        onProgress: ProgressListener?,
        destDir: File,
        options: RequestOptions,
        useExistingIn: Boolean = true,
        headers: Map<String, String>? = null,
        cancellationToken: CancellationToken? = null,
    ): File {
        var useExisting = useExistingIn
        // The caller's map is reused across retries, so the Range header goes into a copy.
        val reqHeaders = LinkedHashMap(headers ?: emptyMap())

        // A first request only to learn the extension, range support and size.
        var ext: String
        val rangeSupported: Boolean
        val fullContentLength: Long?
        Http.requestStream("GET", url, reqHeaders, options).use { probe ->
            ext = probe.header("content-disposition")?.split('.')?.last() ?: "apk"
            if (ext.endsWith("\"")) ext = ext.substring(0, ext.length - 1)
            rangeSupported = probe.header("accept-ranges")?.trim()?.lowercase() == "bytes"
            fullContentLength = probe.contentLength
        }
        val urlPath = Url.tryParse(url)?.path ?: url
        if (ApkFilter.isApkOrContainerFile(urlPath, includeArchives = true, includeTarballs = true)) {
            // Keep the real extension so bundles are recognised and unpacked later.
            ext = urlPath.split('.').last().lowercase()
        } else if (ext == "attachment") {
            ext = "apk"
        }
        // A source-provided name is reduced to a plain file name so it cannot leave destDir.
        val fileName = fileNameIn.replace('\\', '/').split('/').last()
        if (fileName.isEmpty() || fileName == "." || fileName == "..") {
            throw SourceError(Tr.get("unexpectedError"))
        }
        val downloadedFile = if (fileNameHasExt) File(destDir, fileName) else File(destDir, "$fileName.$ext")

        if (useExisting && downloadedFile.exists()) {
            val length = downloadedFile.length()
            if (fullContentLength == null || !rangeSupported) return downloadedFile
            if (length == fullContentLength) return downloadedFile
            if (length > fullContentLength) useExisting = false
        }

        val tempFile = File(downloadedFile.path + ".part")
        if (tempFile.exists() && useExisting) {
            waitForConcurrentDownload(tempFile, downloadedFile)?.let { return it }
        }

        var rangeStart = if (useExisting && tempFile.exists()) tempFile.length() else 0L
        var sentRangeRequest = false
        if (rangeSupported && fullContentLength != null && rangeStart > 0) {
            reqHeaders["range"] = "bytes=$rangeStart-${fullContentLength - 1}"
            sentRangeRequest = true
        } else if (tempFile.exists()) {
            deleteFile(tempFile)
        }

        Http.requestStream("GET", url, reqHeaders, options).use { response ->
            // The server ignored the range and sent the whole file: appending would corrupt it.
            var append = sentRangeRequest
            if (sentRangeRequest && response.statusCode == 200) {
                append = false
                rangeStart = 0
                if (tempFile.exists()) deleteFile(tempFile)
            }
            if (response.statusCode !in 200..299) {
                if (tempFile.exists()) deleteFile(tempFile)
                throw Http.statusError(response, url)
            }
            var received = if (rangeStart > 0 && fullContentLength != null) rangeStart else 0L
            var lastProgress = 0L
            destDir.mkdirs()
            try {
                FileOutputStream(tempFile, append).use { output ->
                    response.body.use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            cancellationToken?.throwIfCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (onProgress != null && now - lastProgress >= PROGRESS_INTERVAL_MS) {
                                val percent = if (fullContentLength != null) {
                                    (received.toDouble() / fullContentLength * 100).coerceIn(0.0, 100.0)
                                } else PROGRESS_FALLBACK
                                onProgress(percent, received, fullContentLength)
                                lastProgress = now
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // The ".part" file stays so the download can resume later.
                if (e is CancellationSignal || cancellationToken?.isCancelled == true) throw CancellationSignal()
                throw e
            }
            onProgress?.invoke(null, null, null)
            // A stream can end early without an error; keep the part for a ranged retry.
            if (fullContentLength != null && received < fullContentLength && cancellationToken?.isCancelled != true) {
                throw IOException("Incomplete download: received $received of $fullContentLength bytes")
            }
            if (tempFile.exists()) {
                if (downloadedFile.exists() && !tempFile.renameTo(downloadedFile)) {
                    downloadedFile.delete()
                }
                if (tempFile.exists() && !tempFile.renameTo(downloadedFile) && !downloadedFile.exists()) {
                    throw IOException("Could not move ${tempFile.name} into place")
                }
            }
            return downloadedFile
        }
    }

    fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) {
            throw SourceError(Tr.get("fileDeletionError", file.path))
        }
    }

    /**
     * A pseudo-version for a file that has no version: the hash of its first bytes. Two requests
     * must agree; a smaller prefix is tried when they do not.
     */
    fun checkPartialDownloadHashDynamic(
        url: String,
        options: RequestOptions,
        headers: Map<String, String>? = null,
    ): String {
        var size = PARTIAL_HASH_START
        while (size >= PARTIAL_HASH_LOWER_LIMIT) {
            val first = checkPartialDownloadHash(url, size, options, headers)
            val second = checkPartialDownloadHash(url, size, options, headers)
            if (first == second) return first
            size -= PARTIAL_HASH_DECREMENT
        }
        throw NoVersionError()
    }

    fun checkPartialDownloadHash(
        url: String,
        bytesToGrab: Int,
        options: RequestOptions,
        headers: Map<String, String>? = null,
    ): String {
        val reqHeaders = LinkedHashMap(headers ?: emptyMap())
        reqHeaders["Range"] = "bytes=0-$bytesToGrab"
        Http.requestStream("GET", url, reqHeaders, options).use { response ->
            if (response.statusCode !in 200..299) {
                throw SourceError(response.reasonPhrase.ifEmpty { Tr.get("unexpectedError") }).also { it.url = url }
            }
            val wanted = bytesToGrab + 1
            val data = ByteArray(wanted)
            var filled = 0
            response.body.use { input ->
                while (filled < wanted) {
                    val read = input.read(data, filled, wanted - filled)
                    if (read < 0) break
                    filled += read
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").apply { update(data, 0, filled) }.digest()
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.substring(0, 8)
        }
    }

    /** A pseudo-version from the ETag header, or null when the server sends none. */
    fun checkETagHeader(url: String, options: RequestOptions, headers: Map<String, String>? = null): String? =
        Http.requestStream("GET", url, headers ?: emptyMap(), options).use { response ->
            if (response.statusCode !in 200..299) return null
            val etag = response.header("etag")?.replace("\"", "")
            etag?.let { sha256Hex(it).substring(0, 12) }
        }

    /** The announced size of a download, or null when unknown; never throws. */
    fun getDownloadSize(url: String, headers: Map<String, String>?, options: RequestOptions): Long? = try {
        Http.requestStream("GET", url, headers ?: emptyMap(), options).use { response ->
            if (response.statusCode !in 200..299) null else response.contentLength
        }
    } catch (e: Exception) {
        null
    }
}
