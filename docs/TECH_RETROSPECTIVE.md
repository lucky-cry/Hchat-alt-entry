# Hchat 维护线技术复盘

从复刻上游到 6.6.6 的完整踩坑与修复记录。

- 分支：`feat/libxposed-102`
- 区间：2026-11-07 → 2026-11-27
- 提交：218 个
- 微信版本：8.0.80（versionCode 3220）
- 模块起点：6.4.1（versionCode 591），当前 6.6.6

本文按"阶段 → 问题 → 根因 → 修法 → 教训"组织。同一个坑反复出现过的，
在最后一章汇总成硬规则，改代码前先对一遍。

---

## 一、时间线总览

| 阶段 | 日期 | 提交量 | 主要产出 |
|---|---|---|---|
| 1. 建立维护线 | 11-07 | 1 | 分叉、改自有签名 |
| 2. 维护线工程化 | 11-08 | ~7 | 版本段、CI、基线文档、锚点脚本 |
| 3. 8.0.80 首批适配 | 11-08 | ~20 | 缓存修复、发送按钮、铃声、视频号 |
| 4. libxposed 102 迁移 | 11-08 | ~60 | Hook 适配层、入口切换、legacy 清理 |
| 5. 102 入口排查 | 11-08 | ~12 | 从"能识别不加载"到真正跑通 |
| 6. 8.0.80 深度适配 | 11-08~09 | ~30 | 红包、AddMsg、表情、免打扰、首页 |
| 7. 聊天分组 / 标签页 | 11-18~22 | ~70 | 虚拟行 → 原生分组页、空白问题 |
| 8. 功能新增 | 11-22~27 | ~15 | 取消模块、优先级排序、表情上限、Windows 在线、Agent 头 |
| 9. 稳定性收尾 | 11-23~27 | ~8 | 语音、骰子、缓存分层、防撤回改造 |

---

## 二、阶段 1~2：建立维护线

### 做了什么

1. 从上游复刻后**换自有签名**，版本段改为 `6.4.x / 600+run`，避免与上游包冲突。
2. 建立 `docs/MY_BASELINE.md`：记录"我们改了什么、和上游差异在哪"。
3. 统一测试包与正式包的 versionCode 序列（测试包 `600+run`，正式包 `1000+run`）。
4. 加锚点核对脚本：`scripts/check_wechat_anchors.py`、`scripts/dex_string_refs.py`、
   `scripts/dex_class_methods.py`。

### 教训

**签名必须自己控制**。用上游签名会导致：无法覆盖安装、无法回退、GitHub Release 装不上。
签名文件和密码要单独备份，不要只放在 CI Secret 里。

**必须有"锚点存在性"脚本**。微信每次升级，最先坏的都是字符串锚点。
有了脚本可以 5 秒判断"是锚点没了，还是逻辑写错了"，省掉大量瞎猜。

---

## 三、阶段 3：8.0.80 首批适配

### 坑 1：DexKit 缓存把「解析失败」当成「已解析」

**现象**
```text
消息变更监听未就绪: databaseHooks=31 wrapperHooks=0
```
消息变更（防撤回、自动回复、消息统计）全部失灵，但其他功能正常。

**根因**
快照缓存序列化时，`sqliteDbWrapperClass` 为 null 也被写进了缓存。
下次启动命中缓存 → 认为"已经解析过了" → 永远不再解析 → 永久失效。

**修法**
1. 命中缓存后校验关键字段：`sqliteDbWrapperClass == null` 则**补解析一次**并回写；
2. 快照版本号 `CACHE_SNAPSHOT_VERSION` 2 → 3 → 4，作废旧快照；
3. 给 sqlite wrapper 加单锚点兜底 `findSqliteWrapperBySingleAnchor()`。

**教训（最重要的一条）**
> 缓存里**不允许出现 null 的"已解析"状态**。要么不缓存该字段，
> 要么命中后校验并补解析。这个坑在后面又复发了 3 次（网络队列、AddMsg、表情）。

---

### 坑 2：诊断日志被 LSPosed 过滤，看不到

**现象**
加了诊断日志，日志页什么都没有，误判成"模块没加载"。

**根因**
日志内容里带了某些关键词（如 `database` / `wrapper` 组合），被过滤规则吃掉。

**修法**
1. 加**构建标记**：`[Hchat:Entry] 构建标记 build=test.64 code=664 diag=DBWRAP-2`。
   只要能看到这行，就证明"代码跑到了"，问题不在加载层；
2. 诊断日志绕开敏感关键词，改用 `[Hchat:DBDiag]` 前缀。

