package h.Hchat.hooks.items.conversationgroup

import android.content.Context
import h.Hchat.hooks.api.runtime.WeChatDatabaseApi
import org.json.JSONObject

private const val ACCOUNT = "account-a"
private const val GROUP_A = "wxid_hchat_group_a"
private const val GROUP_B = "wxid_hchat_group_b"
private const val KEY = "original_parent_refs"
private var checks = 0
private fun same(expected: Any?, actual: Any?, label: String) {
    check(expected == actual) { "$label: expected <$expected>, got <$actual>" }
    checks++
}
private fun fails(label: String, action: () -> Unit) {
    var failure: Throwable? = null
    try { action() } catch (caught: Throwable) { failure = caught }
    check(failure != null) { "$label: expected failure" }
    checks++
}
private fun originals(vararg entries: Pair<String, String>): String = JSONObject().apply {
    put(ACCOUNT, JSONObject().apply { entries.forEach { (talker, parent) -> put(talker, parent) } })
}.toString()

fun main() {
    val runtime = ParentRuntimeUnderTest
    val policy = ConversationGroupParentPolicy
    ConversationGroupStore.account = ACCOUNT

    // First commit fails after changing memory. No database mutation is authorized by memory equality.
    val events = arrayListOf<String>()
    val prefs = MemoryPreferences(events = events)
    val database = WeChatDatabaseApi(mapOf("friend" to "message_fold"), events)
    val plan = policy.plan(mapOf("friend" to GROUP_A), database.parents, emptySet(), emptyMap())
    prefs.failCommits.add(1)
    fails("failed backup commit aborts updates") { runtime.applyParentPlan(database, prefs, ACCOUNT, plan) }
    same(0, database.updateCalls, "failure never writes parent")
    same("message_fold", database.parents["friend"], "failed backup keeps native parent")
    same(mapOf("friend" to "message_fold"), runtime.loadOriginalParentRefs(prefs, ACCOUNT), "failed commit changed memory")
    same("{}", prefs.disk[KEY], "failed commit did not change disk")
    val retry = policy.plan(mapOf("friend" to GROUP_A), database.parents, emptySet(),
        runtime.loadOriginalParentRefs(prefs, ACCOUNT))
    same(setOf("friend"), runtime.applyParentPlan(database, prefs, ACCOUNT, retry), "retry succeeds")
    same(2, prefs.commitCount, "identical memory still recommits before parent mutation")
    same(listOf("commit:false", "commit:true", "update:friend:$GROUP_A"), events, "durable ordering on retry")
    same("message_fold", JSONObject(prefs.disk.getValue(KEY)).getJSONObject(ACCOUNT).getString("friend"),
        "retry persisted restoration information")
    same(GROUP_A, database.parents["friend"], "retry changes parent only after successful commit")

    // Failed native restoration retains the original mapping for a later retry.
    val restorePrefs = MemoryPreferences(originals("publisher" to "officialaccounts"))
    val restoreDb = WeChatDatabaseApi(mapOf("publisher" to GROUP_A), restorePrefs.events)
    val restorePlan = policy.plan(mapOf("publisher" to GROUP_B), restoreDb.parents, setOf("publisher"),
        runtime.loadOriginalParentRefs(restorePrefs, ACCOUNT))
    restoreDb.failTalkers.add("publisher")
    same(emptySet<String>(), runtime.applyParentPlan(restoreDb, restorePrefs, ACCOUNT, restorePlan),
        "failed restoration reports no successful talkers")
    same(GROUP_A, restoreDb.parents["publisher"], "failed restoration retains existing parent")
    same(mapOf("publisher" to "officialaccounts"), runtime.loadOriginalParentRefs(restorePrefs, ACCOUNT),
        "failed restoration keeps recovery mapping")
    same(restorePrefs.memory, restorePrefs.disk, "failed restoration backup remains persisted")
    restoreDb.failTalkers.clear()
    same(setOf("publisher"), runtime.applyParentPlan(restoreDb, restorePrefs, ACCOUNT, restorePlan),
        "restoration retries")
    same("officialaccounts", restoreDb.parents["publisher"], "restoration recovers saved native parent")
    same(emptyMap<String, String>(), runtime.loadOriginalParentRefs(restorePrefs, ACCOUNT),
        "only successful restoration removes backup")

    // Explicit saved root is valid; absence must not silently become the root.
    val rootPrefs = MemoryPreferences(originals("publisher" to ""))
    val rootDb = WeChatDatabaseApi(mapOf("publisher" to GROUP_A), rootPrefs.events)
    val rootPlan = policy.plan(emptyMap(), rootDb.parents, setOf("publisher"),
        runtime.loadOriginalParentRefs(rootPrefs, ACCOUNT))
    same(setOf("publisher"), runtime.applyParentPlan(rootDb, rootPrefs, ACCOUNT, rootPlan), "known empty restores")
    same("", rootDb.parents["publisher"], "explicit empty root recovered")
    val unknownPrefs = MemoryPreferences()
    val unknownDb = WeChatDatabaseApi(mapOf("publisher" to GROUP_A))
    val unknownPlan = policy.plan(emptyMap(), unknownDb.parents, setOf("publisher"), emptyMap())
    same(emptySet<String>(), runtime.applyParentPlan(unknownDb, unknownPrefs, ACCOUNT, unknownPlan),
        "unknown restoration has no successes")
    same(0, unknownDb.updateCalls, "unknown original never writes root")
    same(GROUP_A, unknownDb.parents["publisher"], "unknown original stays grouped")

    // Malformed JSON, wrong account shape and non-string mappings stop at loading.
    for (raw in listOf("{", "[]", "null", "{\"account-a\":false}",
        "{\"account-a\":{\"publisher\":null}}", "{\"account-a\":{\"publisher\":3}}",
        "{\"account-a\":{\"publisher\":{}}}")) {
        val broken = MemoryPreferences(raw)
        fails("malformed saved mapping $raw") { runtime.loadOriginalParentRefs(broken, ACCOUNT) }
        same(raw, broken.memory[KEY], "invalid mapping not replaced in memory")
        same(raw, broken.disk[KEY], "invalid mapping not replaced on disk")
        same(0, broken.commitCount, "invalid load never saves")
    }
    val malformedSave = MemoryPreferences("{")
    fails("save never replaces unparseable root") {
        runtime.saveOriginalParentRefs(malformedSave, ACCOUNT, mapOf("friend" to ""), true)
    }
    same("{", malformedSave.memory[KEY], "save failure preserves invalid root")
    same(0, malformedSave.commitCount, "save parse error avoids editor")
    val otherAccount = MemoryPreferences("{\"other\":{\"untouched\":\"photoaccounts\"}}")
    same(emptyMap<String, String>(), runtime.loadOriginalParentRefs(otherAccount, ACCOUNT), "missing account starts empty")
    runtime.saveOriginalParentRefs(otherAccount, ACCOUNT, mapOf("friend" to ""))
    same("photoaccounts", JSONObject(otherAccount.disk.getValue(KEY)).getJSONObject("other").getString("untouched"),
        "save preserves another account")

    // An already changed account must abort even the backup write.
    val switchedPrefs = MemoryPreferences()
    val switchedDb = WeChatDatabaseApi(mapOf("friend" to "message_fold"))
    ConversationGroupStore.account = "account-b"
    fails("account switched before apply") { runtime.applyParentPlan(switchedDb, switchedPrefs, ACCOUNT, plan) }
    same(0, switchedPrefs.commitCount, "account mismatch never persists")
    same(0, switchedDb.updateCalls, "account mismatch never mutates")
    ConversationGroupStore.account = ACCOUNT
    switchedPrefs.afterCommit = { ConversationGroupStore.account = "account-b" }
    fails("account switched during commit") { runtime.applyParentPlan(switchedDb, switchedPrefs, ACCOUNT, plan) }
    same(1, switchedPrefs.commitCount, "backup for captured account remains recoverable")
    same(0, switchedDb.updateCalls, "account rechecked after commit")
    ConversationGroupStore.account = ACCOUNT

    // Each batch rechecks identity, retaining every recovery entry if a later batch aborts.
    val batchPrefs = MemoryPreferences()
    val batchDb = WeChatDatabaseApi(mapOf("one" to "", "two" to "message_fold"), batchPrefs.events)
    val batchPlan = policy.plan(mapOf("one" to GROUP_A, "two" to GROUP_B), batchDb.parents,
        emptySet(), emptyMap())
    batchDb.afterUpdate = { ConversationGroupStore.account = "account-b" }
    fails("account changed between native batches") { runtime.applyParentPlan(batchDb, batchPrefs, ACCOUNT, batchPlan) }
    same(1, batchDb.updateCalls, "later batch not executed after account switch")
    same("message_fold", batchDb.parents["two"], "second chat untouched after account switch")
    ConversationGroupStore.account = ACCOUNT
    same(mapOf("one" to "", "two" to "message_fold"), runtime.loadOriginalParentRefs(batchPrefs, ACCOUNT),
        "interrupted batches retain all backups")

    // A failed cleanup commit after successful restoration leaves a safe, retryable disk backup.
    val cleanupPrefs = MemoryPreferences(originals("publisher" to "officialaccounts"))
    cleanupPrefs.failCommits.add(2)
    val cleanupDb = WeChatDatabaseApi(mapOf("publisher" to GROUP_A), cleanupPrefs.events)
    val cleanupPlan = policy.plan(emptyMap(), cleanupDb.parents, setOf("publisher"),
        runtime.loadOriginalParentRefs(cleanupPrefs, ACCOUNT))
    fails("restoration cleanup commit failure") { runtime.applyParentPlan(cleanupDb, cleanupPrefs, ACCOUNT, cleanupPlan) }
    same("officialaccounts", cleanupDb.parents["publisher"], "successful restore stays native after cleanup failure")
    same("officialaccounts", JSONObject(cleanupPrefs.disk.getValue(KEY)).getJSONObject(ACCOUNT).getString("publisher"),
        "cleanup failure retains durable original")
    val afterRestart = MemoryPreferences(cleanupPrefs.disk.getValue(KEY))
    val recoveredPlan = policy.plan(emptyMap(), cleanupDb.parents, setOf("publisher"),
        runtime.loadOriginalParentRefs(afterRestart, ACCOUNT))
    same(emptyMap<String, String>(), recoveredPlan.updates, "restart never rewrites already native parent")
    runtime.applyParentPlan(cleanupDb, afterRestart, ACCOUNT, recoveredPlan)
    same(emptyMap<String, String>(), runtime.loadOriginalParentRefs(afterRestart, ACCOUNT), "restart retires stale backup")
    same(1, cleanupDb.updateCalls, "cleanup retry does not repeat native write")

    checkDisabledSync()
    println("ConversationGroupParentRuntime: $checks assertions passed")
}

