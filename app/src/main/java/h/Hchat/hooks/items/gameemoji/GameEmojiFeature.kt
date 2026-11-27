package h.Hchat.hooks.items.gameemoji

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import h.Hchat.hooks.core.HookCallback
import h.Hchat.hooks.core.HookParam
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class GameEmojiFeature : BaseFeature() {
    private var runtime: GameEmojiRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "指定骰子猜拳"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GameEmojiSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = GameEmojiRuntime(context, ::logRuntimeError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.WARMUP) {
            runtime?.install() == true
        }
    }

    private fun logRuntimeError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "game_emoji_result"
    }
}

private class GameEmojiRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val replaying = ThreadLocal<Boolean>()
    private val dialogShowing = AtomicBoolean(false)
    private var emojiFields: Map<String, Field> = emptyMap()
    private var emojiFieldsClass: Class<*>? = null

    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val methods = locateSendMethods()
        if (methods.isEmpty()) {
            logger("游戏表情发送方法未找到", null)
            return false
        }
        return runCatching {
            methods.forEach { method ->
                HookRegistry.get().hook(method, object : HookCallback {
                    override fun before(param: HookParam) {
                        if (replaying.get() == true) return
                        val emojiInfo = findEmojiArg(param.args) ?: return
                        if (ensureEmojiFields(emojiInfo.javaClass) == null) return
                        val gameType = detectGameType(emojiInfo) ?: return
                        val fixed = GameEmojiSettings.fixedResultEnabled(context.hostContext())
                        val pick = GameEmojiSettings.pickBeforeSendEnabled(context.hostContext())
                        if (!fixed && !pick) return
                        if (pick) {
                            interceptForChoice(method, param, gameType, emojiInfo)
                        } else {
                            val result = configuredResult(gameType)
                            if (!applyResult(emojiInfo, gameType, result)) {
                                logger("改写游戏表情结果失败", null)
                            }
                        }
                    }
                })
            }
            installed = true
            true
        }.getOrElse {
            emojiFields = emptyMap()
            emojiFieldsClass = null
            logger("游戏表情发送 Hook 安装失败", it)
            false
        }
    }

    private fun locateSendMethods(): List<Method> {
        val loader = context.hostClassLoader()
        val out = LinkedHashMap<String, Method>()
        fun add(method: Method?) {
            method ?: return
            out.putIfAbsent("${method.declaringClass.name}#${method.name}${method.parameterTypes.joinToString { it.name }}", method)
        }
        // 聊天窗口表情发送组件（8.0.80 由 component.sm/wm 调用）。
        val chatSender = KavaReflector.loadClass("com.tencent.mm.ui.chatting.k8", loader)
        chatSender?.let { sender ->
            KavaReflector.declaredMethods(sender).filter { method ->
                method.parameterTypes.size == 1 && isEmojiInfoType(method.parameterTypes[0])
            }.forEach(::add)
        }
        // 聊天窗口最终发送入口。
        KavaReflector.loadClass("com.tencent.mm.feature.emoji.s0", loader)?.let { sender ->
            KavaReflector.declaredMethods(sender).firstOrNull { method ->
                method.name == "Mj" && method.parameterTypes.size == 1 &&
                    isEmojiInfoType(method.parameterTypes[0])
            }?.let(::add)
        }
        // 其他发送路径兜底。
        KavaReflector.loadClass("com.tencent.mm.feature.emoji.u0", loader)?.let { sender ->
            KavaReflector.declaredMethods(sender).firstOrNull { method ->
                method.name == "Ej" &&
                    method.parameterTypes.size == 4 &&
                    method.parameterTypes[0].name == "android.content.Context" &&
                    isEmojiInfoType(method.parameterTypes[1])
            }?.let(::add)
        }
        KavaReflector.loadClass("com.tencent.mm.feature.emoji.b0", loader)?.let { sender ->
            val candidates = KavaReflector.declaredMethods(sender).filter { method ->
                method.returnType == Void.TYPE && method.parameterTypes.any(::isEmojiInfoType)
            }
            add(candidates.firstOrNull { it.name == "Tj" })
        }
        add(context.dexFinder().emojiSendMethod?.takeIf(::isEmojiSendMethod))
        return out.values.toList()
    }

    private fun isEmojiInfoType(type: Class<*>): Boolean {
        val name = type.name
        return name == "com.tencent.mm.storage.emotion.EmojiInfo" ||
            name == "com.tencent.mm.api.IEmojiInfo" ||
            name.endsWith(".EmojiInfo")
    }

    private fun findEmojiArg(args: Array<Any?>?): Any? {
        args ?: return null
        return args.firstOrNull { value ->
            val name = value?.javaClass?.name ?: return@firstOrNull false
            name == "com.tencent.mm.storage.emotion.EmojiInfo" ||
                name.endsWith(".EmojiInfo") ||
                name == "com.tencent.mm.api.IEmojiInfo" ||
                name.endsWith("IEmojiInfo")
        }
    }

    private fun ensureEmojiFields(emojiClass: Class<*>): Map<String, Field>? {
        emojiFields.takeIf { emojiFieldsClass == emojiClass && it.isNotEmpty() }?.let { return it }
        val resolved = resolveEmojiFields(emojiClass) ?: return null
        emojiFields = resolved
        emojiFieldsClass = emojiClass
        return resolved
    }

    private fun interceptForChoice(
        method: Method,
        param: HookParam,
        gameType: GameType,
        emojiInfo: Any
    ) {
        val activity = (WeChatApis.currentActivity()?.currentActivity() as? Activity)
            ?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: findActivityFromObject(param.thisObject)
            ?: run {
                logger("选择骰子结果失败: 未找到 Activity", null)
                return
            }
        if (!dialogShowing.compareAndSet(false, true)) {
            param.result = cancelResult(method)
            return
        }
        val emojiIndex = param.args?.indexOfFirst { it === emojiInfo } ?: -1
        if (emojiIndex < 0) {
            logger("选择骰子结果失败: EmojiInfo 参数索引未找到", null)
            dialogShowing.set(false)
            return
        }
        val pending = PendingSend(method, param.thisObject, param.args.copyOf(), gameType, emojiIndex)
        param.result = cancelResult(method)
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                dialogShowing.set(false)
                replay(pending, null)
                return@post
            }
            val choices = resultsFor(gameType).map { it.label to "" }
            val handle = VoiceForwardMiuixDialog.showChoices(
                activity = activity,
                title = if (gameType == GameType.DICE) "选择骰子点数" else "选择猜拳结果",
                summary = "",
                choices = choices,
                onSelected = { index ->
                    resultsFor(gameType).getOrNull(index)?.let { replay(pending, it) }
                },
                onDismiss = { dialogShowing.set(false) }
            )
            if (!handle.isShowing()) {
                dialogShowing.set(false)
                replay(pending, null)
            }
        }
    }

    private fun cancelResult(method: Method): Any? = when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    private fun findActivityFromObject(target: Any?, depth: Int = 2): Activity? {
        target ?: return null
        if (target is Activity) return target
        if (target is Context) return findActivity(target)
        if (depth <= 0) return null
        val clazz = target.javaClass
        if (clazz.name.startsWith("java.") || clazz.name.startsWith("android.")) return null
        for (field in KavaReflector.declaredFields(clazz)) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            val value = runCatching { KavaReflector.readField(field, target) }.getOrNull() ?: continue
            if (value is Activity) return value
            if (value is Context) findActivity(value)?.let { return it }
            if (field.type.name.startsWith("com.tencent.mm.")) {
                findActivityFromObject(value, depth - 1)?.let { return it }
            }
        }
        return null
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return current as? Activity
    }

    private fun replay(pending: PendingSend, selected: GameResult?) {
        val emojiInfo = pending.args.getOrNull(pending.emojiArgIndex) ?: return
        if (selected != null && !applyResult(emojiInfo, pending.gameType, selected.value)) {
            logger("应用所选游戏表情结果失败", null)
            return
        }
        replaying.set(true)
        try {
            KavaReflector.invokeOrThrow(pending.method, pending.receiver, *pending.args)
        } catch (e: Throwable) {
            logger("重新发送游戏表情失败", e)
        } finally {
            replaying.remove()
        }
    }

    private fun configuredResult(gameType: GameType): Int {
        return if (gameType == GameType.DICE) {
            GameEmojiSettings.diceResult(context.hostContext())
        } else {
            GameEmojiSettings.rpsResult(context.hostContext())
        }
    }

    private fun detectGameType(emojiInfo: Any): GameType? {
        val name = readString(emojiInfo, FIELD_NAME)
        val content = readString(emojiInfo, FIELD_CONTENT)
        val md5 = readString(emojiInfo, FIELD_MD5)
        return when {
            name.startsWith("dice", ignoreCase = true) ||
                content.contains("type=\"2\"") || DICE_MD5.contains(md5) -> GameType.DICE
            name.startsWith("jsb", ignoreCase = true) ||
                content.contains("type=\"1\"") || RPS_MD5.contains(md5) -> GameType.RPS
            else -> null
        }
    }

    private fun applyResult(emojiInfo: Any, gameType: GameType, value: Int): Boolean {
        val result = resultsFor(gameType).firstOrNull { it.value == value } ?: return false
        val contentValue = if (gameType == GameType.DICE) value + 3 else value
        val values = mapOf<String, Any?>(
            FIELD_MD5 to result.md5,
            FIELD_SIZE to result.size,
            FIELD_CONTENT to "<gameext type=\"${gameType.protocolType}\" content=\"$contentValue\" ></gameext>",
            FIELD_NAME to result.fileName,
            FIELD_SVR_ID to "",
            FIELD_CATALOG to 50,
            FIELD_RESERVED_3 to 0,
            FIELD_RESERVED_4 to 0,
            FIELD_GROUP_ID to "50",
            FIELD_SOURCE to 0,
            FIELD_DESIGNER_ID to null,
            FIELD_THUMB_URL to null
        )
        var success = true
        values.forEach { (name, fieldValue) ->
            success = KavaReflector.writeField(emojiFields[name], emojiInfo, fieldValue) && success
        }
        return success
    }

    private fun readString(emojiInfo: Any, name: String): String {
        return KavaReflector.readField(emojiFields[name], emojiInfo) as? String ?: ""
    }

    private fun resolveEmojiFields(emojiClass: Class<*>): Map<String, Field>? {
        val fields = LinkedHashMap<String, Field>()
        REQUIRED_FIELDS.forEach { name ->
            val field = KavaReflector.findFieldRecursive(emojiClass, name) ?: run {
                logger("EmojiInfo 缺少字段: $name", null)
                return null
            }
            fields[name] = field
        }
        return fields
    }

    private fun isEmojiSendMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == java.lang.Void.TYPE &&
            types.size >= 4 &&
            types[0] == String::class.java &&
            types[1].name == "com.tencent.mm.storage.emotion.EmojiInfo"
    }

    private fun resultsFor(gameType: GameType): List<GameResult> {
        return if (gameType == GameType.DICE) DICE_RESULTS else RPS_RESULTS
    }

    private data class PendingSend(
        val method: Method,
        val receiver: Any?,
        val args: Array<Any?>,
        val gameType: GameType,
        val emojiArgIndex: Int
    )

    private enum class GameType(val protocolType: Int) {
        RPS(1),
        DICE(2)
    }

    private data class GameResult(
        val value: Int,
        val label: String,
        val md5: String,
        val size: Int,
        val fileName: String
    )

    companion object {
        private const val FIELD_MD5 = "field_md5"
        private const val FIELD_SVR_ID = "field_svrid"
        private const val FIELD_CATALOG = "field_catalog"
        private const val FIELD_SIZE = "field_size"
        private const val FIELD_NAME = "field_name"
        private const val FIELD_CONTENT = "field_content"
        private const val FIELD_RESERVED_3 = "field_reserved3"
        private const val FIELD_RESERVED_4 = "field_reserved4"
        private const val FIELD_GROUP_ID = "field_groupId"
        private const val FIELD_SOURCE = "field_source"
        private const val FIELD_DESIGNER_ID = "field_designerID"
        private const val FIELD_THUMB_URL = "field_thumbUrl"

        private val REQUIRED_FIELDS = listOf(
            FIELD_MD5,
            FIELD_SVR_ID,
            FIELD_CATALOG,
            FIELD_SIZE,
            FIELD_NAME,
            FIELD_CONTENT,
            FIELD_RESERVED_3,
            FIELD_RESERVED_4,
            FIELD_GROUP_ID,
            FIELD_SOURCE,
            FIELD_DESIGNER_ID,
            FIELD_THUMB_URL
        )

        private val RPS_RESULTS = listOf(
            GameResult(1, "剪刀", "514914788fc461e7205bf0b6ba496c49", 2782, "jsb_j.png"),
            GameResult(2, "石头", "f790e342a02e0f99d34b316547f9aeab", 2278, "jsb_s.png"),
            GameResult(3, "布", "091577322c40c05aa3dd701da29d6423", 3612, "jsb_b.png")
        )
        private val DICE_RESULTS = listOf(
            GameResult(1, "1 点", "da1c289d4e363f3ce1ff36538903b92f", 2342, "dice_1.png"),
            GameResult(2, "2 点", "9e3f303561566dc9342a3ea41e6552a6", 2278, "dice_2.png"),
            GameResult(3, "3 点", "dbcc51db2765c1d0106290bae6326fc4", 2404, "dice_3.png"),
            GameResult(4, "4 点", "9a21c57defc4974ab5b7c842e3232671", 2422, "dice_4.png"),
            GameResult(5, "5 点", "3a8e16d650f7e66ba5516b2780512830", 2538, "dice_5.png"),
            GameResult(6, "6 点", "5ba8e9694b853df10b9f2a77b312cc09", 2536, "dice_6.png")
        )
        private val RPS_MD5 = RPS_RESULTS.mapTo(HashSet()) { it.md5 }
        private val DICE_MD5 = DICE_RESULTS.mapTo(HashSet()) { it.md5 }
    }
}
