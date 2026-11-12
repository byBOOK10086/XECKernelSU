package me.weishu.kernelsu.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope

/**
 * Upper bound for waiting on the navigation transition. A transition that never reports
 * "finished" (nothing ever started one, or the scope is created in a running state) used to
 * keep [rememberContentReady] false forever, and the pages behind it are gated on it: the
 * window then shows a bottom bar over an empty pager, i.e. a blank screen. Measured on
 * Xiaomi 17 / Android 16: the manager rendered 6 nodes and zero text with the app's own logs
 * showing KsuCli EACCES instead of any crash.
 */
private const val NAV_TRANSITION_FALLBACK_MS = 400L

/**
 * Returns true only after the navigation transition animation has completed
 * and an additional buffer frame has passed.
 *
 * Timeline:
 * - During animation: returns false → page shows lightweight placeholder (smooth animation)
 * - Animation ends + 1 frame: returns true → heavy content composes
 *   (stutter is invisible because the page is already static)
 *
 * The value is sticky — once true it never reverts to false,
 * so content stays visible during exit transitions.
 */
@Composable
fun rememberContentReady(): Boolean {
    val transitionRunning = LocalNavTransitionScope.current.isRunning
    val ready = remember { mutableStateOf(false) }

    LaunchedEffect(transitionRunning) {
        if (ready.value) return@LaunchedEffect
        if (transitionRunning) {
            // Bounded wait: a stuck transition must not starve the content forever.
            delay(NAV_TRANSITION_FALLBACK_MS)
        } else {
            withFrameNanos { }
        }
        ready.value = true
    }

    return ready.value
}
