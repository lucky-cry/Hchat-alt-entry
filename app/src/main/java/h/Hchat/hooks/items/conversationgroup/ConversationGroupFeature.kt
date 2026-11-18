package h.Hchat.hooks.items.conversationgroup

import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.HLog

class ConversationGroupFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "聊天分组"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ConversationGroupSettingsProvider())
        // 必须在首页 RecyclerView.setAdapter 之前装好，BRIDGE 阶段已经太晚。
        ConversationGroupClickBridge.install(context)
    }

    override fun onFeatureInstall(context: FeatureContext) {
        HLog.e("[Hchat:ConversationGroup] onFeatureInstall")
        ConversationGroupClickBridge.install(context)
        runCatching {
            HLog.e(
                "[Hchat:ConversationGroup] 原生分组页 Hook=" +
                    ConversationGroupRuntime.installNativePageOnly(context)
            )
        }
        val generation = ConversationGroupRuntime.beginInstall()
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.BRIDGE) {
            if (!ConversationGroupRuntime.isInstallGenerationActive(generation)) {
                true
            } else {
                ConversationGroupRuntime.install(context, generation)
            }
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        ConversationGroupRuntime.destroy(context)
    }

    companion object {
        const val ID = "conversation_group"
    }
}
