package me.weishu.kernelsu.ui.navigation3

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.modulerepo.RepoModuleArg
import me.weishu.kernelsu.ui.screen.scriptrun.ScriptRunArg
import me.weishu.kernelsu.ui.util.FlashItSerializer
import me.weishu.kernelsu.ui.util.RepoModuleArgSerializer
import me.weishu.kernelsu.ui.util.ScriptRunArgSerializer
import me.weishu.kernelsu.ui.util.TemplateInfoSerializer
import me.weishu.kernelsu.ui.viewmodel.TemplateViewModel
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Type-safe navigation keys for miuix-nav.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
@Serializable
sealed interface Route : NavKey, Parcelable {
    @Parcelize
    @Serializable
    data object Main : Route

    @Parcelize
    @Serializable
    data object Home : Route

    @Parcelize
    @Serializable
    data object SuperUser : Route

    @Parcelize
    @Serializable
    data object Module : Route

    @Parcelize
    @Serializable
    data object Settings : Route

    @Parcelize
    @Serializable
    data object About : Route

    @Parcelize
    @Serializable
    data object Sulog : Route

    @Parcelize
    @Serializable
    data object ColorPalette : Route

    @Parcelize
    @Serializable
    data object AppProfileTemplate : Route

    @Parcelize
    @Serializable
    data class TemplateEditor(
        @Serializable(with = TemplateInfoSerializer::class) val template: TemplateViewModel.TemplateInfo,
        val readOnly: Boolean
    ) : Route

    @Parcelize
    @Serializable
    data class AppProfile(val uid: Int) : Route

    @Parcelize
    @Serializable
    data object Install : Route

    @Parcelize
    @Serializable
    data class ModuleRepoDetail(@Serializable(with = RepoModuleArgSerializer::class) val module: RepoModuleArg) : Route

    @Parcelize
    @Serializable
    data object ModuleRepo : Route

    @Parcelize
    @Serializable
    data class Flash(@Serializable(with = FlashItSerializer::class) val flashIt: FlashIt) : Route

    @Parcelize
    @Serializable
    data class ExecuteModuleAction(val moduleId: String, val fromShortcut: Boolean = false) : Route

    @Parcelize
    @Serializable
    data class ScriptRun(@Serializable(with = ScriptRunArgSerializer::class) val arg: ScriptRunArg) : Route

    /**
     * Themes: store / mine / editor in one screen.
     *
     * A plain object rather than a parameterised route: a theme package can carry background
     * images, and navigation arguments are marshalled through `rememberSaveable` (Binder, ~1MB
     * when the process dies). The screen reads what it needs from
     * [me.weishu.kernelsu.data.theme.ThemeRepository] by id instead.
     */
    @Parcelize
    @Serializable
    data object Themes : Route

    /** Custom background: one light and one dark image in the app's private directory. */
    @Parcelize
    @Serializable
    data object Wallpaper : Route

    /** Terminal plus the file-manager tab, reached from the toolbox. */
    @Parcelize
    @Serializable
    data object Terminal : Route

    /** Toolbox: terminal / external modules (and later the stealth entrance) in one place. */
    @Parcelize
    @Serializable
    data object Tools : Route

    /**
     * Optional add-ons: the TEE engine and TA enhancement built by this project, plus a
     * link-out to ReZygisk (third-party, AGPL-3.0-or-later, never bundled).
     */
    @Parcelize
    @Serializable
    data object ExternalModules : Route

    /** Remote module assistant: generate, sign and fetch modules through our own server. */
    @Parcelize
    @Serializable
    data object RemoteAssistant : Route

    /** Hiding the launcher icon, including the way back in (tile / adb). */
    @Parcelize
    @Serializable
    data object Stealth : Route
}
