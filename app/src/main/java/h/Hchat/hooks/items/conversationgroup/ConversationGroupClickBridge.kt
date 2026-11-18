package h.Hchat.hooks.items.conversationgroup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.RecyclerView
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookCallback
import h.Hchat.hooks.core.HookParam
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.util.Collections
import java.util.WeakHashMap

/**
 * 8.0.80 首页是 RecyclerView；分组运行时在部分机型上会卡在首页投影定位，
 * 这里单独装一个最小点击桥，保证已存在的虚拟分组行能进入分组页，
 * 而不是把 wxid_hchat_group_... 当普通联系人打开。
 */
internal object ConversationGroupClickBridge {
    private const val TAG = "[Hchat:ConversationGroup]"
    private const val VIRTUAL_PREFIX = "wxid_hchat_group_"
    private const val CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI"
    private const val CHAT_USER = "Chat_User"

    private val installedViews = Collections.newSetFromMap(WeakHashMap<RecyclerView, Boolean>())
    private val activeOverlays = Collections.synchronizedMap(WeakHashMap<Activity, View>())
    private val backCallbacks = Collections.synchronizedMap(
        WeakHashMap<Activity, OnBackPressedCallback>()
    )
    @Volatile private var installed = false

    /** 在 ModuleEntry.bootstrap 阶段调用：早于微信首页 RecyclerView 创建。 */
    fun installEarly(classLoader: ClassLoader): Boolean {
        if (installed) return true
        val recyclerClass = KavaReflector.loadClass(
            "androidx.recyclerview.widget.RecyclerView",
            classLoader
        ) ?: return false
        var ok = false
        KavaReflector.declaredConstructors(recyclerClass).forEach { ctor ->
            if (runCatching {
                    HookRegistry.get().hook(ctor, object : HookCallback {
                        override fun after(param: HookParam) {
                            (param.thisObject as? RecyclerView)?.let(::attach)
                        }
                    })
                }.isSuccess
            ) ok = true
        }
        val setAdapter = KavaReflector.declaredMethods(recyclerClass).firstOrNull {
            it.name == "setAdapter" && it.parameterTypes.size == 1
        }
        if (setAdapter != null && runCatching {
                HookRegistry.get().hook(setAdapter, object : HookCallback {
                    override fun after(param: HookParam) {
                        (param.thisObject as? RecyclerView)?.let(::attach)
                    }
                })
            }.isSuccess
        ) ok = true
        // 8.0.80 首页可能仍保留 b3.onItemClick 作为兼容点击入口。
        val legacyClickClass = KavaReflector.loadClass(
            "com.tencent.mm.ui.conversation.b3",
            classLoader
        )
        val legacyClick = legacyClickClass?.let { clazz ->
            KavaReflector.declaredMethods(clazz).firstOrNull {
                it.name == "onItemClick" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[2] == java.lang.Integer.TYPE
            }
        }
        val legacyInstalled = legacyClick?.let { method ->
            runCatching {
                HookRegistry.get().hook(method, object : HookCallback {
                    override fun before(param: HookParam) {
                        val adapter = param.args?.getOrNull(0) as? android.widget.AdapterView<*> ?: return
                        val position = (param.args?.getOrNull(2) as? Int) ?: return
                        val item = runCatching { adapter.getItemAtPosition(position) }.getOrNull() ?: return
                        val talker = virtualTalker(item) ?: return
                        HLog.e("$TAG 旧点击入口命中: talker=$talker")
                        val activity = findActivity(adapter.context) ?: return
                        param.result = null
                        openGroup(activity, talker)
                    }
                })
            }.isSuccess
        } ?: false
        if (ok || legacyInstalled) installed = true
        HLog.e("$TAG 点击桥早期安装: ctor/setAdapter=$ok legacy=$legacyInstalled")
        return ok || legacyInstalled
    }

