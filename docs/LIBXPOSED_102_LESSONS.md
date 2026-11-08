# libxposed 102 迁移故障复盘

记录本迁移过程中"能识别模块但零日志、微信无入口"的完整排查链与结论，
以后再遇到同类现象按本文顺序排查。

## 一、最终根因（关键）

**`onPackageReady` 发生在 `Application` 实例创建之前。**

API 102 的回调时序：

```
onModuleLoaded（进程级）
  → onPackageLoaded
  → onPackageReady        ← ClassLoader 已就绪，但 Application 还是 null
  → Application 构造 / onCreate
```

早期入口实现是：

```kotlin
val app = currentApplication() ?: return   // ← 这里直接返回了
```

于是模块"加载了、回调进了、但什么都没初始化"，表现就是：
LSPosed 里能看到模块、能勾选作用域，**日志里没有模块输出、微信里也没有入口**。

**正确做法**：入口允许 `app == null`，把初始化交给 `ModuleEntry`，
由它去 Hook `Application.onCreate`，在回调里再拿真正的 Application。

## 二、排查过程中排除/确认的其它因素

| 怀疑点 | 结论 |
|---|---|
| 缺 `XposedProvider` | **不是必需**。可用的 102 模块（ForbidAd4TieBa）Manifest 里没有 provider；该 provider 只用于模块配置服务 |
| `io.github.libxposed:service` 依赖 | 不需要；且它会把 `api` 传递依赖打进来，必须避免（api 只能由框架提供） |
| legacy `de.robv.android.xposed` 残留 | API 102 规范明确禁止调用，dex 里不能留这类引用 |
| `staticScope` / `scope.list` | 可用（最终已恢复 `staticScope=true`，作用域由 `scope.list` 声明 `com.tencent.mm`）；排查期曾临时改成 false 以排除变量 |
| R8 重命名入口类 | **会踩**：`java_init.list` 按类名加载，必须 `-keep class ...HchatModule { *; }` |
| 无 R8 包多 dex | 入口可能落在 `classes2+.dex`；不排除加载器只读主 dex 的可能，验证入口建议用 R8 单 dex 包 |
| LSPosed 版本 | 2.2.1 支持 api=102（日志里 `api=102` 可确认） |

## 三、入口实现要点（见 `h/Hchat/libxposed/HchatModule.kt`）

1. 每个生命周期回调都调用 `super.xxx(param)`；
2. 用 `onModuleLoaded` 的 `param.processName` 判定进程，别只依赖 `/proc/self/cmdline`；
3. 只对 `com.tencent.mm` 主进程与 `:appbrand*` 执行 bootstrap，其余进程打印"跳过进程"；
4. 日志同时写 `android.util.Log`（logcat，tag `HCHAT102`）与 `module.log`（LSPosed 日志页）；
5. 所有回调包 try/catch：入口异常会让框架静默放弃加载。

## 四、验证清单

```text
[ ] logcat 出现 "构造 HchatModule"
[ ] 出现 "onModuleLoaded ... api=102"
[ ] 出现 "onPackageReady: pkg=com.tencent.mm process=com.tencent.mm"
[ ] 出现 "bootstrap 开始 ... app=null"（正常，App 还没创建）
[ ] 出现 "构建标记 build=..."（说明 initModule 真的跑到了）
[ ] 微信里能打开模块设置
```

## 五、当前遗留问题（test.64 / commit dd33bad 实测）

| 问题 | 日志 | 影响 |
|---|---|---|
| 网络派发器未定位 | `sendNetworkRequest 失败: dispatcher=false method=false` ×18 | 自动抢红包/自动收款、脚本发网络请求会静默失效 |
| LauncherUI#onCreate 未找到 | `[Hchat:Hook] 未找到方法: com.tencent.mm.ui.LauncherUI#onCreate` ×2 | 设置入口注入部分失效（后续已修，当前设置入口正常） |
| 自动礼物消息类缺失 | `HchatGift 未找到消息类` ×12 | 自动礼物（已知未修） |

## 六、微信 8.0.80 网络队列定位（2026-11-08 实测）

### 现象

```text
[Hchat:DexFinder] 网络队列解析: class=null candidates=54 resolved=false
    receive=false receiveUnion=false open=false openUnion=false
```

`candidates=54` 说明 DexKit 已找到候选类，但旧的筛选条件全部不命中。

