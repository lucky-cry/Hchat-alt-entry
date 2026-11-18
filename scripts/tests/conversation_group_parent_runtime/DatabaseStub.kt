package h.Hchat.hooks.api.runtime

internal class WeChatDatabaseApi(
    initial: Map<String, String>,
    val events: MutableList<String> = arrayListOf()
) {
    val parents = initial.toMutableMap()
    val failTalkers = hashSetOf<String>()
    var updateCalls = 0
    var afterUpdate: (() -> Unit)? = null
    val contacts = hashSetOf<String>()
    val avatars = hashSetOf<String>()

    private fun usernames(table: String) = when (table) {
        "rconversation" -> parents.keys
        "rcontact" -> contacts
        "img_flag" -> avatars
        else -> error("Unexpected table: $table")
    }

    fun query(sql: String, args: Array<String>): List<Map<String, String>> {
        val table = sql.substringAfter(" FROM ").substringBefore(" ")
        val like = Regex(args.single().replace("_", ".").replace("%", ".*"))
        return usernames(table).filter(like::matches).map { mapOf("username" to it) }
    }

    fun delete(table: String, where: String, args: Array<String>) {
        check(where == "username=?")
        val talker = args.single()
        events.add("delete:$table:$talker")
        usernames(table).remove(talker)
    }

    fun updateParents(talkers: List<String>, parent: String): Boolean {
        updateCalls++
        events.add("update:${talkers.joinToString()}:$parent")
        if (talkers.any { it in failTalkers }) return false
        talkers.forEach { parents[it] = parent }
        afterUpdate?.invoke()
        return true
    }
}
