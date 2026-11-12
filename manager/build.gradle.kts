plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 31
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
extra["managerVersionCode"] = getVersionCode()
// 首页判断内置 LKM 是否落后时，拿内核的 KERNEL_SU_VERSION 跟这个基准码比。
// 口径与 kernel/Kbuild 完全一致（见 getBaseVersionCode），所以两处必然相等。
extra["managerBaseVersionCode"] = getBaseVersionCode()
extra["managerVersionName"] = getVersionName()

fun getGitCommitCount(): Int {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    return process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
}

fun getGitDescribe(): String {
    // `--exclude 'gki-*'`：gki-* 是内核预发布标签（build-susfs-gki.yml 打的），
    // 不是管理器版本。不排除的话最近的那个 GKI 标签会变成 versionName。
    val process = Runtime.getRuntime()
        .exec(arrayOf("git", "describe", "--tags", "--always", "--exclude", "gki-*"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

fun getVersionCode(): Int {
    return getBaseVersionCode()
}

/**
 * 公开版本码，必须和内核 `KERNEL_SU_VERSION` 同口径。
 *
 * 本仓库（XECKernelSU）的 `kernel/Kbuild` 用的是
 * `30000 + $(git rev-list --count HEAD)`，所以管理器也用同一条规则——
 * 两边报出同一个号，首页才不会永远提示「LKM 可更新」。
 *
 * 注意：CI 必须 `fetch-depth: 0`（build-manager.yml 已经是），否则浅克隆下
 * 提交数为 1，版本码会退化成 30001。
 */
fun getBaseVersionCode(): Int {
    return 30000 + getGitCommitCount()
}

fun getVersionName(): String {
    // 必须带 v 前缀，让 APK 文件名形如 XECKernelPro_v<ver>_<code>-release.apk。
    val desc = getGitDescribe()
    return if (desc.startsWith("v")) desc else "v$desc"
}