### 逆向结论

用 `scripts/dex_string_refs.py` / `scripts/dex_class_methods.py` 和 jadx 对
8.0.80 `base.apk`（versionCode 3220）分析：

- 旧锚点 `doSceneImp`、`waitingQueue_size`、`dispatcher is null` 已没有代码引用；
- `MicroMsg.NetSceneQueue` 仍被 `com.tencent.mm.modelbase.s1` 引用；
- `s1` 就是 NetSceneQueue：
  - `g(com.tencent.mm.modelbase.n1) -> boolean`，内部直接 `h(n1, 0)`；
  - `h(com.tencent.mm.modelbase.n1, int) -> boolean` 是真正的入队/发包；
  - `i(n1, int) -> void`、`j(n1, int, int, int) -> void` 是运行队列处理；
  - 还实现了 `u0` 回调并引用 `MicroMsg.NetSceneQueue`。
- 旧的已知类 `com.tencent.mm.modelbase.r1` 在 8.0.80 只剩一个接口方法 `u() -> int`，
  不是队列。
- 请求基类 `com.tencent.mm.modelbase.n1` 是抽象类，`getType() -> int` 是抽象方法。

普通红包请求类也发生了变化：

- `cgi-bin/mmpay-bin/receivewxhb` / `openwxhb` / `receivehongbao` 只剩字符串池，
  没有代码引用，因此 `receiveLuckyMoneyClass`、`openLuckyMoneyClass` 为 null；
- 实际请求类是 Union 类：
  - `com.tencent.mm.plugin.luckymoney.model.o6`：6 参构造；
  - `com.tencent.mm.plugin.luckymoney.model.i6`：10 参构造；
  - 两者继承链最终都是 `com.tencent.mm.modelbase.n1`。

### 修复

1. `DexFinder.collectKnownNetworkQueueClasses()` 增加
   `com.tencent.mm.modelbase.s1`。
2. 队列判定增加形状兜底：方法名 `g/h/i/j` 或 `dispatch/add/send`，首参是
   带 `getType() -> int` 的请求类型，返回 `boolean/int/void`。
3. `WeChatNetworkDispatcher.isNetworkRequestType()` 对 `n1` 增加按类名快速判定，
   并对抽象 `getType()` 补 `getDeclaredMethod` 兜底。
4. 缓存命中时补解析 Union 红包类（`receiveUnionLocatorResolved` /
   `openUnionLocatorResolved`），避免旧快照把 null 永久缓存。
5. `NETWORK_QUEUE_LOCATOR_VERSION` 升到 3，强制旧缓存重新定位。
6. 普通红包请求类为空时，`RedPacketSilentHandler` 回退到 Union 请求类。

### 真机验证点

重装模块并重启微信后，日志应出现：

```text
网络队列候选命中: com.tencent.mm.modelbase.s1
网络队列解析: class=com.tencent.mm.modelbase.s1 ... resolved=true
网络发包器已缓存: com.tencent.mm.modelbase.s1#g
```

随后发一个普通红包，应出现 `普通红包请求类缺失，回退 Union 请求`，
并能看到 `静默收包` / `拆红包请求已发送`。

### 2026-11-08 23:37 test.70 实测

已通过 GitHub Actions 构建并安装 test.70（code 670），小米 K90 Pro Max /
Android 17 / 微信 8.0.80 / LSPosed 2.2.1 实测：

```text
[Hchat:DexFinder] 红包Union补解析结果: receive=true open=true
[Hchat:DexFinder] 网络队列候选命中: com.tencent.mm.modelbase.s1
[Hchat:DexFinder] 网络队列解析: class=com.tencent.mm.modelbase.s1
    candidates=53 resolved=true receive=false receiveUnion=true open=false openUnion=true
[Hchat:NetQueue] Hook成功: com.tencent.mm.modelbase.s1 methods=3
[Hchat:NetQueue] 缓存发包器: com.tencent.mm.modelbase.s1#h
```

`#h` 是 `h(n1, int) -> boolean`，即真正的入队/发包方法；`g(n1)` 只是
`return h(n1, 0)`。到此网络队列定位与 Hook 已验证通过。

普通红包的 Union 回退还需要收一个真实红包后看
`普通红包请求类缺失，回退 Union 请求` 与后续 `静默收包` / `拆红包请求已发送`。
