package h.Hchat.hooks.items.conversationgroup

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.hooks.api.runtime.WeChatDatabaseApi

internal object ConversationGroupStore {
    const val PREFS_NAME = "Hchat_conversation_groups"
    var account = "account-a"
    var enabled = false
    fun accountKey(): String = account
    fun isEnabled(context: Context): Boolean = enabled
}

internal object HchatStorage {
    fun preferences(context: Context, name: String): SharedPreferences = context.preferences
}

internal object WeChatApis {
    var currentDatabase: WeChatDatabaseApi? = null
    fun database() = currentDatabase
    fun conversations(): ConversationApi? = null

    interface ConversationApi {
        fun deleteConversation(talker: String): Boolean
    }
}

internal object ConversationGroupHomeProjection {
    fun refresh() {
        WeChatApis.currentDatabase?.events?.add("refresh")
    }
}

internal class MemoryPreferences(
    initial: String = "{}",
    val events: MutableList<String> = arrayListOf()
) : SharedPreferences {
    val memory = linkedMapOf("original_parent_refs" to initial)
    val disk = memory.toMutableMap()
    val failCommits = hashSetOf<Int>()
    var commitCount = 0
    var afterCommit: (() -> Unit)? = null

    override fun getString(key: String, defaultValue: String?): String? = memory[key] ?: defaultValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            // FastKV's editor mutates its in-memory map before commit reports disk failure.
            if (value == null) memory.remove(key) else memory[key] = value
            return this
        }
        override fun commit(): Boolean {
            commitCount++
            val success = commitCount !in failCommits
            events.add("commit:$success")
            if (success) {
                disk.clear()
                disk.putAll(memory)
            }
            afterCommit?.invoke()
            return success
        }
    }
}
