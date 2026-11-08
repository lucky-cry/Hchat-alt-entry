package h.Hchat.hooks.core

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.util.concurrent.CancellationException

/**
 * 统一保存 Xposed hook 句柄，便于防御性卸载。
 */
class HookRegistry private constructor() {
    private val lock = Any()
    private val unhooks = ArrayList<XC_MethodHook.Unhook>()
    private val extraHandles = ArrayList<Any>()
    private val installationLease = ThreadLocal<Long>()
    @Volatile private var generation = 0L

    fun installationGeneration(): Long = installationLease.get() ?: generation

    fun isInstallationGenerationActive(generation: Long): Boolean = this.generation == generation

    /** 迁移入口：功能层通过 HookCallback 挂载，引擎可替换为 libxposed 实现。 */
    @Volatile private var engine: HookEngine = LegacyHookEngine()

    /** 由入口按运行环境注入：legacy 用 LegacyHookEngine，102 用 LibxposedHookEngine。 */
    fun setEngine(newEngine: HookEngine) {
        engine = newEngine
    }

    fun currentEngine(): HookEngine = engine

    fun hookForMigration(method: Member, callback: HookCallback): Any? =
        engine.hook(method, callback)

    /** 按类 + 方法名挂载（替代 XposedHelpers.findAndHookMethod）。 */
    fun hookMethod(
        clazz: Class<*>?,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        callback: HookCallback
    ): Any? {
        if (clazz == null) return null
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(methodName, *parameterTypes)
                method.isAccessible = true
                return hookForMigration(method, callback)
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        h.Hchat.utils.HLog.e("[Hchat:Hook] 未找到方法: ${clazz.name}#$methodName")
        return null
    }

    /** 按类名 + ClassLoader 挂载（替代 XposedHelpers.findAndHookMethod 的字符串重载）。 */
    fun hookMethodByName(
        className: String,
        classLoader: ClassLoader?,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        callback: HookCallback
    ): Any? {
        return try {
            hookMethod(Class.forName(className, false, classLoader), methodName, parameterTypes, callback)
        } catch (e: Throwable) {
            h.Hchat.utils.HLog.e("[Hchat:Hook] 类加载失败: $className", e)
            null
        }
    }

    fun <T> withInstallationGeneration(generation: Long, action: () -> T): T {
        val previous = installationLease.get()
        if (!isInstallationGenerationActive(generation) || previous != null && previous != generation) {
            throw CancellationException("安装代次已失效")
        }
        installationLease.set(generation)
        try { return action() }
        finally {
            if (previous == null) installationLease.remove() else installationLease.set(previous)
        }
    }

    /** 迁移期重载：调用点无需改动，回调类型换成 HookCallback 即可。 */
    fun hook(method: Member, callback: HookCallback): Any? = hookForMigration(method, callback)

    /** 迁移期重载：接受引擎返回的任意句柄（HookHandle 或 Unhook）。 */
    fun add(handle: Any?) {
        if (handle == null) return
        if (handle is XC_MethodHook.Unhook) {
            add(handle)
            return
        }
        synchronized(lock) { extraHandles.add(handle) }
    }

    fun hook(method: Member, callback: XC_MethodHook): XC_MethodHook.Unhook = synchronized(lock) {
        if (!isInstallationGenerationActive(installationGeneration())) {
            throw CancellationException("安装代次已失效")
        }
        val unhook = XposedBridge.hookMethod(method, callback)
        if (unhook != null) {
            unhooks.add(unhook)
        }
        unhook
    }

    fun add(unhook: XC_MethodHook.Unhook?) {
        if (unhook == null) return
        synchronized(lock) {
            if (isInstallationGenerationActive(installationGeneration())) {
                unhooks.add(unhook)
                return
            }
        }
        unhook.unhook()
        throw CancellationException("安装代次已失效")
    }

    fun size(): Int = synchronized(lock) { unhooks.size }

    fun unhook(unhook: XC_MethodHook.Unhook?) {
        if (unhook == null) return
        unhook.unhook()
        // Xposed 解除回调后，还必须释放模块注册表持有的句柄及脚本解释器。
        synchronized(lock) { unhooks.remove(unhook) }
    }

    fun unhookAll() {
        val previous = synchronized(lock) {
            generation++
            unhooks.toList().also { unhooks.clear() }
        }
        for (unhook in previous) {
            try {
                unhook.unhook()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private val INSTANCE = HookRegistry()

        @JvmStatic
        fun get(): HookRegistry = INSTANCE
    }
}
