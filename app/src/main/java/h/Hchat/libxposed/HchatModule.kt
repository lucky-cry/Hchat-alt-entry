package h.Hchat.libxposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 入口（迁移中）。
 *
 * 当前只做参数接线与日志，不接管 Hook：真正的初始化仍由 legacy
 * `h.Hchat.ModuleEntry`（assets/xposed_init）完成，因此本类暂不加入
 * META-INF/xposed/java_init.list，避免两套入口同时声明。
 */
class HchatModule : XposedModule {

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 迁移完成后在这里转调 ModuleEntry 的初始化逻辑：
        // param.classLoader / param.packageName / param.applicationInfo
    }
}
