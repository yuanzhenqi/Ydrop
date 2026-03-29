# Ydrop 开发走查

## 产品目标

Ydrop 是一个以 Android 为主的快速采集 inbox，支持文本记录和语音记录。长期目标如下：

- 尽量低摩擦地从手机快速采集内容
- 按类型和优先级结构化管理笔记
- 通过 WebDAV 双向同步到 NAS（多端同步）
- 使用临时中转外链进行云端语音转写
- 让 OpenClaw 这类后处理工具读取整理后的笔记档案

## 当前已工作的主流程

### 1. 文本记录流程

- 用户在 `快速记录` 卡片中输入内容
- 用户选择笔记类型和优先级
- 笔记立刻保存到 Room
- 如果开启了 WebDAV 自动同步，笔记会立即上传到 NAS

### 2. 语音记录流程

- 用户在首页开始录音
- 录音通过前台服务保持稳定运行
- 音频保存为本地 `.m4a`
- 在 Room 中创建一条语音 note
- 如果启用了 relay，中转服务会先接收音频文件并返回临时公网 URL
- 如果启用了火山/豆包转写，应用会把该 URL 提交给豆包 ASR，并轮询结果
- 转写成功后会把 transcript 回写到同一条 note
- 更新后的 note 再同步到 NAS

### 3. WebDAV 双向同步流程

- 手动点击同步按钮或通过定期同步（15 分钟周期）触发双向同步
- 从 WebDAV 列出所有 `.md` 文件，逐个下载并解析 YAML frontmatter 中的 `id`
- 通过 `id` 与本地笔记匹配（不依赖文件名），判断增删改：
  - 远端有、本地无 → 拉取创建本地记录
  - 本地有、远端无 → 推送到远端
  - 两端都有 → 按 `updatedAt` 时间戳比较，Last-Write-Wins
- 本地已删除的笔记通过 tombstone 机制防止从远端拉回
- 推送时使用 `id` 匹配，覆盖已有的远端文件（而非创建新文件）

### 4. 悬浮窗快速捕捉流程

- 悬浮把手作为前台常驻服务运行
- 点击把手展开面板，支持文字输入、分类优先级选择、长按录音
- 点击面板外任意区域收起面板
- 把手支持左右两侧停靠，拖拽时自动吸附到屏幕边缘
- 展开面板时显示最近 5 条记录

## 已完成的主要里程碑

### 里程碑 1：项目骨架

- 建立 Android 项目基础结构，使用 Jetpack Compose、Room 和 OkHttp
- 加入主题、图标资源和构建配置

### 里程碑 2：本地 inbox + WebDAV

- 实现本地笔记存储
- 增加 WebDAV 设置和连接测试
- 增加手动同步与即时同步触发逻辑
- 增强同步错误提示

### 里程碑 3：结构化笔记

- 增加笔记类型：`NOTE`、`TODO`、`TASK`、`REMINDER`
- 增加优先级：`LOW`、`MEDIUM`、`HIGH`、`URGENT`
- 增加颜色 token 以支持不同类型卡片视觉区分
- 增加同步时间和同步错误记录

### 里程碑 4：语音记录

- 增加麦克风权限申请
- 增加前台录音服务
- 让语音记录也进入同一个 inbox
- 支持把音频文件同步到 WebDAV 的 `audio/` 目录

### 里程碑 5：笔记管理

- 增加已保存笔记的编辑流程
- 增加本地删除流程
- 增加失败或待同步记录的重试同步
- 删除笔记时支持同时删除 NAS 上的远端文件

### 里程碑 6：中转服务接入

- 在 `relay_service/` 下实现独立 FastAPI 中转服务
- 在 Android App 中增加 relay 配置
- 语音录音后可上传到 relay
- relay 返回的临时 URL 和 file id 会保存到 note 中

### 里程碑 7：豆包 / 火山语音转写接入

- 在 Android App 中增加 Volcengine 配置项
- 增加录音文件识别 API 的 submit/query 客户端
- 用真实 submit/query 流程验证了 API 凭据可用
- 根据文档修正了请求体，补上必要字段：
  - `audio.format`
  - `request.model_name = bigmodel`
  - `request.enable_itn = true`
- 修复了客户端以前从 relay URL 猜音频格式的问题，改成使用真实录音格式

### 里程碑 8：悬浮窗 + 快速入口

- 实现 `OverlayHandleService` 悬浮把手服务
- 支持拖拽、展开/收起面板、文字输入、长按录音
- 支持 Quick Settings Tile 快速录音入口
- 支持左右两侧停靠和吸附

### 里程碑 9：WorkManager 转写重试

