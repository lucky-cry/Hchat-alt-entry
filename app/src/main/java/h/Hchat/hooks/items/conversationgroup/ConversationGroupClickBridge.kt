package h.Hchat.hooks.items.conversationgroup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
                runCatching {
                    activity.startActivity(
                        Intent().setClassName(activity, CHATTING_UI).also {
                            it.putExtra(CHAT_USER, talker)
                        }
                    )
                }.onFailure {
                    HLog.e("$TAG 点击桥打开分组失败: ${it.message}", it)
                }
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
