package h.Hchat.hooks.core

import de.robv.android.xposed.XC_MethodHook
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

/** 基于 legacy Xposed API 的引擎，行为与现有代码完全一致。 */
class LegacyHookEngine : HookEngine {
    override fun hook(method: Member, callback: HookCallback): Any? {
        return HookRegistry.get().hook(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                callback.before(LegacyHookParam(param))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                callback.after(LegacyHookParam(param))
            }
        })
    }

    private class LegacyHookParam(private val param: XC_MethodHook.MethodHookParam) : HookParam {
        override val executable: java.lang.reflect.Member? get() = param.method
        override val thisObject: Any? get() = param.thisObject
        override val args: Array<Any?> get() = param.args as? Array<Any?> ?: arrayOfNulls(0)
        override var result: Any?
            get() = param.result
            set(value) {
                param.result = value
            }

        override val hasThrowable: Boolean get() = param.hasThrowable()

        override fun getObjectExtra(key: String): Any? = param.getObjectExtra(key)

        override fun setObjectExtra(key: String, value: Any?) {
            param.setObjectExtra(key, value)
        }
        override var throwable: Throwable?
            get() = param.throwable
            set(value) {
                param.throwable = value
            }

    }
}
