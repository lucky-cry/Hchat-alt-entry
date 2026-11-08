package h.Hchat.libxposed

import android.app.Application
import android.app.ActivityThread
import android.os.Process
import h.Hchat.HostEnv
import h.Hchat.ModuleEntry
import h.Hchat.utils.HLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 入口。
 *
 * 与 legacy 入口的区别只在外壳：拿到 ClassLoader / ApplicationInfo 后，
 * 统一交给 ModuleEntry.bootstrap(HostEnv)，所有功能逻辑不变。
 */
class HchatModule : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam?) {
        HLog.installSink { message, throwable ->
            if (throwable == null) {
                log(LogLevel.ERROR, "Hchat", message)
            } else {
                log(LogLevel.ERROR, "Hchat", message, throwable)
            }
        }
        super.onModuleLoaded(param)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.packageName.startsWith(WECHAT_PKG)) return
        val app = currentApplication() ?: return
        if (!isMainOrAppBrand(param.packageName)) return
        val env = HostEnv(
            app,
            param.classLoader ?: app.classLoader,
            param.packageName,
            currentProcessName() ?: param.packageName,
            param.applicationInfo
        )
        runCatching { ModuleEntry().bootstrap(env) }
            .onFailure { HLog.e("[Hchat:Entry] 102 入口初始化失败", it) }
    }

    private fun isMainOrAppBrand(packageName: String): Boolean {
        val process = currentProcessName() ?: return true
        return process == packageName || process.startsWith("$packageName:appbrand")
    }

    private fun currentApplication(): Application? = runCatching {
        ActivityThread.currentApplication()
    }.getOrNull()

    private fun currentProcessName(): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            null
        }
    }.getOrNull() ?: runCatching {
        // 兜底：读 /proc/self/cmdline
        java.io.File("/proc/self/cmdline").readText().trim().trimEnd('\u0000')
    }.getOrNull()

    companion object {
        private const val WECHAT_PKG = "com.tencent.mm"
    }
}
