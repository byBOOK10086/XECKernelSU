package me.weishu.kernelsu.ui

/**
 * The entry that stays enabled when the launcher icon is hidden.
 *
 * Hiding the icon means disabling every component that answers MAIN/LAUNCHER - including
 * [MainActivity]. A disabled component cannot be started, not even with an explicit
 * `am start -n`, so pointing the "way back in" at the launcher component would be a one-way
 * door. This activity therefore has no launcher filter, is never disabled by
 * [me.weishu.kernelsu.privilege.StealthEntrance], and simply *is* the manager (it extends
 * [MainActivity]) so the quick-settings tile and the adb fallback both land on the real UI.
 *
 * It carries the same VIEW filters as [MainActivity] on purpose: while the icon is hidden the
 * main activity is disabled, and opening a module zip from a file manager has to keep working.
 */
class StealthLaunchActivity : MainActivity()
