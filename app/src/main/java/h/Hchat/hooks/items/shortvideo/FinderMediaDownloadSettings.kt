package h.Hchat.hooks.items.shortvideo

object FinderMediaDownloadSettings {
    const val PREFS_NAME = "Hchat_finder_media_download"
    const val KEY_ENABLE = "enable"
    const val DEFAULT_ENABLE = false
    // 自定义保存目录；留空表示使用私有路径（Android/media/<微信包名>/Hchat/Finder）。
    const val KEY_SAVE_PATH = "save_path"

    @JvmStatic
    fun savePath(context: android.content.Context): String {
        val sp = h.Hchat.preferences.HchatStorage.preferences(context, PREFS_NAME)
        return sp.getString(KEY_SAVE_PATH, "").orEmpty().trim()
    }
}
