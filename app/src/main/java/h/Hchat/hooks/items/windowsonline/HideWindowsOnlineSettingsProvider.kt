package h.Hchat.hooks.items.windowsonline

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class HideWindowsOnlineSettingsProvider : SimpleFeatureSettingsProvider(
    HideWindowsOnlineFeature.ID,
    "隐藏 Windows 在线",
    "隐藏微信首页“已登录 Windows 电脑”提示条",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
