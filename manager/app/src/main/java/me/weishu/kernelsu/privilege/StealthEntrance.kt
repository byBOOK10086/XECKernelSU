package me.weishu.kernelsu.privilege

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.ui.MainActivity
import me.weishu.kernelsu.ui.StealthLaunchActivity
import me.weishu.kernelsu.ui.StealthTileService

/**
 * Hiding the launcher icon, rewritten so that the state on disk is never the truth.
 *
 * The previous implementation had six defects worth naming, because each one drove a design
 * decision here:
 *
 * 1. it shelled out to `ksud stealth start --component …` - a daemon that no longer exists;
 * 2. it needed `getKsuDaemonPath()`, which lives in the removed userspace layer;
 * 3. it disabled *every* component that answered MAIN/LAUNCHER, including the one it then told
 *    the user to start (a disabled component cannot be started, even explicitly);
 * 4. "restore" only existed inside the same screen that became unreachable once hidden;
 * 5. the truth was a boolean in SharedPreferences, so a state that drifted on disk still read
 *    back as "hidden";
 * 6. failures were swallowed: the shell result was checked, the component writes were not.
 *
 * This version therefore:
 *
 * * writes components through [PackageManager] only - no root, no daemon, no shell;
 * * keeps [StealthLaunchActivity] permanently enabled and *never* touches it, so there is
 *   always an entry that `adb shell am start` can reach;
 * * derives the truth from [PackageManager] on every read ([State.iconHidden]), and treats the
 *   stored preference as an expectation that gets corrected by reality, not the other way;
 * * verifies every write by reading it back, and rolls the whole operation back when a
 *   verification step fails, so "hidden" and "half hidden" cannot be confused.
 */
object StealthEntrance {

    private const val PREFS = "stealth_entrance"
    private const val KEY_SAVED_LAUNCHERS = "saved_launchers"
    private const val KEY_EXPECTED_HIDDEN = "expected_hidden"
    private const val KEY_LAST_ERROR = "last_error"

    /**
     * What the device looks like right now.
     *
     * [iconHidden] and [entryEnabled] are read from the package manager, [expectedHidden] is
     * what the last operation asked for. A mismatch means something else changed our components
     * (a restore from a backup, another manager, a factory reset of the setting) and
     * [repair] has already re-synchronised it.
     */
    @Immutable
    data class State(
        val iconHidden: Boolean,
        val entryEnabled: Boolean,
        val expectedHidden: Boolean,
        val lastError: String?,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mainComponent(context: Context) = ComponentName(context, MainActivity::class.java)

    /** The entry that always stays enabled; opening it opens the manager itself. */
    fun entryComponent(context: Context) = ComponentName(context, StealthLaunchActivity::class.java)

    fun tileComponent(context: Context) = ComponentName(context, StealthTileService::class.java)

    /**
     * Components of this package that currently put an icon in the launcher.
     *
     * Disabled components are not returned, which is exactly why this cannot be used to restore:
     * the list is recorded *before* hiding and kept in [KEY_SAVED_LAUNCHERS] for that.
     */
    private fun launcherComponents(context: Context): List<ComponentName> {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.let { info -> ComponentName(info.packageName, info.name) } }
            .distinct()
    }

