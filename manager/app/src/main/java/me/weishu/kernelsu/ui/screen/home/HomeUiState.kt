package me.weishu.kernelsu.ui.screen.home

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.KernelVersion
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo

@Immutable
data class HomeUiState(
    val kernelVersion: KernelVersion,
    val ksuVersion: Int?,
    val managerUAPIVersion: Int,
    val kernelUAPIVersion: Int?,
    val lkmMode: Boolean?,
    val isLkmBundled: Boolean,
    val isManager: Boolean,
    val isManagerPrBuild: Boolean,
    val isKernelPrBuild: Boolean,
    val requiresNewKernel: Boolean,
    val requiresNewManager: Boolean,
    val isRootAvailable: Boolean,
    val isSafeMode: Boolean,
    val isLateLoadMode: Boolean,
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentManagerVersionCode: Long,
    val systemInfo: SystemInfo,
    /**
     * SUSFS variant reported by the kernel (`susfs -v` style string), or null
     * when the feature is not present. Populated by the SUSFS probe; the home
     * stat panel shows a placeholder until it arrives, so a null here means
     * "not probed yet / not supported", never "supported and empty".
     */
    val susfsVersion: String? = null,
    /**
     * SUSFS variant/flavour string (`susfs4ksu`, `gki-decoupled`, ...). Null when
     * the version is absent or the variant probe has not run.
     */
    val susfsVariant: String? = null,
    /**
     * Whether at least one root probe has completed.
     *
     * The first frame can only answer "not yet": the probe touches a root shell
     * and must not run on the main thread. Without this flag the hero would show
     * "root failed" for a few hundred milliseconds on every cold start. Warnings
     * are gated on it, so an unknown state never renders as a failure.
     */
    val rootProbeSettled: Boolean = true,
    /** True while a pull-to-refresh / toolbar refresh is in flight. */
    val isRefreshing: Boolean = false,
) {
    val isSELinuxPermissive: Boolean
        get() = systemInfo.selinuxStatus == "Permissive"

    val showGkiWarning: Boolean
        get() = ksuVersion != null && lkmMode == false

    val showLkmUpdate: Boolean
        get() = isManager &&
                lkmMode == true &&
                isLkmBundled &&
                ksuVersion?.toLong() != currentManagerVersionCode &&
                !requiresNewKernel &&
                !requiresNewManager

    // Jailbreak mode runs on locked bootloaders, so flashing a boot image would brick the device.
    val canInstallKernelUpdate: Boolean
        get() = lkmMode == true && !isLateLoadMode

    val showCustomLkmBadge: Boolean
        get() = lkmMode == true && !isLkmBundled

    val showRootWarning: Boolean
        get() = ksuVersion != null && !isRootAvailable

    val showManagerPrBuildWarning: Boolean
        get() = isManager && isManagerPrBuild

    val showKernelPrBuildWarning: Boolean
        get() = isManager && !isManagerPrBuild && isKernelPrBuild

    val hasUpdate: Boolean
        get() = latestVersionInfo.versionCode > currentManagerVersionCode
}

@Immutable
data class HomeActions(
    val onInstallClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onJailbreakClick: () -> Unit = {},
    /** Pull-to-refresh and the header refresh button share this one callback. */
    val onRefresh: () -> Unit = {},
    val onOpenSuperuser: () -> Unit = {},
    val onOpenSulog: () -> Unit = {},
)
