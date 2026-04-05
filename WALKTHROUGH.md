# Ydrop 开发走查

这份文档面向继续维护这个项目的人，记录当前产品结构、核心模块、数据流和最近几轮已经落地的改动。

## 产品定位

Ydrop 是一个 Android 端的快速采集 inbox：

- 入口足够低摩擦：主界面、悬浮窗、快捷磁贴、桌面快捷录音、专用直录入口。
- 内容进入同一个 inbox：文本、语音、AI 建议、提醒。
- 最终通过 WebDAV 双向同步到 NAS。

## 当前整体架构

### App 内主要模块

- `ui/`
  - Compose 主界面
  - 设置页
  - 日历 / agenda 视图
- `overlay/`
  - 悬浮窗侧边轨
  - 输入卡、编辑卡、最近卡片
- `recording/`
  - 录音
  - 本地导出
  - 本地播放
- `transcription/`
  - relay + 转写编排
  - 转写重试
- `sync/`
  - WebDAV 双向同步
  - 定时同步
- `ai/`
  - AI provider / relay 接入
  - 建议生成
- `reminder/`
  - AlarmManager 调度
  - 到点通知
  - 开机重挂
- `quickrecord/`
  - 专用直录入口
  - 动态快捷方式 / 桌面快捷方式

### 服务端

- `relay_service/`
  - 临时音频上传
  - 健康检查
  - AI 分析接口 `/ai/analyze-note`

## 核心数据模型

### Note

当前 `Note` 仍然是产品核心对象，承担：

- 文本记录
- 语音记录
- 分类
- 优先级
- 归档 / 回收站状态
- 转写状态
- 远端同步状态
- 本地音频路径

重要说明：
- AI 建议不直接写进 note。
- 提醒也不直接挂在 note 表里。

### AiSuggestion

AI 第一阶段是非破坏式建议流，单独存储：

- `summary`
- `suggestedTitle`
- `suggestedCategory`
- `suggestedPriority`
- `todoItems`
- `extractedEntities`
- `reminderCandidates`
- `status`

状态包含：
- `RUNNING`
- `READY`
- `FAILED`
- `APPLIED`
- `DISMISSED`

### ReminderEntry

提醒单独建模，不和 note 结构硬绑定：

- `noteId`
- `title`
- `scheduledAt`
- `source`
- `status`
- `deliveryTargets`

当前第一阶段只支持单次提醒，不支持重复规则。

## 关键链路

### 1. 文本记录

1. 用户在主界面快速记录卡或悬浮窗输入卡输入内容
2. 保存到 Room
3. 如开启 WebDAV 自动同步，则立即推送
4. 如开启 AI 自动整理，则进入 AI 建议流程

### 2. 语音记录

1. 主界面或悬浮窗开始录音
2. 录音文件保存到 App 私有目录
3. 同时导出一份系统可见副本
4. 创建 voice note
5. 如启用 relay + 转写，则进入上传和转写链路
6. 转写结果回写到 note
7. 如开启 WebDAV 自动同步，则推送到 NAS
8. 如开启 AI 自动整理，则在转写后生成建议

### 3. AI 整理

1. App 构造 `AiAnalyzeRequest`
2. 根据 AI 模式走不同协议：
   - `RELAY`
   - `OPENAI`
   - `ANTHROPIC`
   - `AUTO`
3. 返回结构化 `AiAnalyzeResponse`
4. 保存到本地 `AiSuggestion`
5. UI 展示建议区
6. 用户决定应用或忽略

### 4. 提醒

1. 用户手动创建，或从 AI 候选时间创建
2. 落本地 `ReminderEntry`
3. `ReminderScheduler` 调用 AlarmManager
4. 到点后 `ReminderReceiver` 发本机通知
5. 也可以一键导出到系统闹钟

### 5. WebDAV 双向同步

1. `SyncOrchestrator` 统一调度
2. `WebDavSyncClient` 扫描：
   - `inbox/`
   - `archive/`
3. 同步匹配依赖 frontmatter 的 `id`
4. 规则：
   - 活跃 note -> `inbox/`
   - 归档 note -> `archive/`
   - 回收站 note -> 远端删除
5. tombstone 防止被远端重新拉回

## AI 配置现状

### 当前支持的模式

- `AUTO`
- `RELAY`
- `OPENAI`
- `ANTHROPIC`

### 使用语义

- `RELAY`
  - Base URL 指向 Ydrop relay
  - 使用 `/healthz` 和 `/ai/analyze-note`

- `OPENAI`
  - Base URL 指向模型网关根地址
  - 使用 `/v1/chat/completions`

