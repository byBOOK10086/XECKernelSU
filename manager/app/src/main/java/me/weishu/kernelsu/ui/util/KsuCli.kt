package me.weishu.kernelsu.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.deviceKmi
import me.weishu.kernelsu.core.tasks.BootKernelVersion
import me.weishu.kernelsu.core.tasks.ExtractImage
import me.weishu.kernelsu.core.tasks.ProbeResult
import me.weishu.kernelsu.core.utils.DataSourceChannel
import me.weishu.kernelsu.ksuApp
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"

internal fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libxeccli.so"
}

data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

/**
 * 设备上可能存在的 su 客户端（绝对路径）。
 *
 * 不同 root 提供方放的位置不一样，而且**不一定**在 PATH 里：Magisk 在
 * /debug_ramdisk、KernelSU 在 /data/adb/ksu/bin、APatch 在 /data/adb/apd/bin。
 * 只按 `su` 这个名字找，遇到"授权了但 su 不在 PATH"的机器就会永远探不到 root，
 * 于是所有需要 root 的操作都以 EACCES 收场（用户看到的就是
 * /dev/block/by-name/xxx Permission denied）。
 */
private val SU_CLIENT_PATHS = listOf(
    "su",
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/debug_ramdisk/su",
    "/data/adb/ksu/bin/su",
    "/data/adb/magisk/su",
    "/data/adb/apd/bin/su",
)

class NoRootException(message: String = "Root permission is unavailable") : IllegalStateException(message)

private class RootShellSlot {
    @Volatile var shell: Shell? = null
    @Volatile var retryAtElapsedMs = 0L
    @Volatile var probing = false
    var retryCount = 0
    var refreshScheduled = false
}

object KsuCli {
    private val shellLock = Any()
    private val normalSlot = RootShellSlot()
    private val globalMountSlot = RootShellSlot()
    private val _rootReady = MutableStateFlow(false)
    val rootReady: StateFlow<Boolean> = _rootReady.asStateFlow()
    private val _probeSettled = MutableStateFlow(false)
    val probeSettled: StateFlow<Boolean> = _probeSettled.asStateFlow()
    private var plainShellCache: Shell? = null
    private val plainShellLock = Any()

    fun plainShell(): Shell = synchronized(plainShellLock) {
        plainShellCache?.takeIf { it.isAlive }
            ?: Shell.Builder.create().build("sh").also { plainShellCache = it }
    }

    fun retryRootNow() {
        synchronized(shellLock) {
            normalSlot.retryAtElapsedMs = 0
            globalMountSlot.retryAtElapsedMs = 0
            normalSlot.retryCount = 0
            globalMountSlot.retryCount = 0
        }
        scheduleRefresh(normalSlot, false)
    }

