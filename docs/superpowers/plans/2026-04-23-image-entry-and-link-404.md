# 图片 App 内入口 + 链接预览 404 修复

> **给执行者**：两条独立线，**Track B 先做**（解除当前阻塞），Track A 后做（新功能）。两条都在 master 分支直接落；改动总量可控。

**Goal**：(A) 让用户在 App 内直接加图，不再依赖系统分享；(B) 解决当前 `/api/links/preview` 返回 404 的部署 gap，同时把 UI 错误提示从裸 HTTP 码改成友好引导。

**Architecture**：
- Track A：`NewNoteEditorScreen` + `EditNoteCard` 各加一个 PhotoPicker 入口；选中图走 `AttachmentStore.import` 落盘 → 文本+附件一起走 `createAttachmentNote` 或 `addAttachmentsToNote`；保存时排 `ImageAnalyzeWorker`。
- Track B：诊断 → push relay 代码 → NAS rebuild；同时在 `LinkPreviewClient` 里把 404 错误转成 `error="relay 版本过旧，需要升级到支持 /api/links/preview 的版本"`，让用户看得懂。

**Tech Stack**：Jetpack Compose / PhotoPicker (`ActivityResultContracts.PickVisualMedia` / `PickMultipleVisualMedia`) / OkHttp / Docker Compose。

---

## Track B — 链接预览 404 修复（先做）

### Task B.1：诊断 + 确认是部署 gap

**Files:** 无代码改动，只是一次 curl 验证

- [ ] **Step 1**：运行诊断命令

```bash
# Mac 本地，把 $TOKEN 换成真实的 RELAY_TOKEN
curl -i -X POST https://yrc.wxyzq.fun:7788/api/links/preview \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
```

- [ ] **Step 2**：确认返回结果

```
期望（已部署新版）：
  HTTP/1.1 200 OK
  Content-Type: application/json
  {"url":"https://example.com","title":"Example Domain",...}

还没部署：
  HTTP/1.1 404 Not Found
  Content-Type: application/json
  {"detail":"Not Found"}           ← FastAPI 告诉你没这个端点

反代/网关问题：
  HTTP/1.1 404 Not Found
  Content-Type: text/html           ← nginx/caddy 的 404 页
  <html>...</html>
```

- [ ] **Step 3**：分支
  - 如果 **FastAPI 404（JSON）**：走 Task B.2 + B.3（部署）
  - 如果 **200 OK**：问题不在 relay，直接跳 Task B.5（排查 Android 端拼路径）
  - 如果 **HTML 404**：检查 NAS 上的反向代理是否把 `/api/links/*` 转给了 relay（不太可能，`/api/*` 通常是统一规则）；如果反代是按路径前缀匹配，新端点会自动被现有规则捕获

---

### Task B.2：选择性 commit + push（保护本地 token）

当前 `DefaultConfig.kt` / `SettingsStore.kt` 的 WIP 含个人 token，**不能**和本次一起提交。只提 relay 相关。

**Files:**
- Staged：`relay_service/**`、`docs/**`、`WALKTHROUGH.md`
- Left out：`app/**`（Android 端改动留在 working tree，等单独处理 token 问题）

- [ ] **Step 1**：检查 stage 范围

```bash
cd /Users/yzq/Desktop/project/Ydoc
git status --short | grep -E "^( M|\?\?) (relay_service/|docs/|WALKTHROUGH\.md)"
# 期望输出（示意）：
#  M relay_service/Dockerfile
#  M relay_service/app/ai.py
#  M relay_service/app/ai_provider.py
#  M relay_service/app/main.py
#  M relay_service/app/models.py
#  M relay_service/app/routes_ai.py
#  M relay_service/requirements.txt
#  M WALKTHROUGH.md
# ?? relay_service/app/routes_images.py
# ?? relay_service/app/routes_links.py
# ?? docs/
```

- [ ] **Step 2**：精确 add

```bash
git add relay_service/Dockerfile \
        relay_service/app/ai.py \
        relay_service/app/ai_provider.py \
        relay_service/app/main.py \
        relay_service/app/models.py \
        relay_service/app/routes_ai.py \
        relay_service/app/routes_images.py \
        relay_service/app/routes_links.py \
        relay_service/requirements.txt \
        WALKTHROUGH.md \
        docs/
```

- [ ] **Step 3**：创建 commit

