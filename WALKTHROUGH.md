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

## 里程碑 G：tags 系统 + 快速记录栏底部固定 + AI 时间提取优化

> 分支 `claude/1.2.0`，未提交 WIP，基于 `e73c6ff`
> 改动 15 文件，+397 -86 行

### 本轮目标

1. Note 支持用户自定义标签，在主界面卡片上显示
2. 快速记录栏（主界面 + 悬浮窗）在侧边栏拉开时固定在屏幕底部居中
3. AI 整理提取提醒时间不准（中文相对时间解析错误）

### 涉及模块

#### data — tags 存储层

- `Note.kt` 新增 `tags: List<String>` 字段
- `NoteEntity.kt` 新增 `tagsJson: String?` 列（JSON 序列化）
- `YDocDatabase` Migration 13→14，`ALTER TABLE notes ADD COLUMN tagsJson TEXT`
- `Mappers.kt` 双向转换 tags ↔ tagsJson
- `MarkdownFormatter` frontmatter 增加 `tags:` 行，导入时解析回 `List<String>`
- `NoteRepository.createTextNote()` 接受 tags 参数；`savePulledNote()` 保留远端 tags
- `AppContainer` AI 专用 OkHttpClient，独立超时（connect 15s / read 90s / call 120s），防止 provider 挂起卡死 AiSuggestion

#### ui — 主界面改动

- `CaptureDraft` / `EditDraft` 增加 `tags` 字段
- `HeroCaptureCard` 从 LazyColumn 首个 item 移到外层 Box 底部固定（`Modifier.align(BottomCenter)`）
- `EditNoteCard` 增加标签输入框（逗号分隔）
- `NoteCardV2` 用 `FlowRow` 显示 `#tag` 芯片
- `NoteCardV2` 操作区增加"复制内容"按钮（`onCopyNote`）
- 日历 agenda 视图增加「新增日程」按钮 + `CreateReminderDialog`（选日期、时、分）
- `AppViewModel` 新增 `updateDraftTags` / `updateEditingTags` / `copyNoteContent` / `createReminderForDate`

#### overlay — 悬浮窗快速记录栏

- `overlay_handle.xml` 新增 `overlayStripEntryBar`（FrameLayout 子 View，`layout_gravity=bottom|center_horizontal`）
- `OverlayHandleService`：strip 展开时 ComposerEntryView 渲染到 `stripEntryBar` 而非 RecyclerView
- RecyclerView 底部 padding 从 4dp 改为 92dp，给底部浮层留空间
- `OverlayStripAdapter`：`OverlayComposerPressView` 改为 internal，供 HandleService 直接访问
- strip 编辑卡 overlay 也同步支持 tags

#### ai — 时间提取 prompt 优化

- `RelayAiClient.buildSystemPrompt()` 新增 TIME RESOLUTION RULES 段：
  - 中文相对时间映射表（明天/后天/大后天/下周一/上午/下午/晚上/凌晨）
  - 明确要求从 `currentTimeEpochMs` 算出绝对 epoch ms
  - 强调输出前要 double-check 算术
- `defaultAiPromptTemplate()` 补充中文时间表达识别指导

### 关键决策

- tags 用 `tagsJson`（JSON 字符串）存在 Room 而非关联表——tag 数量少、不需要按 tag 查询，简单够用
- 主界面 HeroCaptureCard 用 Box 叠加而非 bottomBar，避免干扰 Scaffold 布局
- 悬浮窗快速记录栏用独立 LinearLayout 浮层，脱离 RecyclerView 滚动
- AI 时间 prompt 用显式映射表而不是让模型自己推算中文时间，降低出错率

### 遗留 / 待办

- 标签输入目前是逗号分隔文本框，后续可改为 chip 输入 + 自动补全
- 悬浮窗 stripEntryBar 需要真机验证不同屏幕尺寸的适配
- AI 时间提取优化需要用真实中文时间表达做端到端验证
- tags 尚未接入 AI 建议流程（AI 不会建议标签）

### 手动回归清单

- [ ] 悬浮窗 strip 展开，快速记录栏是否显示在底部居中
- [ ] 悬浮窗 strip 展开后长按快速记录栏录音，是否能正常触发
- [ ] 主界面快速记录栏是否固定在底部，不随列表滚动
- [ ] 主界面新建便签时填写标签，保存后 NoteCard 上是否显示 #tag
- [ ] 编辑已有便签时修改标签，保存后标签是否正确更新
- [ ] WebDAV 同步后远端 markdown 文件 frontmatter 是否包含 tags 行
- [ ] 从 NAS 拉取带 tags 的 markdown 文件，本地是否正确还原标签
- [ ] Room Migration 13→14 在已安装 App 上是否成功执行
- [ ] AI 分析包含"明天上午 X 点"类时间表达的便签，提醒时间是否准确
- [ ] 日历 agenda 视图「新增日程」弹窗创建提醒是否正常
- [ ] NoteCard 复制内容按钮是否将文本复制到系统剪贴板

