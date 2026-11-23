package h.Hchat.hooks.items.conversationpriority

import android.content.Context
import h.Hchat.hooks.items.conversationgroup.ConversationGroupStore
import h.Hchat.preferences.HchatStorage
import org.json.JSONObject

/** Per-account conversation priority values. Higher values sort first. */
internal object ConversationPriorityStore {
    const val PREFS_NAME = "Hchat_conversation_priority"
    const val KEY_ENABLE = "enabled"
    const val DEFAULT_ENABLE = true

    @Volatile private var current: Map<String, Int> = emptyMap()
    @Volatile private var initialized = false

    fun enabled(context: Context): Boolean =
        HchatStorage.preferences(context, PREFS_NAME).getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun current(): Map<String, Int> = current

    fun priority(talker: String): Int = current[talker] ?: 0

    fun ensureLoaded(context: Context) {
        if (initialized) return
        val account = ConversationGroupStore.accountKey()
        if (account.isBlank()) return
        load(context)
    }

    fun load(context: Context) {
        val account = ConversationGroupStore.accountKey()
        current = if (account.isBlank()) emptyMap() else read(context, account)
        initialized = true
    }

    fun setPriority(context: Context, talker: String, value: Int): Boolean {
        if (talker.isBlank()) return false
        val account = ConversationGroupStore.accountKey()
        if (account.isBlank()) return false
        val next = LinkedHashMap(read(context, account))
        if (value == 0) next.remove(talker) else next[talker] = value.coerceIn(MIN_VALUE, MAX_VALUE)
        val json = JSONObject().apply { next.forEach { (key, priority) -> put(key, priority) } }
        if (!HchatStorage.preferences(context, PREFS_NAME)
                .edit()
                .putString(key(account), json.toString())
                .commit()
        ) return false
        current = next
        initialized = true
        return true
    }

    private fun read(context: Context, account: String): Map<String, Int> {
        val raw = HchatStorage.preferences(context, PREFS_NAME).getString(key(account), "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val talker = keys.next()
                    val value = json.optInt(talker, 0)
                    if (talker.isNotBlank() && value in MIN_VALUE..MAX_VALUE) put(talker, value)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun key(account: String): String = "priorities_$account"

    const val MIN_VALUE = 0
    const val MAX_VALUE = 10
}