    /** 虚拟分组被当普通会话打开时，ChattingUI 直接转去原生分组页。 */
    fun installChattingRedirect(classLoader: ClassLoader): Boolean {
        val chattingClass = KavaReflector.loadClass(CHATTING_UI, classLoader) ?: return false
        var owner: Class<*>? = chattingClass
        var onCreate: java.lang.reflect.Method? = null
        while (owner != null && onCreate == null) {
            onCreate = KavaReflector.declaredMethods(owner).firstOrNull {
                it.name == "onCreate" && it.parameterTypes.size == 1
            }
            owner = owner.superclass
        }
        if (onCreate == null) {
            HLog.e("$TAG 找不到 ChattingUI.onCreate")
            return false
        }
        return runCatching {
            HookRegistry.get().hook(onCreate, object : HookCallback {
                override fun after(param: HookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val talker = activity.intent?.getStringExtra(CHAT_USER) ?: return
                    if (!talker.startsWith(VIRTUAL_PREFIX)) return
                    HLog.e("$TAG 拦截虚拟分组会话: talker=$talker")
                    runCatching {
                        activity.startActivity(
                            Intent().setClassName(
                                activity,
                                "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
                            ).putExtra("Contact_User", talker)
                        )
                        activity.finish()
                    }.onFailure {
                        HLog.e("$TAG 转原生分组页失败: ${it.message}", it)
                    }
                }
            })
        }.isSuccess
    }

    /**
     * 通用 Fragment 观察器：不依赖具体聊天 Fragment 混淆名。
     * 任何 Fragment 的字段里出现虚拟 talker 时，直接跳原生分组页并移除该 Fragment。
     */
    fun installFragmentWatcher(classLoader: ClassLoader): Boolean {
        val fragmentClass = KavaReflector.loadClass(
            "androidx.fragment.app.Fragment",
            classLoader
        ) ?: return false
        val onResume = KavaReflector.declaredMethods(fragmentClass).firstOrNull {
            it.name == "onResume" && it.parameterTypes.isEmpty()
        } ?: return false
        return runCatching {
            HookRegistry.get().hook(onResume, object : HookCallback {
                override fun after(param: HookParam) {
                    val fragment = param.thisObject ?: return
                    val talker = findVirtualField(fragment) ?: return
                    HLog.e("$TAG Fragment 观察器命中: ${fragment.javaClass.name} talker=$talker")
                    val activity = KavaReflector.invokeMethod(fragment, "getActivity") as? Activity
                        ?: return
                    // ChattingUIFragment 的 onResume 早于聊天内容最终布局；立即挂浮层
                    // 会被随后渲染的原生“非好友”页盖住。延后一拍再显示，并在
                    // 后续 onResume 中把已有浮层提到最前。
                    showGroupPageOverlay(activity, talker)
                    activeOverlays[activity]?.bringToFront()
                }
            })
        }.isSuccess
    }

