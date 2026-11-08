package h.Hchat.hooks.core

import java.lang.reflect.Member
import java.util.concurrent.CancellationException

/**
 * 统一保存 Xposed hook 句柄，便于防御性卸载。
 */
class HookRegistry private constructor() {
    private val lock = Any()
    private val unhooks = ArrayList<Any>()
    private val extraHandles = ArrayList<Any>()
    private val installationLease = ThreadLocal<Long>()
    @Volatile private var generation = 0L

    fun installationGeneration(): Long = installationLease.get() ?: generation

    fun isInstallationGenerationActive(generation: Long): Boolean = this.generation == generation

    /** 迁移入口：功能层通过 HookCallback 挂载，引擎可替换为 libxposed 实现。 */
    @Volatile private var engine: HookEngine = NoopHookEngine()

    /** 由入口按运行环境注入：legacy 用 LegacyHookEngine，102 用 LibxposedHookEngine。 */
    fun setEngine(newEngine: HookEngine) {
        engine = newEngine
    }

    fun currentEngine(): HookEngine = engine

    fun hookForMigration(method: Member, callback: HookCallback): Any? =
        engine.hook(method, callback)

    /** 批量挂载：类及其父类中所有同名方法（替代 XposedBridge.hookAllMethods）。 */
    fun hookAllMethods(clazz: Class<*>?, name: String, callback: HookCallback): List<Any> {
        if (clazz == null) return emptyList()
        val handles = ArrayList<Any>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (name.isNotEmpty() && method.name != name) continue
                method.isAccessible = true
                runCatching { hookForMigration(method, callback) }.getOrNull()?.let { handles.add(it) }
            }
            current = current.superclass
        }
        return handles
    }

    /** 批量挂载：类及其父类的全部构造器（替代 XposedBridge.hookAllConstructors）。 */
    fun hookAllConstructors(clazz: Class<*>?, callback: HookCallback): List<Any> {
        if (clazz == null) return emptyList()
        val handles = ArrayList<Any>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (constructor in current.declaredConstructors) {
                constructor.isAccessible = true
                runCatching { hookForMigration(constructor, callback) }.getOrNull()?.let { handles.add(it) }
            }
            current = current.superclass
        }
        return handles
    }

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
    fun hook(method: Member, callback: HookCallback): Any = hookForMigration(method, callback) ?: Unit

    /** 迁移期重载：接受引擎返回的任意句柄（HookHandle 或 Unhook）。 */
    fun add(handle: Any?) {
        if (handle == null) return
        synchronized(lock) { extraHandles.add(handle) }
    }


    fun size(): Int = synchronized(lock) { unhooks.size }

    /** 迁移期统一卸载：兼容 legacy Unhook 与 libxposed HookHandle。 */
    fun unhookHandle(handle: Any?) {
        if (handle == null) return
        runCatching {
            val close = handle.javaClass.methods.firstOrNull {
                it.name == "close" || it.name == "unhook"
            } ?: return@runCatching
            close.isAccessible = true
            close.invoke(handle)
        }
        synchronized(lock) { extraHandles.remove(handle) }
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
