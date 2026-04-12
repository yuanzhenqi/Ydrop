# CLAUDE.md

## 项目简介
**Ydrop** 是一个 Android 端的个人快速采集 inbox，定位是「把脑子里冒出的碎片一秒钟丢进来」。
它把文字、语音、悬浮窗速记、桌面/磁贴快捷录音都汇进同一个 inbox，再通过 AI 整理、提醒、WebDAV 同步把碎片变成可执行内容。核心场景是便签 + 日历 + AI 随手记。

## 技术栈
- **语言**：Kotlin
- **UI**：Jetpack Compose + Material3（Compose BOM 2024.09.00）
- **构建**：AGP 8.5.2，Kotlin 2.0.21，KSP 2.0.21-1.0.27
- **SDK**：`minSdk 26`，`targetSdk 35`，`compileSdk 35`，JVM 17
- **包名 / applicationId**：`com.ydoc.app`
- **本地存储**：Room 2.6.1 + DataStore Preferences 1.1.1
- **异步**：Kotlinx Coroutines 1.8.1 + Flow
- **后台任务**：WorkManager 2.9.1
- **网络**：OkHttp 4.12.0 + kotlinx.serialization
- **导航**：Navigation Compose 2.8.0
- **后端**：`relay_service/`（FastAPI，用于 AI 分析和转写中转）
- **同步**：自研 WebDAV 双向同步

## 仓库结构
```text
app/src/main/java/com/ydoc/app/
  MainActivity.kt
  YDocApplication.kt
  ai/            # AI provider / relay 接入、建议生成编排
  config/        # 运行时配置
  data/          # Repository、Settings、Markdown 序列化
  logging/       # 日志工具
  model/         # Note / Reminder / AI / Playback 等数据模型
  overlay/       # 悬浮窗服务、侧边轨、输入/编辑卡
  quickrecord/   # QuickRecordEntryActivity 专用直录入口、动态 App Shortcut
  quicktile/     # Quick Settings Tile 快速录音
  recording/     # 前台录音服务、本地导出、播放
  reminder/      # AlarmManager 调度、到点通知、开机重挂
  relay/         # Relay 上传客户端
  sync/          # WebDAV 双向同步、定时同步
  transcription/ # 转写链路与重试
  ui/            # Compose 主界面、设置页、日历/agenda 视图

relay_service/app/
  main.py        # FastAPI 入口
  ai.py          # AI 分析入口与 provider 兼容层

README.md        # 功能与 AI 配置说明
WALKTHROUGH.md   # 开发走查与迭代记录（改动前先读它）
```

## 当前已经实现的能力
- **快速记录**：主界面折叠快速记录卡、悬浮窗侧边轨、Quick Settings Tile、桌面快捷、`QuickRecordEntryActivity` 直录入口
- **类型与优先级**：`Note / Todo / Task / Reminder` + 优先级
- **语音**：前台服务稳定录音，App 私有目录保存，同时导出一份到系统可见位置，主界面 + 悬浮窗都能播放，单卡进度条 + seek
- **AI 整理**：文本保存后 / 语音转写完成后自动整理，输出「建议」不直接改写原 note；支持摘要、标题、分类、优先级、待办提取、信息提取、提醒候选时间
- **AI 接入**：`AUTO / RELAY / OPENAI / ANTHROPIC` 四种模式，AUTO 优先探测 provider 再回退 relay，兼容 ```json 包裹返回
- **提醒 & 日历**：`ReminderEntry` 本地模型、Agenda 视图、AlarmManager 通知、开机重挂、一键导出到系统闹钟，可从 note 卡或 AI 建议创建
- **WebDAV 同步**：本地→NAS 自动 + 双向拉取，按 note id 匹配；活跃笔记 `inbox/`、归档 `archive/`、音频 `inbox/audio/`；回收站语义为本地可恢复 + 远端删除
- **记录管理**：收件箱 / 归档 / 回收站三分区，编辑、归档、删除、恢复、彻底删除、重试同步

## 常用命令
```bash
# 构建 Debug APK
./gradlew assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk

# 单元测试
./gradlew test

# Instrumented 测试（需连接设备或模拟器）
./gradlew connectedAndroidTest

# Lint
./gradlew lint

