package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.KsuIsValid
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedDropdownItem
import me.weishu.kernelsu.ui.component.material.SegmentedSwitchItem


/**
 * Material-mode half of the feature switches: the same rows as [FeatureSwitchCardsMiuix],
 * laid out as segmented columns.
 */
@Composable
fun FeatureSwitchColumnsMaterial(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
) {
    KsuIsValid {
        val suCompatModeItems = listOf(
            stringResource(id = R.string.settings_mode_enable_by_default),
            stringResource(id = R.string.settings_mode_disable_until_reboot),
            stringResource(id = R.string.settings_mode_disable_always),
        )

        SegmentedColumn(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            content = listOf(
                {
                    val suSummary = when (uiState.suCompatStatus) {
                        "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                        "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                        "forced" -> stringResource(id = R.string.feature_status_forced_summary)
                        else -> stringResource(id = R.string.settings_sucompat_summary)
                    }
                    SegmentedDropdownItem(
                        icon = Icons.Filled.AdminPanelSettings,
                        title = stringResource(id = R.string.settings_sucompat),
                        summary = suSummary,
                        items = suCompatModeItems,
                        enabled = uiState.suCompatStatus == "supported",
                        selectedIndex = uiState.suCompatMode,
                        onItemSelected = actions.onSetSuCompatMode
                    )
                },
                {
                    val umountSummary = when (uiState.kernelUmountStatus) {
                        "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                        "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                        "forced" -> stringResource(id = R.string.feature_status_forced_summary)
                        else -> stringResource(id = R.string.settings_kernel_umount_summary)
                    }
                    SegmentedSwitchItem(
                        icon = Icons.Filled.LayersClear,
                        title = stringResource(id = R.string.settings_kernel_umount),
                        summary = umountSummary,
                        enabled = uiState.kernelUmountStatus == "supported",
                        checked = uiState.isKernelUmountEnabled,
                        onCheckedChange = actions.onSetKernelUmountEnabled
                    )
                },
                {
                    val selinuxHideSummary = when (uiState.selinuxHideStatus) {
                        "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                        "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                        "forced" -> stringResource(id = R.string.feature_status_forced_summary)
                        else -> stringResource(id = R.string.settings_selinux_hide_summary)
                    }
                    SegmentedSwitchItem(
                        icon = Icons.Filled.Security,
                        title = stringResource(id = R.string.settings_selinux_hide),
                        summary = selinuxHideSummary,
                        enabled = uiState.selinuxHideStatus == "supported",
                        checked = uiState.isSelinuxHideEnabled,
                        onCheckedChange = actions.onSetSelinuxHideEnabled
                    )
                },
                {
                    val sulogSummary = when (uiState.sulogStatus) {
                        "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                        "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                        "forced" -> stringResource(id = R.string.feature_status_forced_summary)
                        else -> stringResource(id = R.string.settings_sulog_summary)
                    }
                    SegmentedSwitchItem(
                        icon = Icons.AutoMirrored.Filled.Article,
                        title = stringResource(id = R.string.settings_sulog),
                        summary = sulogSummary,
                        enabled = uiState.sulogStatus == "supported",
                        checked = uiState.isSulogEnabled,
                        onCheckedChange = actions.onSetSulogEnabled
                    )
                },
                {
                    val adbRootSummary = when (uiState.adbRootStatus) {
                        "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                        "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                        "forced" -> stringResource(id = R.string.feature_status_forced_summary)
                        else -> stringResource(id = R.string.settings_adb_root_summary)
                    }
                    SegmentedSwitchItem(
                        icon = Icons.Filled.Adb,
                        title = stringResource(id = R.string.settings_adb_root),
                        summary = adbRootSummary,
                        enabled = uiState.adbRootStatus == "supported",
                        checked = uiState.isAdbRootEnabled,
                        onCheckedChange = actions.onSetAdbRootEnabled
                    )
                },
                {
                    val shellSuAdbOnlySummary = when (uiState.shellSuAdbOnlyStatus) {
                        "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                        "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                        "forced" -> stringResource(id = R.string.feature_status_forced_summary)
                        else -> stringResource(id = R.string.settings_shell_su_adb_only_summary)
                    }
                    SegmentedSwitchItem(
                        icon = Icons.Filled.VerifiedUser,
                        title = stringResource(id = R.string.settings_shell_su_adb_only),
                        summary = shellSuAdbOnlySummary,
                        enabled = uiState.shellSuAdbOnlyStatus == "supported",
                        checked = uiState.isShellSuAdbOnlyEnabled,
                        onCheckedChange = actions.onSetShellSuAdbOnlyEnabled
                    )
                },
                {
                    SegmentedSwitchItem(
                        icon = Icons.Filled.RestartAlt,
                        title = stringResource(id = R.string.settings_soft_reboot),
                        summary = stringResource(id = R.string.settings_soft_reboot_summary),
                        enabled = !uiState.isLateLoadMode,
                        checked = uiState.isLateLoadMode || uiState.useSoftReboot,
                        onCheckedChange = actions.onSetUseSoftReboot
                    )
                },
            )
        )

        SegmentedColumn(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            content = listOf(
                {
                    SegmentedSwitchItem(
                        icon = Icons.AutoMirrored.Filled.Rule,
                        title = stringResource(id = R.string.settings_umount_modules_default),
                        summary = stringResource(id = R.string.settings_umount_modules_default_summary),
                        checked = uiState.isDefaultUmountModules,
                        onCheckedChange = actions.onSetDefaultUmountModules
                    )
                },
                {
                    SegmentedSwitchItem(
                        icon = Icons.Filled.DeveloperMode,
                        title = stringResource(id = R.string.enable_web_debugging),
                        summary = stringResource(id = R.string.enable_web_debugging_summary),
                        checked = uiState.enableWebDebugging,
                        onCheckedChange = actions.onSetEnableWebDebugging
                    )
                },
                {
                    SegmentedSwitchItem(
                        icon = Icons.Filled.FlashOn,
                        title = stringResource(id = R.string.settings_auto_jailbreak),
                        summary = stringResource(id = R.string.settings_auto_jailbreak_summary),
                        enabled = uiState.isLateLoadMode,
                        checked = uiState.autoJailbreak,
                        onCheckedChange = actions.onSetAutoJailbreak
                    )
                }
            )
        )
    }
}
