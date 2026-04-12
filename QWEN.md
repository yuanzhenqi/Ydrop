# Qwen 项目专属文档 - Ydrop (Ydoc)

> 最后更新：2026-04-11

---

## 一、项目概览

**Ydrop** 是一款 Android 端个人快速采集 Inbox 工具，核心理念是「把脑子里冒出的碎片一秒钟丢进来」。

### 核心特性
- **闪电速记**：文本快速输入，支持悬浮窗
- **语音采集**：一键录音，支持快捷入口（磁贴/快捷方式）
- **AI 智能整理**：自动提取摘要、标题、分类、优先级、待办事项
- **提醒系统**：基于 AI 分析的智能提醒调度
- **WebDAV 双向同步**：与云端保持数据一致
- **多入口设计**：主界面 / 悬浮窗 / Quick Tile / 快捷方式

### 技术栈
| 维度 | 技术选型 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| UI 框架 | Jetpack Compose (BOM 2024.09.00) |
| 数据库 | Room 2.6.1 |
| 异步 | Kotlin Coroutines + Flow |
| 后台任务 | WorkManager 2.9.1 / AlarmManager |
| 网络 | OkHttp 4.12.0 |
| 序列化 | kotlinx-serialization-json 1.7.2 |
| AI 接入 | 多 Provider（OpenAI / Anthropic / 火山引擎 / 自建 Relay） |

### 构建参数
- `compileSdk` / `targetSdk`: 35
- `minSdk`: 26
- `JVM Target`: 17
- `包名`: `com.ydoc.app`
- `versionCode`: 1 / `versionName`: `0.1.0`

---

## 二、目录结构

```
Ydoc/
├── app/                           # Android 应用主模块
│   ├── build.gradle.kts           # 应用级构建配置
│   ├── proguard-rules.pro         # 代码混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml    # 清单文件
│       ├── java/com/ydoc/app/
│       │   ├── MainActivity.kt          # 主 Activity
│       │   ├── YDocApplication.kt       # Application 初始化
│       │   ├── ai/                      # AI 客户端与编排 (3)
│       │   ├── config/                  # 运行时配置 (1)
│       │   ├── data/                    # 数据层 (10)
│       │   │   ├── local/               # Room 实体与 DAO (7)
│       │   │   └── ...                  # 仓库、映射、设置
│       │   ├── logging/                 # 日志工具 (1)
│       │   ├── model/                   # 领域模型 (5)
│       │   ├── overlay/                 # 悬浮窗系统 (5)
│       │   ├── quickrecord/             # 快捷录音入口 (2)
│       │   ├── quicktile/               # 快速设置磁贴 (1)
│       │   ├── recording/               # 录音与播放 (5)
│       │   ├── relay/                   # Relay 上传客户端 (2)
│       │   ├── reminder/                # 提醒调度 (3)
│       │   ├── sync/                    # WebDAV 同步 (5)
│       │   ├── transcription/           # 语音转写 (4)
│       │   └── ui/                      # Compose UI 层 (7)
│       │       ├── components/          # 可复用组件
│       │       └── theme/               # 主题系统 (5)
│       └── res/                         # 资源文件
│           ├── drawable/
│           ├── layout/
│           ├── mipmap-anydpi-v26/
│           ├── values/
│           └── xml/
│
├── relay_service/                 # Python FastAPI 后端中继服务
│   ├── app/
│   │   ├── main.py                # FastAPI 入口
│   │   ├── ai.py                  # AI 分析
│   │   ├── auth.py                # Token 认证
│   │   ├── config.py              # 配置
│   │   ├── models.py              # 请求/响应模型
│   │   └── storage.py             # 文件存储
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── requirements.txt
│   └── .env.example
│
├── build.gradle.kts               # 根构建脚本（插件版本声明）
├── settings.gradle.kts            # 项目设置（仓库配置）
├── gradle.properties              # Gradle 属性
├── CLAUDE.md                      # AI 助手上下文
├── README.md                      # 功能说明与 AI 配置指南
└── WALKTHROUGH.md                 # 开发走查与迭代记录
```

---

## 三、核心架构设计

### 3.1 数据流向

```
用户输入 ──→ Note 创建 ──→ Room 本地存储
                │
                ├─→ AI 编排 ──→ AI 建议生成 ──→ AiSuggestion 存储
                │
                ├─→ 提醒调度 ──→ AlarmManager ──→ 通知推送
                │
                ├─→ Relay 上传 ──→ 云端存储 ──→ 转写服务
                │
                └─→ WebDAV 同步 ──→ 双向同步 (inbox/ + archive/)
```

### 3.2 核心领域模型