```bash
git commit -m "$(cat <<'EOF'
feat(relay): 链接预览 + 图片识别双端点 + AI 助手 prompt 放宽

- 新增 POST /api/links/preview：og meta + readability 正文 + 可选 LLM 摘要
- 新增 POST /api/images/analyze：multipart 上传 + vision provider（OpenAI/Anthropic 双协议）
- routes_ai.py：max_notes 上限抬到 200、content 截到 1500、移除 server 端重排、放宽系统 prompt
- analyze-note：prompt 注入 LINK_PREVIEWS + IMAGE_CONTEXTS 段，裸 URL / 纯图笔记也能整理
- Dockerfile：装 libxml2/libxslt 运行时，requirements 加 beautifulsoup4 / lxml / readability-lxml
- main.py：/static/images mount 独立挂载，不依赖 Next.js 构建产物
EOF
)"
```

- [ ] **Step 4**：push

```bash
git push origin master
```

- [ ] **Step 5**：验证 push 成功

```bash
git log -1 --oneline
# 期望：新 commit hash 和上面的消息
```

---

### Task B.3：NAS 侧 pull + rebuild

> 下面命令假设 NAS 上 Ydrop 仓库路径是 `~/ydrop` 或 `/volume1/docker/ydrop`——按你实际路径调整。

**Files:** 无代码改动，只是 ssh + docker 命令

- [ ] **Step 1**：ssh 上 NAS

```bash
ssh <nas-user>@<nas-host>
```

- [ ] **Step 2**：拉新代码

```bash
cd <path-to-ydrop-repo>      # 换成你的实际路径
git pull origin master
git log -1 --oneline
# 期望：和 Mac 端 Task B.2 Step 5 看到的 hash 一致
```

- [ ] **Step 3**：rebuild + up（`--no-cache` 是必须的，因为 Dockerfile 加了新的 apt 依赖）

```bash
cd relay_service
docker compose build --no-cache relay
docker compose up -d relay
```

- [ ] **Step 4**：看启动日志确认 routers 加载成功

```bash
docker compose logs --tail=50 relay
# 期望看到（uvicorn 启动）：
#   INFO:     Uvicorn running on http://0.0.0.0:8787
#   INFO:     Application startup complete.
# 不要看到 ModuleNotFoundError / ImportError
```

- [ ] **Step 5**：从外部再跑一次 Task B.1 的 curl

```bash
# 在 Mac 端
curl -i -X POST https://yrc.wxyzq.fun:7788/api/links/preview \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
# 期望：HTTP/1.1 200 OK + JSON body 里 title="Example Domain"
```

---

### Task B.4：Android 端把 404 错误转成友好提示

避免下次 relay 没跟上的时候，用户 UI 上看到「预览失败：link preview http 404: {"detail":"Not Found"}」。改成明确引导。

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/ai/LinkPreviewClient.kt:52-58`
- Modify: `app/src/main/java/com/ydoc/app/ai/ImageAnalyzeClient.kt:55-62`（图片分析端点同理，一起改）

- [ ] **Step 1**：在 `LinkPreviewClient.fetchPreview` 的失败分支分类处理 404

替换 `LinkPreviewClient.kt` 第 52-58 行：

```kotlin
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            AppLogger.relay("POST $endpoint -> ${resp.code}")
            if (resp.code == 404) {
                // relay 缺这个端点 = 服务端版本旧，用户看到裸 HTTP 码没用，直接告诉他需要升级
                error("relay 服务端还没有 /api/links/preview 端点，请升级 relay 到 0.3+")
            }
            if (!resp.isSuccessful) {
                error("link preview http ${resp.code}: ${text.take(160)}")
            }
            json.decodeFromString(Response.serializer(), text)
        }
```

- [ ] **Step 2**：对 `ImageAnalyzeClient.analyze` 做同等处理

替换 `ImageAnalyzeClient.kt` 里 `if (!resp.isSuccessful)` 那段：

```kotlin
            if (resp.code == 404) {
                error("relay 服务端还没有 /api/images/analyze 端点，请升级 relay 到 0.3+")
            }
            if (!resp.isSuccessful) {
                error("image analyze http ${resp.code}: ${text.take(160)}")
            }
```

- [ ] **Step 3**：编译验证

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
# 期望：BUILD SUCCESSFUL
```

- [ ] **Step 4**：commit（Android 端这次只改这两个 client，不动 DefaultConfig，所以可以安全提交）

