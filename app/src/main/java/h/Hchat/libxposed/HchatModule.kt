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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libxposed API 102 入口。
 *
 * 关键时序（API 102 规范）：`onPackageReady` 发生在 AppComponentFactory 创建
 * classloader 之后、Application 实例创建之前，此时 `ActivityThread.currentApplication()`
 * 通常为 null。因此入口不能在这里 `return`，也不能把 Application 当作 bootstrap 的前置条件：
 * 真正的初始化要由 ModuleEntry 挂 `Application.onCreate` 后触发。早期版本的入口在拿不到
 * Application 时直接返回，表现就是「模块能识别、零日志、微信无入口」。
 *
 * 日志同时写 android.util.Log（logcat）和 module.log（LSPosed 日志页），便于区分
 * 「框架没加载入口」和「入口加载了但没初始化」。
 */
class HchatModule : XposedModule() {

    private val bootstrapped = AtomicBoolean(false)

    /** onModuleLoaded 拿到的进程名最权威，避免 /proc/self/cmdline 读取失败时误判主进程。 */
    @Volatile
    private var moduleProcessName: String? = null

    init {
        Log.i(TAG, "构造 HchatModule")
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        Log.i(TAG, "onModuleLoaded")
        moduleProcessName = runCatching { param.processName }.getOrNull()
        try {
            HookRegistry.get().setEngine(LibxposedHookEngine(this))
            HLog.installSink { message, throwable ->
                try {
                    if (throwable == null) log(Log.ERROR, "Hchat", message)
                    else log(Log.ERROR, "Hchat", message, throwable)
                } catch (_: Throwable) {
                }
            }
            logBoth(Log.INFO, "onModuleLoaded process=" + safeProcessName(param))
            logBoth(
                Log.INFO,
                "framework=" + runCatching { frameworkName }.getOrDefault("?") +
                    " version=" + runCatching { frameworkVersion }.getOrDefault("?") +
                    " api=" + runCatching { apiVersion }.getOrDefault(-1)
            )
        } catch (t: Throwable) {
            logBoth(Log.ERROR, "onModuleLoaded 初始化失败", t)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        val pkg = runCatching { param.packageName }.getOrDefault("?")
        logBoth(Log.INFO, "onPackageLoaded: $pkg")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val pkg = runCatching { param.packageName }.getOrDefault("")
        val process = moduleProcessName ?: currentProcessName() ?: pkg
        logBoth(Log.INFO, "onPackageReady: pkg=$pkg process=$process")
        if (!pkg.startsWith(WECHAT_PKG)) return
        if (process != pkg && !process.startsWith("$pkg:appbrand")) {
            logBoth(Log.INFO, "跳过进程: $process")
            return
        }
        if (!bootstrapped.compareAndSet(false, true)) {
            logBoth(Log.INFO, "bootstrap 已执行，忽略重复的 onPackageReady")
            return
        }
        try {
            // 这里通常为 null：onPackageReady 早于 Application 创建。
            // ModuleEntry 会挂 Application.onCreate，在回调里拿到真正的 Application。
            val app = currentApplication()
            logBoth(Log.INFO, "Application 状态: " + if (app == null) "尚未创建" else app.javaClass.name)
            val env = HostEnv(
                app,
                param.classLoader,
                pkg,
                process,
                param.applicationInfo
            )
            ModuleEntry().bootstrap(env)
            logBoth(Log.INFO, "bootstrap 已执行（等待 Application.onCreate 初始化）")
        } catch (t: Throwable) {
            bootstrapped.set(false)
            logBoth(Log.ERROR, "bootstrap 失败", t)
        }
    }

    private fun safeProcessName(param: XposedModuleInterface.ModuleLoadedParam): String =
        runCatching { param.processName }.getOrDefault("?")

    private fun logBoth(priority: Int, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.println(priority, TAG, message)
        else Log.println(priority, TAG, message + "\n" + Log.getStackTraceString(throwable))
        try {
            if (throwable == null) log(priority, TAG, message)
            else log(priority, TAG, message, throwable)
        } catch (_: Throwable) {
            // 未 attach 或框架日志不可用时只保留 logcat
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
