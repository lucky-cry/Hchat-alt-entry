# Hchat 社区开发规则

这份文件会被 Codex 自动读取。开始任何任务前，先读本文件，再按需要读取：

- `docs/AI_CODING_TUTORIAL.md`：Termux、Codex、DexClub MCP、构建、提 PR 的完整教程
- `docs/FEATURE_FRAMEWORK.md`：模块功能架构、生命周期和构建约定
- `docs/WECHAT_APIS.md`：模块公共微信 API
- `docs/SCRIPT_PLUGIN_API.md`：脚本插件完整接口
- `app/src/main/assets/script_plugin_agent_guide.md`：脚本插件 Agent 的运行时能力清单
- `skills/hchat-project/SKILL.md`：长期协作规则

## 仓库与目标环境

- 当前工作仓库：`origin = https://github.com/lucky-cry/Hchat-alt-entry.git`
- 当前开发分支：`feat/libxposed-102`
- 上游参考：`upstream = https://github.com/Hchat-dev/Hchat-alt-entry.git`
- 当前主线适配：微信 `8.0.80`（versionCode `3220`），`arm64-v8a`，Android 17 / HyperOS。
- 其他微信版本没有逐版验证时，必须明确写“未验证”，不能假设兼容。
- 本分支只支持 `arm64-v8a` 和 64 位微信进程；新增 native 库只提供 `arm64-v8a`。

## 参考项目

- 重点参考项目：WeKit，https://github.com/Ujhhgtg/WeKit
  - 功能文档：https://github.com/Ujhhgtg/WeKit/tree/main/docs/features
  - 本地分析克隆：`D:\github\WeKit`（仅用于阅读/对比，不属于本仓库，不提交其源码）
- 新增或修改功能前，先检索 WeKit 是否已有同类功能、交互设计和实现思路；优先借鉴其方案，再结合本仓库架构与目标微信版本重新实现。
- 借鉴时只参考思路，不直接复制未经确认许可证兼容的代码；WeKit 中的混淆类名/字段不能直接套用，必须用 DexSQL / DexKit 在当前目标微信 APK 上重新确认。
- 已借鉴记录：会话优先级排序参考 WeKit `SuperConversationPinning`，最终采用 Hook 首页 MVVM 会话项 `compareTo` 的方案，而不是排序 Adapter 数据。

## 开发底线

- 先读取 `git status` 和相关源码，再修改；不要覆盖别人未提交的工作。
- 不确定微信内部类、方法、字段、数据库或 Intent 时，先用 DexClub / DexKit 逆向确认，不能凭记忆猜混淆名称。
- 修改 DexKit 定位时必须考虑缓存、微信版本、热更新和 ClassLoader；不要每次启动无条件重跑定位。
- 新增反射代码走 `h.Hchat.utils.KavaReflector`；模块错误日志走 `HLog.e(...)`。
- 新增功能、公共 API、脚本 API、设置入口、构建流程或文件路径时，同步更新相关文档。
- 默认不在本地运行 Gradle；只有用户明确要求构建或验证时才构建。低内存机器优先使用 GitHub Actions。
- 不要提交 keystore、APK、`local.properties`、构建缓存、服务器密码、API key、Token、聊天数据库或个人媒体。
- 提交信息和 PR 标题、说明使用中文。

## 功能结构约定

- 功能实现放在 `app/src/main/java/h/Hchat/hooks/items/<feature>/`。
- 新功能在 `h.Hchat.hooks.core.FeatureRegistry` 注册，设置页通过 `FeatureSettingsProvider` 暴露入口。
- 设置页路由和分组入口：
  - 分组入口在 `MiuixSettingsPage.kt`；
  - 页面实现在 `MiuixSettingsPagePages.kt` / `MiuixSettingsPageSections.kt`。
- 新功能开关必须真正控制运行时行为，不能只改设置页状态。
- 模块运行在微信进程时，宿主 `Context` 与模块自身 Context 不同；配置读写统一走 `HchatStorage.preferences(hostContext, ...)`。
- 宿主 ClassLoader 加载的 AndroidX 类与模块内依赖类不是同一个类：不要用模块内的 `is androidx.recyclerview.widget.RecyclerView` 判断宿主对象；优先用反射/DexKit 或宿主方法。