    fun isEnabled(context: Context, component: ComponentName): Boolean =
        context.packageManager.getComponentEnabledSetting(component) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /**
     * Enable or disable one of *our own* components.
     *
     * `DONT_KILL_APP` matters: the caller is usually the app itself and killing it mid-way would
     * leave the operation half applied with nobody left to roll it back.
     */
    private fun setEnabled(context: Context, component: ComponentName, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun canStart(context: Context, component: ComponentName): Boolean =
        isEnabled(context, component) &&
            runCatching { context.packageManager.getActivityInfo(component, 0) }.isSuccess

    private fun savedLaunchers(context: Context): List<ComponentName> {
        val raw = prefs(context).getString(KEY_SAVED_LAUNCHERS, null) ?: return emptyList()
        return raw.split('\n')
            .mapNotNull { it.trim().takeIf { name -> name.isNotEmpty() } }
            .map { ComponentName(context.packageName, it) }
    }

    fun state(context: Context): State = State(
        iconHidden = launcherComponents(context).isEmpty(),
        entryEnabled = canStart(context, entryComponent(context)),
        expectedHidden = prefs(context).getBoolean(KEY_EXPECTED_HIDDEN, false),
        lastError = prefs(context).getString(KEY_LAST_ERROR, null),
    )

    /**
     * Reconcile stored expectation with reality and make sure the always-on entry is usable.
     *
     * Called from the manager's own start-up path and from the settings screen. Reality wins:
     * if a launcher component is visible again (user restored a backup, another manager
     * re-enabled us, a system update re-declared the component) then the stored flag is
     * corrected instead of the UI claiming "hidden" while an icon sits in the drawer.
     */
    fun repair(context: Context): State {
        if (!canStart(context, entryComponent(context))) {
            // The entry is the only way back in; leaving it disabled would be a one-way door.
            runCatching { setEnabled(context, entryComponent(context), true) }
        }
        val hidden = launcherComponents(context).isEmpty()
        prefs(context).edit().putBoolean(KEY_EXPECTED_HIDDEN, hidden).apply()
        return state(context)
    }

    /** @return success only when the icon is verifiably gone and the entry is verifiably usable. */
    fun hide(context: Context): Result<Unit> {
        val entry = entryComponent(context)
        if (!canStart(context, entry)) {
            runCatching { setEnabled(context, entry, true) }
            if (!canStart(context, entry)) {
                return fail(context, "入口组件无法启用，已放弃隐藏（否则无法再次打开）")
            }
        }
        val launchers = launcherComponents(context)
        if (launchers.isEmpty()) {
            // Already hidden (or never visible): nothing to disable, but the expectation has to
            // say so, otherwise the screen shows a warning forever.
            prefs(context).edit()
                .putBoolean(KEY_EXPECTED_HIDDEN, true)
                .remove(KEY_LAST_ERROR)
                .apply()
            return Result.success(Unit)
        }

        prefs(context).edit()
            .putString(KEY_SAVED_LAUNCHERS, launchers.joinToString("\n") { it.className })
            .apply()

        val touched = launchers + mainComponent(context)
        touched.forEach { runCatching { setEnabled(context, it, false) } }

        val stillVisible = launcherComponents(context)
        if (stillVisible.isNotEmpty()) {
            rollback(context, touched)
            return fail(
                context,
                "仍有桌面组件可见：${stillVisible.joinToString { it.className }}，已全部恢复",
            )
        }
        if (!canStart(context, entry)) {
            rollback(context, touched)
            return fail(context, "隐藏后入口不可启动，已全部恢复")
        }
        prefs(context).edit()
            .putBoolean(KEY_EXPECTED_HIDDEN, true)
            .remove(KEY_LAST_ERROR)
            .apply()
        return Result.success(Unit)
    }

    /** Put the icon back. Works from the always-on entry as well as from a visible manager. */
    fun restore(context: Context): Result<Unit> {
        val targets = (savedLaunchers(context) + mainComponent(context)).distinct()
        targets.forEach { runCatching { setEnabled(context, it, true) } }
        // The entry stays enabled on purpose: it is harmless while visible and is still the
        // fallback if the icon write above failed on a locked-down ROM.
        runCatching { setEnabled(context, entryComponent(context), true) }

        if (launcherComponents(context).isEmpty()) {
            return fail(context, "已尝试恢复，但系统仍没有报告桌面图标；请重启一次管理器再试")
        }
        prefs(context).edit()
            .putBoolean(KEY_EXPECTED_HIDDEN, false)
            .remove(KEY_SAVED_LAUNCHERS)
            .remove(KEY_LAST_ERROR)
            .apply()
        return Result.success(Unit)
    }

    private fun rollback(context: Context, components: List<ComponentName>) {
        components.forEach { runCatching { setEnabled(context, it, true) } }
        prefs(context).edit().putBoolean(KEY_EXPECTED_HIDDEN, false).apply()
    }

    private fun fail(context: Context, message: String): Result<Unit> {
        prefs(context).edit().putString(KEY_LAST_ERROR, message).apply()
        return Result.failure(IllegalStateException(message))
    }

    fun clearError(context: Context) {
        prefs(context).edit().remove(KEY_LAST_ERROR).apply()
    }

    /**
     * `am start` line for the confirmation dialog: typing this into a computer terminal is the
     * last-resort way back when the tile was not added and the icon is gone.
     */
    fun adbFallback(context: Context): String {
        val entry = entryComponent(context)
        return "adb shell am start -n ${entry.packageName}/${entry.className}"
    }
}
