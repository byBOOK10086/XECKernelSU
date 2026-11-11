package me.weishu.kernelsu.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import me.weishu.kernelsu.R
import me.weishu.kernelsu.privilege.StealthEntrance

/**
 * The way back in once the launcher icon is hidden.
 *
 * The old implementation relied on the system settings app ("tap the build number ten times"),
 * which only worked because a daemon side hook rewrote that gesture. That hook lived in the
 * userspace layer this tree dropped, so the entry has to be something a normal app can actually
 * provide: a quick-settings tile. It is added by the user from the quick-settings edit panel
 * (the stealing screen offers the system dialog on Android 13+), survives reboots, and does not
 * put anything in the app drawer.
 *
 * The tile is deliberately usable while the icon is *visible* too - a toggle that only appears
 * in one state is a trap.
 */
class StealthTileService : TileService() {

    override fun onStartListening() {
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        // unlockAndRun shows the lock screen first when the device is locked, then opens the
        // manager on the next unlock instead of silently failing.
        unlockAndRun { openManager() }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val state = StealthEntrance.state(this)
        tile.state = if (state.iconHidden) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.stealth_tile_label)
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.updateTile()
    }

    private fun openManager() {
        // While hidden MainActivity is disabled and cannot be started; the always-on entry is
        // the same UI under a different name.
        val hidden = StealthEntrance.state(this).iconHidden
        val target = if (hidden) StealthLaunchActivity::class.java else MainActivity::class.java
        val intent = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    companion object {
        fun component(context: android.content.Context): ComponentName =
            ComponentName(context, StealthTileService::class.java)
    }
}
