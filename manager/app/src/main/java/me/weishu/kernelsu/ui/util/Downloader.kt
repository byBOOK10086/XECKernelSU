package me.weishu.kernelsu.ui.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.registry.ExpectedFile
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
import okhttp3.Request

/**
 * SHA-256 of an already-fetched URI, lowercase hex, or null when the stream
 * cannot be opened (the caller treats null as "cannot decide", never as "ok").
 */
fun sha256Of(context: Context, uri: Uri): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    } ?: return@runCatching null
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

/** Same for a file we wrote ourselves (downloaded into the app's private dir). */
fun sha256Of(file: File): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

/** Hard cap on anything fetched through [downloadToFile] (500 MB). */
const val MAX_DOWNLOAD_BYTES: Long = 500L * 1024 * 1024

private const val DOWNLOAD_USER_AGENT = "Mozilla/5.0 (Linux; Android) XECKernelPro/1.0"

/**
 * Plain HTTP(S) download straight to [target], with progress and one retry.
 *
 * Deliberately not built on OkHttp: these are one-shot transfers of a file the
 * caller then verifies by hash, so a short connect timeout, a generous read
 * timeout, a size cap and exactly one retry cover the failure modes that matter
 * (a redirect chain that hands us an HTML page, a flaky mirror, a truncated
 * body). The retry deletes the partial file first so a resumed half-file can
 * never be mistaken for a complete one.
 */
fun downloadToFile(
    url: String,
    target: File,
    onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
) {
    try {
        downloadToFileOnce(url, target, onProgress)
    } catch (e: IOException) {
        target.delete()
        downloadToFileOnce(url, target, onProgress)
    }
}

private fun downloadToFileOnce(
    url: String,
    target: File,
    onProgress: (downloaded: Long, total: Long) -> Unit,
) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 120_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", DOWNLOAD_USER_AGENT)
    runCatching {
        val host = URL(url).host
        conn.setRequestProperty("Referer", "https://$host/")
    }
    conn.connect()
    val code = conn.responseCode
    if (code !in 200..299) throw IOException("download HTTP $code")
    val total = conn.contentLengthLong
    if (total > MAX_DOWNLOAD_BYTES) throw IOException("file too large: $total")
    conn.inputStream.use { input ->
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var read = 0L
            var reported = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                read += n
                if (read > MAX_DOWNLOAD_BYTES) throw IOException("file too large")
                out.write(buf, 0, n)
                if (read - reported >= 2L * 1024 * 1024) {
                    reported = read
                    onProgress(read, if (total > 0) total else 0)
                }
            }
            onProgress(read, if (total > 0) total else read)
        }
    }
}

/**
 * @author weishu
 * @date 2023/6/22.
 */
suspend fun download(
    url: String,
    fileName: String,
    onDownloaded: (Uri) -> Unit = {},
    onDownloading: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    expected: ExpectedFile? = null,
    onFailed: (DownloadManager.DownloadState) -> Unit = {},
) {
    onDownloading()

    val downloadId = DownloadManager.enqueue(
        context = ksuApp,
        url = url,
        fileName = fileName,
        onCompleted = onDownloaded,
        expected = expected,
    )

    val finalState = DownloadManager.downloads
        .onEach { map -> map[downloadId]?.let { onProgress(it.progress) } }
        .first { map ->
            val status = map[downloadId]?.status
            status == DownloadManager.Status.COMPLETED ||
                status == DownloadManager.Status.FAILED
        }[downloadId]
    if (finalState?.status == DownloadManager.Status.FAILED) {
        withContext(Dispatchers.Main) { onFailed(finalState) }
    }
}

internal suspend fun isDownloadAvailable(uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        ksuApp.contentResolver.openFileDescriptor(uri, "r").use { it != null }
    }.getOrDefault(false)
}

fun checkNewVersion(): LatestVersionInfo {
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    val url = "https://api.github.com/repos/tiann/KernelSU/releases/latest"
    // default null value if failed
    val defaultValue = LatestVersionInfo()
    runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { response ->
                if (!response.isSuccessful) {
                    return defaultValue
                }
                val body = response.body.string()
                val json = org.json.JSONObject(body)
                val changelog = json.optString("body")

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (!name.endsWith(".apk")) {
                        continue
                    }

                    val regex = Regex("v(.+?)_(\\d+)-")
                    val matchResult = regex.find(name) ?: continue
                    matchResult.groupValues[1]
                    val versionCode = matchResult.groupValues[2].toInt()
                    val downloadUrl = asset.getString("browser_download_url")

                    return LatestVersionInfo(
                        versionCode,
                        downloadUrl,
                        changelog
                    )
                }

            }
    }
    return defaultValue
}
