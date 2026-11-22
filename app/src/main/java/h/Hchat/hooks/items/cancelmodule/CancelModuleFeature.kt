package h.Hchat.hooks.items.cancelmodule

import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class CancelModuleFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "取消模块"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(CancelModuleSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) = Unit

    companion object {
        const val ID = "cancel_module"
    }
}

class CancelModuleSettingsProvider : SimpleFeatureSettingsProvider(
    CancelModuleFeature.ID,
    "取消模块",
    "从微信首页加号菜单一键取消模块并重启微信",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
