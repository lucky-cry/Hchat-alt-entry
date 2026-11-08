# libxposed API 102 迁移方案

目标：从 legacy Xposed API（`de.robv.android.xposed:api`）迁移到
libxposed（`io.github.libxposed:api:102`），并使用新模块格式声明**静态作用域**。

参考实现：`BetterHeybox_v0.9.0.apk`（minApiVersion=101 / targetApiVersion=102）。

## 1. 两套 API 的对应关系

| legacy | libxposed 102 |
|---|---|
| `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| `IXposedHookLoadPackage.handleLoadPackage` | `XposedModule.onPackageLoaded` / `onPackageReady` |
| `XC_MethodHook.before/afterHookedMethod` | `Hooker.before/after`（`HookHandle`） |
| `XposedBridge.hookMethod` | `XposedInterface.hook(method, priority, hooker)` |
| `XposedBridge.log` | `module.log(level, msg)` |
| `XposedHelpers.findAndHookMethod` | `XposedInterface` 的 hook + 自行查找 Method |
| Manifest `xposedminversion` 等 meta-data | `META-INF/xposed/module.prop` |
| `@array/xposed_scope` | `META-INF/xposed/scope.list` |

新模块必须包含：

```text
META-INF/xposed/module.prop     minApiVersion=102
                                targetApiVersion=102
                                staticScope=true
                                autoHotReload=true
META-INF/xposed/scope.list      com.tencent.mm
META-INF/xposed/java_init.list  h.Hchat.libxposed.HchatModule
```

## 2. 迁移顺序（每步都可独立编译 + 真机验证）

1. **适配层骨架**（已完成，`hooks/core/HookApi.kt`）
   `HookParam` / `HookCallback` / `HookEngine` + `LegacyHookEngine`，行为不变。
2. **HookRegistry 走引擎**
   `HookRegistry.get().hook(...)` 内部改为委托 `HookEngine`；legacy 仍是默认实现。
   验收：现有功能全部正常，日志无新增错误。
3. **libxposed 依赖与元数据**
   `build.gradle.kts` 增加 `compileOnly("io.github.libxposed:api:102")`；
   新增 `META-INF/xposed/*`；实现 `h.Hchat.libxposed.HchatModule`，
   `onPackageLoaded` 里转调现有 `ModuleEntry` 的初始化逻辑（先只做入口转发）。
   验收：LSPosed 识别为 102 模块，静态作用域生效，功能不回归。
4. **日志与崩溃上报**
   `HLog.e` 改为经由模块实例输出（保留 legacy fallback），避免两套入口都依赖
   `XposedBridge`。
5. **功能层分批迁移**
   按目录分批：`hooks/api/**` → 高频功能（消息/红包/防撤回）→ 其余。
   每批一个提交，跑一遍 `docs/MY_BASELINE.md` 冒烟清单。
6. **收尾**
   删除 legacy 依赖与 `assets/xposed_init`，只保留 102 入口；补 102 版本说明文档。

## 3. 风险与约束

- 两套入口**不能同时声明**：LSPosed 只按一种方式加载，第 3 步必须一次切换成功，
  失败要能回滚到 legacy（因此保持 `my/stable` 不动）。
- 新 API 的 `Hooker` 没有 `XC_MethodHook` 的 `param` 包装，替换时要重写参数读写方式，
  尤其 `param.result = null`（吞掉原返回值）在 102 里要用 `HookHandle.returnEarly`。
- `DexKit` 与反射逻辑不受影响，可原样保留。
- 迁移期间正式版仍走 `my/stable`（legacy），本分支只在测试包验证。