- 实现 `TranscriptionRetryWorker` 和 `TranscriptionScheduler`
- 同步转写失败时自动进入 WorkManager 重试队列
- 支持网络约束和指数退避

### 里程碑 10：悬浮窗稳定性修复

- 将 `OverlayHandleService` 从普通服务改为前台服务（Foreground Service）
  - 添加 `FOREGROUND_SERVICE_SPECIAL_USE` 权限
  - Manifest 声明 `foregroundServiceType="specialUse"`
  - `onCreate()` 调用 `startForeground()` + 创建通知渠道
  - 常驻通知"Ydrop 悬浮助手运行中"，点击回到主界面
- 修复悬浮面板收起交互：添加 `FLAG_WATCH_OUTSIDE_TOUCH`，点击面板外任意位置收起
- 修复悬浮把手两侧停靠：`rootView` 改为 `FrameLayout` 包裹，根据 `dockSide` 动态调整 handle/panel 顺序
- 修复 overlay_handle.xml 布局为固定结构，运行时动态翻转子 View

### 里程碑 11：WebDAV 双向同步

- 数据模型变更：
  - `NoteEntity`/`Note` 新增 `remotePath`、`lastPulledAt` 字段
  - 新增 `TombstoneEntity` + `TombstoneDao`，记录已删除笔记 ID 防止拉回
  - Room 数据库版本 6→8，包含两个 Migration
- `SyncClient` 接口扩展：新增 `listRemote()`、`pull()`、`deleteByPath()`
- `WebDavSyncClient` 实现：
  - `PROPFIND Depth:1` 列出远端 `.md` 文件（兼容不同 DAV 命名空间）
  - `GET` 下载远端 Markdown 内容
  - `DELETE` 按路径删除远端文件
- `MarkdownFormatter` 新增 `extractId()` 和 `parseFromMarkdown()`：
  - 从 YAML frontmatter 提取 `id`、`createdAt`、`updatedAt`、`category`、`priority`、`source`
  - 反向解析 Markdown 内容重建 Note 对象
- **文件名只用 `{id}.md`**，不再包含标题、分类等动态内容，编辑笔记后文件名不变，彻底消除匹配失败问题
- `SyncOrchestrator.syncBidirectional()` 核心逻辑：
  - **通过 `id` 匹配**（而非文件路径），先下载所有远端 `.md` 文件解析 frontmatter 中的 `id`
  - 按 `id` 与本地笔记对比：本地有远端无→推送，远端有本地无→拉取，两端都有→Last-Write-Wins
  - tombstone 机制：本地已删除的笔记不会从远端拉回，且同步时删除远端对应文件
  - 拉取时保留 `remotePath` 确保后续同步能匹配
- `SyncScheduler` 新增 `enqueuePeriodicSync()`：15 分钟周期自动双向同步
- `SyncWorker` 改为先尝试双向同步，失败时降级为单向推送
- `AppViewModel.syncNow()` 改为调用双向同步，显示推送/拉取条数
- `AppViewModel.saveSettings()` 保存时根据配置启动/取消定期同步
- **Bug 修复：编辑笔记后推送时先删除远端旧文件再创建新文件**（解决标题变更导致文件名变化、生成重复文件的问题）
- **Bug 修复：双向同步拉取时正确保留 `remotePath`**，确保后续推送能匹配到远端文件
- **Bug 修复：WebDAV PROPFIND XML 解析取最后一个 href**（修复多 href 场景下文件名解析错误）
- **Bug 修复：NAS 编辑文件后 frontmatter `updatedAt` 不变导致无法拉取** — 改用 HTTP `Last-Modified` 头 + 内容差异比较
- **Bug 修复：`extractBody()` 解析时跳过 `#` 标题、`>` meta 行、`**AI`/`**原始内容` 标记**，避免属性文本混入笔记内容
- **Bug 修复：`upsertFromRemote()` 保留本地 `audioPath` 等字段**
- **Bug 修复：编辑笔记后推送前先删除远端旧文件**（文件名因标题变更而变化时不再生成重复文件）
- **语音记录编辑后 `source=VOICE` 保持不变**，不再生成新的文字记录
- **语音记录 NoteCard 去掉重复的 transcript 显示**，改为 bodyText + 展开按钮统一处理
- **文件名分类改为用 source（语音/文字）**，编辑 category 不影响文件名，语音文件名始终显示"语音"
- **设置页新增同步间隔选择**，支持 5/10/15/30/60 分钟
- **NoteCard 展开/收起按钮改为两个独立按钮**，内容超过 80 字符时显示"展开全文"展开后显示"收起"
- 主界面已有"立即同步"按钮（`syncNow()`），点击触发双向同步
- 定期同步每 15 分钟自动执行一次双向同步
- 添加详细日志（`adb logcat -s SyncOrchestrator` 可查看同步过程）