    /** UI reads cached state; process creation and authorization never run on its thread. */
    fun rootAvailable(): Boolean {
        val cached = normalSlot.shell?.takeIf { it.isAlive && it.isRoot }
        if (cached != null) return true
        _rootReady.value = false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scheduleRefresh(normalSlot, false)
            return false
        }
        return runCatching { requireRoot(false).isRoot }.getOrDefault(false)
    }

    val SHELL: Shell get() = requireRoot(false)
    val GLOBAL_MNT_SHELL: Shell get() = requireRoot(true)

    /** A privileged caller receives uid 0 or an explicit failure, never a plain sh. */
    private fun requireRoot(globalMnt: Boolean): Shell {
        val slot = if (globalMnt) globalMountSlot else normalSlot
        slot.shell?.takeIf { it.isAlive && it.isRoot }?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scheduleRefresh(slot, globalMnt)
            throw NoRootException("Root is not ready; authorization is running in the background")
        }
        return probeOnce(slot, globalMnt) ?: throw NoRootException()
    }

    private fun probeOnce(slot: RootShellSlot, globalMnt: Boolean): Shell? {
        synchronized(shellLock) {
            slot.shell?.takeIf { it.isAlive && it.isRoot }?.let { return it }
            if (slot.probing || SystemClock.elapsedRealtime() < slot.retryAtElapsedMs) return null
            slot.probing = true
        }
        val candidate = runCatching { createRootShell(globalMnt) }
        synchronized(shellLock) {
            slot.probing = false
            _probeSettled.value = true
            val shell = candidate.getOrNull()
            if (shell != null) {
                slot.shell = shell
                slot.retryCount = 0
                slot.retryAtElapsedMs = 0
            } else {
                slot.retryAtElapsedMs = SystemClock.elapsedRealtime() +
                    (3_000L shl slot.retryCount.coerceAtMost(4)).coerceAtMost(30_000L)
                slot.retryCount = (slot.retryCount + 1).coerceAtMost(8)
                Log.w(TAG, "Root probe failed", candidate.exceptionOrNull())
            }
            _rootReady.value = normalSlot.shell?.let { it.isAlive && it.isRoot } == true
            return shell
        }
    }

    private fun scheduleRefresh(slot: RootShellSlot, globalMnt: Boolean) {
        synchronized(shellLock) {
            if (slot.refreshScheduled) return
            slot.refreshScheduled = true
        }
        Thread({
            try {
                repeat(8) {
                    val wait = slot.retryAtElapsedMs - SystemClock.elapsedRealtime()
                    if (wait > 0) Thread.sleep(wait.coerceAtMost(30_000L))
                    if (probeOnce(slot, globalMnt) != null) return@Thread
                    if (slot.probing) Thread.sleep(150)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(shellLock) { slot.refreshScheduled = false }
            }
        }, "xec-root-probe").apply { isDaemon = true }.start()
    }
}

/** Only tokens, never shell syntax, are accepted from partition/KMI selectors. */
private fun safeBootToken(value: String): String? =
    value.takeIf { it.matches(Regex("^[A-Za-z0-9_.-]{1,32}$")) }

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

/** Wait on IO for authorization; expiry is an explicit failure. */
suspend fun awaitRootShell(
    globalMnt: Boolean = false,
    timeoutMs: Long = 10_000L,
): Shell = withContext(Dispatchers.IO) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    do {
        runCatching { getRootShell(globalMnt) }.getOrNull()?.let { return@withContext it }
        delay(150)
    } while (SystemClock.elapsedRealtime() < deadline)
    throw NoRootException("Timed out waiting for root authorization")
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    check(Looper.myLooper() != Looper.getMainLooper()) { "Root acquisition must run on IO" }
    val daemon = listOf(getKsuDaemonPath(), "debug", "su").let { if (globalMnt) it + "-g" else it }
    // External su permits installation from an existing provider; it does not imply our driver is active.
    val external = SU_CLIENT_PATHS.map { if (globalMnt) listOf(it, "-mm") else listOf(it) }
    val candidates = listOf(daemon) + external
    for (argv in candidates) {
        val shell = runCatching { Shell.Builder.create().build(*argv.toTypedArray()) }.getOrNull()
            ?: continue
        val probe = runCatching { shell.newJob().add("id -u").to(ArrayList<String>(), ArrayList<String>()).exec() }.getOrNull()
        val uid = probe?.out?.firstOrNull()?.trim()
        if (probe != null && probe.code == 0 && uid == "0") {
            return shell
        }
        Log.w(TAG, "root shell candidate failed: ${argv.first()} (code=${probe?.code}, uid=$uid)")
        runCatching { shell.close() }
    }
    throw NoRootException("No authorized root shell is available")
}

fun execKsud(args: String, newShell: Boolean = false, globalMnt: Boolean = false): Boolean {
    require('\u0000' !in args && '\n' !in args && '\r' !in args) { "Invalid daemon arguments" }
    return if (newShell) {
        withNewRootShell(globalMnt = globalMnt) {
            ShellUtils.fastCmdResult(this, "${shQuote(getKsuDaemonPath())} $args")
        }
    } else {
        val shell = getRootShell(globalMnt)
        ShellUtils.fastCmdResult(shell, "${shQuote(getKsuDaemonPath())} $args")
    }
}

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${shQuote(getKsuDaemonPath())} feature check $feature").to(ArrayList<String>(), null).exec().out
    out.firstOrNull()?.trim().orEmpty()
}