- `ANTHROPIC`
  - Base URL 指向模型网关根地址
  - 使用 `/v1/messages`

- `AUTO`
  - 优先尝试把当前地址识别为 provider
  - 识别失败时回退为 relay

### 为什么最近要补这一层

因为实践里用户会直接把模型网关地址填到 App 设置里，而原先 App 只支持 relay 语义：

- 测试连接时请求 `/healthz`
- 真正整理时请求 `/ai/analyze-note`

如果用户填的是网关地址，就会拿到 `200 + HTML`，被误判成“连接成功”，整理时再在 JSON 解析阶段炸掉。

现在已经修成：

- 测试连接不再只看 HTTP 200
- 会判断是不是网页而不是 JSON 接口
- OpenAI 和 Anthropic 两种返回都能解析
- 对把 JSON 包在 ```json fenced block``` 里的返回做了兼容

## 悬浮窗现状

### 当前结构

- 单窗口 overlay root
- 折叠态只显示把手
- 展开态为侧边滚动轨
- 第一张是输入卡
- 后面是最近 note 卡片

### 当前交互

- 输入卡点击：文字输入
- 输入卡长按：按住录音，松手结束
- note 卡左右滑：
  - 右滑归档
  - 左滑回收站
- note 卡底部快捷图标按类型变化

### 目前已经收过的关键 bug

- 悬浮窗卡片与原面板重叠
- 输入法遮挡
- 输入卡长按录音约 1 秒自动取消
- 录音结束后错误收起整个悬浮窗
- 红色录音键长按无效

当前语义：
- 输入卡长按录音结束后保持侧边轨展开
- 新语音卡应立刻出现在最近列表中

## 主界面现状

### 已落地

- 折叠式快速记录卡
- 双分组胶囊：
  - 类型
  - 优先级
- note 卡图标化动作区
- 语音 note 单卡播放器
- 语音标题 UI 层隐藏底层 id
- 日历 / agenda 入口
- AI 建议区

## 快捷启动现状

### 已提供的入口

- Quick Settings Tile
- 动态快捷方式 `快速录音`
- 桌面固定快捷录音
- `QuickRecordEntryActivity`

### 当前目标

不做私有 OEM SDK 适配，但提供一个稳定的官方直录入口，方便用户把它绑定到系统支持的快捷启动路径。

## 最近几轮主要里程碑

### 里程碑 A：归档 / 回收站闭环

- 数据层补齐归档与回收站字段
- 主界面加入收件箱 / 归档 / 回收站
- 悬浮窗左右滑归档与回收站
- 回收站同步语义改为远端删除

### 里程碑 B：NAS 目录语义完善

- 活跃 note 到 `inbox/`
- 归档 note 到 `archive/`
- 回收站远端删除
- `archived` 状态进入 frontmatter

### 里程碑 C：悬浮窗侧边轨重构

- 从多 overlay 冲突结构改成单窗口侧边轨
- 输入卡 + 多类型卡片
- 列表级 swipe
- 悬浮窗编辑卡

### 里程碑 D：本地音频双份保存与播放

- 录音先保存私有副本
- 再导出一份系统可见副本
- 主界面和悬浮窗都支持播放

### 里程碑 E：AI / 提醒 / 直录入口底座

- `AiSuggestion` 本地表
- `ReminderEntry` 本地表
- agenda 视图
- AlarmManager 通知调度
- relay `/ai/analyze-note`
- 动态快捷录音 / 桌面快捷录音 / 专用直录入口

### 里程碑 F：AI provider 兼容层

- App 直接支持 `Relay / OpenAI / Anthropic / Auto`
- 避免把 provider 地址误当 relay 地址
- 兼容 HTML 错配和 fenced JSON 返回

## 当前验证状态

已经验证过的：

- Android `assembleDebug`
- relay 新增 Python 文件语法检查
- 模拟器可安装、可启动
- 主界面录音和播放基本可用
- AI provider 直连逻辑已用真实网关 URL 做过请求验证

仍建议继续真机重点回归的：

- 悬浮窗长按录音在不同 ROM 下的稳定性
- AI 建议的真实效果与不同模型兼容性
- 提醒通知在不同系统电池策略下的准时性
- WebDAV 双向同步在真实 NAS 环境下的完整一致性

## 下一阶段最值得做的事

### P0

- AI 问答二期：基于 note 的问答，而不是只做建议
- 自定义提醒时间选择器
- 悬浮窗真机交互回归与边缘稳定性

### P1

- 提醒重复规则
- 更细的 AI 应用粒度
- 更完整的快捷启动机型指引

### P2

- 飞书 / 聊天软件外发提醒
- 离线 ASR fallback
- 更完整的自动化测试链路
