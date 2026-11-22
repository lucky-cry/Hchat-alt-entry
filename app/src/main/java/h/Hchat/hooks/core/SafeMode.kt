package h.Hchat.hooks.core

import android.content.Context
import java.io.File

/**
 * 一次性安全模式：只在下一进次启动微信时跳过 Hchat 全部 Hook，
 * 启动后立即清除标记，再下一次启动自动恢复模块。
 *
 * 标记必须写在宿主微信的数据目录里，因为 ModuleEntry.bootstrap 阶段
 * 还没有微信 Application/Context 可用。
 */
object SafeMode {
    private const val MARKER = "Hchat_safe_mode_next_launch"

    fun request(context: Context) {
        runCatching {
            File(context.filesDir, MARKER).writeText(System.currentTimeMillis().toString())
        }
    }

    fun consumeForPackage(packageName: String): Boolean {
        val file = markerFile(packageName)
        return runCatching { file.exists() && file.delete() }.getOrDefault(false)
    }

    private fun markerFile(packageName: String): File =
        File(File("/data/user/0", packageName), "files/$MARKER")
}
