package h.Hchat.hooks.items.conversationpriority

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookCallback
import h.Hchat.hooks.core.HookParam
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.conversationgroup.ConversationGroupHomeProjection
import h.Hchat.hooks.items.conversationgroup.ConversationGroupRuntime
import h.Hchat.hooks.items.quickcontactedit.ConversationMenuLocator
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.FieldsMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

class ConversationPriorityFeature : BaseFeature() {
    private var runtime: ConversationPriorityRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "会话优先级排序"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ConversationPrioritySettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = ConversationPriorityRuntime(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) { runtime?.install() == true }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "conversation_priority"
    }
}

private class ConversationPriorityRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val hookedMethods = Collections.newSetFromMap(Collections.synchronizedMap(HashMap<Method, Boolean>()))
    private val bindings = Collections.synchronizedMap(WeakHashMap<MenuItem, MenuTarget>())

    private data class MenuTarget(val activity: WeakReference<Activity>, val talker: String)

    @Volatile private var installed = false

    fun install(): Boolean {
        if (installed) return true
        ConversationPriorityStore.load(context.hostContext())
        val comparatorInstalled = installComparator()
        val menuInstalled = installMenu()
        if (comparatorInstalled && menuInstalled) installed = true
        if (comparatorInstalled) {
            for (delay in longArrayOf(1200L, 3000L)) {
                main.postDelayed({
                    ConversationPriorityStore.ensureLoaded(context.hostContext())
                    if (ConversationPriorityStore.current().isNotEmpty()) reloadHome()
                }, delay)
            }
        }
        return comparatorInstalled && menuInstalled
    }

    fun destroy() {
        bindings.clear()
        hookedMethods.clear()
        installed = false
    }

    private fun installComparator(): Boolean = runCatching {
        val conversationClass = context.hostClassLoader()
            .loadClass("com.tencent.mm.storage.l4") ?: return false
        val candidates = context.dexKitBridge().findClass(FindClass().apply {
            matcher(ClassMatcher().apply {
                fields(FieldsMatcher().apply {
                    add(FieldMatcher().type(conversationClass))
                })
            })
        }).asSequence()
            .mapNotNull { data -> KavaReflector.loadClass(data.name, context.hostClassLoader()) }
            .filter { clazz ->
                KavaReflector.declaredFields(clazz).any { it.type == conversationClass } &&
                    KavaReflector.declaredMethods(clazz).any { method ->
                        method.name == "compareTo" && method.parameterCount == 1 &&
                            method.parameterTypes[0] == Any::class.java && method.returnType == Integer.TYPE
                    }
            }
            .toList()
        val clazz = candidates.firstOrNull { it.name == "cp5.a0" } ?: candidates.firstOrNull()
        val method = clazz?.let { owner ->
            KavaReflector.declaredMethods(owner).firstOrNull { candidate ->
                candidate.name == "compareTo" && candidate.parameterCount == 1 &&
                    candidate.parameterTypes[0] == Any::class.java && candidate.returnType == Integer.TYPE
            }
        } ?: return false
        hook(method, object : HookCallback {
            override fun before(param: HookParam) {
                applyPriorityOrder(param)
            }
        })
        true
    }.onFailure { logger("会话优先级比较器 Hook 安装失败", it) }.getOrDefault(false)

    private fun applyPriorityOrder(param: HookParam) {
        val current = param.thisObject ?: return
        val other = param.args?.firstOrNull() ?: return
        ConversationPriorityStore.ensureLoaded(context.hostContext())
        val priorities = ConversationPriorityStore.current()
        if (priorities.isEmpty()) return
        val currentConversation = conversationOf(current) ?: return
        val otherConversation = conversationOf(other) ?: return
        val currentUsername = stringField(currentConversation, "field_username") ?: return
        val otherUsername = stringField(otherConversation, "field_username") ?: return
        val currentPinned = (longField(currentConversation, "field_flag") ?: 0L) and PINNED_FLAG != 0L
        val otherPinned = (longField(otherConversation, "field_flag") ?: 0L) and PINNED_FLAG != 0L
        if (currentPinned != otherPinned) {
            param.result = if (currentPinned) -1 else 1
            return
        }
        val currentPriority = priorities[currentUsername] ?: 0
        val otherPriority = priorities[otherUsername] ?: 0
        if (currentPriority != otherPriority) {
            param.result = otherPriority.compareTo(currentPriority)
        }
    }

    private fun installMenu(): Boolean = runCatching {
        val create = ConversationMenuLocator.menuCreateMethod(context, logger) ?: return false
        hook(create, object : HookCallback {
            override fun after(param: HookParam) {
                addMenuItem(param)
            }
        })
    }.onFailure { logger("会话优先级菜单 Hook 安装失败", it) }.getOrDefault(false)

    private fun addMenuItem(param: HookParam) {
        if (!ConversationPriorityStore.enabled(context.hostContext())) return
        val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
        if (!installClickHook(param.thisObject)) return
        val target = resolveTarget(param.thisObject) ?: return
        menu.removeItem(MENU_ITEM_ID)
        val groupId = runCatching { menu.getItem(0).groupId }.getOrDefault(0)
        val item = menu.add(groupId, MENU_ITEM_ID, menu.size(), "设置优先级")
        bindings[item] = target
    }

    private fun installClickHook(listener: Any?): Boolean {
        listener ?: return false
        val method = KavaReflector.declaredFields(listener.javaClass)
            .asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field -> KavaReflector.readField(field, listener) }
            .mapNotNull { callback ->
                KavaReflector.findMethod(
                    callback.javaClass,
                    "onMMMenuItemSelected",
                    MenuItem::class.java,
                    Integer.TYPE
                )
            }
            .firstOrNull { candidate ->
                candidate.returnType == Void.TYPE &&
                    candidate.declaringClass.name.startsWith("com.tencent.mm.ui.conversation.")
            } ?: return false
        return hook(method, object : HookCallback {
            override fun before(param: HookParam) {
                val item = param.args?.getOrNull(0) as? MenuItem ?: return
                if (item.itemId != MENU_ITEM_ID) return
                val target = bindings.remove(item) ?: return
                param.result = null
                main.post {
                    val activity = target.activity.get()
                        ?.takeUnless { it.isFinishing || it.isDestroyed }
                        ?: return@post
                    showEditor(activity, target.talker)
                }
            }
        })
    }

    private fun resolveTarget(listener: Any?): MenuTarget? {
        listener ?: return null
        val fields = KavaReflector.declaredFields(listener.javaClass)
        val activity = fields.asSequence()
            .filter { Activity::class.java.isAssignableFrom(it.type) }
            .mapNotNull { KavaReflector.readField(it, listener) as? Activity }
            .firstOrNull() ?: return null
        val conversations = WeChatApis.conversations() ?: return null
        val talker = fields.asSequence()
            .filter { it.type == String::class.java }
            .mapNotNull { KavaReflector.readField(it, listener) as? String }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { value ->
                runCatching { conversations.getConversation(value) != null }.getOrDefault(false)
            } ?: return null
        return MenuTarget(WeakReference(activity), talker)
    }

    private fun showEditor(activity: Activity, talker: String) {
        ConversationPriorityStore.ensureLoaded(context.hostContext())
        VoiceForwardMiuixDialog.showTextInput(
            activity = activity,
            title = "设置会话优先级",
            summary = "范围 0–10，数值越大越靠前；0 恢复默认排序",
            initialValue = ConversationPriorityStore.priority(talker).toString(),
            placeholder = "输入 0–10",
            maxLength = 2,
            singleLine = true,
            allowEmpty = false,
            onConfirm = { text ->
                val value = text.trim().toIntOrNull()
                if (value == null || value !in ConversationPriorityStore.MIN_VALUE..ConversationPriorityStore.MAX_VALUE) {
                    Toast.makeText(activity.applicationContext, "请输入 0–10", Toast.LENGTH_SHORT).show()
                    showEditor(activity, talker)
                    return@showTextInput
                }
                if (!ConversationPriorityStore.setPriority(context.hostContext(), talker, value)) {
                    Toast.makeText(activity.applicationContext, "保存优先级失败", Toast.LENGTH_SHORT).show()
                    return@showTextInput
                }
                Toast.makeText(
                    activity.applicationContext,
                    if (value == 0) "已恢复默认排序" else "优先级已设置为 $value",
                    Toast.LENGTH_SHORT
                ).show()
                reloadHome()
            },
            onDismiss = {}
        )
    }

    private fun reloadHome() {
        val reloaded = runCatching { ConversationGroupRuntime.reloadConversationList() }.getOrDefault(false)
        if (!reloaded) ConversationGroupHomeProjection.refresh()
    }

    private fun conversationOf(item: Any?): Any? {
        item ?: return null
        var current: Class<*>? = item.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type.name == "com.tencent.mm.storage.l4") {
                    return KavaReflector.readField(field, item)
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun stringField(item: Any, name: String): String? {
        var current: Class<*>? = item.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type == String::class.java && field.name == name) {
                    return KavaReflector.readField(field, item) as? String
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun longField(item: Any, name: String): Long? {
        var current: Class<*>? = item.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.name == name && field.type == java.lang.Long.TYPE) {
                    return (KavaReflector.readField(field, item) as? Number)?.toLong()
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun hook(method: Method, callback: HookCallback): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("会话优先级 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private companion object {
        const val MENU_ITEM_ID = 0x48435052
        const val PINNED_FLAG = 1L shl 62
    }
}
