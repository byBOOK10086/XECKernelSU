package me.weishu.kernelsu.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.only
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.channels.Channel
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.privilege.StealthEntrance
import me.weishu.kernelsu.ui.design.token.XcTheme
import me.weishu.kernelsu.ui.navigation3.IntentDispatcher
import me.weishu.kernelsu.ui.navigation3.KernelSuNavHost
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.navigation3.rememberNavigator
import me.weishu.kernelsu.ui.screen.home.KernelMissingPage
import me.weishu.kernelsu.ui.theme.KernelSUTheme
import me.weishu.kernelsu.ui.theme.LocalColorMode
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.theme.LocalEnableFloatingBottomBar
import me.weishu.kernelsu.ui.theme.LocalEnableFloatingBottomBarBlur
import me.weishu.kernelsu.ui.theme.LocalEnableNavigationBadge
import me.weishu.kernelsu.ui.theme.LocalModuleDescriptionMaxLines
import me.weishu.kernelsu.ui.util.install
import me.weishu.kernelsu.ui.viewmodel.MainActivityViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import me.weishu.kernelsu.ui.MainScreen

/**
 * `open` because [StealthLaunchActivity] extends it: hiding the launcher icon disables this
 * component, and a disabled component cannot be started, so the way back in has to be a
 * second activity that *is* the same UI.
 */
open class MainActivity : ComponentActivity() {

    private val intentChannel = Channel<Intent>(capacity = Channel.BUFFERED)
    private var contentReady = false
    private var splashStartedAt = 0L
    private val splashAnimationDurationMs = 500L


    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashStartedAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            !contentReady || SystemClock.uptimeMillis() - splashStartedAt < splashAnimationDurationMs
        }

        val isManager = Natives.isManager
        if (!isManager) {
            // This build is not the manager of the kernel that is running (different package
            // or signature, or no KernelSU kernel at all). Every layer below - theme, nav
            // host, view models, the root shell - assumes a kernel it can talk to; on a real
            // Android 16 device that combination produced an empty window with no crash and
            // no text. Render one plain page instead of a themed tree that cannot work.
            //
            // contentReady has to be released here as well: the main content is what normally
            // flips it, and without it the splash stays on screen forever (measured: the page
            // was in the accessibility tree while the screenshot still showed the splash).
            contentReady = true
            setContent { KernelMissingPage() }
            return
        }
        if (isManager && Natives.kernelUAPIVersion == Natives.managerUAPIVersion) install()

        // Reconcile the "icon hidden" expectation with what the package manager reports, and
        // make sure the hidden-state entry exists. Cheap, idempotent, and it is what keeps the
        // stealth screen from claiming a state the device is not actually in.
        StealthEntrance.repair(this)

        if (savedInstanceState == null) intent?.let { intentChannel.trySend(it) }

        setContent {
            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
            val uiMode = uiState.uiMode
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val context = LocalContext.current
            val uriHandler = LocalUriHandler.current
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }

            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        try {
                            uriHandler.openUri(uri)
                        } catch (_: IllegalArgumentException) {
                            Toast.makeText(context, R.string.external_link_open_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                LocalEnableBlur provides uiState.enableBlur,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
                LocalModuleDescriptionMaxLines provides uiState.moduleDescriptionMaxLines,
                LocalUiMode provides uiMode,
            ) {
                // The XEC design tokens have to sit OUTSIDE the miuix theme: miuix
                // re-provides LocalIndication internally, and our glass highlights only
                // reach it when provided from the outside. With no theme applied these
                // tokens equal the built-in defaults, so the look does not change.
                XcTheme(
                    isDark = darkMode,
                    isAmoled = appSettings.colorMode.isAmoled,
                    theme = uiState.theme,
                    componentAlpha = if (uiState.componentTransparent) 0f else 1f,
                ) {
                    KernelSUTheme(appSettings = appSettings, uiMode = uiMode) {
                        IntentDispatcher(intentChannel = intentChannel)
                        val swipeDismiss = if (uiState.enableSwipeDismiss) {
                            if (LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                                NavSwipeDirection.RightToLeft
                            } else {
                                NavSwipeDirection.LeftToRight
                            }
                        } else {
                            NavSwipeDirection.None
                        }
                        val mainScreenEntry = @Composable {
                            MainScreen(
                                initialPage = selectedMainPage,
                                pagerInterceptionMode = uiState.pagerInterceptionMode,
                                onPageChanged = viewModel::setSelectedMainPage,
                            )
                        }

                        val navDisplay = @Composable {
                            KernelSuNavHost(
                                navigator = navigator,
                                swipeDismiss = swipeDismiss,
                                mainScreenEntry = mainScreenEntry,
                            )
                        }

                        when (uiMode) {
                            UiMode.Material -> androidx.compose.material3.Scaffold(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ) { navDisplay() }

                            UiMode.Miuix -> Scaffold { navDisplay() }
                        }
                        SideEffect { contentReady = true }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentChannel.trySend(intent)
    }
}
