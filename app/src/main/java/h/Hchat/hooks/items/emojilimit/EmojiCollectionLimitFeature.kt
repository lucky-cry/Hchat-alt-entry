package h.Hchat.hooks.items.emojilimit

import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookCallback
import h.Hchat.hooks.core.HookParam
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class EmojiCollectionLimitFeature : BaseFeature() {
    override fun featureId() = ID
    override fun name() = "扩展表情收藏上限"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(object : SimpleFeatureSettingsProvider(
            ID, name(), "将本机收藏上限提高到 5000", FeatureSettingsProvider.CATEGORY_PRACTICAL
        ) {})
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val version = WeChatApis.version()?.current() ?: return
        if (version.packageName != "com.tencent.mm" || version.versionName != "8.0.80" ||
            version.versionCode != 3220L
        ) return

        // Install the hook on supported hosts so the switch can take effect during this process.
        DexInstallScheduler.schedule(ID, name()) { installLimitHook(context) }
    }

    private fun installLimitHook(context: FeatureContext): Boolean {
        val host = context.hostContext()
        val loader = context.hostClassLoader()
        val prefs = DexMethodCache.prefs(host, "Hchat_emoji_collection_limit_method_cache")
        val runtimeKey = DexMethodCache.runtimeKey(host, loader)
        val cached = DexMethodCache.load(prefs, runtimeKey, loader, CACHE_METHOD)?.takeIf(::isLimitGetter)
        // The storage full check, collection manager and album picker share this getter.
        val method = cached ?: context.dexKitBridge().findMethod(
            FindMethod().matcher(
                MethodMatcher().declaredClass("tr.a0").name("a").paramCount(0)
                    .returnType("int").usingEqStrings("CustomEmojiMaxSize")
            )
        ).singleOrNull()?.getMethodInstance(loader)?.takeIf(::isLimitGetter)
        if (method == null) {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_METHOD)
            logError("未匹配收藏上限方法 tr.a0.a()I，跳过 Hook", null)
            return false
        }

        val config = context.configStore()
        HookRegistry.get().hook(method, object : HookCallback {
            override fun before(param: HookParam) {
                if (config.getBoolean(ID, KEY_ENABLED, DEFAULT_ENABLED)) {
                    param.result = EXPANDED_LIMIT
                }
            }
        })
        if (cached == null) DexMethodCache.save(prefs, runtimeKey, CACHE_METHOD, method)
        return true
    }

    private fun isLimitGetter(method: Method): Boolean =
        method.declaringClass.name == "tr.a0" && method.name == "a" && method.parameterCount == 0 &&
            method.returnType == Int::class.javaPrimitiveType && Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers)

    companion object {
        const val ID = "emoji_collection_limit"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = false
        private const val EXPANDED_LIMIT = 5000
        private const val CACHE_METHOD = "limit_getter_v1"
    }
}