```bash
git add app/src/main/java/com/ydoc/app/ai/LinkPreviewClient.kt \
        app/src/main/java/com/ydoc/app/ai/ImageAnalyzeClient.kt
git commit -m "feat(android): 链接/图片分析 404 降级成"升级 relay"引导文案"
```

---

### Task B.5：如果 B.1 返回 200（即 relay 已是新版但 App 端还 404）

走到这里说明服务端 OK，问题在客户端——很可能是 `relay.baseUrl` 末尾带 `/v1` 或者 `/api`，拼出了错误路径。

**Files:**
- Diagnose: `app/src/main/java/com/ydoc/app/ai/LinkPreviewClient.kt:43`

- [ ] **Step 1**：看端点拼接

```kotlin
val endpoint = relay.baseUrl.trimEnd('/') + "/api/links/preview"
```

只做了 `trimEnd('/')`，如果 `relay.baseUrl = "https://x.com/v1"`，会拼成 `https://x.com/v1/api/links/preview` → 404。

- [ ] **Step 2**：用 `adb logcat -s YDOC_RELAY:D` 看 app 实际请求的 URL（LinkPreviewClient 打了日志），对照是不是多了奇怪的前缀。

- [ ] **Step 3**：如果真是 baseUrl 里带了 `/v1`：要么让用户在设置里把 baseUrl 调成 host-only（推荐），要么在客户端加一层智能剥离（不推荐，容易误杀）。先走用户层面调整。

---

## Track A — App 内图片入口

### 背景

当前只有「系统分享图片 → Ydrop」一个入口（`MainActivity.handleShareIntent` 已接住）。App 内没法直接「选图 + 加字 + 保存」。本 Track 加两个入口：

1. **新建笔记编辑器**（`NewNoteEditorScreen`）加「加图」按钮 → 文字+图片一起保存成一条笔记
2. **编辑已有笔记**（`EditNoteCard`）加「加图」按钮 → 给已有笔记追加图片

两个入口都用 Android 13+ 的系统 PhotoPicker（`ActivityResultContracts.PickMultipleVisualMedia`），不需要 `READ_EXTERNAL_STORAGE` 权限。

### Task A.1：VM 层新增 saveQuickTextWithAttachments

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/ui/AppViewModel.kt`（在 `saveQuickText` 旁边加一个新方法）

- [ ] **Step 1**：在 `AppViewModel` 里加方法

找到 `saveQuickText` 函数（AppViewModel.kt 行 791），在它下面粘：

```kotlin
/**
 * 从 NewNoteEditor 保存：同时有文字 / 附件的情况都走这个路径。
 * 纯文字（attachments 为空）和原 saveQuickText 等价；有附件时走 createAttachmentNote，
 * 附件建完后逐张排 ImageAnalyzeWorker。
 */
fun saveNewNote(rawContent: String, attachments: List<com.ydoc.app.model.NoteAttachment>) {
    val content = rawContent.trim()
    if (content.isBlank() && attachments.isEmpty()) {
        _uiState.value = _uiState.value.copy(message = "先写点什么或加一张图吧。")
        return
    }
    viewModelScope.launch {
        val note = withContext(Dispatchers.IO) {
            if (attachments.isEmpty()) {
                container.noteRepository.createTextNote(
                    content = content,
                    category = NoteCategory.NOTE,
                    priority = NotePriority.MEDIUM,
                )
            } else {
                container.noteRepository.createAttachmentNote(
                    attachments = attachments,
                    hint = content,
                )
            }
        }
        // 每张图排一轮 OCR + Vision
        attachments.forEach { att ->
            com.ydoc.app.sync.ImageAnalyzeWorker.schedule(
                getApplication(), note.id, att.id,
            )
        }
        _uiState.value = _uiState.value.copy(
            showNewNoteEditor = false,
            isSaving = false,
            message = if (attachments.isEmpty()) "已保存到本地 inbox。"
                else "已保存带 ${attachments.size} 张图的笔记。",
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { syncIfEnabled(note) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.aiOrchestrator.maybeAnalyze(note.id, AiRunTrigger.TEXT_SAVE) }
        }
    }
}

