package h.Hchat.libxposed

import android.app.Application
import android.util.Log
import h.Hchat.HostEnv
import h.Hchat.ModuleEntry
import h.Hchat.utils.HLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 入口。
 *
 * 与 legacy 入口的区别只在外壳：拿到 ClassLoader / ApplicationInfo 后，
 * 统一交给 ModuleEntry.bootstrap(HostEnv)，功能逻辑不变。
 */
class HchatModule : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HLog.installSink { message, throwable ->
            if (throwable == null) {
                log(Log.ERROR, "Hchat", message)
            } else {
                log(Log.ERROR, "Hchat", message, throwable)
            }
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.packageName.startsWith(WECHAT_PKG)) return
        val process = currentProcessName() ?: param.packageName
        if (process != param.packageName && !process.startsWith("${param.packageName}:appbrand")) return
        val app = currentApplication() ?: return
        val env = HostEnv(
            app,
            param.classLoader ?: app.classLoader,
            param.packageName,
            process,
            param.applicationInfo
        )
        runCatching { ModuleEntry().bootstrap(env) }
            .onFailure { HLog.e("[Hchat:Entry] 102 入口初始化失败", it) }
    }

    /** ActivityThread 是隐藏 API，编译期不可直接引用，走反射。 */
    private fun currentApplication(): Application? = runCatching {
        val clazz = Class.forName("android.app.ActivityThread")
        val method = clazz.getMethod("currentApplication")
        method.isAccessible = true
        method.invoke(null) as? Application
    }.getOrNull()

    private fun currentProcessName(): String? = runCatching {
        java.io.File("/proc/self/cmdline").readText().trim().trim('\u0000')
    }.getOrNull()

    companion object {
        private const val WECHAT_PKG = "com.tencent.mm"
    }
}
