package me.weishu.kernelsu.ui.screen.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 工具箱：低频但必须随手可用的几个入口。
 *
 * 为什么单独一屏而不是铺进设置：这几项要么会接管整块屏幕（终端、文件管理），要么
 * 会往设备里装东西（扩展模块），性质和设置里的开关不是一类；放进设置会让那一页
 * 又变回"什么都往里塞"。所以设置里只留一行入口。
 *
 * 这里只放**镜像里有实现**的东西：终端（含文件管理页签）与扩展模块（TEE 引擎 /
 * TA 增强 / ReZygisk 跳转）。隐身入口在重写完成后加到这一屏，提权（GhostLock）
 * 与一键隐藏不进这一版——前者是已被否掉的路线，后者依赖被删掉的隐身守护链。
 */
@Composable
fun ToolsScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.tools_title),
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null,
        ) {
            item {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ToolRow(
                            title = stringResource(R.string.tools_terminal_title),
                            summary = stringResource(R.string.tools_terminal_summary),
                            icon = Icons.Rounded.Terminal,
                            onClick = { navigator.push(Route.Terminal) },
                        )
                        // 扩展模块：装的是本项目自己构建的 TEE 引擎 / TA 增强，以及 ReZygisk
                        // 的跳转。安装动作会真的往设备里写模块，所以单独一条路径、单独一屏说明。
                        ToolRow(
                            title = stringResource(R.string.tools_external_modules_title),
                            summary = stringResource(R.string.tools_external_modules_summary),
                            icon = Icons.Rounded.Extension,
                            onClick = { navigator.push(Route.ExternalModules) },
                        )
                        // 隐身：屏幕上先给出"回来"的办法，再给隐藏按钮。
                        ToolRow(
                            title = stringResource(R.string.stealth_title),
                            summary = stringResource(R.string.stealth_summary),
                            icon = Icons.Rounded.VisibilityOff,
                            onClick = { navigator.push(Route.Stealth) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        startAction = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = colorScheme.onBackground,
                modifier = Modifier.padding(end = 6.dp),
            )
        },
        onClick = onClick,
    )
}
