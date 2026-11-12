package me.weishu.kernelsu.ui.screen.tools

/*
 * 工具箱：评分 / 提权 / 终端 / 外部模块 / 安全锁 / 隐身。
 * 2026-10-26 从设置页拆出——低频工具占设置一整屏，收进一个入口；
 * 「超级密钥」对用户改叫「安全锁」，文案全部走大白话（不出现 fd/ioctl/守卫等词）。
 *
 * 2026-11-09（扁平方案 A）：六个入口收进**一张面板**，行与行之间只有 1px 发丝线，
 * 不再一组一张卡；评分卡从雷达改成"大数字 + 九格"。
 */
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.R
import me.weishu.kernelsu.privilege.StealthEntrance
import me.weishu.kernelsu.ui.component.dialog.XDialog
import me.weishu.kernelsu.ui.component.dialog.XDialogTitle
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.design.panel.Xc8Chevron
import me.weishu.kernelsu.ui.design.panel.Xc8Panel
import me.weishu.kernelsu.ui.design.panel.Xc8Row
import me.weishu.kernelsu.ui.design.panel.Xc8SectionLabel
import me.weishu.kernelsu.ui.design.panel.Xc8Switch
import me.weishu.kernelsu.ui.MainPagerBus
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ToolsScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current

    // XEC SCORE 从首页搬到这里（2026-11-09）：数据仍然只有一个来源 —— HomeViewModel
    // 与 `ui/screen/home/XecScore.kt`，工具箱不另造评分逻辑。
    // 进页面刷一次：工具箱可能先于首页被打开（从设置直达、或进程恢复直接落在这一页），
    // 那样 ViewModel 还是初始态，不刷就会显示"未安装 / 0 分"。
    val homeViewModel = viewModel<HomeViewModel>()
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { homeViewModel.refresh() }

    var stealthOn by rememberSaveable { mutableStateOf(false) }
    var stealthBusy by rememberSaveable { mutableStateOf(false) }
    val showStealthConfirm = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        stealthOn = StealthEntrance.isIconHidden(context)
    }
    val stealthConfirm = rememberConfirmDialog(
        onConfirm = {
            stealthBusy = true
            Thread {
                val ok = StealthEntrance.enable(context)
                stealthOn = ok
                stealthBusy = false
            }.start()
        }
    )
    if (showStealthConfirm.value) {
        stealthConfirm.showConfirm(
            title = stringResource(id = R.string.stealth_title),
            content = stringResource(
                id = R.string.stealth_confirm_body,
                StealthEntrance.adbFallback(context),
            ),
        )
        showStealthConfirm.value = false
    }
    // 行点击与开关共用同一入口：开 -> 先弹确认；关 -> 直接执行。
    val onToggleStealth: (Boolean) -> Unit = { want ->
        if (want) {
            showStealthConfirm.value = true
        } else if (!stealthBusy) {
            stealthBusy = true
            Thread {
                StealthEntrance.disable(context)
                stealthOn = false
                stealthBusy = false
            }.start()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.tools_title),
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        val layoutDirection = LocalLayoutDirection.current
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
                .padding(horizontal = 22.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                ) {
                    // 结论先于工具：评分是"该修哪一项"的诊断入口，点它直接去检测页看九项明细。
                    // 2026-11-09：底栏不再常驻「检测」，这里是它唯一的入口。跨路由切页
                    // 走 MainPagerBus（有界事件通道），不再读 LocalMainPagerState（P0 源）。
                    XecScoreCard(state = homeState) {
                        MainPagerBus.jumpTo(3)
                        navigator.pop()
                    }

                    Xc8SectionLabel(
                        text = stringResource(R.string.tools_section_tools),
                        modifier = Modifier.padding(top = 22.dp),
                    )
                    Xc8Panel {
                        Xc8Row(
                            title = stringResource(id = R.string.tools_terminal_title),
                            subtitle = stringResource(id = R.string.tools_terminal_summary),
                            leading = Icons.Rounded.Code,
                            onClick = { navigator.push(Route.Terminal) },
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(id = R.string.tools_external_modules_title),
                            subtitle = stringResource(id = R.string.tools_external_modules_summary),
                            leading = Icons.Rounded.Extension,
                            onClick = { navigator.push(Route.ExternalModules) },
                            trailing = { Xc8Chevron() },
                        )
                        Xc8Row(
                            title = stringResource(id = R.string.stealth_title),
                            subtitle = stringResource(id = R.string.stealth_summary),
                            leading = Icons.Rounded.VisibilityOff,
                            onClick = { onToggleStealth(!stealthOn) },
                            trailing = {
                                Xc8Switch(
                                    checked = stealthOn,
                                    onCheckedChange = onToggleStealth,
                                )
                            },
                        )
                    }

                }
            }
        }
    }

}
