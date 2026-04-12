# Ydrop — AI 闪念胶囊

> 把脑子里冒出的碎片一秒钟丢进来，AI 帮你整理成待办、提醒和笔记。

Ydrop 是一个 Android 端的个人快速采集 inbox，集 **随手记 · AI 整理 · 待办提取 · 语音转写 · 提醒日历 · WebDAV 同步** 于一体。文字、语音、悬浮窗速记、桌面快捷录音——所有入口汇进同一个收件箱，再由 AI 把碎片变成可执行内容。

**关键词**：`AI 整理` · `待办提取` · `随手记` · `闪念胶囊` · `语音转写` · `悬浮窗速记` · `提醒日历` · `WebDAV 同步`

---

## 核心功能

### 闪念速记
- 主界面底部常驻快速记录栏，点击即写、长按即录
- 悬浮窗侧边轨：任意 App 上方一键呼出，支持文字输入和长按录音
- Quick Settings Tile / 桌面快捷方式 / 动态 App Shortcut / 专用直录入口，多种系统级快捷启动
- 支持 `Note / Todo / Task / Reminder` 四种类型和优先级标记
- 标签系统：自定义 tag，筛选、同步全打通

### AI 智能整理
- 文本保存后 / 语音转写完成后自动触发 AI 整理
- AI 输出为"建议"，不直接改写原始内容，用户一键采纳或忽略
- 支持能力：
  - 摘要生成、标题建议
  - 智能分类、优先级建议
  - **待办提取**（从自然语言中抽取 todo）
  - **信息实体提取**（人名、地点、时间等）
  - **提醒候选时间**（支持中文时间表达："明天上午 10 点"、"下周三"）
- 四种接入模式：`AUTO / RELAY / OPENAI / ANTHROPIC`，兼容主流模型网关

### 语音记录与转写
- 前台服务稳定录音，App 私有目录保存 + 系统可见目录导出
- 主界面 & 悬浮窗均可播放，单卡进度条 + 拖动 seek
- 录音完成自动上传转写，转写完成自动触发 AI 整理

### 提醒与日历
- 本地提醒模型，AlarmManager 驱动的通知提醒
- 日历 Agenda 视图，按日查看提醒与关联笔记
- 开机自动重挂未到期提醒
- 一键导出到系统闹钟
- 从笔记卡片快捷创建提醒，或从 AI 建议一键生成

### WebDAV 双向同步
- 本地 → NAS 自动同步，NAS → 本地定时双向拉取
- 按 note id 匹配，活跃笔记 `inbox/`、归档 `archive/`、音频 `inbox/audio/`
- Markdown 格式存储，frontmatter 保留分类、优先级、标签等元数据
- 回收站语义：本地可恢复，远端删除

### 记录管理
- 收件箱 / 归档 / 回收站三分区
- 卡片展开后直接展示操作按钮：AI 整理、复制、编辑、归档、删除
- 左右滑动卡片快速归档 / 删除（与悬浮窗操作一致）
- 悬浮窗卡片同样支持类型色、快捷动作、滑动归档/回收站

---

## 技术栈

| 层面 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 本地存储 | Room + DataStore |
| 异步 | Coroutines + Flow |
| 后台任务 | WorkManager |
| 网络 | OkHttp + kotlinx.serialization |
| 同步 | 自研 WebDAV 双向同步 |
| 后端 | FastAPI relay service（AI 分析 & 转写中转） |
| SDK | minSdk 26 · targetSdk 35 · JVM 17 |

---

## 仓库结构

```text
app/src/main/java/com/ydoc/app/
  ai/               # AI provider / relay 接入、建议生成编排
  config/           # 运行时配置
  data/             # Repository、Settings、Markdown 序列化
  logging/          # 日志工具
  model/            # Note / Reminder / AI / Playback 数据模型
  overlay/          # 悬浮窗服务、侧边轨、输入/编辑卡
  quickrecord/      # 专用直录入口、动态 App Shortcut
  quicktile/        # Quick Settings Tile 快速录音
  recording/        # 前台录音服务、本地导出、播放
  reminder/         # AlarmManager 调度、到点通知、开机重挂
  relay/            # Relay 上传客户端
  sync/             # WebDAV 双向同步、定时同步
  transcription/    # 转写链路与重试
  ui/               # Compose 主界面、设置页、日历视图

relay_service/app/
  main.py           # FastAPI 入口
  ai.py             # AI 分析入口与 provider 兼容层
```

---

## AI 配置说明

### Relay 模式
适合自部署 Ydrop relay 服务：
- `AI Base URL` 填 relay 地址
- App 调用 `/healthz` 和 `/ai/analyze-note`

### 模型网关模式
适合直连 OpenAI / Anthropic 兼容网关：
- `AI Base URL` 填网关根地址
- `AI Token` 填 API Key
- `模型名称` 填具体模型（如 `gpt-4o`、`claude-sonnet-4-5-20250514`、`glm-5.1`）
- 协议模式可选 `AUTO / OPENAI / ANTHROPIC`
  - AUTO 优先探测 provider，失败回退 relay
  - OpenAI → `/v1/chat/completions`
  - Anthropic → `/v1/messages`
- 已兼容模型将 JSON 包在 ` ```json ``` ` 代码块里返回的情况

---

## 构建

```bash
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

---

## 接下来的方向

- AI 第二阶段：基于 note 的问答与批量整理
- 提醒第二阶段：自定义时间选择、重复规则、外发通知
- 快捷启动第二阶段：更多系统级绑定指引和机型适配
- 自动化回归测试

---

## License

Private project.
