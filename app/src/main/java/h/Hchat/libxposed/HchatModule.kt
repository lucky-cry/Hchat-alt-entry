package h.Hchat.libxposed

import android.app.Application
import android.util.Log
import h.Hchat.HostEnv
import h.Hchat.ModuleEntry
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.core.LibxposedHookEngine
import h.Hchat.utils.HLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 入口。
 *
 * 所有回调都用 android.util.Log 直接输出（不依赖模块日志与框架 getter），
 * 并整体包在 try/catch 里：入口早期任何异常都会让 LSPosed 静默放弃加载，
 * 表现为「能识别模块但没有日志、没有入口」。
 */
class HchatModule : XposedModule() {

    init {
        Log.i(TAG, "构造 HchatModule")
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        Log.i(TAG, "onModuleLoaded")
        try {
            HookRegistry.get().setEngine(LibxposedHookEngine(this))
            HLog.installSink { message, throwable ->
                try {
                    if (throwable == null) log(Log.ERROR, "Hchat", message)
                    else log(Log.ERROR, "Hchat", message, throwable)
                } catch (_: Throwable) {
                }
            }
            Log.i(TAG, "引擎与日志已注入")
        } catch (t: Throwable) {
            Log.e(TAG, "onModuleLoaded 初始化失败", t)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        Log.i(TAG, "onPackageLoaded: " + runCatching { param.packageName }.getOrDefault("?"))
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val pkg = runCatching { param.packageName }.getOrDefault("")
        Log.i(TAG, "onPackageReady: $pkg")
        if (!pkg.startsWith(WECHAT_PKG)) return
        try {
            val process = currentProcessName() ?: pkg
            Log.i(TAG, "进程: $process")
            if (process != pkg && !process.startsWith("$pkg:appbrand")) return
            val app = currentApplication() ?: run {
                Log.e(TAG, "拿不到 Application")
                return
            }
            val env = HostEnv(
                app,
                param.classLoader ?: app.classLoader,
                pkg,
                process,
                param.applicationInfo
            )
            ModuleEntry().bootstrap(env)
            Log.i(TAG, "bootstrap 完成")
        } catch (t: Throwable) {
            Log.e(TAG, "onPackageReady 失败", t)
        }
    }

    private fun currentApplication(): Application? = runCatching {
        val clazz = Class.forName("android.app.ActivityThread")
        clazz.getMethod("currentApplication").apply { isAccessible = true }.invoke(null) as? Application
    }.getOrNull()

    private fun currentProcessName(): String? = runCatching {
        java.io.File("/proc/self/cmdline").readText().trim().trim('\u0000')
    }.getOrNull()

    companion object {
        private const val TAG = "HCHAT102"
        private const val WECHAT_PKG = "com.tencent.mm"
    }
}
