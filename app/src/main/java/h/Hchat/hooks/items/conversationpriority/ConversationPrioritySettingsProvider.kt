package h.Hchat.hooks.items.conversationpriority

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ConversationPrioritySettingsProvider : SimpleFeatureSettingsProvider(
    ConversationPriorityFeature.ID,
    "会话优先级排序",
    "长按首页会话设置 0–10 优先级，数值越大越靠前；置顶和非置顶分别排序",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
