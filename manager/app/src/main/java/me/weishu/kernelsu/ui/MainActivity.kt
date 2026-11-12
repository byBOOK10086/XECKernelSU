package me.weishu.kernelsu.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.togetherWith
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.bottombar.BottomBar
import me.weishu.kernelsu.ui.component.bottombar.MainPagerState
import me.weishu.kernelsu.ui.component.bottombar.NavigationBadgeState
import me.weishu.kernelsu.ui.component.bottombar.SideRail
import me.weishu.kernelsu.ui.component.bottombar.rememberMainPagerState
import me.weishu.kernelsu.ui.component.bottombar.useNavigationRail
import me.weishu.kernelsu.ui.component.dialog.LocalXDialogBackdrop
import me.weishu.kernelsu.ui.component.dialog.LocalXDialogHost
import me.weishu.kernelsu.ui.component.dialog.XDialogHost
import me.weishu.kernelsu.ui.component.dialog.XDialogHostState
import me.weishu.kernelsu.ui.design.clear.LocalWallpaperBackdrop
import me.weishu.kernelsu.ui.design.liquid.XDropletHost
import me.weishu.kernelsu.ui.design.panel.Xc8Dock
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcTheme
import me.weishu.kernelsu.ui.design.token.xcPageEnter
import me.weishu.kernelsu.ui.design.token.xcPageExit
import me.weishu.kernelsu.ui.navigation3.IntentDispatcher
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.navigation3.rememberNavigator
import me.weishu.kernelsu.ui.screen.about.AboutScreen
import me.weishu.kernelsu.ui.screen.appprofile.AppProfileScreen
import me.weishu.kernelsu.ui.screen.colorpalette.ColorPaletteScreen
import me.weishu.kernelsu.ui.screen.executemoduleaction.ExecuteModuleActionScreen
import me.weishu.kernelsu.ui.screen.extmodules.ExternalModulesScreen
import me.weishu.kernelsu.ui.screen.flash.FlashScreen
import me.weishu.kernelsu.ui.screen.detect.DetectPager
import me.weishu.kernelsu.ui.screen.home.HomePager
import me.weishu.kernelsu.ui.screen.remote.RemoteAssistantScreen
import me.weishu.kernelsu.ui.screen.terminal.TerminalScreen
import me.weishu.kernelsu.ui.screen.tools.ToolsScreen
import me.weishu.kernelsu.ui.screen.theme.ThemeScreen
import me.weishu.kernelsu.ui.screen.install.InstallScreen
import me.weishu.kernelsu.ui.screen.module.ModuleActionSheet
import me.weishu.kernelsu.ui.screen.module.ModulePager
import me.weishu.kernelsu.ui.screen.module.ModuleSheetState
import me.weishu.kernelsu.ui.screen.modulerepo.ModuleRepoDetailScreen
import me.weishu.kernelsu.ui.screen.modulerepo.ModuleRepoScreen
import me.weishu.kernelsu.ui.screen.settings.SettingPager
import me.weishu.kernelsu.ui.screen.sulog.SulogScreen
import me.weishu.kernelsu.ui.screen.superuser.SuperUserPager
import me.weishu.kernelsu.ui.screen.template.AppProfileTemplateScreen
import me.weishu.kernelsu.ui.screen.templateeditor.TemplateEditorScreen
import me.weishu.kernelsu.ui.screen.wallpaper.WallpaperScreen
import me.weishu.kernelsu.ui.theme.KernelSUTheme
import me.weishu.kernelsu.ui.theme.isInDarkTheme

import kotlin.random.Random
import me.weishu.kernelsu.ui.theme.LocalColorMode
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.theme.LocalEnableFloatingBottomBar
import me.weishu.kernelsu.ui.theme.LocalEnableFloatingBottomBarBlur
import me.weishu.kernelsu.ui.theme.LocalEnableNavigationBadge
import me.weishu.kernelsu.ui.util.getSuperuserCount
import me.weishu.kernelsu.ui.util.install
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.util.rememberContentReady
import me.weishu.kernelsu.ui.util.WallpaperStore
import me.weishu.kernelsu.ui.viewmodel.MainActivityViewModel
import me.weishu.kernelsu.ui.viewmodel.MainPagerConfig
import me.weishu.kernelsu.ui.viewmodel.ModuleViewModel
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val WALLPAPER_COUNT = 4


private val BG_LIGHT_DRAWABLES = intArrayOf(
    R.drawable.bg_light_1,
    R.drawable.bg_light_2,
    R.drawable.bg_light_3,
    R.drawable.bg_light_4,
)

