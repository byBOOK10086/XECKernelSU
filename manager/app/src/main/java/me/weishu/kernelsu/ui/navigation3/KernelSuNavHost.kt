package me.weishu.kernelsu.ui.navigation3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import me.weishu.kernelsu.ui.screen.about.AboutScreen
import me.weishu.kernelsu.ui.screen.appprofile.AppProfileScreen
import me.weishu.kernelsu.ui.screen.colorpalette.ColorPaletteScreen
import me.weishu.kernelsu.ui.screen.executemoduleaction.ExecuteModuleActionScreen
import me.weishu.kernelsu.ui.screen.extmodules.ExternalModulesScreen
import me.weishu.kernelsu.ui.screen.flash.FlashScreen
import me.weishu.kernelsu.ui.screen.install.InstallScreen
import me.weishu.kernelsu.ui.screen.modulerepo.ModuleRepoDetailScreen
import me.weishu.kernelsu.ui.screen.modulerepo.ModuleRepoScreen
import me.weishu.kernelsu.ui.screen.remote.RemoteAssistantScreen
import me.weishu.kernelsu.ui.screen.scriptrun.ScriptRunScreen
import me.weishu.kernelsu.ui.screen.stealth.StealthScreen
import me.weishu.kernelsu.ui.screen.sulog.SulogScreen
import me.weishu.kernelsu.ui.screen.template.AppProfileTemplateScreen
import me.weishu.kernelsu.ui.screen.templateeditor.TemplateEditorScreen
import me.weishu.kernelsu.ui.screen.terminal.TerminalScreen
import me.weishu.kernelsu.ui.screen.theme.ThemeScreen
import me.weishu.kernelsu.ui.screen.tools.ToolsScreen
import me.weishu.kernelsu.ui.screen.wallpaper.WallpaperScreen
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


/**
 * The navigation graph: every destination the manager can reach.
 *
 * Lives outside [me.weishu.kernelsu.ui.MainActivity] so the Activity only owns window
 * level concerns (splash, insets, theme) while this file owns routes. The two
 * back-enabled flags are read here, not inside the builder: NavDisplay only rebuilds
 * its entries (and their swipeDismiss) when the builder lambda changes, so the value
 * has to be captured by that lambda.
 */
@Composable
fun KernelSuNavHost(
    navigator: Navigator,
    swipeDismiss: NavSwipeDirection,
    mainScreenEntry: @Composable () -> Unit,
) {
    val flashRoute = navigator.current() as? Route.Flash
    var flashBackEnabled by remember(flashRoute) { mutableStateOf(false) }
    val scriptRunRoute = navigator.current() as? Route.ScriptRun
    var scriptRunBackEnabled by remember(scriptRunRoute) { mutableStateOf(true) }
    // Read here, not inside the NavDisplay builder: NavDisplay only rebuilds its entries
    // (and their swipeDismiss) when the builder lambda changes, so it must capture the value.
    val scriptRunSwipeDismiss = if (scriptRunBackEnabled) swipeDismiss else NavSwipeDirection.None
    val navDisplay = @Composable {
        // If this number is 0 the window renders nothing at all - no pages, no crash, just a
        // blank ComposeView. Logged so a blank screen can be diagnosed from xec-boot.log.
        androidx.compose.runtime.LaunchedEffect(navigator.backStack.size) {
            me.weishu.kernelsu.ui.util.BootTrace.log(
                "navhost backStack=${navigator.backStack.size} current=${navigator.current()}"
            )
        }
        NavDisplay(
            backStack = navigator.backStack,
            effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
            onBack = {
                when (val top = navigator.current()) {
                    is Route.TemplateEditor -> {
                        if (!top.readOnly) {
                            navigator.setResult("template_edit", true)
                        } else {
                            navigator.pop()
                        }
                    }

                    else -> navigator.pop()
                }
            }) {
            entry<Route.Main>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
            entry<Route.About>(swipeDismiss = swipeDismiss) { AboutScreen() }
            entry<Route.Sulog>(swipeDismiss = swipeDismiss) { SulogScreen() }
            entry<Route.ColorPalette>(swipeDismiss = swipeDismiss) { ColorPaletteScreen() }
            entry<Route.AppProfileTemplate>(swipeDismiss = swipeDismiss) { AppProfileTemplateScreen() }
            entry<Route.TemplateEditor>(swipeDismiss = swipeDismiss) { key -> TemplateEditorScreen(key.template, key.readOnly) }
            entry<Route.AppProfile>(swipeDismiss = swipeDismiss) { key -> AppProfileScreen(key.uid) }
            entry<Route.ModuleRepo>(swipeDismiss = swipeDismiss) { ModuleRepoScreen() }
            entry<Route.ModuleRepoDetail>(swipeDismiss = swipeDismiss) { key -> ModuleRepoDetailScreen(key.module) }
            entry<Route.Install>(swipeDismiss = swipeDismiss) { InstallScreen() }
            entry<Route.Flash>(swipeDismiss = if (flashBackEnabled) swipeDismiss else NavSwipeDirection.None) { key ->
                FlashScreen(
                    key.flashIt,
                    onBackEnabledChange = { enabled ->
                        if (navigator.current() == key) flashBackEnabled = enabled
                    },
                )
            }
            entry<Route.ExecuteModuleAction>(swipeDismiss = swipeDismiss) { key ->
                ExecuteModuleActionScreen(
                    key.moduleId,
                    key.fromShortcut
                )
            }
            entry<Route.ScriptRun>(swipeDismiss = scriptRunSwipeDismiss) { key ->
                ScriptRunScreen(
                    key.arg,
                    onBackEnabledChange = { enabled ->
                        if (navigator.current() == key) scriptRunBackEnabled = enabled
                    },
                )
            }
            entry<Route.Themes>(swipeDismiss = swipeDismiss) { ThemeScreen() }
            entry<Route.Wallpaper>(swipeDismiss = swipeDismiss) { WallpaperScreen() }
            entry<Route.Tools>(swipeDismiss = swipeDismiss) { ToolsScreen() }
            entry<Route.Terminal>(swipeDismiss = swipeDismiss) { TerminalScreen() }
            entry<Route.ExternalModules>(swipeDismiss = swipeDismiss) { ExternalModulesScreen() }
            entry<Route.RemoteAssistant>(swipeDismiss = swipeDismiss) { RemoteAssistantScreen() }
            entry<Route.Stealth>(swipeDismiss = swipeDismiss) { StealthScreen() }
            entry<Route.Home>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
            entry<Route.SuperUser>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
            entry<Route.Module>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
            entry<Route.Settings>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
        }
    }
}
