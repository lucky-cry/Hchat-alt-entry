// Extract the actual production persistence/apply methods and exercise failure ordering without Gradle.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const cache = process.argv[2] || path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1');
function jar(group, name, version) {
    const directory = path.join(cache, group, name, version);
    for (const hash of fs.readdirSync(directory)) {
        const candidate = path.join(directory, hash, name + '-' + version + '.jar');
        if (fs.existsSync(candidate)) return candidate;
    }
    throw new Error('Missing dependency: ' + directory);
}
const stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0');
const json = process.env.JSON_JAR || jar('org.json', 'json', '20240303');
const compiler = [
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    jar('org.jetbrains', 'annotations', '13.0')
];
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-conversation-parent-runtime-tests-'));
process.on('exit', () => fs.rmSync(output, {recursive: true, force: true}));
const artifact = path.join(output, 'parent-runtime.jar');
const java = process.env.JAVA || 'java';
const production = 'app/src/main/java/h/Hchat/hooks/items/conversationgroup/';
const runtime = fs.readFileSync(path.join(root, production, 'ConversationGroupRuntime.kt'), 'utf8');
function sourceBetween(start, end) {
    const from = runtime.indexOf(start);
    const to = runtime.indexOf(end, from + start.length);
    if (from < 0 || to < 0) throw new Error('Production method boundary missing: ' + start);
    return runtime.slice(from, to).replaceAll('private fun ', 'fun ');
}
const extracted = path.join(output, 'ParentRuntimeUnderTest.kt');
fs.writeFileSync(extracted, `package h.Hchat.hooks.items.conversationgroup
import android.content.Context
import android.content.SharedPreferences
import h.Hchat.utils.HLog
import org.json.JSONObject
import java.util.LinkedHashMap
internal object ParentRuntimeUnderTest {
    private const val TAG = "ConversationGroupTest"
    private const val KEY_ORIGINAL_PARENT_REFS = "original_parent_refs"
    private const val PARENT_FAILURE_HISTORY_MAX = 4096
    private const val PARENT_FAILURE_HISTORY_TTL_MS = 24 * 60 * 60 * 1000L
    private const val VIRTUAL_PREFIX = "wxid_hchat_group_"
    private val parentRestoreFailures = LinkedHashMap<String, Long>(128, 0.75f, true)
    private val lifecycleLock = Any()
    var activeGeneration = 1L
    private var effectiveConversationSnapshot: Any? = null
    private fun isRuntimeGenerationActive(generation: Long) = generation == activeGeneration
    private fun isVirtualTalker(value: String?) = value?.startsWith(VIRTUAL_PREFIX) == true
    private val automaticAvatarSources = mutableMapOf<String, String>()
    private fun value(row: Map<String, String>, key: String) = row[key].orEmpty()
    private data class ConversationParents(val parents: Map<String, String>)
    private fun loadConversationParents(database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        assigned: Set<String>) = ConversationParents(database.parents.filter { (talker, parent) ->
        !isVirtualTalker(talker) && (talker in assigned || ConversationGroupParentPolicy.isModuleParent(parent))
    })
    private fun updateParentRefs(database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        talkers: List<String>, parent: String): Boolean = database.updateParents(talkers, parent)
` + sourceBetween('    private fun applyParentPlan(', '    private fun syncDatabase(') +
    sourceBetween('    private fun syncDatabase(', '        var groups = ConversationGroupStore.load(context)') + '    }\n' +
    sourceBetween('    private fun cleanupVirtualRows(', '    private fun updateParentRefs(') +
    sourceBetween('    private fun loadOriginalParentRefs(', '    private fun locateQueryMethod(') + '}\n');
function run(args) {
    const result = spawnSync(java, args, {cwd: root, encoding: 'utf8', timeout: 60000});
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
const tests = 'scripts/tests/conversation_group_parent_runtime/';
run(['-Xmx256m', '-cp', compiler.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', [stdlib, json].join(path.delimiter), '-d', artifact,
    production + 'ConversationGroupParentPolicy.kt', extracted,
    ...fs.readdirSync(path.join(root, tests)).filter(file => file.endsWith('.kt')).map(file => tests + file)]);
run(['-cp', [artifact, stdlib, json].join(path.delimiter),
    'h.Hchat.hooks.items.conversationgroup.ParentRuntimeRegressionKt']);