private fun checkDisabledSync() {
    val runtime = ParentRuntimeUnderTest
    val policy = ConversationGroupParentPolicy
    val settings = """{"groups":[{"id":"a","conversationIds":["friend"],"conversationOrderIds":["friend"],"automaticGroupingEnabled":true}]}"""
    val prefs = MemoryPreferences()
    prefs.memory["groups_v1"] = settings
    val lookalike = "wxidXhchatXgroupXa" // Matches the unescaped LIKE pattern.
    val db = WeChatDatabaseApi(mapOf("friend" to "message_fold", lookalike to ""), prefs.events)
    WeChatApis.currentDatabase = db
    val context = Context(prefs)
    val generation = runtime.activeGeneration
    val assigned = mapOf("friend" to GROUP_A)
    fun applyEnabledParentPlan() = runtime.applyParentPlan(db, prefs, ACCOUNT,
        policy.plan(assigned, db.parents.filterKeys(assigned::containsKey), emptySet(),
            runtime.loadOriginalParentRefs(prefs, ACCOUNT)))

    // Start grouped, switch off, then feed the preserved assignment back through the enabled planner.
    ConversationGroupStore.enabled = true
    same(setOf("friend"), applyEnabledParentPlan(), "enable assigns the saved group")
    db.parents[GROUP_A] = ""
    db.contacts.add(GROUP_A)
    db.avatars.add(GROUP_A)
    ConversationGroupStore.enabled = false
    runtime.syncDatabase(context, generation)
    same(mapOf("friend" to "message_fold", lookalike to ""), db.parents, "disable restores parent and removes only the module row")
    same(emptySet<String>(), db.contacts, "disable removes virtual contact")
    same(emptySet<String>(), db.avatars, "disable removes virtual avatar")
    same(listOf("commit:true", "update:friend:message_fold", "commit:true",
        "delete:rconversation:$GROUP_A", "delete:rcontact:$GROUP_A", "delete:img_flag:$GROUP_A", "refresh"),
        db.events.takeLast(7), "restore and persist before cleanup, refresh afterwards")
    same(settings, prefs.disk["groups_v1"], "disable leaves membership, order and automatic settings intact")
    ConversationGroupStore.enabled = true
    same(setOf("friend"), applyEnabledParentPlan(), "re-enable planner reuses saved assignment")
    same(GROUP_A, db.parents["friend"], "re-enable planner assigns the same parent")
    ConversationGroupStore.enabled = false

    val nativeDb = WeChatDatabaseApi(mapOf(
        "gh_restored" to GROUP_A, "gh_untouched" to "service_officialaccounts",
        "officialaccounts" to "", "service_officialaccounts" to "", GROUP_A to ""
    ))
    WeChatApis.currentDatabase = nativeDb
    runtime.syncDatabase(Context(MemoryPreferences(originals("gh_restored" to "officialaccounts"))), generation)
    same(mapOf(
        "gh_restored" to "officialaccounts", "gh_untouched" to "service_officialaccounts",
        "officialaccounts" to "", "service_officialaccounts" to ""
    ), nativeDb.parents, "restore native official parent and preserve both native aggregate rows")

    // Existing disabled install with stale virtual rows and no real children.
    val startupDb = WeChatDatabaseApi(mapOf(GROUP_A to ""))
    startupDb.contacts.add(GROUP_B)
    WeChatApis.currentDatabase = startupDb
    runtime.syncDatabase(Context(MemoryPreferences()), generation)
    same(emptyMap<String, String>(), startupDb.parents, "already disabled startup removes leftover conversation")
    same(emptySet<String>(), startupDb.contacts, "startup removes leftover virtual contact without conversation")
    same(0, startupDb.updateCalls, "orphan cleanup needs no parent writes")

    val recoveryPrefs = MemoryPreferences(originals("friend" to "", "second" to "message_fold"))
    val recoveryDb = WeChatDatabaseApi(mapOf("friend" to GROUP_A, "second" to GROUP_B, GROUP_A to "", GROUP_B to ""))
    WeChatApis.currentDatabase = recoveryDb
    recoveryDb.failTalkers.add("second")
    runtime.syncDatabase(Context(recoveryPrefs), generation)
    same("", recoveryDb.parents["friend"], "successful parent is restored during a partial failure")
    same(GROUP_B, recoveryDb.parents["second"], "failed parent stays in its group")
    same(mapOf("second" to "message_fold"), runtime.loadOriginalParentRefs(recoveryPrefs, ACCOUNT),
        "failed parent remains retryable")
    same(emptyList<String>(), recoveryDb.events.filter { it.startsWith("delete:") },
        "partial failure prevents all virtual row deletion")
    recoveryDb.failTalkers.clear()
    runtime.syncDatabase(Context(recoveryPrefs), generation)
    same(mapOf("friend" to "", "second" to "message_fold"), recoveryDb.parents, "retry restores before deleting groups")

    val unresolvedDb = WeChatDatabaseApi(mapOf("unknown" to GROUP_A, GROUP_A to ""))
    WeChatApis.currentDatabase = unresolvedDb
    runtime.syncDatabase(Context(MemoryPreferences()), generation)
    same(mapOf("unknown" to GROUP_A, GROUP_A to ""), unresolvedDb.parents, "missing backup keeps group and conversation")

    // A completed parent update alone does not authorize deleting rows from a stale sync.
    for (invalidate in listOf<() -> Unit>(
        { ConversationGroupStore.account = "account-b" },
        { runtime.activeGeneration++ },
        { ConversationGroupStore.enabled = true }
    )) {
        val staleDb = WeChatDatabaseApi(mapOf("friend" to GROUP_A, GROUP_A to ""))
        staleDb.afterUpdate = invalidate
        WeChatApis.currentDatabase = staleDb
        runtime.syncDatabase(Context(MemoryPreferences(originals("friend" to ""))), generation)
        same(mapOf("friend" to "", GROUP_A to ""), staleDb.parents, "invalidated sync leaves virtual row intact")
        ConversationGroupStore.account = ACCOUNT
        ConversationGroupStore.enabled = false
        runtime.activeGeneration = generation
    }
}
