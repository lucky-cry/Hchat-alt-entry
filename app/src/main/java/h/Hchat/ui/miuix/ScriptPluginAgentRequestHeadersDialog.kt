package h.Hchat.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import h.Hchat.hooks.items.script.agent.ScriptPluginAgentRequestHeaders
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ScriptPluginAgentRequestHeadersDialog(
    headers: Map<String, String>,
    allowOverride: Boolean,
    onApply: (Map<String, String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(headers) { mutableStateOf(headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }) }
    var overrideAuth by remember(allowOverride) { mutableStateOf(allowOverride) }
    var error by remember { mutableStateOf("") }
    WindowDialog(
        show = true,
        title = "自定义请求头",
        onDismissRequest = onDismiss,
        content = {
            Column {
                Text(
                    text = "每行一项，例如 X-Tenant-ID: demo。留空可清除。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it; error = "" },
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.secondaryVariant)
                        .padding(12.dp)
                )
                if (error.isNotEmpty()) {
                    Text(text = error, color = MiuixTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
                SwitchRow(
                    checked = overrideAuth,
                    title = "允许覆盖鉴权头",
                    summary = if (overrideAuth) "同名鉴权头使用自定义值" else "仅追加，保留同名内置鉴权头",
                    onCheckedChange = { overrideAuth = it }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = "确认",
                        onClick = {
                            val parsed = try {
                                ScriptPluginAgentRequestHeaders.parse(draft)
                            } catch (invalid: IllegalArgumentException) {
                                error = invalid.message.orEmpty()
                                return@TextButton
                            }
                            onApply(parsed, overrideAuth)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}
