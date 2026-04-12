# Ydrop

Ydrop 是一个以 Android 为主的个人收件箱工具，用来把文字、语音、悬浮窗快记、快捷启动录音统一收进同一个 inbox，再通过 AI、提醒和 WebDAV 同步把碎片信息整理成可执行内容。

## 现在已经能做什么

### 快速记录
- 文本快速记录，支持 `Note / Todo / Task / Reminder` 和优先级。
- 主界面折叠式“快速记录”输入卡。
- 悬浮窗侧边轨输入卡，支持点击输入、长按录音。
- Quick Settings Tile 快速录音。
- 桌面快捷录音和动态 App Shortcut。
- 专用直录入口 `QuickRecordEntryActivity`，方便系统或厂商快捷启动绑定。

### 语音记录
- 前台服务稳定录音。
- 录音文件先保存到 App 私有目录。
- 同时导出一份到系统可见位置，便于在手机文件系统中查找。
- 主界面和悬浮窗都支持播放本地音频。
- 主界面语音卡支持单卡进度条和拖动 seek。

### AI 整理
- 文本保存后可自动整理。
- 语音转写完成后可自动整理。
- AI 当前输出为“建议”，不会直接改写原 note。
- 支持输出：
  - 摘要
  - 标题建议
  - 分类建议
  - 优先级建议
  - 待办提取
  - 信息提取
  - 提醒候选时间
- 支持 `AUTO / RELAY / OPENAI / ANTHROPIC` 四种 AI 接入模式。

### 提醒与日历
- 本地提醒模型 `ReminderEntry`。
- Agenda 视图查看提醒。
- AlarmManager 本机通知提醒。
- 开机后自动重挂未到期提醒。
- 一键导出到系统闹钟。
- 可以从 note 卡快捷创建提醒，也可以从 AI 建议里创建提醒。

### WebDAV 双向同步
- 本地到 NAS 自动同步。
- NAS 到本地手动/定时双向拉取。
- 通过 note `id` 匹配，而不是文件名。
- 活跃笔记同步到 `inbox/`。
- 归档笔记同步到 `archive/`。
- 回收站语义是“本地可恢复，远端删除”。
- 音频附件固定在 `inbox/audio/`。

### 记录管理
- 收件箱 / 归档 / 回收站三分区。
- 主界面 note 卡支持编辑、归档、删除、恢复、彻底删除、重试同步。
- 悬浮窗卡片支持类型色、快捷动作、左右滑归档/回收站。
- 主界面语音标题在 UI 层隐藏底层 id，优先显示转写首句。

## AI 配置说明

Ydrop 现在有两种接法：

### 1. Relay 模式
适合你自己部署了 Ydrop 的 relay 服务。

- `AI Base URL` 填 relay 地址
- App 会请求：
  - `/healthz`
  - `/ai/analyze-note`

### 2. 模型网关模式
适合直接填写兼容 OpenAI / Anthropic 的模型网关。

- `AI Base URL` 填网关根地址，例如 `https://example.com`
- `AI Token` 填 API Key
- `模型名称` 填具体模型，例如 `glm-5.1`
- `协议模式` 可选：
  - `AUTO`
  - `OPENAI`
  - `ANTHROPIC`

说明：
- `AUTO` 会优先按 provider 探测，失败时再回退到 relay 语义。
- OpenAI 模式会请求 `/v1/chat/completions`。
- Anthropic 模式会请求 `/v1/messages`。
- 已兼容模型把 JSON 包在 ```json 代码块``` 里的返回。

## 技术栈

- Android / Kotlin / Jetpack Compose
- Room
- DataStore
- WorkManager
- OkHttp
- WebDAV
- FastAPI relay service

## 仓库结构

```text
app/
  src/main/java/com/ydoc/app/
    ai/               # AI 客户端与编排
    data/             # Repository、Settings、Markdown
    model/            # Note / Reminder / AI / Playback 模型
    overlay/          # 悬浮窗服务与侧边轨
    quickrecord/      # 专用直录入口与快捷方式
    quicktile/        # Quick Settings Tile
    recording/        # 录音、导出、播放
    reminder/         # 提醒调度、通知、重挂
    relay/            # Relay 上传客户端
    sync/             # WebDAV 双向同步
    transcription/    # 转写链路与重试
    ui/               # Compose UI
relay_service/
  app/
    ai.py             # AI 分析入口与 provider 兼容
    main.py           # FastAPI 入口
WALKTHROUGH.md        # 开发走查与迭代记录
```

## 构建

```bash
./gradlew assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 当前最值得继续做的方向

- AI 第二阶段：基于 note 的问答与批量整理。
- 提醒第二阶段：自定义时间选择、重复规则、外发通知。
- 快捷启动第二阶段：更多系统级绑定指引和机型适配。
- 真机回归脚本与更完整的自动化验证。