**教训**
**永远先区分"没加载"和"加载了但没工作"**。构建标记是成本最低的判据。

---

### 坑 3：脚本发送按钮定位失败

**现象**：`未定位到ChatFooter发送入口`

**根因**：8.0.80 里监听类的字段类型变了（`eb6/a`），原来按字段类型匹配失效。

**修法**
1. 方法判定 `isChatFooterSendClickMethod` 增加**构造参数兜底**；
2. 清快照重解析。

---

### 坑 4：视频号详情请求构造失败

**现象**：视频号下载拿不到详情。

**根因**：8.0.80 请求构造器参数变了（多了默认参数）。

**修法**
1. `isRequestConstructor` 只校验**前四个参数** `(long, String, int, int)`，其余放宽；
2. `buildRequestArgs` 为缺的参数补默认值（Kotlin 默认参数 mask 方案）。

**教训**
> 微信的构造器参数会**增加且带默认值**。按"全参数精确匹配"必然周期性失效，
> 应按"前 N 个稳定参数 + 其余补默认"匹配。

---

### 坑 5：群通话铃声入口定位不到

**现象**：屏蔽通话铃声开关失效。

**根因**：方向方法（`(Z,I)->V` / `(Z)->V`）的宿主类变了。

**修法**
1. 新增 `locateMultiTalkDirectionMethodsByClassAnchor()`：先 `findMethod` 反查宿主类，
   再在宿主类里全收方向方法；
2. 放宽筛选：路由方法 `(Z,I)->V` 全收，方向方法 `(Z)->V` 全收。

---

### 坑 6：视频号下载保存位置

**需求变化**：先做"存相册" → 用户要求改成"自定义目录，默认私有路径"。

**修法**
1. 新增 `KEY_SAVE_PATH` + `savePath(context)`；
2. 默认 `Android/media/com.tencent.mm/Hchat/Finder/`；
3. 设置页加"保存位置 → 自定义目录"输入行，state 提升到 composable 作用域。

**教训**
涉及存储路径的功能，**默认值要用应用私有目录**，避免相册污染和权限问题。

---

## 四、阶段 4：libxposed API 102 迁移

这是整个维护线里工程量最大的一段，也是坑最多的一段。

### 4.1 为什么要迁

legacy API（`de.robv.android.xposed:api:82`）已停止演进，新 LSPosed 主推 libxposed API。
迁移目标：`io.github.libxposed:api:102.0.0` + 静态作用域。

### 4.2 架构设计

```text
HookApi.kt
  ├── HookParam      (executable/thisObject/args/result/throwable/objectExtra)
  ├── HookCallback   (before/after)
  ├── HookEngine     (hook(Member, HookCallback): Any?)
  ├── NoopHookEngine   ← 默认，避免初始化时加载 legacy 类
  └── LibxposedHookEngine ← 102 实现

HookRegistry.kt
  ├── hookMethod / hookMethodByName / hookAllMethods / hookAllConstructors
  ├── add(handle) / unhookHandle(handle) / unsubscribe
  └── setEngine(engine)   ← 由入口注入
```

### 4.3 迁移中的编译坑（逐条）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `XposedModule 需构造` | 102 里是抽象类 | `class X : XposedModule()`，入口继承 |
| 2 | `Platform declaration clash: setResult` | 属性与方法同名同签名 | 只保留属性 `param.result` |
| 3 | `chain.proceed` 参数可空 | 102 签名要求非空数组 | `chain.proceed(args ?: arrayOfNulls(0))` |
| 4 | `object : HookCallback()` 编译错 | 接口没有构造器 | 改成 `object : HookCallback` |
| 5 | `new HookCallback(priority)` 编译错 | 同上（Java 侧） | 去掉参数，优先级改常量 |
| 6 | `import de.robv...HookCallback` 混乱 | 批量替换把 import 打乱了 | **先修 import，再替换类型** |
| 7 | `param.setResult()` 报错 | Kotlin 侧是属性 | 改 `param.result = x` |
| 8 | 误伤未迁移文件 | 全局替换没区分 | 按"文件是否仍含 `XC_MethodHook`"区分 |
| 9 | `List<Any>` vs `Set<Any>` | 容器类型不一致 | 统一 `List`，卸载走 `unhookHandle` |
| 10 | `thisObject` 可空导致类型不匹配 | 102 侧声明可空 | 调用点补 `?: return`，安全链 `?.` |
| 11 | 迁移脚本改了**插件模板字符串**里的 import | 模板字符串内含示例代码 | 该文件加跳过名单（`ScriptPluginRuntime.kt`） |