---

## 里程碑 H — Web 端（2026-04-13）

### 背景

手机端核心能力已完备，桌面场景需要 Web 端补充。设计目标：
- 扩展现有 `relay_service`（FastAPI），不新建独立后端
- Web 端用 Next.js 静态导出，由 FastAPI 同端口 serve
- 保持与 Android 端的 WebDAV 双向同步，Markdown 格式 100% 兼容

### 架构

```
[Android] ←WebDAV→ [NAS] ←WebDAV→ [relay_service] ←HTTP→ [Next.js 静态页面]
                                     + SQLite
```

### 后端改动

#### `relay_service/app/` 新增 8 个模块

- **`database.py`** — aiosqlite 连接管理，WAL 模式，4 张表：
  - `notes`（与 Android Room NoteEntity 字段对齐）
  - `ai_suggestions`、`reminders`、`tombstones`
- **`models_notes.py`** — Pydantic 请求/响应模型
- **`markdown_format.py`** — Python 移植的 MarkdownFormatter.kt，100% 兼容：
  - 中文标签：`普通/待办/任务/提醒`、`低/中/高/紧急`、`文字/语音`
  - frontmatter 字段顺序、日期格式、文件名规则完全一致
  - Round-trip 测试通过
- **`routes_notes.py`** — 13 个笔记 CRUD 端点 + AI 分析触发
- **`routes_reminders.py`** — 4 个提醒 CRUD 端点
- **`routes_sync.py`** — 同步状态 + 手动触发
- **`webdav_client.py`** — httpx 异步 WebDAV 客户端：
  - PROPFIND/PUT/GET/DELETE/MKCOL
  - 自动管理 inbox/ 与 archive/ 两个文件夹
- **`sync_orchestrator.py`** — 移植 SyncOrchestrator.kt 的双向同步：
  - 时间戳比较决定冲突方向（last-write-wins）
  - tombstone 语义支持远端清理
  - 后台定时循环 + 手动触发

#### `main.py` 扩展

- 注册新 3 个 router（notes/reminders/sync）
- 启动后台同步循环（lifespan）
- CORS 中间件
- FastAPI 直接 serve Next.js 静态导出：`/_next` + SPA 路由回退

### 前端（`relay_service/web/`）

#### 技术栈

Next.js 14 App Router（静态导出）+ Tailwind CSS + SWR + Zustand + lucide-react

#### 页面（6 个）

| 路由 | 内容 |
|------|------|
| `/inbox` | 收件箱 + QuickCapture + 搜索栏 + NoteCard 列表 |
| `/archive` | 归档 |
| `/trash` | 回收站 + 清空 |
| `/note?id=xxx` | 笔记编辑 + AI 建议面板（用 query 参数绕开静态导出限制） |
| `/calendar` | 月历网格 + 日议程 |
| `/settings` | Token 配置 + 同步状态 |

#### 核心组件

- **NoteCard** — 颜色标识（colorToken）+ 展开操作（AI整理/复制/编辑/归档/删除）
- **QuickCapture** — 快速记录框，支持分类/优先级/标签
- **Sidebar** — 导航栏（lucide 图标）
- **SearchBar** — 全局搜索（Zustand 管理状态）

### 运维

- **`run.sh`** — 一键启动脚本：检查 Python 3.11+、Node.js、构建前端、启动 uvicorn
- **`.env.example`** — 完整环境变量文档
- **README 重写** — 三端架构图、技术栈矩阵、快速启动流程

### 端到端验证

全部通过：
- `/healthz` 200
- `POST /api/notes` 创建笔记，colorToken 自动计算（TODO+HIGH→AMBER）
- `GET /api/notes` 返回带中文标签的笔记列表
- `GET /inbox` 返回完整 Next.js HTML（Sidebar/QuickCapture 渲染正确）
- Markdown round-trip 测试通过

### 关键决策

- **扩展 relay 而非新建后端**：共享鉴权、部署更简单、AI 分析已在这里
- **Web 端用查询参数 `/note?id=xxx`**：静态导出不支持动态路由预生成
- **SQLite WAL 模式**：避免后台同步和 API 请求并发锁冲突
- **npm install 用 npmmirror**：国内网络下 npmjs.org 超时频繁

### 遗留 / 待办

