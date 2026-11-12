package me.weishu.kernelsu.ui.screen.safe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Deliberately dumb UI: plain Material3, no XcTheme, no miui, no navigator, no view models.
 *
 * Shown in its own window when the main content has not composed a few seconds after start, so
 * a broken main UI can never leave the user with a blank screen. It also prints the state the
 * manager sees, which is what turns "it is white" into a usable bug report.
 */
@Composable
fun SafeUiPage(diagnose: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "XECKernel Pro · 安全模式",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "主界面没有画出来，这里是兜底页。以下状态已写入 Android/data/www.xeckernel.pro/files/xec-boot.log",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = diagnose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("继续尝试进入主界面")
            }
        }
    }
}
