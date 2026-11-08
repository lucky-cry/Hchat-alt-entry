package h.Hchat.hooks.core

import java.lang.reflect.Member

/**
 * Hook 适配层。
 *
 * 目标：把功能层从 `XC_MethodHook` 解耦，便于迁移到 libxposed API 102
 * （新接口用 Hooker.before/after，不再有 XC_MethodHook）。
 *
 * 迁移期间：
 * - `LegacyHookEngine` 仍然走 XposedBridge，保证现有行为不变；
 * - 未来 `LibxposedHookEngine` 实现同一接口，功能层无需改动。
 */
interface HookParam {
    val executable: java.lang.reflect.Member?
    val thisObject: Any?
    val args: Array<Any?>
    var result: Any?
    var throwable: Throwable?
    val hasThrowable: Boolean get() = throwable != null

    /** 兼容 Xposed 的 per-hook 附加数据（102 下由实现自行存储）。 */
    fun getObjectExtra(key: String): Any? = null

    fun setObjectExtra(key: String, value: Any?) {}
}

/** 功能层写 Hook 时只依赖这个回调。 */
interface HookCallback {
    fun before(param: HookParam) {}

    fun after(param: HookParam) {}
}

/** 挂载引擎：legacy(XposedBridge) 与 libxposed 各一份实现。 */
interface HookEngine {
    fun hook(method: Member, callback: HookCallback): Any?
}

/** 占位引擎：尚未注入真实引擎时使用，避免初始化阶段触碰 legacy 类。 */
class NoopHookEngine : HookEngine {
    override fun hook(method: Member, callback: HookCallback): Any? {
        h.Hchat.utils.HLog.e("[Hchat:Hook] 引擎未注入，跳过挂载: ${method.name}")
        return null
    }
}