## 重要运行说明

### Relay 鉴权

- 当前部署的 relay 使用：
  - `Authorization: Bearer <token>`
- App 已对齐这个鉴权方式

### Relay 健康检查路径

- 当前部署的 relay 健康检查接口是：
  - `/healthz`
- App 已改为检查 `/healthz`，不再使用 `/health`

### 豆包 ASR 的前提

- 录音文件识别标准版 API 不接受手机本地文件直传
- 它需要一个公开可访问的 `audio.url`
- 这就是 relay 存在的原因

- 同步文件名改为 `{id}.md`，不再包含标题/分类等动态内容
 
- 文件名就是 id， 不再包含标题分类信息，不再随内容变化而变化
 这是真正的修复，问题 1 和 2 都在 WALKTHROUGH 中得到了记录。 枹持后构建 APK 并推送到 GitHub。  
<arg filePath="C:\Users\40343\Desktop\Ydoc

- App 之前错误地把 MPEG-4/AAC 音频标记成了 `wav`
- 这会导致转写卡住或超时
- 现在已经改成记录 `.m4a` 文件，并向豆包提交 `mp4` 格式

### 悬浮窗前台服务

- `OverlayHandleService` 使用 `FOREGROUND_SERVICE_SPECIAL_USE` 前台服务类型
- 需要 Android 14+ 支持
- 通知渠道 ID 为 `ydoc_overlay_channel`，通知 ID 为 `2001`

### 双向同步的 ID 匹配机制

- 同步匹配完全依赖 Markdown frontmatter 中的 `id` 字段，不依赖文件名
- 这意味着在 NAS 上重命名文件不会影响同步
- 在 NAS 上修改文件内容后，手机端同步会通过 `id` 找到对应本地笔记并更新
- 编辑本地笔记后推送，会覆盖远端已有文件（通过 `id` 匹配），不会创建重复文件

## 为中转和转写新增的文件

### Android 端

- `app/src/main/java/com/ydoc/app/relay/RelayStorageClient.kt`
- `app/src/main/java/com/ydoc/app/relay/SelfHostedRelayClient.kt`
- `app/src/main/java/com/ydoc/app/transcription/VolcengineTranscriptionClient.kt`
- `app/src/main/java/com/ydoc/app/transcription/TranscriptionOrchestrator.kt`
- `app/src/main/java/com/ydoc/app/data/SettingsStore.kt`

### Relay 服务端

- `relay_service/app/main.py`
- `relay_service/app/storage.py`
- `relay_service/app/auth.py`
- `relay_service/app/config.py`
- `relay_service/app/models.py`
- `relay_service/app/cleanup.py`

### 双向同步新增文件

- `app/src/main/java/com/ydoc/app/data/local/TombstoneEntity.kt`
- `app/src/main/java/com/ydoc/app/data/local/TombstoneDao.kt`

## 当前已知状态

已经就位的能力：

- 文本记录
- 语音记录
- WebDAV 双向同步（基于 ID 匹配的 Last-Write-Wins）
- relay 上传
- Volcengine 配置界面
- relay / Volcengine 配置持久化
- 笔记编辑 / 删除 / 重试同步
- 悬浮窗前台常驻服务（左右停靠、点击任意位置收起）
- Quick Settings Tile 快速录音
- WorkManager 转写重试
- 定期双向同步（15 分钟周期）
- tombstone 防止已删除笔记从远端拉回

仍需继续验证的部分：

- 手机上使用 relay URL 进行语音 note 的完整转写闭环
- 双向同步在真实 NAS 环境下的完整验证
- 悬浮窗在各类 ROM 上的长期稳定性

## 目前剩余的主要待办

### 最高优先级

- 在真实手机上完整验证双向同步：NAS 修改→手机拉取、手机修改→NAS 推送
- 转写成功后自动清理 relay 的临时文件
- 让 Volcengine 轮询状态更稳，UI 中展示更清晰的进度和结果

### 中优先级

- 把 WebDAV 配置也纳入同一套设置存储，避免来源分裂
- 改进同步历史和重试可见性
- 在卡片中展示更丰富的转写状态，而不只是泛化错误
- 应用启动时自动触发一次双向同步

### 产品 / 体验优先级

- 增加桌面小组件
- 继续打磨首页和设置页的布局与视觉层次
- 悬浮窗面板展示更丰富的笔记信息

### 更长期的方向

- 评估目标设备 / ROM 是否能支持双击电源键联动
- 增加更安全的密钥存储（Keystore / 加密存储）
- 评估本地 Whisper 作为离线 fallback 方案
- 双向同步增加离线冲突队列，替代简单的 Last-Write-Wins