## Bug 排查与测试日志

- 遇到 bug 先复现，再在测试版本加**可判定**的日志，不要只凭源码猜结论。
- 日志必须能回答：入口有没有走到、关键 ID/参数是什么、走了哪个分支、最终成功还是失败。
- 使用稳定的 `HLog.e("$TAG ...")` 前缀和唯一事件名，例如 `removeGroupNow: id=... talker=...`，方便直接 grep。
- 关键路径至少记录“开始/命中条件/实际删除或写入结果”；失败分支要带异常和关键参数。
- 测试包安装后优先查 LSPosed 日志：
  ```sh
  adb shell "su -c 'ls -1t /data/adb/lspd/log | grep modules | head -1'"
  adb exec-out su -c "grep -a -E '关键字' /data/adb/lspd/log/<modules日志> | tail -n 100"
  ```
- 先根据日志确认根因，再改代码；修复后保留一条关键成功/失败日志，便于回归。
- 正式版可以保留少量关键失败日志，详细调试日志仅用于测试版，避免长期刷屏。

## 构建与发布

- 正式包统一走 GitHub Actions：`.github/workflows/android-single.yml`（`workflow_dispatch`）。
- 工作流会自动计算下一个 `6.x.y` 版本、构建签名 APK、创建 GitHub Release，并可选推送 Telegram。
- 正式签名证书：`CN=lucky-cry, O=Hchat, C=CN`。
- 本地测试包与正式包使用同一签名，但本地测试包可能使用更高 versionCode；正式包安装到测试机前，如提示降级，需要先卸载测试版或明确允许降级。
- **打包正式版前必须得到用户明确确认；未经确认不得推送 release、不得上传正式 APK。**
- 用户流程要求：先在本地完成构建和设备验证，再推送 GitHub，最后再询问是否打包正式版。
- 用户明确要求“推送并打包”时，才执行 push + workflow dispatch + release 验证。

## 已修复问题

- 标签分组：从「未读」切到其他分类时，宿主 `ItemAnimator` 的预测动画会让 `LinearLayoutManager` 在 `onLayoutChildren` 中执行 `offsetChildrenVertical(1588)`，把 `TaskBarContainer` 从 `top=-2068` 推到 `-480`，首页顶部出现大块空白。
  - 修复：离开「未读」前临时保存并置空宿主列表 `ItemAnimator`，600ms 后恢复；避开预测布局，不修改滚动位置和 View 树。
  - 快速切换标签偶发不生效：`requestRefresh` 的旧延迟链取消无效、多个投影刷新并发，旧查询结果可能覆盖新结果。现已按切换代次只保留一条刷新链，取消旧 `refreshHostAdapters`，并把 `ConversationGroupHomeProjection.refresh` 改为串行、运行期间的新请求结束后补跑。
  - 历史排查提交：`3e827c1`、`be8f93a`、`00525e7`、`e8e096e`、`7ce2046`、`1731cdf`；定位过程提交 `b9e0f35` 至 `1f12fc3`。
- 语音转发/预览：修复微信 8.0.80 语音文件路径回退（`msg_<fileName>.amr`）、发送前重新解析语音/服务容器 API、菜单点击 Activity 回退、真实音频时长兜底；语音消息不再显示「转发到朋友圈」（朋友圈不支持语音播放）。
- 关于页「历史版本」：改为应用内弹窗读取 GitHub Releases（API 403 时回退 `releases.atom`），展示版本、日期、更新内容和下载入口，点击下载才跳转浏览器。

## 修改完成前

至少执行并汇报：

```sh
git status --short
git diff --check
git diff --stat
```

能运行测试时再运行对应测试，例如：

```sh
node scripts/run_conversation_group_parent_runtime_tests.cjs <maven缓存目录>
```

不要把“编译失败”隐藏成“功能已完成”，也不要把未经设备验证的行为写成已验证。