- 真实三端同步验证待做（Phase B）
- AI 问答 / 批量整理接口未实现
- Web 端键盘快捷键、Markdown 预览、图片附件未做
- 提醒自定义时间 / 重复规则未做

### 手动回归清单

- [ ] `run.sh` 在干净环境能一键启动（检查 venv 创建、npm install、构建）
- [ ] Web 端首次访问在设置页输入 token 能正常鉴权
- [ ] QuickCapture 创建笔记后 NoteCard 立即刷新显示
- [ ] 编辑笔记改分类，返回收件箱颜色圆点正确更新
- [ ] 左侧导航 Inbox/Archive/Trash/Calendar/Settings 路由正常
- [ ] WebDAV 配置后 5 分钟内自动同步触发
- [ ] 手动 `POST /api/sync/trigger` 返回状态，日志中有 PUSHED/PULLED 记录

---

## 里程碑 I — Web 端 Phase A/B/C 全套（2026-04-13）

里程碑 H 打完 Web 端基础架构后，这一轮把五个阶段连成 pipeline 全部 ship：收尾合并、真实三端同步验证、AI 第二阶段、体验打磨、提醒增强。

### Phase A — 收尾合并（PR #3）

- `README.md` 重写：三端架构图、技术栈矩阵（Android / Web / 后端）、Android 和 Web 双端启动流程
- `relay_service/run.sh`：一键启动脚本，自动检查 Python 3.11+、Node.js、构建前端、启动 uvicorn
- `relay_service/.env.example`：补齐 SQLite / WebDAV / Static 等新增变量，中文注释
- `WALKTHROUGH.md` 追加里程碑 H（Web 端完整走查）

### Phase B — 真实三端同步验证（PR #4）

用 `rclone serve webdav` 起本地 8888 端口，配合 relay_service 跑了 6 轮端到端测试：

1. Web 端创建笔记 → `pushed=1` → 远端出现 `inbox/<filename>.md` ✓
2. 模拟 Android 推送（WebDAV PUT）→ `pulled=1` → SQLite 出现该笔记 ✓
3. Web 端归档 → 文件从 `inbox/` 移到 `archive/` ✓
4. 编辑 category/priority → colorToken 自动重算（URGENT→ROSE）+ 远端刷新 ✓
5. 彻底删除 → tombstone → 远端文件清理 ✓
6. Markdown frontmatter 中文标签 / 日期格式 / 文件命名 100% 与 Android 端兼容 ✓

顺手修了一个 list API 的默认过滤 bug：`archived=None` 时应该默认排除而非包含，保持与 `trashed` 行为一致。

### Phase C.1 — AI 问答 + 批量整理（PR #5）

新增 `relay_service/app/routes_ai.py`：

- **POST /api/ai/chat** — 基于笔记的问答
  - 支持筛选：`category`、`priority`、`tag`、时间范围、是否包含归档
  - 加载最近 N 条匹配笔记作为 LLM context
  - 系统提示词明确要求「只基于笔记内容」「引用时用《标题》标注」「找不到就说明」
  - 未配 provider 走启发式兜底：关键词匹配 + 最近笔记列表

- **POST /api/ai/batch-organize** — 聚类分析
  - 识别相似笔记建议合并、随手记集合建议转任务
  - 返回 `clusters[] { cluster_id, theme, note_ids, suggested_action, suggested_title, reason }`
  - action: `merge` / `convert_to_task` / `keep`
  - 启发式兜底：按标签分组 + 按 NOTE 分类聚合

Web 端新增两页：

- **`/chat`** — 对话界面，快捷问题模板（"这周的待办" / "紧急任务" 等），实时引用笔记数
- **`/organize`** — 聚类卡片展示，「应用」按钮：
  - `convert_to_task`：批量把笔记 category 改为 TASK
  - `merge`：拉取所有笔记内容合并到第一条并更新标题

### Phase C.3 — Web 端体验打磨（PR #6）

三件套：

1. **键盘快捷键**（inbox 页）
   - `j/k` 上下选择 / `e` 编辑 / `a` 归档 / `d` 删除 / `/` 聚焦搜索 / `?` 显示帮助 / `Esc` 取消
   - 新增 `useKeyboardShortcuts` hook，输入框聚焦时自动不触发
   - NoteCard 加选中态 `ring-1 ring-emerald-100 border-emerald-400`
   - 选中时自动滚动到视野内

2. **标签管理 `/tags`**
   - 标签云：按使用频率动态缩放字号（xl/lg/base/sm）
   - 完整列表 + 计数 + 重命名（批量更新所有关联笔记，去重合并）
   - 点击标签直接跳转 inbox 并套用筛选

