package me.weishu.kernelsu.ui.screen.remote

import java.io.File
import java.io.IOException
import java.net.URL
import me.weishu.kernelsu.ui.util.MAX_DOWNLOAD_BYTES
import me.weishu.kernelsu.ui.util.downloadToFile

/**
 * Netdisk share links open a web page (login / redirect), so fetching one yields
 * an HTML document instead of the file. Recognising the family up front lets the
 * UI say "this is a share link" instead of surfacing a mysterious transfer error.
 */
private val PAN_SHARE_HOSTS = listOf("123pan", "lanzou", "pan.baidu.com", "cloud.189.cn", "caiyun.139.com")

fun isPanSharePage(url: String): Boolean {
    val host = runCatching { URL(url).host }.getOrNull() ?: return false
    return PAN_SHARE_HOSTS.any { host.contains(it) } ||
        Regex("""123\d{3}\.(com|cn)""").containsMatchIn(host)
}

/**
 * Remote module transfer.
 *
 * The HTTP mechanics live in `ui/util/downloadToFile` - shared with the theme
 * store, with the same timeouts, size cap, progress granularity and single
 * retry. What is specific to modules is the payload check: a module package must
 * be a zip, so an HTML or JSON body means the link served a page and the
 * installer must never see it.
 */
object RemoteDownloader {
    /** Hard cap; the upload side checks the same value so a doomed transfer is refused early. */
    const val MAX_FILE_BYTES: Long = MAX_DOWNLOAD_BYTES

    fun downloadTo(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        downloadToFile(url, target, onProgress)
        requireZip(target)
    }

    private fun requireZip(file: File) {
        val head = ByteArray(4)
        val n = file.inputStream().use { it.read(head) }
        val isZip = n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (isZip) return
        if (n >= 1 && (head[0] == '<'.code.toByte() || head[0] == 0x7B.toByte())) {
            throw IOException("the link returned an HTML page or JSON, not the module zip")
        }
        throw IOException("the payload is not a zip; the link probably does not point at a file")
    }
}