suspend fun getFeaturePersistValue(feature: String): Long? = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${shQuote(getKsuDaemonPath())} feature get --config $feature").to(ArrayList<String>(), null).exec().out
    val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

fun install() {
    val start = SystemClock.elapsedRealtime()
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val result = execKsud("install --libadbroot ${shQuote(libadbroot)} --data-path ${shQuote(ksuApp.applicationInfo.deviceProtectedDataDir)}", true)
    check(result) { "Daemon installation failed" }
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
}

fun listModules(): String {
    val out = runCatching {
        withNewRootShell {
            newJob().add("${shQuote(getKsuDaemonPath())} module list").to(ArrayList<String>(), null).exec().out
        }
    }.getOrDefault(emptyList())
    return out.joinToString("\n").ifBlank { "[]" }
}

fun getSuperuserCount(): Int {
    return Natives.getSuperuserCount()
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable ${shQuote(id)}"
    } else {
        "module disable ${shQuote(id)}"
    }
    val result = execKsud(cmd, true)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall ${shQuote(id)}"
    val result = execKsud(cmd, true)
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall ${shQuote(id)}"
    val result = execKsud(cmd, true)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    val file = File.createTempFile("module-", ".zip", ksuApp.cacheDir)
    return try {
        copySelectedFile(uri, file)
        FlashResult(flashWithIO("${shQuote(getKsuDaemonPath())} module install ${shQuote(file.path)}", onStdout, onStderr))
    } catch (e: Exception) {
        val message = e.message ?: "Module installation failed"
        onStderr(message)
        FlashResult(-1, message, false)
    } finally {
        file.delete()
    }
}

private fun copySelectedFile(uri: Uri, target: File) {
    val input = ksuApp.contentResolver.openInputStream(uri)
        ?: throw java.io.IOException("Cannot open selected file")
    input.use { source -> target.outputStream().use { source.copyTo(it) } }
}

private const val AK3_UPDATER_ENTRY = "META-INF/com/google/android/update-binary"

/**
 * 从 AnyKernel3 压缩包中取出 `update-binary`（AK3 的入口脚本）。
 */
private fun extractAnyKernelUpdater(zip: File, dest: File): Boolean {
    return try {
        ZipFile(zip).use { zipFile ->
            var entry = zipFile.getEntry(AK3_UPDATER_ENTRY)
            if (entry == null) {
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val candidate = entries.nextElement()
                    val name = candidate.name
                    if (name.equals("update-binary", ignoreCase = true) ||
                        name.endsWith("/update-binary", ignoreCase = true)
                    ) {
                        entry = candidate
                        break
                    }
                }
            }
            if (entry == null) return false
            zipFile.getInputStream(entry).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dest.length() > 0L
    } catch (e: Throwable) {
        Log.e("XECKernelPro", "extract update-binary failed", e)
        false
    }
}

/**
 * 刷入第三方 AnyKernel3 包（例如第三方 GKI 内核）。
 *
 * 与 suk / KernelSU-Next 管理器一致：把 zip 拷进 app 私有目录，按 AK3 约定的
 * `update-binary <api> <outfd> <zipfile>` 调用它，并把 `AKHOME` / `POSTINSTALL`
 * 指向私有目录，让 AK3 在应用内完成刷写（脚本检测到 BOOTMODE 后会跳过
 * `setup_env` / `restore_env`，不会破坏当前挂载）。
 */