#### Note（笔记）
```kotlin
data class Note(
    val id: String,              // UUID
    val text: String?,           // 文本内容
    val audioUri: String?,       // 音频 URI
    val category: NoteCategory,  // INBOX / ARCHIVE / TRASH
    val priority: NotePriority,  // LOW / MEDIUM / HIGH / URGENT
    val tags: List<String>,      // 标签列表
    val isArchived: Boolean,
    val isTrashed: Boolean,
    val transcriptionStatus: TranscriptionStatus,
    val syncStatus: SyncStatus
)
```

#### AiSuggestion（AI 建议）
- **非破坏式存储**：独立表，不与 Note 直接耦合
- **建议类型**：摘要、标题建议、分类建议、优先级、待办提取、信息提取、提醒候选
- **状态流转**：RUNNING → READY → APPLIED / DISMISSED / FAILED

#### ReminderEntry（提醒）
- 与 Note 软绑定（noteId 可选）
- 字段：title、scheduledAt、source、status、deliveryTargets

### 3.3 依赖注入

通过 `AppContainer` 实现轻量 DI：
```kotlin
class AppContainer(private val context: Context) {
    val database by lazy { /* Room DB */ }
    val settingsStore by lazy { /* DataStore */ }
    val noteRepository by lazy { /* NoteRepo */ }
    // ... 其他依赖
}
```

---

## 四、关键模块说明

### 4.1 AI 系统（ai/）
- **AiClient.kt**：接口定义，支持多种 Provider
- **AiOrchestrator.kt**：编排 AI 调用流程
- **RelayAiClient.kt**：对接自建 Relay 服务

**支持的 AI 模式**：
| 模式 | 说明 |
|---|---|
| AUTO | 自动选择最佳 Provider |
| RELAY | 通过自建中继服务 |
| OPENAI | 直接调用 OpenAI API |
| ANTHROPIC | 直接调用 Anthropic API |

### 4.2 同步系统（sync/）
- **WebDavSyncClient.kt**：WebDAV 协议实现
- **SyncOrchestrator.kt**：同步编排逻辑
- **SyncScheduler.kt**：定时同步调度
- **SyncWorker.kt**：WorkManager Worker

**同步策略**：
- `inbox/` 目录：未归档笔记
- `archive/` 目录：已归档笔记
- 双向同步，使用 Tombstone 机制处理删除

### 4.3 录音系统（recording/）
- **AudioRecorder.kt**：录音核心逻辑
- **RecordingService.kt**：前台服务（防止被杀）
- **LocalAudioPlayer.kt**：本地播放
- **LocalAudioExporter.kt**：导出为系统可见
- **VoiceNoteProcessor.kt**：语音笔记处理流水线

### 4.4 悬浮窗系统（overlay/）
- **OverlayHandleService.kt**：悬浮窗前台服务
- **OverlayStripAdapter.kt**：侧边轨列表适配器
- **OverlayPresentation.kt**：悬浮窗 UI 渲染
- **OverlayPermissionHelper.kt**：权限辅助
- **SwipeableNoteView.kt**：可滑动 Note 视图

### 4.5 提醒系统（reminder/）
- **ReminderScheduler.kt**：AlarmManager 精确调度
- **ReminderReceiver.kt**：广播接收器，触发通知
- **ReminderBootReceiver.kt**：开机重新注册提醒

### 4.6 转写系统（transcription/）
- **TranscriptionOrchestrator.kt**：转写编排
- **TranscriptionScheduler.kt**：调度转写任务
- **TranscriptionRetryWorker.kt**：失败重试
- **VolcengineTranscriptionClient.kt**：火山引擎语音转写

---

## 五、数据库设计

### 5.1 Room 数据库

数据库类：`YDocDatabase.kt`
- 当前已迁移到 **version 14**
- 每次升级都有对应的 Migration

### 5.2 实体列表

| 实体 | 说明 | DAO |
|---|---|---|
| `NoteEntity` | 笔记主表 | `NoteDao` |
| `AiSuggestionEntity` | AI 建议表 | `AiSuggestionDao` |
| `ReminderEntryEntity` | 提醒记录表 | `ReminderEntryDao` |
| `SyncTargetEntity` | 同步目标配置 | `SyncTargetDao` |
| `TombstoneEntity` | 软删除墓碑 | `TombstoneDao` |

### 5.3 DataStore 设置

`SettingsStore.kt` 使用 `DataStore<Preferences>` 存储：
- AI 配置（Provider、API Key 等）
- 同步配置（WebDAV URL、凭据）
- 用户偏好设置

---

## 六、权限清单

| 权限 | 用途 |
|---|---|
| `INTERNET` | 网络请求 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `RECORD_AUDIO` | 录音功能 |
| `FOREGROUND_SERVICE` | 前台服务 |
| `FOREGROUND_SERVICE_MICROPHONE` | 前台录音 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 悬浮窗前台服务 |
| `POST_NOTIFICATIONS` | 发送通知 |
| `RECEIVE_BOOT_COMPLETED` | 开机启动 |
| `SCHEDULE_EXACT_ALARM` | 精确闹钟 |
| `SET_ALARM` | 系统闹钟 |
| `VIBRATE` | 震动反馈 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗权限 |

