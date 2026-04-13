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