fun flashAnyKernel(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    val resolver = ksuApp.contentResolver
    val workDir = File(ksuApp.cacheDir, "anykernel3_${System.currentTimeMillis()}")
    if (!workDir.mkdirs() && !workDir.isDirectory) {
        return FlashResult(1, "Failed to create a temporary directory.", false)
    }

    val zipFile = File(workDir, "anykernel3.zip")
    return try {
        val input = resolver.openInputStream(uri)
            ?: return FlashResult(1, "Failed to open the selected file.", false)
        input.use { source ->
            zipFile.outputStream().use { output -> source.copyTo(output) }
        }
        if (zipFile.length() == 0L) {
            return FlashResult(1, "The selected file is empty.", false)
        }

        val updater = File(workDir, "update-binary")
        if (!extractAnyKernelUpdater(zipFile, updater)) {
            return FlashResult(1, "Not a valid AnyKernel3 package: update-binary not found.", false)
        }

        // AK3 脚本在解压阶段依赖 PATH 中的 unzip（Android 自带 shell 没有），
        // 所以先把内核自带的 busybox 软链成 unzip 并前置到 PATH。
        val workDirPath = workDir.absolutePath
        val zipPath = zipFile.absolutePath
        val cmd = "BB='/data/adb/ksu/bin/busybox'; " +
            "[ -f \"\$BB\" ] || BB='/system/bin/busybox'; " +
            "[ -f \"\$BB\" ] || BB='/system/xbin/busybox'; " +
            "[ -f \"\$BB\" ] || { echo 'busybox not found, cannot install AnyKernel3 package' >&2; exit 1; }; " +
            "W=${shQuote(workDirPath)}; " +
            "rm -rf \"\$W/tmp\"; mkdir -p \"\$W/tmp/bin\" \"\$W/bin\"; " +
            "ln -sf \"\$BB\" \"\$W/bin/unzip\"; " +
            "export POSTINSTALL=\"\$W\"; " +
            "export AKHOME=\"\$W/tmp\"; " +
            "export PATH=\"\$W/bin:/system/bin:/system/xbin:\$PATH\"; " +
            "\"\$BB\" ash \"\$W/update-binary\" 3 1 ${shQuote(zipPath)}"
        val result = flashWithIO(cmd, onStdout, onStderr)
        Log.i("XECKernelPro", "flash anykernel3 $uri result: $result")
        FlashResult(result)
    } finally {
        workDir.deleteRecursively()
    }
}

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add("${shQuote(getKsuDaemonPath())} module action ${shQuote(moduleId)}")
            .to(stdoutCallback, stderrCallback).exec()
    }

    Log.i("XECKernelPro", "Module runAction result: $result")

    return result.isSuccess
}

fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${shQuote(getKsuDaemonPath())} boot-restore -f", onStdout, onStderr)
    return FlashResult(result)
}

fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${shQuote(getKsuDaemonPath())} uninstall --package-name ${BuildConfig.APPLICATION_ID}", onStdout, onStderr)
    return FlashResult(result)
}

@Parcelize
sealed class LkmSelection : Parcelable {
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    @Parcelize
    data object KmiNone : LkmSelection()
}

private fun writeLkmFile(lkm: LkmSelection): File? {
    if (lkm !is LkmSelection.LkmUri) return null
    val file = File.createTempFile("lkm-", ".ko", ksuApp.cacheDir)
    try {
        copySelectedFile(lkm.uri, file)
        require(file.length() > 0) { "Selected LKM is empty" }
        return file
    } catch (e: Exception) {
        file.delete()
        throw e
    }
}

private fun bootPatchFlags(
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
): String = buildString {
    if (allowShell) append(" --allow-shell")
    if (enableAdb) append(" --enable-adbd")
    if (forceBackup) append(" --backup")

}

/** boot-patch accepts boot/vendor_boot images; OTA archives require extraction first. */
private fun looksLikeFlashableImage(file: java.io.File): Boolean {
    if (!file.isFile || file.length() < 8L) return false
    val head = ByteArray(8)
    val read = runCatching {
        file.inputStream().use { it.read(head) }
    }.getOrDefault(0)
    if (read < 8) return false
    val magic = String(head, Charsets.US_ASCII)
    return magic == "ANDROID!" || magic == "VNDRBOOT"
}

fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    var bootFile: File? = null
    var lkmFile: File? = null
    return try {
        val image = bootUri?.let { uri ->
            File.createTempFile("boot-", ".img", ksuApp.cacheDir).also {
                bootFile = it
                copySelectedFile(uri, it)
                require(looksLikeFlashableImage(it)) {
                    "Selected file is not a boot/vendor_boot image (${it.length()} bytes); extract OTA archives first"
                }
            }
        }
        // A file-patch request must never acquire --flash, including invalid-input paths.
        var cmd = if (bootUri == null) "boot-patch -f" else "boot-patch -b ${shQuote(requireNotNull(image).path)}"
        cmd += bootPatchFlags(allowShell, enableAdb, forceBackup)
        if (ota) cmd += " -u"
        lkmFile = writeLkmFile(lkm)
        lkmFile?.let { cmd += " -m ${shQuote(it.path)}" }
        if (lkm is LkmSelection.KmiString) {
            val kmi = requireNotNull(safeBootToken(lkm.value)) { "Invalid KMI name" }
            cmd += " --kmi $kmi"
        }
        if (bootUri != null) {
            val output = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            cmd += " -o ${shQuote(output.path)}"
        }
        partition?.let {
            cmd += " --partition ${requireNotNull(safeBootToken(it)) { "Invalid partition name" }}"
        }
        val result = flashWithIO("${shQuote(getKsuDaemonPath())} $cmd", onStdout, onStderr)
        val showReboot = bootUri == null && result.isSuccess
        if (showReboot) install()
        FlashResult(result, showReboot)
    } catch (e: Exception) {
        val message = e.message ?: "Boot patch failed"
        onStderr(message)
        FlashResult(-1, message, false)
    } finally {
        bootFile?.delete()
        lkmFile?.delete()
    }
}

fun downloadBoot(
    url: String,
    partition: String,
    lkm: LkmSelection,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val bootFile = File.createTempFile("download-boot-", ".img", ksuApp.cacheDir)
    var lkmFile: File? = null
    try {
    var probedKmi: String? = null
    try {
        onStdout("- Downloading and extracting boot image")
        DataSourceChannel(newDownloadClient(), url).use { channel ->
        val magic = readMagic(channel)
        val image = ExtractImage(bootFile, onStdout)
        // Extract the KMI here while the payload is open. ZipFile closes the
        // channel it is built on, so probe on a separate channel.
        val probeChannel = DataSourceChannel(newDownloadClient(), url)
        probedKmi = try {
            if (magic == "CrAU") {
                ExtractImage.probePayload(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            } else {
                ExtractImage.probe(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            }
        } finally {
            probeChannel.close()
        }
        if (magic == "CrAU") {
            image.consumePayload(channel, partition)
        } else {
            image.consume(channel, partition)
        }
        }
    } catch (e: Exception) {
        bootFile.delete()
        return FlashResult(-1, e.message ?: "Download failed", false)
    }

    // init_boot/vendor_boot carry no kernel, so their KMI comes from the
    // payload's boot probe and must be passed explicitly. A remote download
    // is unrelated to this device, so ksud must not use the local kernel.
    val autoKmi = if (lkm is LkmSelection.KmiNone) {
        (probedKmi ?: BootKernelVersion.parseKmiFromBoot(bootFile))?.also {
            onStdout("- Auto detected KMI: $it")
        }
    } else {
        null
    }
    if (autoKmi == null && lkm is LkmSelection.KmiNone) {
        bootFile.delete()
        return FlashResult(-1, "Failed to determine KMI from the package", false)
    }

    // 下载解出来的东西也可能不是 boot 镜像（分区选错、包里没有 init_boot 等）。
    // 直接喂给 ksud 只会得到一句 invalid boot image，这里先把原因说清楚。
    if (!looksLikeFlashableImage(bootFile)) {
        val size = bootFile.length()
        bootFile.delete()
        return FlashResult(
            -1,
            "下载的镜像里没有可用的 $partition（解出 $size 字节，不是 Android boot 镜像）",
            false,
        )
    }

    var cmd = "${shQuote(getKsuDaemonPath())} boot-patch -b ${shQuote(bootFile.path)}"
    cmd += bootPatchFlags(allowShell, enableAdb, forceBackup)

    lkmFile = writeLkmFile(lkm)
    if (lkmFile != null) {
        cmd += " -m ${shQuote(lkmFile.path)}"
    } else if (lkm is LkmSelection.KmiString) {
        val safeKmi = safeBootToken(lkm.value)
            ?: return FlashResult(-1, "refusing unsafe KMI name: ${lkm.value}", false)
        cmd += " --kmi $safeKmi"
    }
    if (autoKmi != null) {
        val safeAutoKmi = safeBootToken(autoKmi)
            ?: return FlashResult(-1, "refusing unsafe KMI name: $autoKmi", false)
        cmd += " --kmi $safeAutoKmi"
    }
    val safePartition = safeBootToken(partition)
        ?: return FlashResult(-1, "refusing unsafe partition name: $partition", false)
    cmd += " --partition $safePartition"
    // ksud defaults to cwd, which is read-only in the su session; use Downloads.
    val downloadsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    cmd += " -o ${shQuote(downloadsDir.path)}"

    val result = flashWithIO(cmd, onStdout, onStderr)
    return FlashResult(result, false)
    } catch (e: Exception) {
        val message = e.message ?: "Download/patch failed"
        onStderr(message)
        return FlashResult(-1, message, false)
    } finally {
        lkmFile?.delete()
        bootFile.delete()
    }
}

suspend fun probeRemoteBootPartitions(url: String): ProbeResult = withContext(Dispatchers.IO) {
    Log.d(TAG, "probe start: $url")
    DataSourceChannel(newDownloadClient(), url).use { channel ->
    Log.d(TAG, "probe connected, size=${channel.size()}")
    val magic = readMagic(channel)
    Log.d(TAG, "probe magic: $magic")
    // Only list the partitions here; the KMI is extracted later when the
    // payload is downloaded for patching.
    val result = if (magic == "CrAU") {
        ExtractImage.probePayload(channel, withKmi = false)
    } else {
        ExtractImage.probe(channel, withKmi = false)
    }
    Log.d(TAG, "probe partitions: ${result.partitions}")
    result
    }
}

private fun newDownloadClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}

private fun readMagic(channel: DataSourceChannel): String {
    val buffer = ByteBuffer.allocate(4)
    channel.read(buffer)
    channel.position(0)
    return String(buffer.array(), StandardCharsets.ISO_8859_1)
}

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        // 软重启走 ksud 的 UAPI；驱动不在应答时它一定失败并返回非 0，所以这里把结果
        // 记进日志而不是"叫了就算成功"（勾了"用软重启代替重启"却什么都没发生最糟）。
        val ok = execKsud("soft-reboot", true, true)
        check(ok) { "Soft reboot failed" }
        return
    }
    rebootNow(reason)
}