/** 给已有笔记追加图片（EditNoteCard 的"加图"按钮调它）。 */
fun addAttachmentsToNote(noteId: String, attachments: List<com.ydoc.app.model.NoteAttachment>) {
    if (attachments.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        container.noteRepository.addAttachmentsToNote(noteId, attachments)
        attachments.forEach { att ->
            com.ydoc.app.sync.ImageAnalyzeWorker.schedule(
                getApplication(), noteId, att.id,
            )
        }
    }
}

/** EditNoteCard 的"×"按钮调它：从笔记里移除一条附件（不删本地文件，保持可恢复）。 */
fun removeAttachmentFromNote(noteId: String, attachmentId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        container.noteRepository.removeAttachmentFromNote(noteId, attachmentId)
    }
}
```

---

### Task A.2：NewNoteEditorScreen 加 PhotoPicker + 缩略图区

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt:1001-1095`（`NewNoteEditorScreen`）
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt:234-236`（`onSaveQuickText` 替换成 `onSaveNewNote`）

- [ ] **Step 1**：改 `NewNoteEditorScreen` 签名和实现

把整个 `NewNoteEditorScreen` 函数替换成（注意要保留原有的 BackHandler / confirm dialog 等逻辑）：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewNoteEditorScreen(
    onCancel: () -> Unit,
    onSave: (String, List<com.ydoc.app.model.NoteAttachment>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<com.ydoc.app.model.NoteAttachment>>(emptyList()) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 系统 PhotoPicker：Android 13+ 原生支持，更低版本 MediaStore 兼容层也能工作。
    val pickImages = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val store = context.appContainer.attachmentStore
            val added = uris.mapNotNull { uri ->
                runCatching { store.import(uri) }.getOrNull()
            }
            if (added.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    pendingAttachments = pendingAttachments + added
                }
            }
        }
    }

    val attemptCancel: () -> Unit = {
        if (content.isNotBlank() || pendingAttachments.isNotEmpty()) {
            showDiscardConfirm = true
        } else {
            onCancel()
        }
    }

    BackHandler { attemptCancel() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = attemptCancel) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                pickImages.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        ) {
                            Icon(
                                painter = painterResource(id = android.R.drawable.ic_menu_gallery),
                                contentDescription = "加图",
                            )
                        }
                        Button(
                            onClick = { onSave(content, pendingAttachments) },
                            enabled = content.isNotBlank() || pendingAttachments.isNotEmpty(),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text("保存", fontWeight = FontWeight.SemiBold)
                        }
                    },
                )
            },
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (pendingAttachments.isNotEmpty()) {
                    NoteAttachmentRow(
                        attachments = pendingAttachments,
                        onOpen = { /* 新建编辑器里不预览，点了没效果 */ },
                        onRemove = { att ->
                            // 本地还没 commit 到笔记，直接从 pending 移出 + 删磁盘文件
                            pendingAttachments = pendingAttachments.filterNot { it.id == att.id }
                            context.appContainer.attachmentStore.delete(att)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (content.isEmpty()) {
                        Text(
                            "想到什么就记下来…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("放弃这条记录？") },
            text = { Text("输入的内容和加的图都会被丢弃，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    // 丢弃时清理磁盘上已拷贝的附件文件
                    pendingAttachments.forEach { context.appContainer.attachmentStore.delete(it) }
                    pendingAttachments = emptyList()
                    onCancel()
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("继续编辑") }
            },
        )
    }
}
```

- [ ] **Step 2**：改调用点（行 402-405 附近）

原来：
```kotlin
if (state.showNewNoteEditor) {
    NewNoteEditorScreen(
        onCancel = onCloseNewNoteEditor,
        onSave = { text -> onSaveQuickText(text) },
    )
}
```

换成：
```kotlin
if (state.showNewNoteEditor) {
    NewNoteEditorScreen(
        onCancel = onCloseNewNoteEditor,
        onSave = { text, atts -> onSaveNewNote(text, atts) },
    )
}
```

- [ ] **Step 3**：改 YDocApp 函数签名和调用链

把 `onSaveQuickText: (String) -> Unit` 全部改成 `onSaveNewNote: (String, List<NoteAttachment>) -> Unit`。搜 `onSaveQuickText` 替换：

```bash
grep -rn "onSaveQuickText" app/src/main/java/com/ydoc/app/ui/YDocApp.kt
# 预计 3-5 处：参数声明、调用点
```

全部替换成 `onSaveNewNote` 并对应改签名。行 236 的 VM 绑定：