    /** 分组页显示时，按返回先收起分组页，让微信自己回首页。 */
    fun installBackHandler(classLoader: ClassLoader): Boolean {
        val launcherClass = KavaReflector.loadClass(
            "com.tencent.mm.ui.LauncherUI",
            classLoader
        ) ?: return false
        val onBackPressed = KavaReflector.declaredMethods(launcherClass).firstOrNull {
            it.name == "onBackPressed" && it.parameterTypes.isEmpty()
        } ?: return false
        var installed = runCatching {
            HookRegistry.get().hook(onBackPressed, object : HookCallback {
                override fun before(param: HookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (removeGroupPage(activity)) {
                        KavaReflector.invokeMethod(activity, "closeChatting", false)
                    }
                }
            })
        }.isSuccess
        val dispatchKey = KavaReflector.declaredMethods(launcherClass).firstOrNull {
            it.name == "dispatchKeyEvent" && it.parameterTypes.size == 1
        }
        dispatchKey?.let { method ->
            installed = runCatching {
                HookRegistry.get().hook(method, object : HookCallback {
                    override fun before(param: HookParam) {
                        val event = param.args?.getOrNull(0) as? android.view.KeyEvent ?: return
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                            event.action == android.view.KeyEvent.ACTION_UP
                        ) {
                            val activity = param.thisObject as? Activity ?: return
                            if (removeGroupPage(activity)) {
                                KavaReflector.invokeMethod(activity, "closeChatting", false)
                                param.result = true
                            }
                        }
                    }
                })
            }.isSuccess || installed
        }
        return installed
    }

    private fun removeGroupPage(activity: Activity?): Boolean {
        activity ?: return false
        backCallbacks.remove(activity)?.remove()
        val view = activeOverlays.remove(activity) ?: return false
        (view.parent as? ViewGroup)?.removeView(view)
        return true
    }

    /** 每次打开聊天都会走 ChattingUIProxy.onInit，用它覆盖第二次及以后打开。 */
    fun installChattingProxyHook(classLoader: ClassLoader): Boolean {
        val proxyClass = KavaReflector.loadClass(
            "com.tencent.mm.ui.chatting.ChattingUIProxy",
            classLoader
        ) ?: return false
        val methods = KavaReflector.declaredMethods(proxyClass)
        val onInit = methods.firstOrNull { it.name == "onInit" } ?: return false
        val onEnterBegin = methods.firstOrNull {
            it.name == "onEnterBegin" && it.parameterTypes.isEmpty()
        }
        val onEnterEnd = methods.firstOrNull {
            it.name == "onEnterEnd" && it.parameterTypes.isEmpty()
        }
        val onExitEnd: java.lang.reflect.Method? = null
        fun show(proxy: Any) {
            val talker = findVirtualFieldDeep(proxy) ?: return
            val activity = findActivityDeep(proxy)
                ?: (WeChatApis.currentActivity()?.currentActivity() as? Activity)
                ?: return
            HLog.e("$TAG ChattingUIProxy 命中虚拟分组: talker=$talker")
            showGroupPageOverlay(activity, talker)
            activeOverlays[activity]?.bringToFront()
        }
        onEnterBegin?.let { method ->
            runCatching {
                HookRegistry.get().hook(method, object : HookCallback {
                    override fun after(param: HookParam) {
                        param.thisObject?.let(::show)
                    }
                })
            }
        }
        var installed = runCatching {
            HookRegistry.get().hook(onInit, object : HookCallback {
                override fun after(param: HookParam) {
                    param.thisObject?.let(::show)
                }
            })
        }.isSuccess
        onEnterEnd?.let { method ->
            installed = runCatching {
                HookRegistry.get().hook(method, object : HookCallback {
                    override fun after(param: HookParam) {
                        param.thisObject?.let(::show)
                    }
                })
            }.isSuccess || installed
        }
        onExitEnd?.let { method ->
            installed = runCatching {
                HookRegistry.get().hook(method, object : HookCallback {
                    override fun after(param: HookParam) {
                        val proxy = param.thisObject ?: return
                        val activity = findActivityDeep(proxy)
                            ?: (WeChatApis.currentActivity()?.currentActivity() as? Activity)
                            ?: return
                        activeOverlays.remove(activity)?.let { view ->
                            (view.parent as? ViewGroup)?.removeView(view)
                        }
                    }
                })
            }.isSuccess || installed
        }
        return installed
    }

    private fun findVirtualFieldDeep(target: Any, depth: Int = 2): String? {
        if (depth < 0) return null
        findVirtualField(target)?.let { return it }
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (field.type.isPrimitive || field.type == String::class.java) continue
                val value = runCatching { KavaReflector.readField(field, target) }.getOrNull() ?: continue
                if (value is android.app.Activity) continue
                findVirtualFieldDeep(value, depth - 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun findActivityDeep(target: Any): Activity? {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val value = runCatching { KavaReflector.readField(field, target) }.getOrNull() ?: continue
                if (value is Activity) return value
                if (value.javaClass.name.contains("MMFragmentActivity")) {
                    return value as? Activity
                }
            }
            current = current.superclass
        }
        return null
    }

    /** 新版微信聊天页是 LauncherUI 内的 ChattingUIFragment。 */
    fun installChattingFragmentRedirect(classLoader: ClassLoader): Boolean {
        val fragmentClass = KavaReflector.loadClass(
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            classLoader
        ) ?: return false
        val candidates = KavaReflector.declaredMethods(fragmentClass).filter {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == android.os.Bundle::class.java
        }
        if (candidates.isEmpty()) return false
        var ok = false
        candidates.forEach { method ->
            if (runCatching {
                    HookRegistry.get().hook(method, object : HookCallback {
                        override fun after(param: HookParam) {
                            val fragment = param.thisObject ?: return
                            val talker = findVirtualField(fragment) ?: return
                            HLog.e("$TAG 聊天 Fragment 命中虚拟分组: talker=$talker")
                            val activity = KavaReflector.invokeMethod(fragment, "getActivity") as? Activity
                                ?: return
                            runCatching {
                                activity.startActivity(
                                    Intent().setClassName(
                                        activity,
                                        "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
                                    ).putExtra("Contact_User", talker)
                                )
                                KavaReflector.invokeMethod(fragment, "finish")
                            }.onFailure {
                                HLog.e("$TAG 聊天 Fragment 转分组页失败: ${it.message}", it)
                            }
                        }
                    })
                }.isSuccess
            ) ok = true
        }
        HLog.e("$TAG 聊天 Fragment 重定向: $ok")
        return ok
    }

    private fun showGroupPageOverlay(activity: Activity, talker: String) {
        val group = ConversationGroupStore.load(activity)
            .firstOrNull { ConversationGroupRuntime.virtualTalker(it.id) == talker } ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        activeOverlays[activity]?.let { existing ->
            existing.bringToFront()
            return
        }
        val contacts = WeChatApis.contact()?.contacts()
        val rows = group.conversationIds.map { id ->
            id to (contacts?.getDisplayName(id)?.takeIf { it.isNotBlank() } ?: id)
        }
        val details = runCatching {
            val ids = group.conversationIds
            if (ids.isEmpty()) return@runCatching emptyMap()
            val placeholders = ids.joinToString(",") { "?" }
            WeChatApis.database()?.query(
                "SELECT username, content, conversationTime, unReadCount " +
                    "FROM rconversation WHERE username IN ($placeholders)",
                ids.toTypedArray()
            ).orEmpty().mapNotNull { row ->
                val username = row["username"] as? String ?: return@mapNotNull null
                username to row
            }.toMap()
        }.getOrDefault(emptyMap())
        activity.runOnUiThread {
            val page = FrameLayout(activity).apply {
                setBackgroundColor(Color.WHITE)
                isClickable = true
                setPadding(0, dp(40), 0, 0)
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
            }
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(Color.WHITE)
            }
            header.addView(TextView(activity).apply {
                text = "‹"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(32, 32, 32))
                setPadding(dp(6), 0, dp(10), 0)
                setOnClickListener {
                    removeGroupPage(activity)
                    KavaReflector.invokeMethod(activity, "closeChatting", false)
                }
            }, LinearLayout.LayoutParams(dp(44), dp(52)))
            header.addView(TextView(activity).apply {
                text = group.name.ifBlank { "聊天分组" }
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(32, 32, 32))
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            header.addView(View(activity), LinearLayout.LayoutParams(dp(44), dp(1)))
            content.addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
            ))
            content.addView(View(activity).apply {
                setBackgroundColor(Color.rgb(235, 235, 235))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            val list = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
            }
            rows.forEach { (id, name) ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    minimumHeight = dp(68)
                    isClickable = false
                }
                val detail = details[id].orEmpty()
                val digest = (detail["content"] as? String)
                    .orEmpty()
                    .replace(Regex("<[^>]+>"), "")
                    .trim()
                    .ifBlank { if (id.endsWith("@chatroom")) "群聊" else "会话" }
                val conversationTime = (detail["conversationTime"] as? Number)?.toLong() ?: 0L
                val unread = (detail["unReadCount"] as? Number)?.toInt() ?: 0
                val avatar = TextView(activity).apply {
                    text = name.take(1)
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(
                            if (id.endsWith("@chatroom")) Color.rgb(87, 160, 92)
                            else Color.rgb(76, 132, 200)
                        )
                    }
                }
                row.addView(avatar, LinearLayout.LayoutParams(dp(48), dp(48)))
                val text = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                }
                text.addView(TextView(activity).apply {
                    this.text = name
                    textSize = 17f
                    setTextColor(Color.rgb(32, 32, 32))
                    maxLines = 1
                })
                text.addView(TextView(activity).apply {
                    this.text = digest
                    textSize = 13f
                    setTextColor(Color.rgb(150, 150, 150))
                    maxLines = 1
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(text, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ))
                val right = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                right.addView(TextView(activity).apply {
                    this.text = formatConversationTime(conversationTime)
                    textSize = 11f
                    gravity = Gravity.END
                    setTextColor(Color.rgb(170, 170, 170))
                })
                if (unread > 0) {
                    right.addView(TextView(activity).apply {
                        this.text = if (unread > 99) "99+" else unread.toString()
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.rgb(230, 70, 70))
                        }
                    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                        topMargin = dp(4)
                    })
                }
                row.addView(right, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                list.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                list.addView(View(activity).apply {
                    setBackgroundColor(Color.rgb(235, 235, 235))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
            content.addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            page.addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            page.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val y = event.y
                    if (y < dp(104)) {
                        decor.removeView(page)
                        activeOverlays.remove(activity)
                    } else {
                        val index = ((y - dp(104)) / dp(68)).toInt()
                        val child = rows.getOrNull(index)?.first
                        if (child != null) {
                            HLog.e("$TAG 点击分组行: id=$child")
                            // 保持分组页在底下，等目标聊天 Activity 盖上来；
                            // 先移除会露出原生“非好友”页，造成闪烁。
                            runCatching {
                                activity.startActivity(
                                    Intent().setClassName(activity, CHATTING_UI).also {
                                        it.putExtra(CHAT_USER, child)
                                    }
                                )
                            }.onFailure {
                                HLog.e("$TAG 打开分组会话失败: ${it.message}", it)
                            }
                        }
                    }
                }
                true
            }
            decor.addView(page, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            activeOverlays[activity] = page
            page.bringToFront()
            backCallbacks.remove(activity)?.remove()
            val backCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (removeGroupPage(activity)) {
                        KavaReflector.invokeMethod(activity, "closeChatting", false)
                    }
                }
            }
            (activity as? ComponentActivity)?.onBackPressedDispatcher?.addCallback(
                activity as ComponentActivity,
                backCallback
            )
            backCallbacks[activity] = backCallback
            val observer = decor.viewTreeObserver
            val preDraw = android.view.ViewTreeObserver.OnPreDrawListener {
                activeOverlays[activity]?.bringToFront()
                true
            }
            observer.addOnPreDrawListener(preDraw)
            page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    runCatching { observer.removeOnPreDrawListener(preDraw) }
                }
            })
            HLog.e("$TAG 分组页已显示: ${rows.size} 行")
        }
    }

    private fun renderGroupPage(fragment: Any, activity: Activity, talker: String) {
        val group = ConversationGroupStore.load(activity)
            .firstOrNull { ConversationGroupRuntime.virtualTalker(it.id) == talker } ?: return
        val root = KavaReflector.invokeMethod(fragment, "getView") as? ViewGroup ?: return
        if (root.tag == "hchat_group_page") return
        root.tag = "hchat_group_page"
        val contacts = WeChatApis.contact()?.contacts()
        val rows = group.conversationIds.map { id ->
            id to (contacts?.getDisplayName(id)?.takeIf { it.isNotBlank() } ?: id)
        }
        root.removeAllViews()
        root.setBackgroundColor(Color.WHITE)
        val list = android.widget.ListView(activity).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(235, 235, 235))
            dividerHeight = 1
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(position: Int) = rows[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        minimumHeight = dp(68)
                    }
                    row.removeAllViews()
                    val (id, name) = rows[position]
                    val avatar = TextView(activity).apply {
                        text = name.take(1)
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(if (id.endsWith("@chatroom")) Color.rgb(87, 160, 92) else Color.rgb(76, 132, 200))
                        }
                    }
                    row.addView(avatar, LinearLayout.LayoutParams(dp(48), dp(48)))
                    val text = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, 0)
                    }
                    text.addView(TextView(activity).apply {
                        this.text = name
                        textSize = 17f
                        setTextColor(Color.rgb(32, 32, 32))
                        maxLines = 1
                    })
                    text.addView(TextView(activity).apply {
                        this.text = if (id.endsWith("@chatroom")) "群聊" else "会话"
                        textSize = 13f
                        setTextColor(Color.rgb(150, 150, 150))
                        setPadding(0, dp(3), 0, 0)
                    })
                    row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    return row
                }
            }
            setOnItemClickListener { _, _, position, _ ->
                val child = rows.getOrNull(position)?.first ?: return@setOnItemClickListener
                runCatching {
                    activity.startActivity(
                        Intent().setClassName(activity, CHATTING_UI).also {
                            it.putExtra(CHAT_USER, child)
                        }
                    )
                }.onFailure { HLog.e("$TAG 打开分组会话失败: ${it.message}", it) }
            }
        }
        root.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        HLog.e("$TAG 分组页已渲染: ${rows.size} 行")
    }

    private fun formatConversationTime(value: Long): String {
        if (value <= 0L) return ""
        val millis = if (value < 10_000_000_000L) value * 1000L else value
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val pattern = if (now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
        ) {
            "HH:mm"
        } else {
            "MM-dd"
        }
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    private fun showGroupDialog(activity: Activity, talker: String) {
        HLog.e("$TAG 准备显示分组列表: talker=$talker")
        val all = ConversationGroupStore.load(activity)
        HLog.e("$TAG 分组数量=${all.size} ids=${all.joinToString { it.id }}")
        val group = all.firstOrNull { ConversationGroupRuntime.virtualTalker(it.id) == talker }
        if (group == null) {
            HLog.e("$TAG 找不到 talker 对应分组: $talker")
            return
        }
        HLog.e("$TAG 分组子会话=${group.conversationIds.size}")
        val contacts = WeChatApis.contact()?.contacts()
        val names = group.conversationIds.map { conversation ->
            contacts?.getDisplayName(conversation)?.takeIf { it.isNotBlank() } ?: conversation
        }
        if (names.isEmpty()) {
            HLog.e("$TAG 分组内没有会话")
            return
        }
        activity.runOnUiThread {
            runCatching {
                val decor = activity.window?.decorView as? ViewGroup ?: return@runOnUiThread
                if (activeOverlays.containsKey(activity)) return@runOnUiThread
                val density = activity.resources.displayMetrics.density
                fun dp(value: Int) = (value * density).toInt()
                val overlay = FrameLayout(activity).apply {
                    setBackgroundColor(Color.argb(150, 0, 0, 0))
                    isClickable = true
                }
                val panel = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                    setPadding(dp(18), dp(14), dp(18), dp(10))
                }
                panel.addView(TextView(activity).apply {
                    text = group.name.ifBlank { "聊天分组" }
                    textSize = 18f
                    setTextColor(Color.BLACK)
                    setPadding(0, 0, 0, dp(8))
                })
                group.conversationIds.forEachIndexed { index, child ->
                    panel.addView(TextView(activity).apply {
                        text = names.getOrNull(index) ?: child
                        textSize = 16f
                        setTextColor(Color.BLACK)
                        setPadding(0, dp(12), 0, dp(12))
                        isClickable = true
                        setOnClickListener {
                            decor.removeView(overlay)
                            activeOverlays.remove(activity)
                            runCatching {
                                activity.startActivity(
                                    Intent().setClassName(activity, CHATTING_UI).also {
                                        it.putExtra(CHAT_USER, child)
                                    }
                                )
                            }.onFailure {
                                HLog.e("$TAG 打开分组会话失败: ${it.message}", it)
                            }
                        }
                    })
                }
                panel.addView(TextView(activity).apply {
                    text = "关闭"
                    textSize = 15f
                    gravity = Gravity.END
                    setTextColor(Color.DKGRAY)
                    setPadding(0, dp(10), 0, 0)
                    isClickable = true
                    setOnClickListener {
                        decor.removeView(overlay)
                        activeOverlays.remove(activity)
                    }
                })
                overlay.addView(panel, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    leftMargin = dp(20)
                    rightMargin = dp(20)
                })
                decor.addView(overlay, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                activeOverlays[activity] = overlay
                HLog.e("$TAG 分组列表浮层已显示")
            }.onFailure {
                HLog.e("$TAG 分组列表浮层失败: ${it.message}", it)
            }
        }
    }

    private fun findVirtualField(target: Any): String? {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type != String::class.java) continue
                val value = KavaReflector.readField(field, target) as? String ?: continue
                if (value.startsWith(VIRTUAL_PREFIX)) return value
            }
            current = current.superclass
        }
        return null
    }

    fun install(context: FeatureContext): Boolean {
        if (installed) return true
        synchronized(this) {
            if (installed) return true
            HLog.e("$TAG 点击桥 install 进入")
            val adapterClass = KavaReflector.loadClass(
                "com.tencent.mm.view.recyclerview.WxRecyclerAdapter",
                context.hostClassLoader()
            ) ?: run {
                HLog.e("$TAG 点击桥找不到 WxRecyclerAdapter")
                return false
            }
            val attach = KavaReflector.declaredMethods(adapterClass).firstOrNull {
                it.name == "onAttachedToRecyclerView" && it.parameterTypes.size == 1
            } ?: run {
                HLog.e("$TAG 点击桥找不到 onAttachedToRecyclerView")
                return false
            }
            val attachInstalled = runCatching {
                HookRegistry.get().hook(attach, object : HookCallback {
                    override fun after(param: HookParam) {
                        (param.args?.getOrNull(0) as? RecyclerView)?.let(::attach)
                    }
                })
            }.isSuccess
            val bind = KavaReflector.declaredMethods(adapterClass).firstOrNull { method ->
                method.name == "O" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[1] == java.lang.Integer.TYPE
            }
            val bindInstalled = bind?.let { method ->
                runCatching {
                    HookRegistry.get().hook(method, object : HookCallback {
                        override fun after(param: HookParam) {
                            val holder = param.args?.getOrNull(0) ?: return
                            val itemView = KavaReflector.readField(holder, "itemView") as? View ?: return
                            itemView.post {
                                (itemView.parent as? RecyclerView)?.let(::attach)
                            }
                        }
                    })
                }.isSuccess
            } ?: false
            val viewAttached = KavaReflector.declaredMethods(adapterClass).firstOrNull {
                it.name == "onViewAttachedToWindow" && it.parameterTypes.size == 1
            }
            val viewAttachedInstalled = viewAttached?.let { method ->
                runCatching {
                    HookRegistry.get().hook(method, object : HookCallback {
                        override fun after(param: HookParam) {
                            val holder = param.args?.getOrNull(0) ?: return
                            val itemView = KavaReflector.readField(holder, "itemView") as? View ?: return
                            (itemView.parent as? RecyclerView)?.let(::attach)
                        }
                    })
                }.isSuccess
            } ?: false
            // 首页 RecyclerView 在桥安装前已经 attach，onAttached 不会再触发；
            // 再挂一个 onLayout，保证现有 RecyclerView 也能补上监听。
            val recyclerClass = KavaReflector.loadClass(
                "androidx.recyclerview.widget.RecyclerView",
                context.hostClassLoader()
            )
            val constructorInstalled = recyclerClass?.let { clazz ->
                KavaReflector.declaredConstructors(clazz).count { ctor ->
                    runCatching {
                        HookRegistry.get().hook(ctor, object : HookCallback {
                            override fun after(param: HookParam) {
                                (param.thisObject as? RecyclerView)?.let(::attach)
                            }
                        })
                    }.isSuccess
                } > 0
            } ?: false
            val setAdapter = recyclerClass?.let { clazz ->
                KavaReflector.declaredMethods(clazz).firstOrNull {
                    it.name == "setAdapter" && it.parameterTypes.size == 1
                }
            }
            val setAdapterInstalled = setAdapter?.let { method ->
                runCatching {
                    HookRegistry.get().hook(method, object : HookCallback {
                        override fun after(param: HookParam) {
                            (param.thisObject as? RecyclerView)?.let(::attach)
                        }
                    })
                }.isSuccess
            } ?: false
            val onLayout = recyclerClass?.let { clazz ->
                KavaReflector.declaredMethods(clazz).firstOrNull {
                    it.name == "onLayout" && it.parameterTypes.size == 4
                }
            }
            val layoutInstalled = onLayout?.let { method ->
                runCatching {
                    HookRegistry.get().hook(method, object : HookCallback {
                        override fun after(param: HookParam) {
                            (param.thisObject as? RecyclerView)?.let(::attach)
                        }
                    })
                }.isSuccess
            } ?: false
            installed = attachInstalled || bindInstalled || layoutInstalled ||
                viewAttachedInstalled || setAdapterInstalled || constructorInstalled
            HLog.e(
                "$TAG 点击桥: attach=$attachInstalled bind=$bindInstalled layout=$layoutInstalled " +
                    "viewAttached=$viewAttachedInstalled setAdapter=$setAdapterInstalled " +
                    "ctor=$constructorInstalled"
            )
            val launcherClass = KavaReflector.loadClass(
                "com.tencent.mm.ui.LauncherUI",
                context.hostClassLoader()
            )
            var resumeOwner: Class<*>? = launcherClass
            var onResume: java.lang.reflect.Method? = null
            while (resumeOwner != null && onResume == null) {
                onResume = KavaReflector.declaredMethods(resumeOwner).firstOrNull {
                    it.name == "onResume" && it.parameterTypes.isEmpty()
                }
                resumeOwner = resumeOwner.superclass
            }
            onResume?.let { method ->
                runCatching {
                    HookRegistry.get().hook(method, object : HookCallback {
                        override fun after(param: HookParam) {
                            (param.thisObject as? Activity)?.let(::attachActivity)
                        }
                    })
                }
            }
            val main = Handler(Looper.getMainLooper())
            listOf(500L, 1500L, 3000L).forEach { delay ->
                main.postDelayed({ attachCurrentWindow() }, delay)
            }
            return installed
        }
    }

    private fun attachCurrentWindow() {
        val activity = WeChatApis.currentActivity()?.currentActivity() as? Activity ?: return
        attachActivity(activity)
    }

    private fun attachActivity(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        walk(decor)
    }

    private fun walk(view: View) {
        if (view is RecyclerView) {
            attach(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
    }

    private fun openGroup(activity: Activity, talker: String) {
        runCatching {
            activity.startActivity(
                Intent().setClassName(activity, CHATTING_UI).also {
                    it.putExtra(CHAT_USER, talker)
                }
            )
        }.onFailure {
            HLog.e("$TAG 点击桥打开分组失败: ${it.message}", it)
        }
    }

    private fun attach(recyclerView: RecyclerView) {
        if (!installedViews.add(recyclerView)) return
        HLog.e("$TAG 点击桥挂载 RecyclerView: ${recyclerView.javaClass.name}")
        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.action != MotionEvent.ACTION_UP) return false
                val child = rv.findChildViewUnder(e.x, e.y) ?: return false
                val position = rv.getChildAdapterPosition(child)
                if (position < 0) return false
                val adapter = rv.adapter ?: return false
                val data = KavaReflector.invokeMethod(adapter, "getData") as? List<*> ?: return false
                val talker = virtualTalker(data.getOrNull(position)) ?: return false
                val activity = findActivity(rv.context) ?: return false
                HLog.e("$TAG 点击桥命中: talker=$talker")
                openGroup(activity, talker)
                return true
            }
        })
    }

    private fun virtualTalker(item: Any?): String? {
        if (item == null) return null
        var current: Class<*>? = item.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type != String::class.java) continue
                val value = KavaReflector.readField(field, item) as? String ?: continue
                if (value.startsWith(VIRTUAL_PREFIX)) return value
            }
            current = current.superclass
        }
        return null
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}