/** [reboot] 的真重启尾巴。 */
private fun rebootNow(reason: String) {
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    check(ShellUtils.fastCmdResult(shell, "/system/bin/svc power reboot ${shQuote(reason)} || /system/bin/reboot ${shQuote(reason)}")) {
        "Reboot request failed"
    }
}

fun rootAvailable(): Boolean = KsuCli.rootAvailable()


/** Run metadata-only commands without coupling installation choices to root authorization. */
private fun runKsudNoRoot(args: String): List<String> = runCatching {
    val shell = KsuCli.plainShell()
    shell.newJob()
        .add("${shQuote(getKsuDaemonPath())} $args")
        .to(ArrayList(), null)
        .exec()
        .out
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}.getOrDefault(emptyList())

suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    runKsudNoRoot("boot-info current-kmi").firstOrNull()
        ?: deviceKmi().takeIf { it.isNotBlank() }
        ?: ""
}

suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    runKsudNoRoot("boot-info supported-kmis")
}

suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    runKsudNoRoot("boot-info is-ab-device").firstOrNull()?.toBooleanStrictOrNull() ?: false
}

suspend fun getDefaultPartition(): String = withContext(Dispatchers.IO) {
    runKsudNoRoot("boot-info default-partition").firstOrNull()
        ?.takeIf { it in setOf("boot", "init_boot", "vendor_boot") } ?: "boot"
}

suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    runKsudNoRoot("boot-info slot-suffix" + if (ota) " --ota" else "").firstOrNull().orEmpty()
}

