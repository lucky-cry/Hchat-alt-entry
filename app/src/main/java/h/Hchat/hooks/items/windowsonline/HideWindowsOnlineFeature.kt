package h.Hchat.hooks.items.windowsonline

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import h.Hchat.hooks.api.core.WeChatApis
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
    private val watched = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
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
                        watch(activity)
                        apply(activity)
                    }, 300L)
                }
            })
            installed = true
            (WeChatApis.currentActivity()?.currentActivity() as? Activity)?.let { activity ->
                activity.window?.decorView?.postDelayed({
                    watch(activity)
                    apply(activity)
                }, 500L)
            }
            HLog.e("$TAG 隐藏 Windows 在线安装完成")
            true
        }.getOrElse {
            HLog.e("$TAG 安装失败: ${it.message}", it)
            false
        }
    }

    private fun watch(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (!watched.add(decor)) return
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            if (prefs.getBoolean(
                    HideWindowsOnlineSettings.KEY_ENABLE,
                    HideWindowsOnlineSettings.DEFAULT_ENABLE
                )
            ) {
                apply(activity)
            }
        }
        // 提示条可能晚于 onResume 才插入，轮询几秒保证不用手动点一下。
        var attempts = 0
        val poll = object : Runnable {
            override fun run() {
                if (!prefs.getBoolean(
                        HideWindowsOnlineSettings.KEY_ENABLE,
                        HideWindowsOnlineSettings.DEFAULT_ENABLE
                    )
                ) return
                apply(activity)
                if (attempts++ < 20 && activity.window?.decorView === decor) {
                    decor.postDelayed(this, 300L)
                }
            }
        }
        decor.postDelayed(poll, 100L)
    }

    private fun apply(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val enabled = prefs.getBoolean(
            HideWindowsOnlineSettings.KEY_ENABLE,
            HideWindowsOnlineSettings.DEFAULT_ENABLE
        )
        if (!enabled) {
            HLog.e("$TAG 开关未启用")
            hidden.remove(activity)?.let { it.visibility = View.VISIBLE }
            return
        }
        val target = findBanner(decor)
        HLog.e("$TAG 开关已启用, 提示条=${target?.javaClass?.name}")
        if (target == null) return
        // 只隐藏整行并折叠高度，不能 removeView：从聊天页返回后
        // RecyclerView 会因 item 被移除而留下空白。
        target.visibility = View.GONE
        target.layoutParams?.let { params ->
            if (params.height != 0) {
                params.height = 0
                target.layoutParams = params
            }
        }
        target.requestLayout()
        hidden[activity] = target
    }

    private fun findBanner(view: View): View? {
        if (view is TextView && view.text?.contains("Windows") == true) {
            HLog.e("$TAG 候选文本: ${view.text}")
        }
        if (view is TextView && view.text?.contains("Windows") == true &&
            view.text?.contains("电脑") == true
        ) {
            var candidate: View = view
            var parent = candidate.parent
            while (parent is ViewGroup && !parent.javaClass.name.contains("RecyclerView")) {
                candidate = parent
                parent = parent.parent
            }
            return candidate.takeIf { it.parent is ViewGroup }
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