### 4.4 迁移规模

```text
XC_MethodHook 引用      520 → 0（除模板字符串）
XposedBridge.hookAll*   → HookRegistry.hookAll*
XposedHelpers.*         → 反射 / KavaReflector
HLog                    → 可注入 Sink（102 走 module.log，回落 android.util.Log）
```

### 4.5 入口切换与「能识别但零日志」

这一段是**最折磨人**的部分，前后 10 多次尝试。完整记录：

#### 尝试 1：加 provider

```xml
<provider android:name="io.github.libxposed.service.XposedProvider"
          android:exported="true" android:authorities="h.Hchat.XposedService" />
```
结果：LSPosed 里**能看到模块了**，但日志仍然为零。

#### 尝试 2：`minApiVersion` 102 → 101

怀疑管理器只实现到 101。结果：无变化。

#### 尝试 3：入口全防御式 + 只用 `android.util.Log`

怀疑回调早期抛异常被框架静默吞掉。结果：无变化。

#### 尝试 4：彻底移除 legacy

按 RFC #62：*targeting API 102 的模块不得调用 legacy `de.robv.android.xposed` API*。
删除依赖、`LegacyHookEngine`、`XposedHelpers/XposedBridge/XCallback` 全部引用。
结果：编译通过，仍然零日志。

#### 尝试 5：R8 keep 规则

发现 R8 包把入口类重命名了，而 `java_init.list` 按类名加载：
```proguard
-keep class h.Hchat.libxposed.HchatModule { *; }
```
结果：入口还在，仍零日志。

#### 尝试 6：排查"入口在不在主 dex"

无 R8 包被拆成 5 个 dex，入口落在 `classes3/4.dex`。
怀疑加载器只读 `classes.dex`。结果：改成 R8 单 dex 后仍零日志。

#### 尝试 7：对齐可用的参考实现

对照 `ForbidAd4TieBa`（确认可用的 102 开源模块）发现三处差异：
1. 它**没有** `io.github.libxposed:service` 依赖；
2. 它 Manifest 里**没有** `XposedProvider`；
3. 它每个回调都调用 `super.xxx(param)`。

全部对齐。结果：**仍然零日志**。

#### 最终根因（用户自己提交 `dd33bad fix bug`）

> **`onPackageReady` 发生在 `Application` 实例创建之前。**
> 入口里 `val app = currentApplication() ?: return` 直接返回了，
> 所以"加载了、回调进了、但什么都没初始化"。

正确做法：
```kotlin
// 入口不要以 Application 为前提
val env = HostEnv(
    app = null,                    // 允许为空
    classLoader = param.classLoader,
    ...
)
ModuleEntry().bootstrap(env)       // 初始化交给 Application.onCreate Hook 触发
```

**为什么极难定位**：模块"被识别""作用域生效""无崩溃"，唯独没有输出。
所有常规判据（日志、崩溃、开关）都指向"正常"，但初始化根本没跑。

#### 教训（写进规则）

1. **入口不能把 Application 当先决条件**。102 时序是
   `onModuleLoaded → onPackageLoaded → onPackageReady → Application 创建`。
2. 排查"零输出"时，**先证明代码被执行**（构建标记 / logcat 探针 / 写文件），
   再谈初始化逻辑。
3. 对照**一个确认可用的同类模块**，比读文档更快。
4. 静态作用域、provider、service 依赖都**不是**加载必需项；
   它们只影响识别与配置服务，别在错误方向上耗时间。

### 4.6 验收清单（每次改入口都跑一遍）

```text
[ ] logcat: 构造 HchatModule
[ ] logcat: onModuleLoaded ... api=102
[ ] logcat: onPackageReady pkg=com.tencent.mm process=com.tencent.mm
[ ] logcat: bootstrap 开始 ... app=null     ← 正常
[ ] logcat: 构建标记 build=...              ← 证明 initModule 跑到
[ ] 微信里能打开模块设置
```

---

## 五、阶段 6：8.0.80 深度适配（第二轮）

### 坑 7：网络队列 / 红包请求类定位失效

**现象**
```text
sendNetworkRequest 失败: dispatcher=false method=false
网络队列解析: class=null candidates=54 resolved=false
             receive=false receiveUnion=false open=false openUnion=false
```
自动抢红包、自动收款、脚本网络 API 全部静默失效。

