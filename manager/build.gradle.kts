import java.io.File

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

/** 版本文件：仓库根的 `version.properties`，内核 `KSU_VERSION` 读的是同一份。 */
private fun versionFile(): File {
    val candidates = listOf(
        rootProject.file("version.properties"),
        rootProject.file("../version.properties"),
    )
    return candidates.firstOrNull { it.isFile }
        ?: error("缺少 version.properties（找过 ${candidates.joinToString(" 和 ") { it.path }}）")
}

private fun versionValue(key: String): String {
    val file = versionFile()
    val line = file.readLines().firstOrNull { it.trimStart().startsWith(key) }
        ?: error("${file.path} 里没有 $key")
    return line.substringAfter('=').trim()
}

fun getBaseVersionCode(): Int =
    versionValue("versionCode").toIntOrNull()
        ?: error("version.properties 的 versionCode 不是整数")

fun getVersionName(): String {
    // 名字也来自同一个文件，避免「内核 30001 / 管理器 v1.1.0-…」两套号。
    val name = versionValue("versionName")
    return if (name.startsWith("v")) name else "v$name"
}
