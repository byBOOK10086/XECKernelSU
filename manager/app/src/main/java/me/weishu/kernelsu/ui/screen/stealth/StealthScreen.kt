package me.weishu.kernelsu.ui.screen.stealth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.CopyAll
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.privilege.StealthEntrance
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Hiding the launcher icon, with the way back in spelled out before the user can lose it.
 *
 * The order of the screen is the order of the mistake the old implementation made: state first
 * (what the device actually reports), then the entry (add the tile, copy the adb line), and only
 * then the hide button. Nothing here talks to ksud or a shell - component enablement is a plain
 * package-manager write and needs no root.
 */
@Composable
fun StealthScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val layoutDirection = LocalLayoutDirection.current
    val confirm = rememberConfirmDialog()

    var state by remember { mutableStateOf(StealthEntrance.repair(context)) }
    var busy by remember { mutableStateOf(false) }

    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    fun refresh() {
        state = StealthEntrance.state(context)
    }

    fun run(action: () -> Result<Unit>, successText: String) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { action() } }
            busy = false
            refresh()
            val failure = result.exceptionOrNull() ?: result.getOrNull()?.exceptionOrNull()
            say(failure?.message ?: successText)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.stealth_title),
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
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    StateRow(
                        title = stringResource(R.string.stealth_state_title),
                        value = stringResource(
                            if (state.iconHidden) R.string.stealth_state_hidden else R.string.stealth_state_visible
                        ),
                    )
                    StateRow(
                        title = stringResource(R.string.stealth_entry_title),
                        value = stringResource(
                            if (state.entryEnabled) R.string.stealth_entry_ok else R.string.stealth_entry_broken
                        ),
                    )
                    val error = state.lastError
                    if (error != null) {
                        Text(
                            text = error,
                            fontSize = 13.sp,
                            color = colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (state.iconHidden != state.expectedHidden) {
                        // Should be impossible after repair(), so if it ever shows up it means
                        // something outside the app rewrote our components.
                        Text(
                            text = stringResource(R.string.stealth_state_drift),
                            fontSize = 13.sp,
                            color = colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.stealth_entry_section),
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // No public API exists to add a tile for the user (android-37 has no
                    // TileService.requestAddTileService), so the instructions are text and the
                    // only one-tap action here is copying the adb command.
                    Text(
                        text = stringResource(R.string.stealth_tile_manual),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    ArrowPreference(
                        title = stringResource(R.string.stealth_adb_title),
                        summary = StealthEntrance.adbFallback(context),
                        startAction = {
                            Icon(
                                Icons.Rounded.Adb,
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        },
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("adb", StealthEntrance.adbFallback(context)))
                            say(context.getString(R.string.stealth_adb_copied))
                        },
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.stealth_hide_action),
                        summary = stringResource(R.string.stealth_hide_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        },
                        enabled = !busy && !state.iconHidden,
                        onClick = {
                            scope.launch {
                                val answer = confirm.awaitConfirm(
                                    title = context.getString(R.string.stealth_hide_action),
                                    content = context.getString(
                                        R.string.stealth_confirm_body,
                                        StealthEntrance.adbFallback(context),
                                    ),
                                    confirm = context.getString(R.string.stealth_hide_action),
                                    dismiss = context.getString(R.string.stealth_restore_action),
                                )
                                if (answer == ConfirmResult.Confirmed) {
                                    run({ StealthEntrance.hide(context) }, context.getString(R.string.stealth_hidden_done))
                                }
                            }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.stealth_restore_action),
                        summary = stringResource(R.string.stealth_restore_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.CopyAll,
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        },
                        enabled = !busy && state.iconHidden,
                        onClick = {
                            run({ StealthEntrance.restore(context) }, context.getString(R.string.stealth_restored_done))
                        },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.stealth_note),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StateRow(title: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = title, fontSize = 15.sp, color = colorScheme.onBackground)
        Text(text = value, fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
    }
}