**根因（两层）**
1. 缓存把 `netQueueClass=null` 当已解析（同坑 1，复发）；
2. 8.0.80 里 `cgi-bin/mmpay-bin/receivewxhb` **字符串还在，但没有任何代码引用**，
   按"请求类参数"筛队列类的策略彻底失效。

**修法（最终由 `5e0c698` 收敛）**
1. 缓存补解析扩展到网络队列与 Union 红包类；
2. 增加 `modelbase.s1` 与 `g/h` 形状兜底；
3. `WeChatNetworkDispatcher` 兼容 `n1` 抽象 `getType`，只 Hook 主队列类，
   避免候选类误缓存；
4. `RedPacketSilentHandler` 普通红包请求缺失时**回退 Union 请求**。

**教训**
> 锚点"字符串存在 ≠ 代码引用存在"。判断锚点是否可用，必须查**引用**，
> 不能只查字符串。（`dex_string_refs.py` 就是为此写的）

---

### 坑 8：AddMsg / 表情 / 免打扰 补解析被旧快照短路

与坑 1、坑 7 同源。缓存版本升到 6，并禁止"负缓存"（`dbcebbc`）。

**规则化**：DexFinder 里任何新增定位，都必须：
1. 有独立 locator 版本号；
2. 命中缓存后校验空值并补解析；
3. 不允许把失败结果写进缓存。

---

### 坑 9：ECS 礼物 / 首页文字颜色

**修法**：`w20.j1` 已知类兜底、`8.0.80` 普通会话形状兜底。

---

## 六、阶段 7：聊天分组 / 标签页（最长战线）

### 演进路线（4 次推翻重做）

```text
虚拟行拦截（RecyclerView 插假行）
  → 命中 Fragment 但显示非好友页
  → 自绘浮层（去重、可进入群聊）
  → 原生分组页（ChattingUIProxy 覆盖、返回键接管）
  → 最终：原生列表 + 禁止回退自绘浮层
```

### 坑 10：切换标签后首页大面积空白

**排查过程**：20+ 次提交，从"第 1 步"到"第 16 步"逐层追踪
`LayoutManager → AnchorInfo → LayoutState → OrientationHelper`。

**最终根因**（精确到数值）

从「未读」切到其他分类时，宿主 `ItemAnimator` 的预测动画会让
`LinearLayoutManager` 在 `onLayoutChildren` 中执行 `offsetChildrenVertical(1588)`，
把 `TaskBarContainer` 从 `top=-2068` 推到 `-480`，首页顶部因此出现大块空白。

**修法**（`f5d2029`）
> 离开「未读」前临时保存并置空宿主列表的 `ItemAnimator`，600ms 后恢复。
> 只绕开预测布局，不改滚动位置、不改 View 树。

**为什么难**：不是布局参数错，而是预测动画与锚点恢复的竞争；
且只在"快速切换 + 未读标签"组合下复现。

### 坑 10b：快速切换标签偶发不生效

**根因**：`requestRefresh` 的旧延迟链取消无效，多个投影刷新并发，
**旧查询结果覆盖新结果**。

**修法**：按切换代次只保留一条刷新链，取消旧 `refreshHostAdapters`，
并把 `ConversationGroupHomeProjection.refresh` 改为串行——运行期间的新请求
在结束后补跑一次。

**定位过程提交**：`b9e0f35` → `1f12fc3`（第 4~16 步）；
**历史排查提交**：`3e827c1`、`be8f93a`、`00525e7`、`e8e096e`、`7ce2046`、`1731cdf`。

**教训**
1. 布局类问题必须先建立**假设清单**，每次只改一个变量（实验 A/B/C）；
2. 诊断代码要**可开关、可收敛**，查完立刻删（`0e426d0` 做了清理）；
3. 结论必须写文档，否则 20 次实验的结论只存在于 git log。

### 坑 11：分组页关闭后无法再次打开 / 第二次点击显示非好友页

**修法**：分组页置顶 + 进入前同步挂载 + 保留状态，避免重复重建。

### 坑 12：返回键行为

**修法**：`OnBackPressedDispatcher` 接管，按下即吞事件；
返回顺序：关聊天 → 回首页，而不是直接退出分组页。

---

## 七、阶段 8：功能新增

| 功能 | 关键实现 | 坑 |
|---|---|---|
| 取消模块 + 安全模式 | `CancelModuleFeature` + `core/SafeMode.kt` | 加号菜单入口不显示开关/图标、点击无反应 → 改独立界面入口 |
| 会话优先级排序 | `ConversationPriority*` 三件套 | 与分组虚拟行刷新竞争；实现参考 WeKit `SuperConversationPinning`，最终 Hook 首页 MVVM 会话项的 `compareTo`，而非排序 Adapter 数据 |
| 表情收藏上限 | `EmojiCollectionLimitFeature` | 补解析被旧快照短路 |
| 隐藏 Windows 在线 | `windowsonline/` | 删除整行会破坏布局 → 改为折叠整行 |
| Agent 请求头 | `ScriptPluginAgentRequestHeaders` | 需独立设置弹窗 |

