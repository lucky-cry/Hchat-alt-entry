package h.Hchat.hooks.core

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Member

/**
 * libxposed API 102 的 Hook 引擎。
 *
 * 102 的回调是 `Hooker.intercept(Chain) -> Object`：
 * before 阶段可改写参数或直接返回；否则 `chain.proceed(args)` 拿原结果，
 * 再交给 after 阶段决定是否覆盖。
 */
class LibxposedHookEngine(private val xposed: XposedInterface) : HookEngine {

    override fun hook(method: Member, callback: HookCallback): Any? {
        val executable = method as? Executable ?: return null
        return xposed.hook(executable).intercept { chain ->
            val param = ChainParam(chain)
            callback.before(param)
            if (param.hasResult) return@intercept param.result

            val result = try {
                chain.proceed(param.consumeArgs())
            } catch (throwable: Throwable) {
                param.result = null
                param.setThrowable(throwable)
                callback.after(param)
                throw param.throwable ?: throwable
            }
            param.result = result
            callback.after(param)
            param.result
        }
    }

    private class ChainParam(private val chain: XposedInterface.Chain) : HookParam {
        private var argsArray: Array<Any?>? = chain.args.toTypedArray()
        private var argsConsumed = false

        var hasResult: Boolean = false
            private set

        override val thisObject: Any? get() = chain.thisObject

        override val args: Array<Any?>? get() = argsArray

        override var result: Any?
            get() = field
            set(value) {
                field = value
                hasResult = true
            }

        override var throwable: Throwable? = null

        override fun setThrowable(throwable: Throwable?) {
            this.throwable = throwable
        }

        fun consumeArgs(): Array<Any?>? {
            if (argsConsumed) return null
            argsConsumed = true
            return argsArray
        }
    }
}