```kotlin
onSaveNewNote = viewModel::saveNewNote,
```

- [ ] **Step 4**：验证编译

```bash
./gradlew assembleDebug
# 期望：BUILD SUCCESSFUL
```

---

### Task A.3：EditNoteCard 加「加图」按钮和附件展示

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt:1097-1166`（`EditNoteCard`）
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt:270-305`（`EditNoteCard` 的调用点，传 noteId + attachments + onAddAttachments/onRemoveAttachment）

- [ ] **Step 1**：`EditDraft` 数据类加 `attachments` 字段

先查 `EditDraft` 在哪里定义：

```bash
grep -rn "data class EditDraft" app/src/main/java/com/ydoc/app
```

找到后加字段：
```kotlin
data class EditDraft(
    val noteId: String,
    val content: String,
    val category: NoteCategory,
    val priority: NotePriority,
    val tags: List<String>,
    val attachments: List<com.ydoc.app.model.NoteAttachment> = emptyList(),
)
```

顺便改 `AppViewModel.startEditing(note)` 初始化 `attachments = note.attachments`。

- [ ] **Step 2**：改 `EditNoteCard` 签名加两个回调

```kotlin
private fun EditNoteCard(
    editing: EditDraft,
    suggestedTags: List<String>,
    onUpdateContent: (String) -> Unit,
    onUpdateCategory: (NoteCategory) -> Unit,
    onUpdatePriority: (NotePriority) -> Unit,
    onUpdateTags: (List<String>) -> Unit,
    onAddAttachments: (List<com.ydoc.app.model.NoteAttachment>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
```

- [ ] **Step 3**：在 `Column` 里加按钮和附件行（放在"取消/保存"按钮 Row 之前）

```kotlin
val editContext = LocalContext.current
val editScope = rememberCoroutineScope()
val pickEditImages = rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5),
) { uris ->
    if (uris.isEmpty()) return@rememberLauncherForActivityResult
    editScope.launch(Dispatchers.IO) {
        val added = uris.mapNotNull {
            runCatching { editContext.appContainer.attachmentStore.import(it) }.getOrNull()
        }
        if (added.isNotEmpty()) {
            withContext(Dispatchers.Main) { onAddAttachments(added) }
        }
    }
}

if (editing.attachments.isNotEmpty()) {
    NoteAttachmentRow(
        attachments = editing.attachments,
        onOpen = { /* 编辑态点击不展开预览 */ },
        onRemove = { att ->
            editContext.appContainer.attachmentStore.delete(att)
            onRemoveAttachment(att.id)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

AssistChip(
    onClick = {
        pickEditImages.launch(
            androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
            ),
        )
    },
    label = { Text("+ 加图") },
)
```

- [ ] **Step 4**：改 `EditNoteCard` 的调用点，绑定 VM

```kotlin
EditNoteCard(
    editing = draft,
    suggestedTags = ...,
    onUpdateContent = viewModel::updateEditContent,
    onUpdateCategory = viewModel::updateEditCategory,
    onUpdatePriority = viewModel::updateEditPriority,
    onUpdateTags = viewModel::updateEditTags,
    onAddAttachments = { atts -> viewModel.addAttachmentsToNote(draft.noteId, atts) },
    onRemoveAttachment = { attId -> viewModel.removeAttachmentFromNote(draft.noteId, attId) },
    onSave = viewModel::saveEditedNote,
    onCancel = viewModel::cancelEditing,
)
```

- [ ] **Step 5**：在 `AppViewModel` 里让 `startEditing` 带上 attachments（这样 draft 初始化就能拿到），`saveEditedNote` 不用动（因为附件的增删已经走 `addAttachmentsToNote / removeAttachmentFromNote` 的即时写库，不经过 draft）

```kotlin
// 找到 AppViewModel.startEditing，把 EditDraft 创建里补：
EditDraft(
    noteId = note.id,
    content = note.content,
    category = note.category,
    priority = note.priority,
    tags = note.tags,
    attachments = note.attachments,
)
```

并且在 `observeNotes` / note 刷新时，如果正在编辑该 note，用最新的 `attachments` 更新 draft。找 state 合并处（通常 `flow.collect { notes -> ... }`）加：

