package me.weishu.kernelsu.ui.util

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R

suspend fun <T> rootIO(block: () -> T): T = withContext(Dispatchers.IO) { block() }

@Composable
fun rememberRootOperation(): (suspend () -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { block ->
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(context,
                    "${context.getString(R.string.permanent_failed)} ${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }
}