3. **Markdown 预览**
   - 引入 `react-markdown` + `remark-gfm`
   - 新增 `MarkdownView` 组件，统一样式（标题、列表、代码块、引用、任务列表）
   - NoteCard 展开时用 MarkdownView 渲染内容，收起时仍然显示纯文本预览

### Phase C.2 — 提醒自定义时间 + 重复规则（PR #7）

这个阶段 Android 端因为涉及 Room migration 14→15 + AlarmManager 重复调度，风险较大，放到下一轮真机回归窗口。这一轮先把后端和 Web 端打通。

**数据库**：

- `reminders` 表新增 `recurrence TEXT` 字段（可空）
- 有效值：`DAILY / WEEKDAYS / WEEKLY / MONTHLY / null`
- 新增 `_migrate()` 幂等迁移：`PRAGMA table_info` 检查后 `ALTER TABLE ADD COLUMN`，对已存在的 DB 无损

**API**：

- `POST /api/reminders` 接受 `recurrence` 参数
- `ReminderResponse` 返回该字段

**Web 端**：

- 新增 `ReminderForm` 组件：
  - `<input type="date">` + `<input type="time">` 原生选择器
  - 5 种重复规则按 chip 排列：`一次 / 每天 / 工作日 / 每周 / 每月`
  - 模态弹窗，Enter 提交 / Esc 取消
- 日历页头部新增「新建提醒」按钮，默认挂到当前选中日期上
- 日议程每条提醒：有 recurrence 时显示 `🔁 每周` 样式的 chip

### 关键决策

- **Phase 拆分顺序**：A（合并）→ B（验证）→ C.1（AI，价值大）→ C.3（纯前端，低风险）→ C.2（跨端，风险大），避免连续动 Android 端。
- **启发式兜底**：AI 接口未配 provider 时不报错，返回可用信息（笔记列表、标签聚类）——降低部署门槛。
- **重复规则用枚举字符串**：`DAILY/WEEKLY/MONTHLY/WEEKDAYS` 而非完整 RFC 5545 RRULE，产品层够用，后续需要再演进。
- **Android migration 推迟**：Web 端率先完成自定义时间 + 重复规则，跨端同步兼容性（Markdown frontmatter 暂不包含 recurrence）——后续 Android 迁移时再决定是否把 recurrence 写进 frontmatter。

### 遗留 / 待办

- Android 端自定义时间选择器、重复规则 Room schema、AlarmManager 重复调度（下一轮真机）
- WebDAV Markdown frontmatter 是否应该包含 recurrence 字段（跨端同步）
- AI 批量整理的 `merge` 操作暂未删除被合并的源笔记（保守策略，避免误合）
- 图片附件支持尚未实现

### 手动回归清单

- [ ] `run.sh` 在干净环境跑通（检查 venv、npm、静态产物）
- [ ] `/chat` 发几个问题验证启发式和 provider 两种模式的应答
- [ ] `/organize` 点「AI 分析我的笔记」→ 看到聚类卡片 → 点「应用」转任务成功
- [ ] `/tags` 标签云按频率缩放正确，重命名后所有关联笔记同步改名
- [ ] inbox 页用 `j/k/e/a/d//` 快捷键全流程操作无冲突
- [ ] NoteCard 展开后 Markdown 代码块 / 列表 / 任务项 / 引用样式正确
- [ ] 日历页新建提醒，选择「每周」，创建后列表里能看到 🔁 chip
- [ ] 重启 relay 后 SQLite 老数据能自动迁移出 recurrence 字段（`_migrate()` 生效）

---

## 里程碑 J — AI 助手看不到笔记修复 + 链接识别 + 图片识别（2026-04-22）

一轮里把三件事压进同一个分支：
1. 修 AI 助手「看不到笔记」的长期体感 bug；
2. 引入链接识别 + 预览卡 + AI 摘要，让「裸 URL」笔记也能被整理；
3. 引入图片附件 + 本地 OCR + 服务端 Vision AI 双通道，让截图 / 相册图也能进 inbox。

> ⚠️ 本轮改动在无 JDK 环境下撰写，所有 Kotlin 改动**尚未本地编译**，relay 侧 Python 模块已通过 `py_compile`。用户端跑一次 `./gradlew lint assembleDebug` 是合入前必须的一步。

### Phase 0 — AI 助手看不到笔记（5 点根因 → 修复）

AgentScreen 里「AI 一直说没找到我的笔记」其实是多个小问题叠加，不是单一 bug：