```kotlin
val editing = _uiState.value.editingNote
if (editing != null) {
    val fresh = notes.firstOrNull { it.id == editing.noteId }
    if (fresh != null && fresh.attachments != editing.attachments) {
        _uiState.value = _uiState.value.copy(
            editingNote = editing.copy(attachments = fresh.attachments),
        )
    }
}
```

- [ ] **Step 6**：编译验证

```bash
./gradlew assembleDebug
# 期望：BUILD SUCCESSFUL
```

---

### Task A.4：commit

**Files:** staged = 上面改动的 YDocApp.kt、AppViewModel.kt

- [ ] **Step 1**：检查 staged 范围（**仍然不要 add `DefaultConfig.kt` / `SettingsStore.kt`**）

```bash
git status --short | grep "^ M app/"
# 预期改动清单：
#  M app/src/main/java/com/ydoc/app/ui/YDocApp.kt
#  M app/src/main/java/com/ydoc/app/ui/AppViewModel.kt
#  M app/src/main/java/com/ydoc/app/model/AppUiModels.kt   # EditDraft 所在文件，按实际情况调整
```

- [ ] **Step 2**：精确 add

```bash
git add app/src/main/java/com/ydoc/app/ui/YDocApp.kt \
        app/src/main/java/com/ydoc/app/ui/AppViewModel.kt \
        <EditDraft 所在文件的精确路径>
```

- [ ] **Step 3**：commit

```bash
git commit -m "feat(android): 新建编辑器 + 编辑卡加图片入口（PhotoPicker）"
```

---

### Task A.5：真机回归

- [ ] 装新 APK：`./gradlew installDebug`
- [ ] 点主界面「写一个」→ 进编辑器 → 点顶栏相册图标 → PhotoPicker 能选 1-5 张图 → 返回后缩略图行出现
- [ ] 输入文字 + 保留 2 张图 → 点保存 → inbox 出现新笔记（标题 = 文字首行）
- [ ] 展开新笔记 → 缩略图显示 → 「i」按钮 10-30 秒后能看到 OCR + AI 描述
- [ ] 点已有笔记 → 编辑卡 → 点「+ 加图」→ 选图 → 缩略图立即出现 → 点 × 能删
- [ ] 取消编辑对话框的「放弃」路径：pending 图片的磁盘文件清理掉（`ls /data/data/com.ydoc.app/files/attachments/` 对比）
- [ ] 系统分享路径（已有功能）不受影响，继续能从相册分享进来

---

## 执行顺序

| 顺序 | Track | 耗时 | 阻塞什么 |
|------|-------|------|---------|
| 1 | **B.1 诊断** | 1 分钟 | 确认病因 |
| 2 | **B.2+B.3 部署 relay** | 10-15 分钟 | 解决 404 |
| 3 | **B.4 UI 友好提示** | 10 分钟（Mac 能编译）| 下次出现同类问题时用户看得懂 |
| 4 | **A.1-A.3 App 内图片入口** | 40-60 分钟 | 新功能，user 体验提升 |
| 5 | **A.4+A.5 commit + 回归** | 30 分钟 | 合入 |

Track B 独立可合入；Track A 依赖 B.4 的错误提示改进（否则老 relay 上看 Android 用户会遇到丑 404）——所以强烈建议 B 全做完再开 A。

---

## Self-Review

**1. Spec coverage**:
- Q1「新版本如何记录图片」→ A.1/A.2/A.3 三个 task 覆盖了编辑器加图 + 编辑卡加图 + VM 方法 + 磁盘清理。
- Q2「链接预览失败返回 404」→ B.1 诊断 + B.2/B.3 部署 + B.4 友好提示。部署问题和 UI 提示都覆盖了。

**2. Placeholder scan**: 没有 TBD / TODO / 「add appropriate handling」之类；所有步骤要么是具体代码块要么是明确命令 + 预期输出。B.3 Step 1 的 NAS 路径是必须由用户提供的变量，不是 placeholder。

**3. Type consistency**:
- `saveNewNote(String, List<NoteAttachment>)` 在 VM 和 UI 里签名一致。
- `addAttachmentsToNote(String, List<NoteAttachment>)` / `removeAttachmentFromNote(String, String)` 在 VM、EditNoteCard 回调里命名一致。
- `EditDraft.attachments` 新字段在所有初始化/拷贝点都覆盖了。
- `NoteAttachmentRow(attachments, onOpen, onRemove?, modifier)` 签名和现有 `ui/components/NoteAttachmentRow.kt` 一致。