# 安装到已连接设备
./gradlew installDebug
```

## 代码规范
- 遵循 Kotlin 官方风格，Compose 组件用 PascalCase
- 按功能模块分包（见「仓库结构」），新功能优先进已有模块而不是散在 `ui/`
- 所有用户可见文案放 `strings.xml`，避免硬编码
- 数据模型放 `model/`，持久化相关放 `data/`，跨模块逻辑通过 Repository 暴露
- 日志统一走 `logging/`，不要直接 `Log.d`
- 注释以中文为主，公开 API 用 KDoc

## 隐私与安全
- 便签、语音、日历都属于用户敏感数据：禁止写进日志样例、测试 fixture、commit message
- AI Token / Base URL 只能从设置或 `local.properties` 读取，**严禁硬编码或 commit**
- 调用 AI 前注意脱敏，relay 和模型网关两条链路都要考虑
- WebDAV 账号信息同上

## 禁止直接修改 / 谨慎修改
- `local.properties`、任何 keystore、签名配置
- `build/`、`.gradle/`、`.idea/`、`.kotlin/` 等生成目录
- 已发布的 **Room schema / 迁移**：只能新增 Migration，不要改旧的
- `relay_service/` 的线上接口契约（`/healthz`、`/ai/analyze-note`、`/v1/chat/completions`、`/v1/messages`）——改了要同时改 app 端
- `WALKTHROUGH.md`：这是开发走查记录，改动前先读，改动后记得追加新的一轮迭代

## 工作流偏好
- 改动前先读 `WALKTHROUGH.md` 最近几轮记录，了解最新迭代背景
- 小改动直接动手；只有在涉及 Room 迁移、签名配置、relay 接口契约，或改动超过 5 个文件时，才先简述计划
- 无论大小改动都不需要等我点头确认，简述完直接执行
- 写完代码自动跑 `./gradlew lint` 和相关 `./gradlew test`
- 悬浮窗 / 前台服务 / 权限相关改动要特别小心，改完提醒我手动回归
- commit message 用中文，短句描述 + 模块前缀，例如 `ai: 支持 Anthropic 协议探测`

## 推荐的 Skill / MCP 组合

### 项目自定义 skill（已落地在 `.claude/skills/`）
- `ydrop-walkthrough`：基于 git diff 自动追加一轮 WALKTHROUGH 迭代记录
- `ydrop-regression`：按本轮改动模块生成真机手动回归清单
- `ydrop-ai-smoke`：对 AUTO / RELAY / OPENAI / ANTHROPIC 四种 AI 模式做冒烟
- `ydrop-release`：跑完整发布流程（lint → test → 版本号 → 构建 → 文档 → tag）

### 建议接入的 MCP
- **Android Emulator / ADB MCP**：`adb install` / `logcat` / 截图 / 启停模拟器
- **Gradle MCP**：直接跑 `assembleDebug` / `lint` / `test` 并解析失败
- **GitHub MCP**（如果托管在 GitHub）：PR、issue、CI 状态
- **HTTP / REST 调试 MCP**：调 `relay_service/` 的 `/healthz`、`/ai/analyze-note`、`/v1/*` 接口
- **SQLite / Room Inspector**：调试本地 DB 和 Room 迁移

### 暂不需要
- Chrome MCP 系列（已明确用 `/browse`）
- Office 全家桶（除非写产品文档 / 隐私政策）

## 当前最值得继续做的方向
- AI 第二阶段：基于 note 的问答与批量整理
- 提醒第二阶段：自定义时间选择、重复规则、外发通知
- 快捷启动第二阶段：更多系统级绑定指引和机型适配
- 真机回归脚本与更完整的自动化验证

## gstack
Use /browse from gstack for all web browsing. Never use mcp__claude-in-chrome__* tools.
Available skills: /office-hours, /plan-ceo-review, /plan-eng-review, /plan-design-review,
/design-consultation, /design-shotgun, /design-html, /review, /ship, /land-and-deploy,
/canary, /benchmark, /browse, /connect-chrome, /qa, /qa-only, /design-review,
/setup-browser-cookies, /setup-deploy, /retro, /investigate, /document-release, /codex,
/cso, /autoplan, /careful, /freeze, /guard, /unfreeze, /gstack-upgrade, /learn.
