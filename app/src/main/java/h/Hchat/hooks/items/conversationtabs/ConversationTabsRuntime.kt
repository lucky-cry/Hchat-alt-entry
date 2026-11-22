package h.Hchat.hooks.items.conversationtabs

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.RelativeLayout
import android.widget.FrameLayout
import h.Hchat.hooks.core.HookCallback
import h.Hchat.hooks.core.HookParam
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.items.conversationgroup.ConversationGroupHomeProjection
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A page-local overlay below the native title; no row hooks or scroll-time tree scans. */
internal object ConversationTabsRuntime {
    private const val TAG = "[Hchat:ConversationTabs]"
    private const val ROOT_TAG = "hchat:conversation-tabs-root"
    private const val HOST_TAG_ID = 0x48435401
    private const val MAIN_VIEW_CLASS = "com.tencent.mm.ui.conversation.MainUIView"
    private const val CONTENT_ID_NAME = "jlt"
    private const val ACTION_BAR_ID_NAME = "ei"
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "Hchat-ConversationTabs").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private val reloadLock = Any()
    private val revision = AtomicLong()
    private val lifecycle = AtomicLong()
    private var pendingDelivery: Runnable? = null
    // Host 由标签栏自身持有；全局索引不能经 View/Activity 反向保活 MainUI。
    private val hosts = WeakHashMap<Any, WeakReference<Host>>()
    private val bitmaps = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile private var context: FeatureContext? = null
    private var hookedMainUi: Class<*>? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var account = ""
    private var selectedId = "all"
    private var config = ConversationTabsConfig()
    private var loadedIcons: Map<String, Bitmap> = emptyMap()
    private var compiledFilters: Map<String, ConversationTabFilter> = emptyMap()
    @Volatile private var filter: ConversationTabFilter? = null
    @Volatile private var ready = false
    private val refresh = Runnable { ConversationGroupHomeProjection.refresh() }

    private data class Host(
        val root: ViewGroup,
        val fixedParent: FrameLayout,
        val strip: HorizontalScrollView,
        val content: LinearLayout,
        var list: View? = null,
        var baseTopMargin: Int? = null,
        var appliedTopMargin: Int? = null,
        val actionBarId: Int,
        var actionBar: View? = null,
        val titleLocation: IntArray = IntArray(2),
        val parentLocation: IntArray = IntArray(2),
        var enabled: Boolean = false,
        var dirty: Boolean = true,
        var observer: ViewTreeObserver? = null,
        var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null,
        var drawListener: ViewTreeObserver.OnPreDrawListener? = null,
        var attachListener: View.OnAttachStateChangeListener? = null,
        val buttons: MutableList<Pair<String, LinearLayout>> = arrayListOf()
    )

    fun initialize(featureContext: FeatureContext) {
        if (context != null) return
        if (!installUiHooks(featureContext.hostClassLoader())) return
        synchronized(reloadLock) {
            lifecycle.incrementAndGet()
            context = featureContext
        }
        ConversationGroupHomeProjection.setTabFilter { filter }
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }.also {
            HchatStorage.preferences(featureContext.hostContext(), ConversationTabsStore.PREFS_NAME)
                .registerOnSharedPreferenceChangeListener(it)
        }
        // Hosts created before the DexKit-backed feature initialization are rendered here too.
        reload()
    }

    /** Register before LauncherUI is created; this path does not need DexKit or account state. */
    @Synchronized
    fun installUiHooks(classLoader: ClassLoader): Boolean {
        val clazz = KavaReflector.loadClass("com.tencent.mm.ui.conversation.MainUI", classLoader)
            ?: run {
                HLog.e("$TAG 未找到 com.tencent.mm.ui.conversation.MainUI，标签分组未安装")
                return false
            }
        if (hookedMainUi == clazz) return true
        val layout = KavaReflector.findMethodRecursive(clazz, "getLayoutView") ?: run {
            HLog.e("$TAG 未找到 MainUI.getLayoutView，标签分组未安装")
            return false
        }
        val resume = KavaReflector.findMethodRecursive(clazz, "onResume") ?: return false
        val destroy = KavaReflector.findMethodRecursive(clazz, "onDestroy") ?: return false
        val rootField = KavaReflector.declaredFields(clazz).singleOrNull {
            !KavaReflector.isStatic(it) && it.type.name == MAIN_VIEW_CLASS
        } ?: run {
            HLog.e("$TAG 未找到 MainUI 的唯一 MainUIView 字段，标签分组未安装")
            return false
        }
        val hooks = arrayListOf<Any>()
        return runCatching {
            hooks += HookRegistry.get().hook(layout, object : HookCallback {
                override fun after(param: HookParam) {
                    if (param.hasThrowable || !clazz.isInstance(param.thisObject)) return
                    val original = param.result as? View ?: return
                    clearChangedAccount()
                    ensureHost(param.thisObject ?: return, original)
                    reload()
                }
            })
            hooks += HookRegistry.get().hook(resume, object : HookCallback {
                override fun after(param: HookParam) {
                    if (param.hasThrowable || !clazz.isInstance(param.thisObject)) return
                    clearChangedAccount()
                    // getLayoutView inflates a NEW root. Read the existing native root instead.
                    val root = KavaReflector.readField(rootField, param.thisObject ?: return) as? View
                    if (root != null) ensureHost(param.thisObject ?: return, root)
                    reload()
                }
            })
            hooks += HookRegistry.get().hook(destroy, object : HookCallback {
                override fun after(param: HookParam) {
                    if (!clazz.isInstance(param.thisObject)) return
                    hosts.remove(param.thisObject)?.get()?.let { removeHost(it) }
                    if (liveHosts().isEmpty()) synchronized(reloadLock) {
                        invalidateLoads()
                        filter = null; loadedIcons = emptyMap(); compiledFilters = emptyMap(); bitmaps.evictAll()
                    }
                }
            })
            hookedMainUi = clazz
            true
        }.getOrElse {
            hooks.forEach { hook -> HookRegistry.get().unhookHandle(hook) }
            HLog.e("$TAG 安装 MainUI 生命周期失败", it)
            false
        }
    }

    private fun ensureHost(owner: Any, root: View) {
        runCatching {
            val previous = hosts[owner]?.get()
            if (previous != null && previous.root === root &&
                previous.strip.parent === previous.fixedParent && findFixedParent(root) === previous.fixedParent) {
                previous.dirty = true
                return
            }
            if (previous != null) {
                hosts.remove(owner)
                removeHost(previous)
            }
            val host = attach(root) ?: return
            hosts[owner] = WeakReference(host)
            render(host)
        }.onFailure { HLog.e("$TAG 挂载顶栏失败", it) }
    }

    private fun attach(original: View): Host? {
        val root = original as? ViewGroup ?: run {
            HLog.e("$TAG MainUI.getLayoutView 返回的不是 ViewGroup: ${original.javaClass.name}")
            return null
        }
        val fixedParent = findFixedParent(root) ?: run {
            // getLayoutView() runs before MMActivityController adds the page to its
            // content layer. Wait for onResume, when the native bounce hierarchy exists.
            return null
        }
        val actionBarId = root.resources.getIdentifier(ACTION_BAR_ID_NAME, "id", "com.tencent.mm")
        if (actionBarId == 0 || fixedParent.findViewWithTag<View>(ROOT_TAG) != null) return null
        val strip = HorizontalScrollView(original.context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            tag = ROOT_TAG
            id = View.generateViewId()
        }
        val row = LinearLayout(original.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        strip.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val stripParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        fixedParent.addView(strip, stripParams)
        strip.bringToFront()
        val host = Host(root, fixedParent, strip, row, actionBarId = actionBarId)
        strip.setTag(HOST_TAG_ID, host)
        host.attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { observeGeometry(host) }
            override fun onViewDetachedFromWindow(view: View) {
                stopObservingGeometry(host)
                if (host.enabled) host.strip.visibility = View.INVISIBLE
            }
        }.also(root::addOnAttachStateChangeListener)
        return host
    }

    private fun liveHosts(): List<Host> {
        val current = arrayListOf<Host>()
        val entries = hosts.entries.iterator()
        while (entries.hasNext()) {
            val host = entries.next().value.get()
            if (host == null) entries.remove() else current.add(host)
        }
        return current
    }

    private fun removeHost(host: Host) {
        host.strip.setTag(HOST_TAG_ID, null)
        setHostEnabled(host, false)
        host.attachListener?.let(host.root::removeOnAttachStateChangeListener)
        (host.strip.parent as? ViewGroup)?.removeView(host.strip)
        host.content.removeAllViews()
        host.buttons.clear()
    }

    /** The controller's page-local content frame, outside any optional bounce wrapper. */
    private fun findFixedParent(root: View): FrameLayout? {
        val id = root.resources.getIdentifier(CONTENT_ID_NAME, "id", "com.tencent.mm")
        if (id == 0) return null
        var parent = root.parent
        while (parent is ViewGroup) {
            if (parent.id == id) return parent as? FrameLayout
            parent = parent.parent
        }
        return null
    }

    private fun findConversationList(root: ViewGroup): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            val name = child.javaClass.name
            if (name == "com.tencent.mm.ui.conversation.ConversationListView" ||
                name == "com.tencent.mm.ui.conversation.recycler.ConversationRecyclerView") {
                if (child.visibility != View.GONE) return child
            }
        }
        return null
    }

    private fun setHostEnabled(host: Host, enabled: Boolean) {
        host.enabled = enabled
        host.dirty = true
        if (enabled) {
            // INVISIBLE participates in measurement but cannot flash over the status bar.
            host.strip.visibility = View.INVISIBLE
            observeGeometry(host)
        } else {
            stopObservingGeometry(host)
            restoreListGeometry(host)
            host.strip.visibility = View.GONE
        }
    }

    private fun observeGeometry(host: Host) {
        if (!host.enabled || !host.root.isAttachedToWindow) return
        val observer = host.root.viewTreeObserver
        if (host.observer === observer) return
        stopObservingGeometry(host)
        host.dirty = true
        host.observer = observer
        host.layoutListener = ViewTreeObserver.OnGlobalLayoutListener { host.dirty = true }
            .also(observer::addOnGlobalLayoutListener)
        host.drawListener = ViewTreeObserver.OnPreDrawListener {
            runCatching { updateGeometry(host) }.getOrElse {
                setHostEnabled(host, false)
                HLog.e("$TAG 更新标签栏坐标失败", it)
                true
            }
        }.also(observer::addOnPreDrawListener)
    }

    private fun stopObservingGeometry(host: Host) {
        host.observer?.takeIf { it.isAlive }?.let { observer ->
            host.layoutListener?.let(observer::removeOnGlobalLayoutListener)
            host.drawListener?.let(observer::removeOnPreDrawListener)
        }
        host.observer = null
        host.layoutListener = null
        host.drawListener = null
    }

    private fun updateGeometry(host: Host): Boolean {
        if (!host.enabled || host.strip.parent !== host.fixedParent) return true
        if (host.dirty) {
            host.dirty = false
            val list = findConversationList(host.root)
            if (host.list !== list) {
                restoreListGeometry(host)
                host.list = list
                host.baseTopMargin = null
                host.appliedTopMargin = null
            }
            if (host.actionBar?.isAttachedToWindow != true ||
                host.actionBar?.rootView !== host.root.rootView) {
                host.actionBar = host.root.rootView.findViewById(host.actionBarId)
            }
        }
        val actionBar = host.actionBar
        val list = host.list
        val stripHeight = host.strip.measuredHeight
        (list as? androidx.recyclerview.widget.RecyclerView)?.let { rv ->
            hideBlankListHeader(rv)
            // 原生空态可能把首页内容整体塞进 paddingTop；它不在 LayoutParams
            // 上，必须在每次布局时纠正，否则分类切换后整页只剩空白。
            if (stripHeight > 0 && rv.paddingTop > stripHeight * 3) {
                rv.setPadding(rv.paddingLeft, 0, rv.paddingRight, rv.paddingBottom)
                rv.translationY = 0f
                rv.scrollToPosition(0)
            }
        }
        // 分类为空时原生列表高度会被压缩/重建，此时继续叠加 topMargin 会把
        // 列表整体推到屏幕中部，留下巨大空白。空列表只恢复原生间距。
        if (!host.root.isShown || actionBar == null || !actionBar.isShown ||
            actionBar.height <= 0 || host.fixedParent.height <= 0 ||
            list?.parent !== host.root || stripHeight <= 0) {
            host.strip.visibility = View.INVISIBLE
            return true
        }
        val listParams = list.layoutParams as? RelativeLayout.LayoutParams ?: run {
            host.strip.visibility = View.INVISIBLE
            return true
        }
        val current = listParams.topMargin
        // 首页列表的 topMargin 直接等于标签栏高度；不要累加原生页可能留下的
        // 旧 topMargin，否则空分类/切换分类后会把列表推出一大块空白。
        val desired = stripHeight
        if (desired != current) {
            listParams.topMargin = desired
            host.appliedTopMargin = desired
            list.layoutParams = listParams
            host.strip.visibility = View.INVISIBLE
            // Let the native list lay out its header before displaying the added strip.
            return false
        }
        host.appliedTopMargin = current
        actionBar.getLocationInWindow(host.titleLocation)
        host.fixedParent.getLocationInWindow(host.parentLocation)
        val visibleTop = host.titleLocation[1] + actionBar.height - host.parentLocation[1]
        val top = visibleTop + host.fixedParent.scrollY
        // Property translation follows the native pull-down without requesting another layout.
        if (host.strip.y != top.toFloat()) host.strip.y = top.toFloat()
        host.strip.visibility = if (visibleTop >= 0 && visibleTop + stripHeight <= host.fixedParent.height)
            View.VISIBLE else View.INVISIBLE
        return true
    }

    private fun restoreListGeometry(host: Host) {
        val list = host.list ?: return
        val params = list.layoutParams as? RelativeLayout.LayoutParams ?: return
        val base = host.baseTopMargin ?: return
        // Do not overwrite a native margin update that has not yet reached our next layout.
        if (params.topMargin == host.appliedTopMargin && params.topMargin != base) {
            params.topMargin = base
            list.layoutParams = params
        }
        host.baseTopMargin = null
        host.appliedTopMargin = null
    }

    // 调用方持有 reloadLock；后台队列和主线程待交付结果都只保留最新一份。
    private fun invalidateLoads() {
        revision.incrementAndGet()
        worker.queue.clear()
        pendingDelivery?.let(main::removeCallbacks)
        pendingDelivery = null
    }

    private fun reload() = synchronized(reloadLock) {
        val owner = context ?: return@synchronized
        invalidateLoads()
        val ticket = revision.get()
        val generation = lifecycle.get()
        worker.execute {
            if (ticket != revision.get()) return@execute
            runCatching {
                val nextAccount = ConversationTabsStore.accountKey()
                val next = ConversationTabsStore.load(owner.hostContext())
                HLog.e("$TAG 配置加载: enabled=${next.enabled} tabs=${next.tabs.size} " +
                    "account=${nextAccount.take(8)} ready=$ready")
                if (ticket != revision.get()) return@runCatching
                val filters = if (next.enabled) next.tabs.associate { it.id to ConversationTabFilter(it) } else emptyMap()
                val images = if (!next.enabled) emptyMap() else buildMap {
                    for (tab in next.tabs) {
                        if (ticket != revision.get()) return@runCatching
                        if (tab.display == ConversationTabDisplay.NAME || tab.iconPath.isBlank()) continue
                        val bitmap = bitmaps.get(tab.iconPath) ?: ConversationTabsIconStore.loadBitmap(tab.iconPath)
                        if (bitmap != null) {
                            synchronized(reloadLock) {
                                if (ticket != revision.get()) return@runCatching
                                bitmaps.put(tab.iconPath, bitmap)
                            }
                            put(tab.iconPath, bitmap)
                        }
                    }
                }
                synchronized(reloadLock) {
                    if (ticket != revision.get() || context !== owner) return@synchronized
                    val delivery = Runnable {
                        synchronized(reloadLock) {
                            if (ticket != revision.get() || context !== owner) return@synchronized
                            pendingDelivery = null
                            if (account != nextAccount) { selectedId = "all"; account = nextAccount }
                            val changed = config != next
                            config = next
                            loadedIcons = images
                            compiledFilters = filters
                            if (config.tabs.none { it.id == selectedId }) selectedId = config.tabs.first { it.kind == ConversationTabKind.ALL }.id
                            val restored = config.tabs.firstOrNull { it.id == selectedId }
                            if (restored?.kind == ConversationTabKind.UNREAD && unreadCount() == 0) {
                                selectedId = config.tabs.first { it.kind == ConversationTabKind.ALL }.id
                            }
                            if (config.enabled && !ready) {
                                HLog.e("$TAG 请求安装标签查询")
                                DexInstallScheduler.schedule(ConversationTabsFeature.ID, "标签分组", DexInstallScheduler.Stage.BRIDGE) {
                                    if (context !== owner || lifecycle.get() != generation) return@schedule false
                                    HLog.e("$TAG 安装标签查询开始")
                                    val success = runCatching {
                                        ConversationGroupHomeProjection.installTabs(owner)
                                    }.onFailure {
                                        HLog.e("$TAG 安装标签查询异常: ${it.message}", it)
                                    }.getOrDefault(false)
                                    HLog.e("$TAG 安装标签查询结果=$success")
                                    if (success) main.post {
                                        synchronized(reloadLock) {
                                            if (context === owner && lifecycle.get() == generation) {
                                                ready = true
                                                publish(); liveHosts().forEach(::render); requestRefresh()
                                                main.postDelayed(::refreshHostAdapters, 150L)
                                            }
                                        }
                                    }
                                    success
                                }
                            }
                            publish()
                            liveHosts().forEach(::render)
                            if (changed) requestRefresh()
                            if (!config.enabled) bitmaps.evictAll()
                        }
                    }
                    pendingDelivery?.let(main::removeCallbacks)
                    pendingDelivery = delivery
                    main.post(delivery)
                }
            }.onFailure { HLog.e("$TAG 读取标签配置失败", it) }
        }
    }

    private fun publish() {
        filter = if (!ready || !config.enabled || account.isBlank() || liveHosts().isEmpty()) null else
            compiledFilters[selectedId]
        HLog.e("$TAG publish: selected=$selectedId ready=$ready enabled=${config.enabled} " +
            "hosts=${liveHosts().size} filter=${filter != null}")
    }

    private fun clearChangedAccount() {
        if (account == ConversationTabsStore.accountKey()) return
        filter = null
        selectedId = "all"
        config = ConversationTabsConfig()
        compiledFilters = emptyMap()
        loadedIcons = emptyMap()
        liveHosts().forEach {
            setHostEnabled(it, false)
            it.content.removeAllViews()
            it.buttons.clear()
        }
    }

    private fun select(id: String) {
        if (id == selectedId || config.tabs.none { it.id == id }) return
        val target = config.tabs.first { it.id == id }
        // 微信首页进入原生空列表后，再切其他分类会留下大块空白。
        // 未读为空时不要进入这个空态，直接留在“全部”。
        if (target.kind == ConversationTabKind.UNREAD && unreadCount() == 0) {
            HLog.e("$TAG 未读为空, 不切换空分类")
            val all = config.tabs.firstOrNull { it.kind == ConversationTabKind.ALL }
            if (all != null && all.id != selectedId) select(all.id)
            return
        }
        selectedId = id
        publish()
        liveHosts().forEach(::highlight)
        requestRefresh()
        main.postDelayed(::refreshHostAdapters, 120L)
        main.postDelayed({
            if (selectedId != id) return@postDelayed
            val host = liveHosts().firstOrNull() ?: return@postDelayed
            val list = host.list ?: findConversationList(host.root) ?: return@postDelayed
            val adapter = list.let { KavaReflector.invokeMethod(it, "getAdapter") }
            val count = KavaReflector.invokeMethod(adapter, "getItemCount") as? Int ?: 0
            // 首页 adapter 可能还有一个占位 header，因此空分类通常是 1 而不是 0。
            if (count <= 1) {
                val all = config.tabs.firstOrNull { it.kind == ConversationTabKind.ALL } ?: return@postDelayed
                if (all.id != selectedId) select(all.id)
            }
        }, 500L)
    }

    private fun refreshHostAdapters() {
        liveHosts().forEach { host ->
            val list = host.list ?: findConversationList(host.root)
            val adapter = list?.let { KavaReflector.invokeMethod(it, "getAdapter") }
            ConversationGroupHomeProjection.refreshFromAdapter(adapter)
            // 空分类时列表可能停在很大的滚动偏移，切回其他分类会先看到一片空白。
            (list as? androidx.recyclerview.widget.RecyclerView)?.let { rv ->
                rv.stopScroll()
                (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.let { lm ->
                    // 首页是顶部对齐列表；空态/筛选后若被设成 stackFromEnd，
                    // 少量行会全部堆在屏幕底部，上方就是大块空白。
                    lm.stackFromEnd = false
                    lm.reverseLayout = false
                }
                rv.scrollToPosition(0)
                // 空态/下拉时原生可能留下位移动画，切换分类后仍把列表整体压在屏幕下方。
                rv.translationY = 0f
                // 原生空态可能写入异常大的 padding/topMargin，直接归位到标签栏高度。
                if (rv.paddingTop > rv.height / 2) {
                    rv.setPadding(rv.paddingLeft, 0, rv.paddingRight, rv.paddingBottom)
                }
                (rv.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                    val expected = host.strip.measuredHeight
                    if (expected > 0 && params.topMargin != expected) {
                        params.topMargin = expected
                        rv.layoutParams = params
                    }
                }
                // 原生 adapter 刷新是异步的，刷新后还会重新应用一次旧滚动位置。
                hideBlankListHeader(rv)
                main.postDelayed({
                    rv.stopScroll()
                    rv.scrollToPosition(0)
                    rv.layoutManager?.let { lm ->
                        runCatching { KavaReflector.invokeMethod(lm, "scrollToPositionWithOffset", 0, 0) }
                    }
                    hideBlankListHeader(rv)
                }, 350L)
            }
        }
    }

    private fun hideBlankListHeader(rv: androidx.recyclerview.widget.RecyclerView) {
        for (index in 0 until rv.childCount) {
            val child = rv.getChildAt(index) ?: continue
            val blank = child.height > rv.height / 2
            if (blank) {
                HLog.e("$TAG 隐藏空态占位: ${child.javaClass.name} h=${child.height} " +
                    "w=${child.width}")
                child.visibility = View.GONE
                child.layoutParams?.let { params ->
                    if (params.height != 0) {
                        params.height = 0
                        child.layoutParams = params
                    }
                }
            } else {
                child.visibility = View.VISIBLE
            }
        }
    }

    private fun hasVisibleText(view: View): Boolean {
        if (view is TextView && view.text?.isNotBlank() == true) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasVisibleText(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun unreadCount(): Int {
        return runCatching {
            WeChatApis.storage()?.queryString(
                "SELECT COUNT(*) AS c FROM rconversation " +
                    "WHERE unReadCount > 0 OR unReadMuteCount > 0",
                null,
                "c"
            )?.toIntOrNull() ?: -1
        }.getOrDefault(-1)
    }

    private fun requestRefresh(retries: Int = 0) {
        main.removeCallbacks(refresh)
        main.postDelayed({
            refresh.run()
            // 首页首次查询早于标签投影安装时，刷新器要等一次原生 page 更新才就绪。
            if (retries < 5) requestRefresh(retries + 1)
        }, if (retries == 0) 80L else 350L)
    }

    private fun render(host: Host) {
        setHostEnabled(host, config.enabled && account.isNotBlank())
        host.content.removeAllViews()
        host.buttons.clear()
        if (!host.enabled) return
        val ctx = host.root.context
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val dark = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        host.strip.setBackgroundColor(if (dark) Color.rgb(25, 25, 25) else Color.rgb(247, 247, 247))
        config.tabs.forEach { tab ->
            val button = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                minimumWidth = dp(48); minimumHeight = dp(48)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                contentDescription = tab.name
                isFocusable = true
                setOnClickListener { select(tab.id) }
            }
            if (tab.display != ConversationTabDisplay.NAME) {
                val bitmap = loadedIcons[tab.iconPath]
                if (bitmap != null) {
                    button.addView(ImageView(ctx).apply {
                        setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                } else {
                    button.addView(TextView(ctx).apply { text = tab.icon; textSize = 18f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                }
            }
            if (tab.display != ConversationTabDisplay.ICON) {
                button.addView(TextView(ctx).apply {
                    text = tab.name; textSize = 14f; setSingleLine()
                    if (tab.display == ConversationTabDisplay.BOTH) setPadding(dp(6), 0, 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }
            host.content.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            host.buttons.add(tab.id to button)
        }
        highlight(host)
    }

    private fun highlight(host: Host) {
        val dark = host.root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val density = host.root.resources.displayMetrics.density
        host.buttons.forEach { (id, button) ->
            val selected = id == selectedId
            button.isSelected = selected
            button.background = if (selected) GradientDrawable().apply {
                cornerRadius = 8 * density; setColor(if (dark) 0x3325BA73 else 0x1825BA73)
            } else null
            for (i in 0 until button.childCount) (button.getChildAt(i) as? TextView)?.let {
                it.setTextColor(if (selected) 0xFF07A45E.toInt() else if (dark) Color.LTGRAY else Color.DKGRAY)
                it.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    fun destroy(owner: FeatureContext) {
        val generation = synchronized(reloadLock) {
            if (context !== owner) return
            ConversationTabsIconPicker.cancelAll()
            invalidateLoads()
            listener?.let { HchatStorage.preferences(owner.hostContext(), ConversationTabsStore.PREFS_NAME)
                .unregisterOnSharedPreferenceChangeListener(it) }
            listener = null; context = null; filter = null; ready = false; account = ""
            loadedIcons = emptyMap(); compiledFilters = emptyMap(); bitmaps.evictAll()
            hookedMainUi = null
            lifecycle.incrementAndGet()
        }
        main.post {
            synchronized(reloadLock) {
                if (lifecycle.get() != generation || context != null) return@synchronized
                main.removeCallbacks(refresh)
                liveHosts().forEach(::removeHost)
                hosts.clear()
                ConversationGroupHomeProjection.refresh()
            }
        }
    }
}