private val BG_DARK_DRAWABLES = intArrayOf(
    R.drawable.bg_dark_1,
    R.drawable.bg_dark_2,
    R.drawable.bg_dark_3,
    R.drawable.bg_dark_4,
)

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
        me.weishu.kernelsu.privilege.StealthEntrance.repair(this)
        val appContext = applicationContext
        splashScreen.setKeepOnScreenCondition {
            !contentReady || SystemClock.uptimeMillis() - splashStartedAt < splashAnimationDurationMs
        }

        val isManager = Natives.isManager
        if (isManager && Natives.kernelUAPIVersion == Natives.managerUAPIVersion) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    install()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.e("KernelSU", "Manager install failed", error)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(applicationContext,
                            "${getString(me.weishu.kernelsu.R.string.permanent_failed)} ${error.message ?: error.javaClass.simpleName}",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        if (savedInstanceState == null) intent?.let { intentChannel.trySend(it) }

        setContent {
            var showBootSplash by rememberSaveable { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                if (showBootSplash) {
                    delay(900)
                    showBootSplash = false
                }
            }
            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
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
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                LocalEnableBlur provides false,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
            ) {
                XcTheme(
                    isDark = isInDarkTheme(),
                    isAmoled = appSettings.colorMode.isAmoled,
                    theme = uiState.theme,
                    componentAlpha = if (uiState.componentTransparent) 0f else 1f,
                ) {
                    val windowBackdrop = Xc.colors.backdrop
                    SideEffect {
                        window.setBackgroundDrawable(ColorDrawable(windowBackdrop.toArgb()))
                    }
                    KernelSUTheme(appSettings = appSettings) {
                        val dialogBackdrop = rememberBlurBackdrop(false)
                        val wallpaperIndex = rememberSaveable {
                            mutableIntStateOf(Random.nextInt(WALLPAPER_COUNT))
                        }
                        val wallpaperDark = isInDarkTheme()
                        val wallpaperRes = remember(wallpaperIndex.intValue, wallpaperDark) {
                            if (wallpaperDark) {
                                BG_DARK_DRAWABLES[wallpaperIndex.intValue]
                            } else {
                                BG_LIGHT_DRAWABLES[wallpaperIndex.intValue]
                            }
                        }
                        val customWallpaperName = if (wallpaperDark) {
                            uiState.wallpaperDark
                        } else {
                            uiState.wallpaperLight
                        }
                        val solidWallpaper = remember(customWallpaperName) {
                            WallpaperStore.solidArgb(customWallpaperName)?.let { Color(it) }
                        }
                        val wallpaperEdge = WallpaperStore.displayEdgeFor(uiState.wallpaperBlur)
                        val customWallpaper by produceState<ImageBitmap?>(
                            initialValue = null,
                            key1 = customWallpaperName,
                            key2 = wallpaperEdge,
                        ) {
                            value = if (customWallpaperName.isEmpty() || WallpaperStore.isSolid(customWallpaperName)) {
                                null
                            } else {
                                withContext(Dispatchers.IO) {
                                    WallpaperStore.decode(
                                        WallpaperStore.fileOf(appContext, customWallpaperName),
                                        wallpaperEdge,
                                    )?.asImageBitmap()
                                }
                            }
                        }
                        val builtinWallpaper by produceState<ImageBitmap?>(
                            initialValue = null,
                            key1 = wallpaperRes,
                            key2 = wallpaperEdge,
                        ) {
                            value = if (wallpaperEdge >= WallpaperStore.DISPLAY_MAX_EDGE) {
                                null
                            } else {
                                withContext(Dispatchers.IO) {
                                    WallpaperStore.decodeResource(
                                        appContext,
                                        wallpaperRes,
                                        wallpaperEdge,
                                    )?.asImageBitmap()
                                }
                            }
                        }
                        val wallpaperBackdrop = rememberBlurBackdrop(false)
                        val dialogHostState = remember { XDialogHostState() }

                        CompositionLocalProvider(
                            LocalXDialogHost provides dialogHostState,
                            LocalXDialogBackdrop provides null,
                            LocalWallpaperBackdrop provides null,
                        ) {
                            XDropletHost {
                                IntentDispatcher(intentChannel = intentChannel)
                                val mainScreenEntry = @Composable {
                                    MainScreen(
                                        initialPage = selectedMainPage,
                                        onPageChanged = viewModel::setSelectedMainPage,
                                    )
                                }

                                val navDisplay = @Composable {
                                    val pageEnter = xcPageEnter()
                                    val pageExit = xcPageExit()
                                    NavDisplay(
                                        backStack = navigator.backStack,
                                        transitionSpec = { pageEnter togetherWith pageExit },
                                        popTransitionSpec = { pageEnter togetherWith pageExit },
                                        transitionEffects = NavDisplayTransitionEffects(
                                            enableCornerClip = false,
                                            dimAmount = 0f,
                                            blockInputDuringTransition = true,
                                        ),
                                        entryDecorators = listOf(
                                            rememberSaveableStateHolderNavEntryDecorator(),
                                            rememberViewModelStoreNavEntryDecorator()
                                        ),
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
                                        },
                                        entryProvider = entryProvider {
                                            entry<Route.Main> { mainScreenEntry() }
                                            entry<Route.About> { AboutScreen() }
                                            entry<Route.Sulog> { SulogScreen() }
                                            entry<Route.ColorPalette> { ColorPaletteScreen() }
                                            entry<Route.Wallpaper> { WallpaperScreen() }
                entry<Route.Themes> { ThemeScreen() }
                                            entry<Route.AppProfileTemplate> { AppProfileTemplateScreen() }
                                            entry<Route.TemplateEditor> { key -> TemplateEditorScreen(key.template, key.readOnly) }
                                            entry<Route.AppProfile> { key -> AppProfileScreen(key.uid) }
                                            entry<Route.ModuleRepo> { ModuleRepoScreen() }
                                            entry<Route.ModuleRepoDetail> { key -> ModuleRepoDetailScreen(key.module) }
                                            entry<Route.Install> { InstallScreen() }
                                            entry<Route.Flash> { key -> FlashScreen(key.flashIt) }
                                            entry<Route.ExecuteModuleAction> { key -> ExecuteModuleActionScreen(key.moduleId, key.fromShortcut) }
                                            entry<Route.RemoteAssistant> { RemoteAssistantScreen() }
                                            entry<Route.Terminal> { TerminalScreen() }
                                            entry<Route.Tools> { ToolsScreen() }
                                            entry<Route.ExternalModules> { ExternalModulesScreen() }
                                            entry<Route.Home> { mainScreenEntry() }
                                            entry<Route.SuperUser> { mainScreenEntry() }
                                            entry<Route.Module> { mainScreenEntry() }
                                            entry<Route.Settings> { mainScreenEntry() }
                                        }
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (dialogBackdrop != null) {
                                                Modifier.layerBackdrop(dialogBackdrop)
                                            } else {
                                                Modifier
                                            }
                                        ),
                                ) {
                                    val wallpaperModifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (wallpaperBackdrop != null) {
                                                Modifier.layerBackdrop(wallpaperBackdrop)
                                            } else {
                                                Modifier
                                            }
                                        )
                                    val currentSolid = solidWallpaper
                                    val customWallpaperBitmap = customWallpaper
                                    val builtinWallpaperBitmap = builtinWallpaper
                                    when {
                                        currentSolid != null -> Box(
                                            modifier = wallpaperModifier.background(currentSolid)
                                        )

                                        customWallpaperBitmap != null -> Image(
                                            bitmap = customWallpaperBitmap,
                                            contentDescription = null,
                                            modifier = wallpaperModifier,
                                            contentScale = ContentScale.Crop,
                                        )

                                        builtinWallpaperBitmap != null -> Image(
                                            bitmap = builtinWallpaperBitmap,
                                            contentDescription = null,
                                            modifier = wallpaperModifier,
                                            contentScale = ContentScale.Crop,
                                        )

                                        else -> Image(
                                            painter = painterResource(wallpaperRes),
                                            contentDescription = null,
                                            modifier = wallpaperModifier,
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Xc.colors.backdrop),
                                    )
                                    Scaffold(containerColor = Xc.colors.backdrop) {
                                        navDisplay()
                                    }
                                }

                                ModuleActionSheet(
                                    module = ModuleSheetState.module,
                                    updateUrl = ModuleSheetState.updateUrl,
                                    backdrop = null,
                                    bottomInnerPadding = bottomBarClearPadding(),
                                    onDismissRequest = { ModuleSheetState.hide() },
                                    onExecuteAction = {
                                        ModuleSheetState.onExecuteAction?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onOpenWebUi = {
                                        ModuleSheetState.onOpenWebUi?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onUpdate = {
                                        ModuleSheetState.onUpdate?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onUninstall = {
                                        ModuleSheetState.onUninstall?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onUndoUninstall = {
                                        ModuleSheetState.onUndoUninstall?.invoke()
                                        ModuleSheetState.hide()
                                    },
                                    onAddActionShortcut = { type ->
                                        ModuleSheetState.onAddActionShortcut?.invoke(type)
                                    },
                                )
                                XDialogHost(
                                    state = dialogHostState,
                                    backdrop = null,
                                )
                                XecBootSplash(visible = showBootSplash)
                                SideEffect { contentReady = true }
                            }
                        }
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

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    val navController = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val useNavigationRail = useNavigationRail(enableFloatingBottomBar)
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, MainPagerConfig.LAST_PAGE_INDEX),
        pageCount = { MainPagerConfig.PAGE_COUNT },
    )
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = !useNavigationRail,
    )
    val isFullFeatured = Natives.isFullFeatured()
    var userScrollEnabled by remember(isFullFeatured) { mutableStateOf(isFullFeatured) }

    val enableNavigationBadge = LocalEnableNavigationBadge.current
    val badgeEnabled = enableNavigationBadge && isFullFeatured
    val moduleViewModel = viewModel<ModuleViewModel>()
    val moduleUiState by moduleViewModel.uiState.collectAsStateWithLifecycle()

    val superUserViewModel = viewModel<SuperUserViewModel>()
    val grantedUidCount by remember(superUserViewModel) {
        superUserViewModel.uiState
            .map { state -> state.groupedApps.count { it.anyAllowSu } }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(0)

    var startupPreloadStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isFullFeatured) {
        if (!isFullFeatured || startupPreloadStarted) {
            return@LaunchedEffect
        }

        moduleViewModel.initializePreferences()
        val moduleState = moduleViewModel.uiState.value
        if (!moduleState.hasLoaded) {
            if (!moduleState.isRefreshing) moduleViewModel.fetchModuleList()
            moduleViewModel.uiState.first { it.hasLoaded }
        }
        moduleViewModel.syncModuleUpdateInfo(moduleViewModel.uiState.value.modules)

        val superUserState = superUserViewModel.uiState.value
        if (!superUserState.hasLoaded) {
            superUserViewModel.initializePreferences()
            if (superUserState.isRefreshing) {
                superUserViewModel.uiState.first { it.hasLoaded }
            } else {
                superUserViewModel.loadAppList().join()
            }
        }

        startupPreloadStarted = true
    }

    var superuserCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(badgeEnabled, grantedUidCount) {
        superuserCount = if (badgeEnabled) withContext(Dispatchers.IO) { getSuperuserCount() } else 0
    }

    val navigationBadge = if (badgeEnabled) {
        NavigationBadgeState(
            superuserCount = superuserCount,
            moduleEnabledCount = moduleUiState.modules.count { it.enabled },
            moduleUpdatableCount = moduleUiState.updateInfo.count { it.value.downloadUrl.isNotBlank() },
        )
    } else {
        NavigationBadgeState()
    }
    val surfaceColor = MiuixTheme.colorScheme.surface.copy(alpha = 1f)
    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(settledPage) {
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    var dockHeightPx by remember { mutableIntStateOf(0) }


    MainScreenBackHandler(mainPagerState, navController)

    LaunchedEffect(mainPagerState) {
        MainPagerBus.pending.collect { page ->
            if (page != null) {
                mainPagerState.animateToPage(page)
                MainPagerBus.consume()
            }
        }
    }

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        val contentReady = rememberContentReady()
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                    HorizontalPager(
                        modifier = Modifier
                            .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                        state = mainPagerState.pagerState,
                        beyondViewportPageCount = if (contentReady) 3 else 0,
                        overscrollEffect = null,
                        userScrollEnabled = userScrollEnabled && !mainPagerState.isGestureLocked,
                    ) { page ->
                    val isCurrentPage = page == settledPage
                    when (page) {
                        0 -> if (contentReady || isCurrentPage) HomePager(navController, bottomInnerPadding, isCurrentPage)
                        1 -> if (contentReady || isCurrentPage) SuperUserPager(navController, bottomInnerPadding, isCurrentPage)
                        2 -> if (contentReady || isCurrentPage) ModulePager(bottomInnerPadding, isCurrentPage)
                        3 -> if (contentReady || isCurrentPage) DetectPager(bottomInnerPadding, isCurrentPage)
                        4 -> if (contentReady || isCurrentPage) SettingPager(navController, bottomInnerPadding, isCurrentPage)
                    }
                }
            }
        }

        if (useNavigationRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            Scaffold(containerColor = Color.Transparent) { _ ->
                Row {
                    SideRail(navigationBadge)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .consumeWindowInsets(startInsets)
                    ) {
                        pagerContent(navBarBottomPadding)
                    }
                }
            }
        } else {
            val bottomBar = @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Xc8Dock(
                        selectedPage = mainPagerState.selectedPage,
                        onSelectPage = { page -> mainPagerState.animateToPage(page) },
                        moduleBadge = navigationBadge.moduleUpdatableCount
                            .takeIf { it > 0 } ?: navigationBadge.moduleEnabledCount,
                        superuserCount = navigationBadge.superuserCount,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { dockHeightPx = it.height },
                    )
                }
            }

            Scaffold(
                bottomBar = bottomBar,
                containerColor = Color.Transparent,
            ) { innerPadding ->
                val measured = with(LocalDensity.current) { dockHeightPx.toDp() }
                pagerContent(maxOf(innerPadding.calculateBottomPadding(), measured))
            }
        }
    }
}

@Composable
private fun bottomBarClearPadding(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 112.dp

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}
