package h.Hchat.hooks.items.windowsonline

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookCallback
import h.Hchat.hooks.core.HookParam
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.util.Collections
import java.util.WeakHashMap

class HideWindowsOnlineFeature : BaseFeature() {
    override fun featureId() = ID
    override fun name() = "隐藏 Windows 在线"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(HideWindowsOnlineSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = HideWindowsOnlineRuntime(context).also { it.install() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    private var runtime: HideWindowsOnlineRuntime? = null

    companion object {
        const val ID = "hide_windows_online"
    }
}

private class HideWindowsOnlineRuntime(private val context: FeatureContext) {
    private val prefs = HchatStorage.preferences(context.hostContext(), HideWindowsOnlineSettings.PREFS_NAME)
    private val hidden = Collections.synchronizedMap(WeakHashMap<Activity, View>())
    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val launcher = KavaReflector.loadClass("com.tencent.mm.ui.LauncherUI", context.hostClassLoader())
            ?: return false
        val onResume = KavaReflector.declaredMethods(launcher).firstOrNull {
            it.name == "onResume" && it.parameterTypes.isEmpty()
        } ?: return false
        return runCatching {
            HookRegistry.get().hook(onResume, object : HookCallback {
                override fun after(param: HookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    activity.window?.decorView?.postDelayed({
                        apply(activity)
                    }, 300L)
                }
            })
            installed = true
            HLog.e("$TAG 隐藏 Windows 在线安装完成")
            true
        }.getOrElse {
            HLog.e("$TAG 安装失败: ${it.message}", it)
            false
        }
    }

    private fun apply(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (!prefs.getBoolean(HideWindowsOnlineSettings.KEY_ENABLE, HideWindowsOnlineSettings.DEFAULT_ENABLE)) {
            hidden.remove(activity)?.let { it.visibility = View.VISIBLE }
            return
        }
        val target = findBanner(decor) ?: return
        target.visibility = View.GONE
        hidden[activity] = target
    }

    private fun findBanner(view: View): View? {
        if (view is TextView && view.text?.contains("Windows") == true &&
            view.text?.contains("电脑") == true
        ) {
            var candidate: View = view
            repeat(2) {
                val parent = candidate.parent as? View ?: return@repeat
                candidate = parent
            }
            return candidate.takeIf { it !is ViewGroup || it.parent != null }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findBanner(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "[Hchat:WindowsOnline]"
    }
}
