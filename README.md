# Ydrop

**把语音、文字快速收进来，再用 AI 自动整理成行动信息的个人助手。**

**你负责随手丢，Ydrop 负责转写、归类、同步。**

## 它在解决什么问题

灵感、待办、语音备忘散落在不同地方，最后不是忘了就是找不到。

Ydrop 想做的是一条完整链路：从闪念采集，到 AI 转写理解，再到 NAS 多端同步，让碎片信息逐步变成可执行的信息。

## 已经能做什么

### 快速采集

- **语音记录并自动转文字**：录完后自动走 relay + 豆包转写链路，保留原始录音
- **文字快速记录**：像原生闪念一样随手记下想法
- **悬浮窗快速捕捉**：屏幕边缘常驻悬浮把手，随时展开记录文字或长按录音
- **Quick Settings Tile**：下拉通知栏一键开始录音
- **支持类型和优先级管理**：待办、任务、提醒、普通，低/中/高/紧急，一眼区分

### WebDAV 双向同步

- **手机 → NAS**：新记录自动推送到 WebDAV
- **NAS → 手机**：在 NAS 上编辑 Markdown 文件，手机端点击同步即可拉取更新
- **多端同步**：通过 `id` 匹配（非文件名），编辑标题/分类不影响同步
- **Last-Write-Wins**：按时间戳自动解决冲突
- **删除同步**：本地删除的笔记自动从 NAS 上移除（tombstone 机制）
- **可配置同步间隔**：5 / 15 / 30 / 60 分钟自动同步

### 记录管理

- **可编辑、删除、重新同步**：记录不是一次性输入，后续还能持续整理
- **语音记录编辑后保留语音属性**：编辑内容不改变记录来源
- **展开/收起全文**：长内容笔记自动检测截断，显示展开按钮

### 可读的 Markdown 文件

NAS 上的文件名包含时间、来源和标题信息，方便浏览：

```
2026-03-30_14-35_语音_语音记录_a1b2c3.md
2026-03-30_15-20_文字_关于项目计划的思考_d4e5f6.md
```

每个文件包含 YAML frontmatter（`id`、`createdAt`、`updatedAt`、`source`、`category`、`priority`）和正文。

## 技术结构

- **Android / Jetpack Compose** — UI 层
- **Room** — 本地存储，数据库版本迁移
- **WebDAV** — 双向同步（PROPFIND / GET / PUT / DELETE）
- **WorkManager** — 定期同步、转写重试
- **Foreground Service** — 悬浮窗常驻 + 录音前台服务
- **Relay 中转服务** — 临时音频上传，生成公网 URL
- **豆包 / 火山引擎** — 语音转文字

## 正在推进

- **相似记录自动归并整理** — 相同标题、相近内容自动整合
- **时间与地点线索深挖** — 提取时间、地点、人物，自动生成日程候选
- **提醒系统** — Ydrop 内提醒 + 外部推送
- **截图与链接理解** — 截图和网页链接也能进入整理链路
- **桌面小组件** — 首屏快速记录入口
- **本地 Whisper 离线转写** — 无网络时也能转写

## 仓库结构

```
app/                  # Android 客户端
  src/main/java/com/ydoc/app/
    ui/               # Compose UI
    sync/             # WebDAV 双向同步
    overlay/          # 悬浮窗服务
    recording/        # 录音服务
    relay/            # Relay 中转客户端
    transcription/    # 豆包转写
    data/             # Room + Repository + MarkdownFormatter
relay_service/        # FastAPI 临时音频中转服务
WALKTHROUGH.md        # 开发走查与里程碑
```

## 构建

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