---

## 七、开发规范与约定

### 7.1 代码风格
- Kotlin 官方风格 (`kotlin.code.style=official`)
- 使用 `android.nonTransitiveRClass=true` 优化 R 类
- 包结构按功能模块划分

### 7.2 架构模式
- **Repository 模式**：数据访问统一通过 Repository
- **MVVM 模式**：UI 层通过 ViewModel 与数据层交互
- **轻量 DI**：使用 `AppContainer` 手动注入（未使用 Hilt/Dagger）

### 7.3 命名约定
| 类型 | 前缀/后缀 | 示例 |
|---|---|---|
| Entity | `Entity` | `NoteEntity` |
| Dao | `Dao` | `NoteDao` |
| Repository | `Repository` | `NoteRepository` |
| Service | `Service` | `RecordingService` |
| ViewModel | `ViewModel` | `AppViewModel` |

### 7.4 构建注意事项
- `FAIL_ON_PROJECT_REPOS` 模式：禁止子模块重复声明仓库
- Release 构建启用 ProGuard/R8
- `android.suppressUnsupportedCompileSdk=35` 抑制 SDK 35 警告

---

## 八、Relay 服务（后端）

### 8.1 技术栈
- Python + FastAPI
- Docker 容器化部署

### 8.2 API 端点

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/healthz` | 健康检查 | 无 |
| POST | `/upload` | 音频文件上传 | Token |
| GET | `/files/{file_id}` | 文件下载 | 无 |
| DELETE | `/files/{file_id}` | 文件删除 | Token |
| POST | `/ai/analyze-note` | AI 分析笔记 | Token |

### 8.3 部署方式
```bash
# 使用 docker-compose
docker-compose up -d
```

---

## 九、快速上手

### 9.1 构建项目
```bash
# 调试版本
./gradlew assembleDebug

# Release 版本
./gradlew assembleRelease

# 运行测试
./gradlew test
```

### 9.2 AI 配置
在应用内设置页面配置：
1. 选择 AI Provider（AUTO / RELAY / OPENAI / ANTHROPIC）
2. 填写对应 API Key
3. 测试连接

### 9.3 同步配置
1. 设置 WebDAV 服务器地址
2. 填写用户名/密码
3. 首次同步将创建目录结构

---

## 十、常见问题排查

### 10.1 Room 迁移
- 每次修改 Schema 必须增加 `version` 并添加 `Migration`
- 使用 `fallbackToDestructiveMigration()` 仅在开发阶段

### 10.2 前台服务
- 录音/悬浮窗必须声明为前台服务
- 需要对应权限：`FOREGROUND_SERVICE_MICROPHONE` / `FOREGROUND_SERVICE_SPECIAL_USE`

### 10.3 WebDAV 同步
- 注意网络可达性
- 使用 OkHttp Logging Interceptor 调试请求

---

## 十一、扩展建议

### 11.1 未来可优化方向
- 引入 Hilt 替代手动 DI
- 增加单元测试覆盖率
- Compose UI 测试覆盖
- 同步冲突优化策略
- AI 缓存机制

### 11.2 性能优化
- 大数据量列表使用 `LazyColumn` 分页
- 图片/音频懒加载
- WorkManager 任务去重

---

## 十二、关键文件速查

| 用途 | 路径 |
|---|---|
| 主入口 | `app/src/main/java/com/ydoc/app/MainActivity.kt` |
| Application 初始化 | `app/src/main/java/com/ydoc/app/YDocApplication.kt` |
| 依赖注入容器 | `app/src/main/java/com/ydoc/app/data/AppContainer.kt` |
| Room 数据库 | `app/src/main/java/com/ydoc/app/data/local/YDocDatabase.kt` |
| Note 模型 | `app/src/main/java/com/ydoc/app/model/Note.kt` |
| AI 编排器 | `app/src/main/java/com/ydoc/app/ai/AiOrchestrator.kt` |
| WebDAV 同步 | `app/src/main/java/com/ydoc/app/sync/WebDavSyncClient.kt` |
| 录音服务 | `app/src/main/java/com/ydoc/app/recording/RecordingService.kt` |
| 悬浮窗服务 | `app/src/main/java/com/ydoc/app/overlay/OverlayHandleService.kt` |
| 设置存储 | `app/src/main/java/com/ydoc/app/data/SettingsStore.kt` |
| UI 根组件 | `app/src/main/java/com/ydoc/app/ui/YDocApp.kt` |
| App ViewModel | `app/src/main/java/com/ydoc/app/ui/AppViewModel.kt` |
| Relay 主服务 | `relay_service/app/main.py` |

---

*本文档由 Qwen 生成，基于对项目结构的全面分析。*
