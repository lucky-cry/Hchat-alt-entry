# libxposed API 102 迁移与加载排查

目标：从 legacy Xposed API（`de.robv.android.xposed:api`）迁移到
libxposed（`io.github.libxposed:api:102`），并使用新模块格式声明作用域。

参考实现：`ForbidAd4TieBa`（`minApiVersion=102` / `targetApiVersion=102`）、
libxposed 官方文档与 `libxposed/api` 仓库。

## 1. 两套 API 的对应关系

| legacy | libxposed 102 |
|---|---|
| `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| `IXposedHookLoadPackage.handleLoadPackage` | `XposedModule.onPackageLoaded` / `onPackageReady` |
| `XC_MethodHook.before/afterHookedMethod` | `XposedInterface.Hooker.intercept(Chain)` |
| `XposedBridge.hookMethod` | `XposedInterface.hook(method).intercept(...)` |
| `XposedBridge.log` | `module.log(level, tag, msg)` |
| `XposedHelpers.findAndHookMethod` | `XposedInterface` 的 hook + 自行查找 Method |
| Manifest `xposedminversion` 等 meta-data | `META-INF/xposed/module.prop` |
| `@array/xposed_scope` | `META-INF/xposed/scope.list` |

新模块必须包含：

```text
META-INF/xposed/module.prop     minApiVersion=101
                                targetApiVersion=102
                                staticScope=false
META-INF/xposed/scope.list      com.tencent.mm
META-INF/xposed/java_init.list  h.Hchat.libxposed.HchatModule
```

> `minApiVersion` 是作者声明的最低版本；框架实际按 `targetApiVersion` 选择加载策略。
> 本模块没有使用 102 独有 API，因此最低版本保留 101。

## 2. 关键加载时序（最容易踩的坑）

libxposed 102 的入口不是 legacy 的 `handleLoadPackage`，各回调触发时机不同：

| 回调 | 时机 | 能拿到什么 |
|---|---|---|
| `onModuleLoaded` | 入口类实例化、`attachFramework` 之后 | 进程名；**可以放心用 `module.log`** |
| `onPackageLoaded` | 默认 classloader 就绪、`AppComponentFactory` 实例化前（API 29+） | `defaultClassLoader`、包名、`ApplicationInfo` |
| `onPackageReady` | AppComponentFactory 创建完 classloader，**Application 实例还没创建** | `classLoader`、包名、`ApplicationInfo` |
| `Application.onCreate` | 宿主 Application 已创建 | 真正的 `Application` 实例 |

**因此 `onPackageReady` 里绝对不能用 `ActivityThread.currentApplication()` 做前置判断。**
API 102 规范明确写着这是「ready to create Application」的时刻，`currentApplication()`
通常为 `null`。正确做法是：

1. 在 `onPackageReady` 里只构建 `HostEnv`（`app` 允许为 null）；
2. 由 `ModuleEntry.bootstrap` 挂 `Application.onCreate`；
3. 在 `onCreate` 回调里拿 `Application`，再启动完整初始化。

本项目旧入口写成 `currentApplication() ?: return`，会在入口第一行直接返回：
LSPosed 日志页没有 `module.log`（只有 `android.util.Log` 写 logcat），微信里也没有入口。

## 3. 日志：LSPosed 日志页只认 `module.log`

- `android.util.Log.*` 只进 logcat，不会出现在 LSPosed 管理器的「日志」页。
- `module.log(level, tag, msg)`（`XposedModule` 自带）才会进 LSPosed 日志页。
- 入口早期用 `logcat` 探针确认框架是否加载了类；初始化后统一走 `HLog.e` → `module.log`。

排查时两条通道都看：

```sh
adb logcat -d -v time | grep -E 'HCHAT102|LSPosedContext|VectorModuleManager|Hchat'
```

LSPosed 日志页搜索 `Hchat` / `HCHAT102`。

## 4. 作用域：`staticScope` 不等于自动勾选

`scope.list` 只是「推荐/允许」的包名列表。即使 `staticScope=true`，Vector/LSPosed
仍要求用户在模块作用域页手动勾选微信：

- `staticScope=true`：只允许从 `scope.list` 里选，列表外的应用不可选；
- `staticScope=false`：列表只是推荐，用户可以选其他应用；
- 两种情况下，**没有勾选 `com.tencent.mm` 就不会注入微信进程**，表现同样是零日志、无入口。

启用模块后必须确认：LSPosed → 模块 → Hchat → 作用域 → 勾选微信 → 强制停止微信再启动。

## 5. 框架版本要求（重要）

| 框架 | 支持的 libxposed API | 能否加载本模块 |
|---|---|---|
| LSPosed 1.9.x（官方最后稳定版） | 100（`io.github.libxposed` 旧包名、构造器注入） | **不能** |
| LSPosed 1.10.x（JingMatrix，2025） | 100（`io.github.libxposed.api`，但仍用两参构造器加载） | **不能** |
| LSPosed 2.x / Vector 2.x（JingMatrix 维护） | 101 / 102（`io.github.libxposed.api`、无参构造 + `attachFramework`） | 能 |
| 仅支持 API 100 的旧管理器 | 100 | 不能 |

LSPosed 1.9.2 / 1.10.2 仍然能通过 `META-INF/xposed/java_init.list` 把 APK 识别为模块，
但运行时不具备 102 的加载方式：1.9.2 用的是旧包名，1.10.2 仍用
`getConstructor(XposedInterface, ModuleLoadedParam)` 加载入口；最终只会留下框架侧
`Failed to instantiate class`，模块日志为空。

API 102 模块请使用实现 102 的框架（Vector 2.x / LSPosed 2.x），不要用 1.9.2 / 1.10.2。

## 6. R8 规则

libxposed 官方要求：

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
```

本项目对入口使用更强的 `-keep class h.Hchat.libxposed.HchatModule { *; }`，类名不会被混淆，
`java_init.list` 不需要改写；但仍建议保留官方规则，避免后续新增入口时漏 keep。

## 7. 真机验收清单

1. `adb logcat -c` 后强制停止微信，再启动。
2. logcat 出现 `HCHAT102: 构造 HchatModule`、`onModuleLoaded`、`onPackageReady`。
3. LSPosed 日志页出现 `HCHAT102 onModuleLoaded` / `onPackageReady`，并带
   `framework=... version=...`；框架名/版本应来自实现 102 的 Vector 2.x。
   注意 `api=` 是模块编译时的 libxposed 库版本，不能单独用来判断框架能力。
4. 出现 `Application 状态: 尚未创建` 是正常现象；随后 `Application.onCreate` 应触发
   `[Hchat:Entry] 构建标记 ...`。
5. 微信「我 → 设置」出现 Hchat 入口，或右上角加号菜单出现配置的入口。
6. 若第 2 步就没有 `HCHAT102`：先确认框架是 Vector 2.x / LSPosed 2.x，再确认作用域
   勾选了微信；仍无输出则抓 `LSPosedContext` / `VectorModuleManager` 的框架日志。
