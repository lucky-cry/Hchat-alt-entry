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
| `staticScope` / `scope.list` | 可用，但若管理器读取异常会表现为"无目标、静默不注入"；排查期建议 `staticScope=false`，用管理器勾选作用域 |
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
| LauncherUI#onCreate 未找到 | `[Hchat:Hook] 未找到方法: com.tencent.mm.ui.LauncherUI#onCreate` ×2 | 设置入口注入部分失效 |
| 自动礼物消息类缺失 | `HchatGift 未找到消息类` ×12 | 自动礼物（已知未修） |
