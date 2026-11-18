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
                    runCatching {
                        val fm = KavaReflector.invokeMethod(fragment, "getParentFragmentManager")
                            ?: KavaReflector.invokeMethod(fragment, "getFragmentManager")
                        if (fm != null) {
                            val tx = KavaReflector.invokeMethod(fm, "beginTransaction")
                            if (tx != null) {
                                KavaReflector.invokeMethod(tx, "remove", fragment)
                                KavaReflector.invokeMethod(tx, "commit")
                            }
                        }
                        showGroupDialog(activity, talker)
                    }.onFailure {
                        HLog.e("$TAG Fragment 观察器跳转失败: ${it.message}", it)
                    }
                }
            })
        }.isSuccess
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
                    setOnClickListener { decor.removeView(overlay) }
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