| 根因 | 修法 |
|------|------|
| `NoteDao.observeActive()` 把归档笔记全过滤掉 → 问「归档里 X 相关」必然失败 | 新增 `observeAgentContextNotes()`（`WHERE isTrashed = 0`），`AgentRepository.sendMessageInternal` 改走它 |
| `ChatFilter.max_notes=30` 硬裁 + App 端只取 60 条 | App 端 `take(80)` + 显式传 `filter.maxNotes = 80`，server 端 `ChatFilter` 加 `field_validator` cap 到 `[1, 200]` |
| `content[:500]` 把长笔记关键段截掉 | 新建 `_clip_note_content(s, limit=1500)` 带「…（省略 N 字）」提示 |
| server 再按 `created_at` 排序，覆盖掉 App 已按 `pinned+updatedAt` 排好的语义 | 移除 inline_notes 分支里的 `candidates.sort(...)` |
| 系统 prompt「如果答案不在笔记里，明确说没有找到」太硬，导致相关线索也被拒答 | 改成分层：直接答案 → 相关线索（并注明）→ 完全没有才说「没找到」 |

并在两端加端到端日志方便下次定位：
- Android：`sendMessage inline_notes_size=80 preview=[abcd12:会议纪要, ...]`
- Relay：`chat: using N inline_notes (filtered from M)` + `chat: notes_for_llm=N preview=[...]`

涉及文件：
- `app/.../data/local/NoteDao.kt` `data/NoteRepository.kt` `data/AgentRepository.kt`
- `relay_service/app/routes_ai.py`

### Phase 1 — 链接识别 + 预览卡 + AI 摘要

**架构**：Note 保存 → URL 扫描 → `LinkPreviewWorker` 排队 → 打 `/api/links/preview` → 回写 `note.linkPreviewsJson` → Compose 展开态渲染预览卡 → AI 整理 pipeline 把 og + summary 注入 prompt。

**Room migration 17→18**：`notes` 加 `linkPreviewsJson TEXT`。

**Relay 端 `POST /api/links/preview`**：
- `urllib.request` 拉页面（UA + 512KB cap），BeautifulSoup4 取 og/title/description/image/site_name；
- `readability-lxml` 抽首屏正文 3000 字；
- 可选调 LLM 生成 150 字中文摘要（走现有 `call_llm`）。
- `requirements.txt` 加 beautifulsoup4 / lxml / readability-lxml；Dockerfile 装 libxml2 / libxslt 运行时。

**Android**：
- `ai/LinkPreviewClient.kt` 调 relay；
- `sync/LinkPreviewWorker.kt` + `extractUrls`（http(s) 正则 + 行尾标点剥除）；
- `NoteRepository.onNoteContentChanged` 回调在 `createTextNote / saveEditedNote / saveNote / saveTranscript / upsertFromRemote` 都触发；已成功抓过且 <7 天的结果直接复用，不重复请求；
- `NoteDao.updateLinkPreviewsJson` 只写该列不动 updatedAt / status，避免把笔记打回 LOCAL_ONLY 触发一次无意义的 WebDAV 推送。

**UI**：`ui/components/LinkPreviewCard.kt`（Coil 加载 og:image；title/summary/description 三层 fallback；error 降级 chip；点击开系统浏览器）。接到 `NoteCardV2` 展开态。

**AI 整理管道注入**：
- `AiAnalyzeRequest.linkPreviews` 新字段；
- Kotlin RelayAiClient：`encodeProviderRequest` 只放有用字段（url/title/summary/siteName），`buildSystemPrompt` 插入 `LINK_PREVIEWS` 段；
- Relay `ai.py` 的 `build_system_prompt` 同步插 `LINK_PREVIEWS` 段。
- `AiOrchestrator` 过滤掉 error / 没 title+summary 的预览，不给模型看噪声。

依赖：`io.coil-kt:coil-compose:2.6.0`。

### Phase 2 — 图片识别（OCR + Vision AI 双通道）

**Room migration 18→19**：`notes` 加 `attachmentsJson TEXT`。新模型 `NoteAttachment(id, type, localPath, thumbPath, ocrText, aiDescription, aiStructuredJson, analyzeError, ...)`。

