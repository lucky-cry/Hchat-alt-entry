package h.Hchat.hooks.items.callmedialimit

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class CallRingtoneBlockFeature : BaseFeature() {
    private var hooker: CallRingtoneBlockHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "屏蔽通话铃声"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(CallRingtoneBlockSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = CallRingtoneBlockHooker(context, ::logRuntimeError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
    }

    private fun logRuntimeError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "call_ringtone_block"
    }
}

private class CallRingtoneBlockHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class CoreStartAccess(
        val start: Method,
        val acknowledge: Method
    )

    private val settingsPrefs = HchatStorage.preferences(
        context.hostContext(),
        CallMediaLimitSettings.PREFS_NAME
    )
    private val methodCache = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
    private val coreAcknowledgeFailures = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        return runCatching {
            val directionMethods = locateDirectionMethods()
            val multiTalkDirectionMethods = locateMultiTalkDirectionMethods()
            val coreAccesses = locateCoreStartAccesses()
            if (directionMethods.isEmpty() && multiTalkDirectionMethods.isEmpty() && coreAccesses.isEmpty()) {
                return false
            }

            directionMethods.forEach { method ->
                hookDirectionMethod(method, OUTGOING_ARGUMENT_INDEX)
            }
            multiTalkDirectionMethods.forEach { method ->
                hookDirectionMethod(method, MULTI_TALK_OUTGOING_ARGUMENT_INDEX)
            }
            coreAccesses.forEach { access ->
                HookRegistry.get().hook(access.start, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val roomRole = param.args?.getOrNull(3) as? Boolean ?: return
                        val outgoing = !roomRole
                        if (!shouldBlock(outgoing)) return
                        val requestId = param.args?.getOrNull(0) as? Long ?: return
                        runCatching {
                            KavaReflector.invokeOrThrow(
                                access.acknowledge,
                                param.thisObject,
                                requestId
                            )
                        }.onSuccess {
                            param.result = null
                        }.onFailure {
                            logCoreAcknowledgeFailure(access.start, it)
                        }
                    }
                })
            }
            installed = true
            true
        }.getOrElse {
            logger("通话铃声屏蔽 Hook 安装失败", it)
            false
        }
    }

    private fun hookDirectionMethod(method: Method, outgoingArgumentIndex: Int) {
        HookRegistry.get().hook(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val outgoing = param.args?.getOrNull(outgoingArgumentIndex) as? Boolean ?: return
                if (shouldBlock(outgoing)) param.result = null
            }
        })
    }

    private fun locateDirectionMethods(): List<Method> {
        val runtimeKey = runtimeKey()
        DexMethodCache.loadList(
            methodCache,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_DIRECTION_METHODS
        ).takeIf { methods -> methods.isNotEmpty() && methods.all(::isDirectionMethod) }
            ?.let { return it }

        val methods = LinkedHashSet<Method>()
        DIRECTION_ANCHOR_GROUPS.forEach { anchors ->
            val candidates = runCatching {
                context.dexKitBridge().findMethod(
                    FindMethod().apply {
                        matcher(MethodMatcher().apply { usingEqStrings(*anchors) })
                    }
                ).mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
            }.getOrElse {
                logger("定位微信通话铃声入口失败: ${anchors.firstOrNull().orEmpty()}", it)
                emptyList()
            }
            candidates.filterTo(methods, ::isDirectionMethod)
        }
        val result = methods.distinctBy { it.toGenericString() }
        if (result.isEmpty()) {
            DexMethodCache.clear(methodCache, runtimeKey, CACHE_DIRECTION_METHODS)
            logger("未定位微信通话铃声入口", null)
        } else {
            DexMethodCache.saveList(methodCache, runtimeKey, CACHE_DIRECTION_METHODS, result)
        }
        return result
    }


    /**
     * 8.0.80 兼容兜底：改用 findMethod 反查「使用铃声锚点的方法」的宿主类，
     * 再在类内找 (Z)->V 方向方法（该类应同时具备 (Z,I)->V 路由方法）。
     * 旧锚点 requestAudioDeviceToStartRing 系列在 8.0.80 已不存在。
     */
    private fun locateMultiTalkDirectionMethodsByClassAnchor(): List<Method> {
        val anchors = listOf(
            "onStartRing, currentState: %s",
            "startRing() called with: username = "
        )
        val result = LinkedHashSet<Method>()
        for (anchor in anchors) {
            val found = runCatching {
                context.dexKitBridge().findMethod(
                    FindMethod().apply {
                        matcher(MethodMatcher().apply { usingEqStrings(anchor) })
                    }
                )
            }.getOrElse {
                logger("铃声兜底: 查询锚点失败 $anchor", it)
                emptyList()
            }
            logger("铃声兜底: 锚点 [$anchor] 命中方法 ${found.size}", null)
            val owners = LinkedHashSet<Class<*>>()
            for (data in found) {
                runCatching {
                    data.getMethodInstance(context.hostClassLoader()).declaringClass
                }.getOrNull()?.let { owners.add(it) }
            }
            for (cl in owners) {
                val declared = KavaReflector.declaredMethods(cl)
                val direction = declared.filter(::isMultiTalkDirectionMethod)
                val hasRoute = declared.any { method ->
                    val types = method.parameterTypes
                    types.size == 2 &&
                        types[0] == java.lang.Boolean.TYPE &&
                        types[1] == java.lang.Integer.TYPE &&
                        method.returnType == Void.TYPE
                }
                logger("铃声兜底: 类 ${cl.name} 方向=${direction.size} 路由=$hasRoute", null)
                // 实测 8.0.80 该类有 5 个 (Z)->V 方法，无法只靠数量区分，
                // 只要有路由方法就全部纳入，由 hook 内部的 outgoing 判断决定是否拦截。
                if (hasRoute && direction.isNotEmpty()) result.addAll(direction)
            }
            if (result.isNotEmpty()) break
        }
        return result.toList()
    }

    private fun locateMultiTalkDirectionMethods(): List<Method> {
        val runtimeKey = runtimeKey()
        DexMethodCache.loadList(
            methodCache,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_MULTI_TALK_DIRECTION_METHODS
        ).takeIf { methods -> methods.isNotEmpty() && methods.all(::isMultiTalkDirectionMethod) }
            ?.let { return it }

        val routeData = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply { usingEqStrings(*MULTI_TALK_ROUTE_ANCHORS) })
                }
            )
        }.getOrElse {
            logger("定位微信群通话铃声路由失败", it)
            emptyList()
        }
        val methods = routeData.mapNotNull { data ->
            val route = runCatching {
                data.getMethodInstance(context.hostClassLoader())
            }.getOrNull()?.takeIf(::isMultiTalkRouteMethod) ?: return@mapNotNull null
            runCatching { data.invokes }.getOrDefault(emptyList())
                .mapNotNull { invoke ->
                    runCatching { invoke.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .filter { method ->
                    method.declaringClass == route.declaringClass && isMultiTalkDirectionMethod(method)
                }
                .distinctBy { it.toGenericString() }
                .singleOrNull()
        }.distinctBy { it.toGenericString() }

        if (methods.isEmpty()) {
            // 8.0.80 起旧路由锚点全部消失，改为按类锚点直接找 (Z)->V 的铃声入口。
            val classFallback = locateMultiTalkDirectionMethodsByClassAnchor()
            if (classFallback.isNotEmpty()) {
                DexMethodCache.saveList(methodCache, runtimeKey, CACHE_MULTI_TALK_DIRECTION_METHODS, classFallback)
                return classFallback
            }
        }
        if (methods.isEmpty()) {
            DexMethodCache.clear(methodCache, runtimeKey, CACHE_MULTI_TALK_DIRECTION_METHODS)
            logger("未定位微信群通话铃声入口", null)
        } else {
            DexMethodCache.saveList(
                methodCache,
                runtimeKey,
                CACHE_MULTI_TALK_DIRECTION_METHODS,
                methods
            )
        }
        return methods
    }

    private fun locateCoreStartAccesses(): List<CoreStartAccess> {
        val runtimeKey = runtimeKey()
        val cachedStarts = DexMethodCache.loadList(
            methodCache,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_CORE_START_METHODS
        )
        val cachedAcknowledges = DexMethodCache.loadList(
            methodCache,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_CORE_ACK_METHODS
        )
        if (cachedStarts.size == cachedAcknowledges.size && cachedStarts.isNotEmpty()) {
            val cached = cachedStarts.zip(cachedAcknowledges).map { (start, acknowledge) ->
                CoreStartAccess(start, acknowledge)
            }
            if (cached.all(::isCoreStartAccess)) return cached
        }
        if (cachedStarts.isNotEmpty() || cachedAcknowledges.isNotEmpty()) {
            methodCache.edit().remove(CACHE_CORE_SCAN_RUNTIME).apply()
        }
        DexMethodCache.clear(methodCache, runtimeKey, CACHE_CORE_START_METHODS)
        DexMethodCache.clear(methodCache, runtimeKey, CACHE_CORE_ACK_METHODS)

        if (methodCache.getString(CACHE_CORE_SCAN_RUNTIME, "") == runtimeKey) {
            return emptyList()
        }
        val methodData = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply { usingEqStrings(*CORE_START_ANCHORS) })
                }
            )
        }.getOrElse {
            logger("定位微信 CoreV2 通话铃声入口失败", it)
            return emptyList()
        }
        val accesses = methodData.mapNotNull { data ->
            val start = runCatching {
                data.getMethodInstance(context.hostClassLoader())
            }.getOrNull()?.takeIf(::isCoreStartMethod) ?: return@mapNotNull null
            val acknowledge = runCatching { data.invokes }.getOrDefault(emptyList())
                .mapNotNull { invoke ->
                    runCatching { invoke.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .filter { method -> isCoreAcknowledgeMethod(method, start) }
                .distinctBy { it.toGenericString() }
                .singleOrNull()
                ?: return@mapNotNull null
            CoreStartAccess(start, acknowledge)
        }.distinctBy { it.start.toGenericString() }

        if (accesses.isNotEmpty()) {
            DexMethodCache.saveList(
                methodCache,
                runtimeKey,
                CACHE_CORE_START_METHODS,
                accesses.map { it.start }
            )
            DexMethodCache.saveList(
                methodCache,
                runtimeKey,
                CACHE_CORE_ACK_METHODS,
                accesses.map { it.acknowledge }
            )
        }
        methodCache.edit().putString(CACHE_CORE_SCAN_RUNTIME, runtimeKey).apply()
        return accesses
    }

    private fun isDirectionMethod(method: Method): Boolean {
        val types = method.parameterTypes
        val threeParameter = types.size == 3 &&
            types[0] == String::class.java &&
            types[1] == java.lang.Boolean.TYPE &&
            types[2] == java.lang.Boolean.TYPE
        val fiveParameter = types.size == 5 &&
            types[0] == String::class.java &&
            types[1] == java.lang.Boolean.TYPE &&
            types[2] == java.lang.Boolean.TYPE &&
            types[3] == java.lang.Long.TYPE &&
            types[4] == java.lang.Boolean.TYPE
        return (threeParameter || fiveParameter) &&
            method.returnType == Void.TYPE &&
            !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method)
    }

    private fun isMultiTalkRouteMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size == 2 &&
            types[0] == java.lang.Boolean.TYPE &&
            types[1] == java.lang.Integer.TYPE &&
            method.returnType == Void.TYPE &&
            !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method)
    }

    private fun isMultiTalkDirectionMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size == 1 &&
            types[0] == java.lang.Boolean.TYPE &&
            method.returnType == Void.TYPE &&
            !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method)
    }

    private fun isCoreStartAccess(access: CoreStartAccess): Boolean {
        return isCoreStartMethod(access.start) &&
            isCoreAcknowledgeMethod(access.acknowledge, access.start)
    }

    private fun isCoreStartMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size == 4 &&
            types[0] == java.lang.Long.TYPE &&
            types[1] == ByteArray::class.java &&
            types[2] == java.lang.Integer.TYPE &&
            types[3] == java.lang.Boolean.TYPE &&
            method.returnType == Void.TYPE &&
            !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method)
    }

    private fun isCoreAcknowledgeMethod(method: Method, start: Method): Boolean {
        val types = method.parameterTypes
        return method.declaringClass == start.declaringClass &&
            types.size == 1 &&
            types[0] == java.lang.Long.TYPE &&
            method.returnType == Void.TYPE &&
            !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method)
    }

    private fun shouldBlock(outgoing: Boolean): Boolean {
        return if (outgoing) {
            settingsPrefs.getBoolean(
                CallMediaLimitSettings.KEY_BLOCK_OUTGOING_RINGTONE,
                CallMediaLimitSettings.DEFAULT_BLOCK_OUTGOING_RINGTONE
            )
        } else {
            settingsPrefs.getBoolean(
                CallMediaLimitSettings.KEY_BLOCK_INCOMING_RINGTONE,
                CallMediaLimitSettings.DEFAULT_BLOCK_INCOMING_RINGTONE
            )
        }
    }

    private fun logCoreAcknowledgeFailure(method: Method, throwable: Throwable) {
        val key = method.toGenericString()
        if (coreAcknowledgeFailures.add(key)) {
            logger("CoreV2 通话铃声确认回调失败: $key", throwable)
        }
    }

    private fun runtimeKey(): String =
        DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())

    private companion object {
        const val CACHE_PREFS = "Hchat_call_ringtone_block_method_cache"
        const val CACHE_DIRECTION_METHODS = "direction_methods_v1"
        const val CACHE_MULTI_TALK_DIRECTION_METHODS = "multi_talk_direction_methods_v1"
        const val CACHE_CORE_START_METHODS = "core_start_methods_v1"
        const val CACHE_CORE_ACK_METHODS = "core_ack_methods_v1"
        const val CACHE_CORE_SCAN_RUNTIME = "core_scan_runtime_v1"
        const val OUTGOING_ARGUMENT_INDEX = 2
        const val MULTI_TALK_OUTGOING_ARGUMENT_INDEX = 0
        val DIRECTION_ANCHOR_GROUPS = listOf(
            arrayOf(
                "MicroMsg.VoIP.VoIPAudioManager",
                "continuePlayStartRing username:",
                "isOutCall",
                "scene",
                "start"
            ),
            arrayOf(
                "startRing() called with: username = ",
                "isOutCall",
                "isSpeakOn",
                "seekStartMs",
                "scene",
                "start"
            )
        )
        val CORE_START_ANCHORS = arrayOf(
            "MicroMsg.VoIPMP.CoreV2",
            "startRing() called with: username = ",
            ", roomType = ",
            ", roomRole = "
        )
        val MULTI_TALK_ROUTE_ANCHORS = arrayOf(
            "MicroMsg.MT.MultiTalkAudioManager",
            "requestAudioDeviceToStartRing but waitting the bt connected",
            "requestAudioDeviceToStartRing ignore bluetooth or not bt plug"
        )
    }
}
