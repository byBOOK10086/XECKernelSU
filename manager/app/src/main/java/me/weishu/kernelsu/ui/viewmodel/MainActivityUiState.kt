package me.weishu.kernelsu.ui.viewmodel

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.design.token.XcThemeData
import me.weishu.kernelsu.ui.theme.AppSettings

@Immutable
data class MainActivityUiState(
    val appSettings: AppSettings,
    val pageScale: Float,
    val enableBlur: Boolean,
    val enableFloatingBottomBar: Boolean,
    val enableFloatingBottomBarBlur: Boolean,
    val enableNavigationBadge: Boolean,
    val enableSwipeDismiss: Boolean,
    val pagerInterceptionMode: Int,
    val moduleDescriptionMaxLines: Int = 4,
    val uiMode: UiMode,
    /**
     * The theme the user applied, already resolved from disk (`null` = the built-in look).
     *
     * Resolved here rather than in the composition so a theme change arrives through the same
     * SharedPreferences listener that drives every other appearance setting.
     */
    val theme: XcThemeData? = null,
    /** "Component transparency": multiplies the theme's surface alpha (0f = fully transparent). */
    val componentTransparent: Boolean = false,
)