**入口**：
- Manifest 增加 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` + `image/*` intent-filter；
- `MainActivity.handleShareIntent`：系统分享进来的单张 / 多张图 → `AttachmentStore.import` 拷到私有目录 + 生成 480px 缩略图 → `NoteRepository.createAttachmentNote` 建新 IMAGE 笔记 → 每张排 `ImageAnalyzeWorker` → 自动跳到新笔记。
- PhotoPicker 按钮下一轮补（系统分享已经闭环，可用性不受阻塞）。

**本地 OCR**：`ai/ImageOcrService` 用 ML Kit `text-recognition` + `text-recognition-chinese`，跑两次取结果更长那版，全 offline。

**服务端 Vision AI**：
- `routes_images.py POST /api/images/analyze`：multipart 上传 → 存 `<static_dir>/images/<uuid>.<ext>` → 返回 `/static/images/...` URL；
- 调 `ai_provider.call_vision`（新增）：OpenAI vision（`data:image/...;base64,...`）+ Anthropic vision（`content[].type=image + source.base64`）双协议，AUTO 先试 OpenAI 再回退 Anthropic；
- 返回 `{remote_url, description, keywords[], actionable_items[], dates[]}`，parse 失败降级把整段当 description。
- `main.py` 新增 `app.mount("/static/images", StaticFiles(...))`（必须在 Next.js 的 catch-all `{full_path:path}` 之前挂）。

**Android 后台分析**：
- `ai/ImageAnalyzeClient.kt` multipart 打过去；
- `sync/ImageAnalyzeWorker`：两阶段 — 先本地 OCR（offline 也能做），再 relay vision；任一步异常写 `analyzeError` 让 UI 降级。已成功分析过的 attachment 不重复烧 token。

**UI**：`ui/components/NoteAttachmentRow.kt` 横向滚动缩略图 + 分析中转圈 indicator + 「i」按钮展开 OCR / AI 描述底部面板 + error 条。接到 `NoteCardV2` 展开态。

**AI 整理管道注入**：
- `AiAnalyzeRequest.imageContexts: List<ImageContext(ocrText, aiDescription, keywords)>`；
- `AiOrchestrator` 从 `note.attachments` 打平并从 `aiStructuredJson` 解出 keywords；
- Kotlin / Python prompt 都插入 `IMAGE_CONTEXTS` 段。

依赖：
- Kotlin：`com.google.mlkit:text-recognition:16.0.1`、`text-recognition-chinese:16.0.1`、`kotlinx-coroutines-play-services:1.8.1`。
- Python：`beautifulsoup4 / lxml / readability-lxml`（Phase 1 带过来）。

### 关键决策

- **架构副作用走 callback，不再直接把 Context 扔进 NoteRepository**：`NoteRepository` 构造函数多一个 `onNoteContentChanged: ((String) -> Unit)?`，`AppContainer` 注入 `LinkPreviewWorker.schedule` 调用，领域层保持不 Android 耦合。
- **`updateLinkPreviewsJson` / `updateAttachmentsJson` 故意不动 `updatedAt / status / lastSyncedAt`**：后台抓取/识别是系统副作用，不该把笔记推回 LOCAL_ONLY 再多触发一次 WebDAV 同步。
- **Vision 协议两路**：OpenAI 用 `image_url` data URI，Anthropic 用 `content[].type=image + base64`。两路在 `ai_provider.call_vision` 统一封装；AUTO 沿用现有 OpenAI-first-then-Anthropic 策略。
- **图片存 `static_dir/images/` + `/static/images` mount**：不依赖 Next.js 是否构建，AI 分析端点任何时候都能返回可访问的 `remote_url`。
- **链接预览已成功的 7 天内不重抓**：节省 LLM 摘要的开销，但允许 error 态在下一次保存时重试（因为抓取瞬时失败很常见）。
- **`encodeDefaults = false` 的坑已在 Phase 0 里规避**：inline_notes 的字段默认值刚好和 pydantic 默认值一致，不会因为省略导致 server 端空字段。

### 遗留 / 待办

- Android 主界面的「+ 加图」PhotoPicker 按钮（目前只支持"从系统分享到 Ydrop"路径）。
- 编辑页对附件的增删 UI（目前展开态只能查看，不能删单条附件；可以用 `NoteRepository.removeAttachmentFromNote` 已经落地的 API 挂一个 ×）。
- 图片附件的 WebDAV 同步：当前图片不走 WebDAV（Android 独占），跨端同步要到再议（relay `remote_url` 已经可以访问，web 端能显示）。
- 链接预览卡的折叠态图片：目前只在展开卡里显示，收起态不透出——如果卡片列表变得太密集再给一个「首条预览 chip」兜底。

### 必做真机回归清单

- [ ] 写 5 条笔记覆盖：最近文本 / 归档文本 / 长笔记（>800 字）/ 无标题 / 带标签。AI 助手分别提问，归档 + 长笔记都应命中（之前会"没找到"）。
- [ ] `adb logcat -s YDOC_AGENT:D` 看到 `inline_notes_size=...` 且 preview 有内容。
- [ ] relay 日志看到 `chat: notes_for_llm=N preview=[...]` 与客户端一致。
- [ ] 写一条只含 https 链接的笔记：10-30 秒内卡片展开能看到预览（title+summary）。断网态下只显示 error chip 但仍能点开浏览器。
- [ ] AI 整理重新跑：suggestedTitle / category 能反映链接内容，不再是空或「链接」。
- [ ] 从系统相册分享单张/多张图到 Ydrop：自动跳新笔记；缩略图出现；「i」按钮看到 OCR 文字（本地）和 AI 描述（relay vision）。断网态只看到 OCR + 「识别失败：…」。
- [ ] 升级 18→19 的设备：旧笔记不丢，新笔记含 `attachmentsJson`。
- [ ] Room migration 17→18→19 按顺序跑通（用一个低版本 ydoc.db 启动 app）。
- [ ] 重启 app 后 LinkPreviewWorker / ImageAnalyzeWorker 有未完成任务会自动继续（`WorkManager` 默认行为）。
- [ ] WebDAV 双向同步：attachmentsJson / linkPreviewsJson 字段的 update **不触发**重推远端（markdown frontmatter 不变）。

---

## 里程碑 K — 上线后 5 项打磨（2026-05-02）

里程碑 J 真机/服务端联调时暴露的 5 个体感问题，按"紧急 → 不急"顺序打掉。`./gradlew lint assembleDebug` 全部通过。

### 1. 智能助理时间识别错位

**根因**：`relay_service/app/routes_ai.py` 的 `_call_chat_provider` 系统 prompt **完全没有"当前时间"锚点**，LLM 只能看到笔记 JSON 里的 `created_at` epoch ms，回答"今天/这周/最近 N 天"全靠瞎猜。AI 整理（`ai.py`）有 TIME RESOLUTION RULES，但 chat 链路一直没复用。

**修法**：
- `routes_ai.py`：`ChatRequest` 新增 `current_time_epoch_ms / current_timezone / current_time_text` 三个可选字段；新 `_build_time_context()` 兜底；`_call_chat_provider` 在 system prompt 顶部注入 `Current system time / timezone / Unix milliseconds` + 中文相对时间换算规则（今天=0-24 时、本周=本周一 0:00 至本周日 24:00 等）。
- `AgentApiClient.kt`：`chat()` 加三个对应入参，`ChatRequestBody` 加序列化字段。
- `AgentRepository.kt`：调 `api.chat` 时填 `System.currentTimeMillis()` + `TimeZone.getDefault().id` + 客户端时区格式化的本地时间字符串。

### 2. 悬浮窗滑动归档/删除不生效

**根因**：`OverlayHandleService.attachStripSwipeHelper` 的 `getMovementFlags` 用 `stripAdapter.getItemOrNull(viewHolder.bindingAdapterPosition)` 判断是否是 NoteStripItem。问题：`bindingAdapterPosition` 在动画 / 即将 detach 的瞬间会回 `RecyclerView.NO_POSITION (-1)`，`getItemOrNull(-1)` 直接返回 null → 落到 else 返回 0,0 → 滑动**完全关闭**。

**修法**：
- `OverlayStripAdapter`：`private companion object` 提升为 `companion object`，让 `VIEW_TYPE_NOTE` 等常量对外可见。
- `OverlayHandleService`：`getMovementFlags` 改用 `viewHolder.itemViewType == OverlayStripAdapter.VIEW_TYPE_NOTE` 判断（itemViewType 在 `onCreateViewHolder` 时定型，整个生命周期不变）；`onChildDraw` 同步换。
- `onSwiped` 加 `AppLogger.overlay("strip swiped dir=... note=...")`，复现时 `adb logcat -s YDOC_OVERLAY:D` 能直接看到方向 + note id。

### 3. AI 整理后无法恢复原内容

**现状**：`Note.originalContent` 早就有（Migration 已加），`applyAiSuggestion` 也存了备份；展开态有「查看原内容」开关——**只能看，不能还原**。

**修法**：
- `AppViewModel.restoreOriginalContent(noteId)`：把 `content = originalContent`、`originalContent = null`，suggestion 状态打回 `DISMISSED`。**只回滚 content**，title / category / priority / tags / colorToken 都不动——用户在 apply 之后可能又手改过这些字段，一刀切回滚反而会丢人为意图。
- `YDocApp.kt` NoteCardV2 展开态：原"查看原内容"那行扩成两个按钮，新的「还原为原内容」用 `colorScheme.error` 染红；点击弹 `AlertDialog` 二次确认（"标题、分类、优先级、标签保留不变。还原后无法撤销。"）。
- `onRestoreOriginalContent: (String) -> Unit` 通过四层参数链路下发（root composable → notes section → NoteCardV2）。

### 4. URL 预览出现两个、其中一个永远 404

**根因怀疑**：同一逻辑 URL 被解析成两个不同字符串（一种带 / 不带 `utm_*`、带 / 不带 trailing `/`、带 / 不带 fragment 等等），`distinct()` 失效；同时 relay 端如果 og:title / `<title>` 都为空会返回空 title，UI 渲染成空预览框被用户当成"404"。

**修法**：
- `LinkPreviewWorker.kt`：抽 URL 后增加 `canonicalizeUrl()` 步骤——小写 host、去 trailing `/`、剥常见追踪参数（`utm_*`、`spm`、`share`、`from`、`ref*`、`scene`、`src`、`session_id`、`weibo_id`、`_t`、`fr` 等），再 `distinct().take(5)`。加 `AppLogger.relay("note=$noteId raw_urls=N dedup=N list=...")` 复现日志。
- `relay_service/app/routes_links.py`：`_extract_meta` 在 og:title / `<title>` 都空时用 `host + 第一段 path` 兜底当 title，避免 UI 看到空预览框。

### 5. OCR 识别率低 + 喂 AI 截断太狠

**已确认**：OCR 内容**早就在喂 AI 整理**（`AiOrchestrator.imageContexts` + `IMAGE_CONTEXTS` prompt 段，Kotlin 和 Python 双侧都在）。问题是 OCR 自身识别率 + 截断长度。

**修法**：
- `ImageOcrService.kt` 完全重写：
  - **行级 union 替代 max-length**——之前 `if (chineseText.length >= latinText.length) chineseText else latinText`，latin recognizer 对中文返回长但糟糕的乱码，长度大却信息更差。改为把两路按行拆分进 `LinkedHashSet` 合并去重，保留首次出现顺序。
  - **小图自动放大**：短边 < 1024 时按 2x 放大喂 ML Kit；< 512 时 3x。手机截屏小字识别率明显提升。
  - **EXIF rotation**：从 `androidx.exifinterface` 读 orientation 传给 `InputImage.fromBitmap(bmp, rotation)`。横拍图被识别成"侧着"是常见漏识别原因。
  - 任一路抛异常都 runCatching 兜底，最差降级成空串。
- AI 截断放宽（OCR 是关键内容，截太狠丢 todo）：
  - `RelayAiClient.kt` wire 端 OCR 800 → 1500、aiDescription 400 → 800、keywords 8 → 10；prompt 端 OCR 300 → 800、aiDescription 240 → 600。
  - `relay_service/app/ai.py` `_render_image_contexts_section` 同步：300 → 800 / 240 → 600 / 8 → 10。

### 关键决策

- **修复用 viewType 而非 position 判断 swipe**：position-based 在 RecyclerView 动画/重绑期间不稳，是 ItemTouchHelper 用法的常见 footgun；viewType 在 `onCreateViewHolder` 时定型，整个生命周期稳定。
- **还原原内容只动 content**：用户对 AI 整理的不满主要在正文改写，title/category/priority 是产品语义，apply 之后可能被手改过；激进回滚会破坏人为意图。
- **URL 归一化只剥追踪参数，不剥业务参数**：保留 `?id=`、`?q=` 这种语义参数；只去 utm/spm/from 这种来源标记。fragment 保留——一些 SPA 的 `#section` 是关键路由。
- **OCR 双路按行 union 而非字符 union**：行是 OCR 输出的最小语义单位，按行去重既能补漏又不会把同一段反复贴。

### 遗留 / 待办

- 悬浮窗滑动修复需要真机回归确认（`adb logcat -s YDOC_OVERLAY:D` 看 `strip swiped dir=...`）。
- URL 预览归一化是否过度激进——观察一阵看会不会把用户实际想区分的 URL 错合。
- OCR 行级 union 在某些场景可能仍漏字（latin/chinese 都漏的同一行）；如还有问题考虑加第三路（如 PaddleOCR 但要打包模型）。
- 时间锚点修复后，AI 助手是否能正确回答"上周做了什么"还需要真实多日数据验证。

### 手动回归清单

- [ ] 智能助理问"今天 / 这周 / 上周"的笔记，回答里日期范围正确。
- [ ] `adb logcat -s YDOC_OVERLAY:D`：滑动 note 卡左/右，看到 `strip swiped dir=LEFT->trash` 或 `RIGHT->archive`。
- [ ] 一条 AI 整理过的笔记，展开态点「还原为原内容」→ 弹确认框 → 还原后正文 = 原内容、标题/分类/优先级/标签不变。
- [ ] 原本带 utm 参数的 URL 笔记，预览只剩 1 个；之前总是 404 那个 box 消失。
- [ ] 一张小字截图保存进笔记，OCR 文字比之前更全；AI 整理出的 todo 能反映截图里的关键任务。

