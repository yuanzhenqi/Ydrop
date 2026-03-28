# Ydrop 开发走查

## 产品目标

Ydrop 是一个以 Android 为主的快速采集 inbox，支持文本记录和语音记录。长期目标如下：

- 尽量低摩擦地从手机快速采集内容
- 按类型和优先级结构化管理笔记
- 通过 WebDAV 同步到 NAS
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

### 已修复的音频格式问题

- App 之前错误地把 MPEG-4/AAC 音频标记成了 `wav`
- 这会导致转写卡住或超时
- 现在已经改成记录 `.m4a` 文件，并向豆包提交 `mp4` 格式

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

## 当前已知状态

已经就位的能力：

- 文本记录
- 语音记录
- WebDAV 同步
- relay 上传
- Volcengine 配置界面
- relay / Volcengine 配置持久化
- 笔记编辑 / 删除 / 重试同步

仍需继续验证的部分：

- 手机上使用 relay URL 进行语音 note 的完整转写闭环
- 轮询完成后 transcript 在 UI 中的最终呈现

## 目前剩余的主要待办

### 最高优先级

- 用最新版本在手机上完整验证：relay -> 豆包 -> transcript -> NAS
- 转写成功后自动清理 relay 的临时文件
- 让 Volcengine 轮询状态更稳，UI 中展示更清晰的进度和结果

### 中优先级

- 把 WebDAV 配置也纳入同一套设置存储，避免来源分裂
- 改进同步历史和重试可见性
- 增加基于 WorkManager 的转写重试，而不是只做同步重试
- 在卡片中展示更丰富的转写状态，而不只是泛化错误

### 产品 / 体验优先级

- 增加 Quick Settings Tile
- 增加桌面小组件
- 增加悬浮侧边把手 / 边缘入口
- 继续打磨首页和设置页的布局与视觉层次

### 更长期的方向

- 评估目标设备 / ROM 是否能支持双击电源键联动
- 增加更安全的密钥存储（Keystore / 加密存储）
- 评估本地 Whisper 作为离线 fallback 方案