**教训**：UI 类入口（菜单项/图标）改动后必须真机验证"三可"：
**可显示、可点击、可返回**。只编译通过不代表入口可用。

---

## 八、反复复发的坑：硬规则

这 6 条在整个维护线里各复发过 2~4 次，写代码前逐条对。

### R1｜缓存不允许负值
> 缓存里不能出现"已解析但值为 null"。命中后必须校验并补解析。
> 新增定位 = 新增 locator 版本号。

### R2｜锚点看引用不看字符串
> 字符串存在不代表可用，必须查 `const-string` 引用。
> 微信升级后第一件事：跑 `check_wechat_anchors.py` + `dex_string_refs.py`。

### R3｜先证明代码被执行
> 排查"没反应"时，先加构建标记/探针，区分"没加载"和"加载了没工作"。

### R4｜入口不依赖 Application
> libxposed 的 `onPackageReady` 早于 Application 创建，入口必须以 null 容错。

### R5｜构造器按前缀匹配
> 微信构造器参数会增长且带默认值，按前 N 个稳定参数匹配，其余补默认。

### R6｜布局问题先假设后实验
> 一次只改一个变量，诊断代码可开关，查完即删，结论落文档。

---

## 九、工程规范与工具

### 脚本

| 脚本 | 用途 |
|---|---|
| `scripts/check_wechat_anchors.py` | 批量核对锚点字符串是否存在 |
| `scripts/dex_string_refs.py` | 查字符串被哪些类引用（判断锚点可用性） |
| `scripts/dex_class_methods.py` | 导出类的方法签名（定位构造器/方法形状） |

> 逆向优先用 DexSQL（`dexsql query --sql "..."`）在目标 APK 上确认混淆名，
> 再用 DexKit/`KavaReflector` 写代码；不要凭记忆猜类名、字段、方法。
| `tools/check_hook_migration.py` | 迁移预检（9 类错误） |

### CI

| 工作流 | 用途 | 产物 |
|---|---|---|
| `android-test.yml` | 无 R8 快速验证 | `Hchat-test` artifact，`600+run` |
| `android-single.yml` | R8 正式包 + Release | `Hchat-release-signed.apk`，`1000+run` |

### 文档

| 文档 | 内容 |
|---|---|
| `docs/MY_BASELINE.md` | 维护线与上游差异 |
| `docs/LIBXPOSED_102_MIGRATION.md` | 迁移步骤 |
| `docs/LIBXPOSED_102_LESSONS.md` | 102 入口排查复盘 |
| `docs/FIXES/2026-11-08-微信8080失效清单.md` | 8.0.80 失效功能清单 |
| 本文 | 全量技术复盘 |

---

## 十、遗留问题

| 问题 | 状态 | 建议 |
|---|---|---|
| 自动礼物（`HchatGift 未找到消息类`） | 用户明确暂不修 | 若要修，改走消息观察 API 而非 AddMsg 锚点 |
| 诊断代码残留 | 23 处候选（多为标识符，真 `[DIAG]` 为 0） | 按清单逐条清理，日志收敛进开关 |
| 疑似未引用 private 函数 | 静态扫描 201 个（含误报） | 出带引用检查的报告后删除 |
| 定位版本号纪律 | `CACHE_SNAPSHOT_VERSION=4`、`NETWORK_QUEUE=2`、`UNION=1` | 写进 AGENTS.md 硬规则 |
| 标签分组缺结论文档 | 20+ 实验散在 git log | 补 `docs/CONVERSATION_TABS_FIX.md` |
| 版本号 ↔ 提交对照 | 线上 6.6.6，锚点仍 6.4.1/600 | 在 MY_BASELINE 维护映射表 |

---

## 附：版本号规则

```text
测试包 versionCode = 600 + GITHUB_RUN_NUMBER   （artifact Hchat-test）
正式包 versionCode = 1000 + GITHUB_RUN_NUMBER  （Release）
versionName        = 6.4.1 起按发布自增（当前 6.6.6）
```

> 正式包基线从 600 提到 1000 的原因：早期正式版 code 低于测试版，
> 覆盖安装会被系统判降级（`a83940b`）。