suspend fun getAvailablePartitions(): List<String> = withContext(Dispatchers.IO) {
    runKsudNoRoot("boot-info available-partitions").filter { it.isNotBlank() }.map { it.trim() }
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    val shell = getRootShell()
    val result =
        shell.newJob().add("${shQuote(getKsuDaemonPath())} sepolicy check ${shQuote(rules)}").to(ArrayList(), null)
            .exec()
    return result.isSuccess
}

fun getSepolicy(pkg: String): String {
    val shell = getRootShell()
    val result =
        shell.newJob().add("${shQuote(getKsuDaemonPath())} profile get-sepolicy ${shQuote(pkg)}").to(ArrayList(), null)
            .exec()
    Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
    return result.out.joinToString("\n")
}

/**
 * POSIX 单引号转义：整段包进 `'...'`，内部的 `'` 变成 `'\''`。
 *
 * 这些命令是**以 root 执行**的字符串拼接，而模板 id、sepolicy 规则、包名都来自
 * 用户可编辑的数据。之前它们直接插进单引号里（甚至不包引号），一个 `'` 就能提前
 * 结束参数、把后面的内容当成新的 root 命令执行。
 */
fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result = shell.newJob()
        .add("${shQuote(getKsuDaemonPath())} profile set-sepolicy ${shQuote(pkg)} ${shQuote(rules)}")
        .to(ArrayList(), null).exec()
    Log.i(TAG, "set sepolicy result: ${result.code}")
    return result.isSuccess
}

fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    return shell.newJob().add("${shQuote(getKsuDaemonPath())} profile list-templates").to(ArrayList(), null)
        .exec().out
}

fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    val result = shell.newJob().add("${shQuote(getKsuDaemonPath())} profile get-template ${shQuote(id)}")
        .to(ArrayList(), null).exec()
    check(result.isSuccess) { "Failed to read profile template $id" }
    return result.out.joinToString("\n")
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    val shell = getRootShell()
    val cmd = "${shQuote(getKsuDaemonPath())} profile set-template ${shQuote(id)} ${shQuote(template)}"
    return shell.newJob().add(cmd)
        .to(ArrayList(), null).exec().isSuccess
}

fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${shQuote(getKsuDaemonPath())} profile delete-template ${shQuote(id)}")
        .to(ArrayList(), null).exec().isSuccess
}

fun forceStopApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result = shell.newJob().add("am force-stop$userArg ${shQuote(packageName)}").exec()
    check(result.isSuccess) { "Failed to stop $packageName" }
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result =
        shell.newJob()
            .add("cmd package resolve-activity --brief$userArg ${shQuote(packageName)} | tail -n 1 | xargs cmd activity start-activity$userArg -n")
            .exec()
    check(result.isSuccess) { "Failed to launch $packageName" }
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}

/**
 * 内置 TEE/TS 引擎的开关（`xudc builtin tee [on|off]`）。
 *
 * 引擎是纯用户态注入（tricky_store 的 daemon + TA_enhanced 的阶段脚本），**不是**内核模块，
 * 所以关掉它不影响 root：LKM 照常加载，su / 模块 / 隐藏全部照常，只是 keystore2 里那条
 * 伪造的证明链不再出现。
 *
 * 查询失败时按"开着"处理 —— 默认行为必须是不改变现状。
 */
fun teeEngineEnabled(): Boolean {
    val result = runCatching {
        getRootShell().newJob().add("${shQuote(getKsuDaemonPath())} builtin tee")
            .to(ArrayList(), null).exec()
    }.getOrNull() ?: return true
    return result.out.joinToString(" ").trim() != "off"
}

fun applyTeeEngineSwitch(enabled: Boolean): Boolean = runCatching {
    getRootShell().newJob()
        .add("${shQuote(getKsuDaemonPath())} builtin tee ${if (enabled) "on" else "off"}")
        .to(ArrayList(), null).exec().isSuccess
}.getOrDefault(false)

/** 开关只在开机路径上读，改完必须重启才生效。 */
