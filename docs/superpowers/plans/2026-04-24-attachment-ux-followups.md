# 附件 & 卡片交互跟进修复 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修掉附件「永久转圈 + 打不开」的阻塞 bug；补上快速记录栏的加图入口；给主界面卡片类型一个快捷切换。

**Architecture:**
- A.1：NoteAttachment 加 `analyzedAt` 时间戳字段，Worker 每条路径都填，UI 只看它判断是否完成。解决"图里没文字 + vision 返回空 description"导致转圈的 bug。
- A.2：废掉外部 `ACTION_VIEW` 跳转，改用 Compose `Dialog` + Coil `AsyncImage` 做 App 内全屏预览。不依赖外部 app 选择器，摆脱"点了没反应 / 打开浏览器加载不出来"的 UX 黑洞。
- B.1：`QuickCaptureBar` 的「写一个」区域右上角加个小相册按钮，点击直接 PhotoPicker → 空文本新建图片笔记。
- B.2：`NoteCardV2` 的 category StatusPill 外包一层 Box + DropdownMenu，主界面长按/点击 pill 就能切类型，VM 加 `changeNoteCategory(noteId, category)`。

**Tech Stack:** Jetpack Compose / Material3 / Coil / WorkManager / Room JSON column。

---

## Phase A — 阻塞 bug（必做）

### Task A.1：修附件永久转圈（问题 2）

**根因**：`NoteAttachmentRow.kt:94-96` 用「`ocrText.isBlank && aiDescription.isBlank && analyzeError == null`」判断 analyzing。当图里没文字 + vision 返回 `description=""` + `error=null`（relay 端 provider 调成功但回了空）三条件同时满足时，永久转圈。

**修法**：给 `NoteAttachment` 加 `analyzedAt: Long = 0` 时间戳字段，Worker 每条 return 路径都填。UI 只看 `analyzedAt == 0L`。

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/model/NoteAttachment.kt`
- Modify: `app/src/main/java/com/ydoc/app/sync/ImageAnalyzeWorker.kt`
- Modify: `app/src/main/java/com/ydoc/app/ui/components/NoteAttachmentRow.kt:94-96`

- [ ] **Step 1：NoteAttachment 加字段**

改 `app/src/main/java/com/ydoc/app/model/NoteAttachment.kt`，在 `createdAt` 下面加：

```kotlin
@Serializable
data class NoteAttachment(
    val id: String,
    val type: String = "IMAGE",
    val localPath: String,
    val publicUri: String? = null,
    val remoteUrl: String? = null,
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val thumbPath: String? = null,
    val ocrText: String = "",
    val aiDescription: String = "",
    val aiStructuredJson: String = "{}",
    val analyzeError: String? = null,
    val createdAt: Long = 0,
    // Vision worker 完成（不管成功/失败/relay 未配等 early return）就填当前时间戳。
    // UI 只看这个字段判断"还在分析"——避免"vision 返回空 description + error 为 null"导致的永久转圈。
    val analyzedAt: Long = 0,
)
```

序列化默认值兼容旧 JSON，已有 attachment 解码时 `analyzedAt` 会回落到 0，下次 Vision 跑完填。**不需要** Room migration。

- [ ] **Step 2：ImageAnalyzeWorker 在所有 return 路径填 analyzedAt**

改 `app/src/main/java/com/ydoc/app/sync/ImageAnalyzeWorker.kt`，关键改四处：

**2a. 加 helper 方法**

在 class 内部（companion object 前）加：

```kotlin
private suspend fun markAnalyzed(noteId: String, attachmentId: String, error: String? = null) {
    applicationContext.appContainer.noteRepository.updateAttachment(noteId, attachmentId) {
        it.copy(
            analyzedAt = System.currentTimeMillis(),
            analyzeError = error ?: it.analyzeError,
        )
    }
}
```

**2b. "relay 未配" early return 之前填**

找到：
```kotlin
if (!relay.enabled || relay.baseUrl.isBlank() || relay.token.isBlank()) {
    AppLogger.relay("ImageAnalyzeWorker: relay not configured, skip vision for $attachmentId")
    return Result.success()
}
```

改成：
```kotlin
if (!relay.enabled || relay.baseUrl.isBlank() || relay.token.isBlank()) {
    AppLogger.relay("ImageAnalyzeWorker: relay not configured, skip vision for $attachmentId")
    markAnalyzed(noteId, attachmentId, error = "relay 未配置，无法做 Vision 分析")
    return Result.success()
}
```

**2c. 成功分支填**

找到：
```kotlin
result.onSuccess { resp ->
    container.noteRepository.updateAttachment(noteId, attachmentId) {
        it.copy(
            remoteUrl = resp.remoteUrl.ifBlank { it.remoteUrl },
            aiDescription = resp.description.ifBlank { it.aiDescription },
            aiStructuredJson = """{"keywords":${jsonArray(resp.keywords)},""" +
                """"actionable_items":${jsonArray(resp.actionableItems)},""" +
                """"dates":${jsonArray(resp.dates)}}""",
            analyzeError = resp.error,
        )
    }
    AppLogger.relay("ImageAnalyzeWorker: ok note=$noteId att=$attachmentId desc_len=${resp.description.length}")
}
```

改成（补 `analyzedAt`）：
```kotlin
result.onSuccess { resp ->
    container.noteRepository.updateAttachment(noteId, attachmentId) {
        it.copy(
            remoteUrl = resp.remoteUrl.ifBlank { it.remoteUrl },
            aiDescription = resp.description.ifBlank { it.aiDescription },
            aiStructuredJson = """{"keywords":${jsonArray(resp.keywords)},""" +
                """"actionable_items":${jsonArray(resp.actionableItems)},""" +
                """"dates":${jsonArray(resp.dates)}}""",
            analyzeError = resp.error,
            analyzedAt = System.currentTimeMillis(),
        )
    }
    AppLogger.relay("ImageAnalyzeWorker: ok note=$noteId att=$attachmentId desc_len=${resp.description.length}")
}
```

**2d. 失败分支填（包括永久失败和可重试失败）**

找到 `onFailure = { e -> ... }` 块，改成：

```kotlin
onFailure = { e ->
    val retryable = isRetryable(e)
    AppLogger.error(
        "YDOC_RELAY",
        "ImageAnalyzeWorker ${if (retryable) "retry" else "giveup"} $attachmentId: ${e.message}",
        e,
    )
    container.noteRepository.updateAttachment(noteId, attachmentId) {
        it.copy(
            analyzeError = (e.message ?: "unknown").take(160),
            // retry 的时候不填 analyzedAt，让 UI 继续转圈；永久失败就终结 analyzing 状态。
            analyzedAt = if (retryable) it.analyzedAt else System.currentTimeMillis(),
        )
    }
    if (retryable) Result.retry() else Result.failure()
},
```

**2e. "imageFile missing" early return 也要填**

找到：
```kotlin
if (!imageFile.exists()) {
    AppLogger.relay("ImageAnalyzeWorker: local file missing $attachmentId")
    return Result.success()
}
```

改成：
```kotlin
if (!imageFile.exists()) {
    AppLogger.relay("ImageAnalyzeWorker: local file missing $attachmentId")
    markAnalyzed(noteId, attachmentId, error = "本地图片文件丢失")
    return Result.success()
}
```

- [ ] **Step 3：UI 只看 analyzedAt**

改 `app/src/main/java/com/ydoc/app/ui/components/NoteAttachmentRow.kt:94-96`：

```kotlin
// 永久转圈 bug：原条件在"图里没文字 + vision 返回空 description + error null"时永远为 true。
// 现在只看 analyzedAt 是否被 Worker 填过——Worker 在 success/failure/relay-not-configured 三条路径都填。
val analyzing = attachment.analyzedAt == 0L && attachment.analyzeError == null
```

- [ ] **Step 4：编译验证**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

期望：`BUILD SUCCESSFUL`。

- [ ] **Step 5：commit**

```bash
git add app/src/main/java/com/ydoc/app/model/NoteAttachment.kt \
        app/src/main/java/com/ydoc/app/sync/ImageAnalyzeWorker.kt \
        app/src/main/java/com/ydoc/app/ui/components/NoteAttachmentRow.kt
git commit -m "fix(attachment): 附件加 analyzedAt 字段，Worker 每条路径填，UI 不再永久转圈"
```

---

### Task A.2：App 内全屏预览替代 ACTION_VIEW（问题 3）

**根因**：`ui/YDocApp.kt:2847-2867` 用 `startActivity(ACTION_VIEW, image/*)` 跳外部 app。Android 会弹"用什么打开"选择器，用户可能选到浏览器（加载自签名 HTTPS 会 hang），或者**根本没 app 接住**时静默失败。FileProvider 修对了只是前置条件。

**修法**：App 内自建全屏预览 Dialog，Coil 加载本地 file:// 或 relay https://，用户点击任意处关闭。不再依赖系统 ACTION_VIEW。

**Files:**
- Create: `app/src/main/java/com/ydoc/app/ui/components/FullImagePreviewDialog.kt`
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt:2846-2871`（NoteCardV2 里 attachments onOpen 回调）

- [ ] **Step 1：新建 FullImagePreviewDialog**

写到 `app/src/main/java/com/ydoc/app/ui/components/FullImagePreviewDialog.kt`：

```kotlin
package com.ydoc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ydoc.app.model.NoteAttachment
import java.io.File

/**
 * 全屏图片预览 Dialog。
 * - 优先用 remoteUrl（有的话 Coil 能直接加载 HTTPS）；否则用本地 localPath（File 对象会被 Coil 按 file:// 读）。
 * - 点背景 / 右上角关闭按钮 关闭。
 * - 底部显示 OCR 文字（如果有），方便用户复用识别结果。
 *
 * 不依赖外部看图器 app——上一版用 Intent.ACTION_VIEW 常常被浏览器接走、或选择器弹不出来。
 */
@Composable
fun FullImagePreviewDialog(
    attachment: NoteAttachment,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,  // 撑满屏幕
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val model: Any = attachment.remoteUrl?.takeIf { it.isNotBlank() }
            ?: File(attachment.localPath)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
                if (attachment.ocrText.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .heightIn(max = 200.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Text(
                            "OCR：${attachment.ocrText}",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(40.dp),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}
```

注意：`heightIn` 和 `Modifier.size` / `Modifier.height` 是 layout import。最上面加 `import androidx.compose.foundation.layout.heightIn`。

- [ ] **Step 2：NoteCardV2 替换 onOpen 实现**

改 `app/src/main/java/com/ydoc/app/ui/YDocApp.kt`，找 NoteAttachmentRow 那块（大约 `if (note.attachments.isNotEmpty())` 块内）：

**先在 NoteCardV2 顶部（和 `var expanded by remember` 附近）加状态：**

找到：
```kotlin
    var expanded by remember(note.id) { mutableStateOf(false) }
```

在它后面加：
```kotlin
    var previewAttachment by remember(note.id) { mutableStateOf<com.ydoc.app.model.NoteAttachment?>(null) }
```

**然后把 NoteAttachmentRow 的 onOpen 改成设 state，而不是 startActivity：**

找到：
```kotlin
                if (note.attachments.isNotEmpty()) {
                    val attCtx = LocalContext.current
                    NoteAttachmentRow(
                        attachments = note.attachments,
                        onOpen = { att ->
                            runCatching {
                                val uri = att.remoteUrl?.takeIf { it.isNotBlank() }
                                    ?.let { android.net.Uri.parse(it) }
                                    ?: androidx.core.content.FileProvider.getUriForFile(
                                        attCtx,
                                        "${attCtx.packageName}.fileprovider",
                                        java.io.File(att.localPath),
                                    )
                                attCtx.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        uri,
                                    )
                                        .setDataAndType(uri, att.mime)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        },
                        onRemove = null,
                    )
                }
```

改成：
```kotlin
                if (note.attachments.isNotEmpty()) {
                    NoteAttachmentRow(
                        attachments = note.attachments,
                        onOpen = { att -> previewAttachment = att },
                        onRemove = null,
                    )
                }
```

**最后在 Card 的 Column 关闭大括号之后（还在 NoteCardV2 函数体内），挂 Dialog：**

找 Card 的结束 `}`（`}` 之前就是 NoteCardV2 函数体结束的 `}`），在它后面（Card 之外但还在 NoteCardV2 内）加：

```kotlin
    previewAttachment?.let { att ->
        com.ydoc.app.ui.components.FullImagePreviewDialog(
            attachment = att,
            onDismiss = { previewAttachment = null },
        )
    }
```

- [ ] **Step 3：删 attCtx（已经不再用 startActivity）**

上一步已把整个 runCatching 块换成了 `previewAttachment = att`。原来那行 `val attCtx = LocalContext.current` 已经没用，搜一下移除它。

```bash
grep -n "val attCtx" app/src/main/java/com/ydoc/app/ui/YDocApp.kt
# 期望：只有一行或零行（如果零行说明 Step 2 已顺带删了）
```

如果还在：删掉这一行。

- [ ] **Step 4：编译**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5：commit**

```bash
git add app/src/main/java/com/ydoc/app/ui/components/FullImagePreviewDialog.kt \
        app/src/main/java/com/ydoc/app/ui/YDocApp.kt
git commit -m "fix(attachment): 改 App 内 Dialog 预览，废弃 ACTION_VIEW 跳外部看图器"
```

---

## Phase B — UX 增强

### Task B.1：QuickCaptureBar 加加图入口（问题 1）

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt`（QuickCaptureBar 约行 800-932，加第三个按钮）
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt`（QuickCaptureBar 的调用点传回调）

**Design**：「写一个」容器右上角叠一个小 IconButton（相册图标），不另起 weight 占一列——保持现有两栏 72dp 布局视觉。点小图标 → PhotoPicker → 直接调 `onSaveNewNote("", atts)` 建笔记；点容器其它位置还是进入 NewNoteEditor。

- [ ] **Step 1：QuickCaptureBar 接受新回调 `onPickImages`**

改 QuickCaptureBar 签名（约行 796）：

```kotlin
@Composable
private fun QuickCaptureBar(
    recording: RecordingUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onOpenTextEditor: () -> Unit,
    onPickImages: (List<NoteAttachment>) -> Unit,
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 2：PhotoPicker launcher**

在函数体开头加：

```kotlin
    val captureContext = LocalContext.current
    val captureScope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        captureScope.launch {
            val added = withContext(Dispatchers.IO) {
                val store = captureContext.appContainer.attachmentStore
                uris.mapNotNull { uri -> runCatching { store.import(uri) }.getOrNull() }
            }
            if (added.isNotEmpty()) onPickImages(added)
        }
    }
```

- [ ] **Step 3：「写一个」容器右上角叠个图标按钮**

找到「写一个」Surface 的代码：

```kotlin
if (isIdle) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(72.dp)
            .clickable(onClick = onOpenTextEditor),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.ic_menu_edit),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "写一个",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

把外层 `Surface` 包进 `Box`，Box 右上角叠一个 IconButton：

```kotlin
if (isIdle) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(72.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onOpenTextEditor),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_edit),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "写一个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(32.dp),
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.ic_menu_gallery),
                contentDescription = "加图",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
```

- [ ] **Step 4：QuickCaptureBar 调用点传回调**

找到 `QuickCaptureBar(...)` 的调用（大约行 755-765），加 `onPickImages`：

```kotlin
QuickCaptureBar(
    recording = state.recording,
    onStartRecording = onStartRecording,
    onStopRecording = onStopRecording,
    onCancelRecording = onCancelRecording,
    onOpenTextEditor = onOpenNewNoteEditor,
    onPickImages = { atts -> onSaveNewNote("", atts) },
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .fillMaxWidth(),
)
```

`onSaveNewNote` 已经支持 `attachments` 参数；这里直接调用即可，VM 会走 `createAttachmentNote` 分支。

- [ ] **Step 5：编译**

```bash
./gradlew assembleDebug
```

- [ ] **Step 6：commit**

```bash
git add app/src/main/java/com/ydoc/app/ui/YDocApp.kt
git commit -m "feat(quick-capture): 快速记录栏加加图按钮，直出图片笔记"
```

---

### Task B.2：主界面快速切换卡片类型（问题 4）

**Design**：NoteCardV2 的 category StatusPill 现在是纯文本。把它包进 `Box` + `clickable` + `DropdownMenu`。点击 pill 弹菜单选类型 → 立即写库。优先级 pill **不改**（避免 UI 被塞爆）。不在多选态下触发。

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/ui/AppViewModel.kt`（加 `changeNoteCategory`）
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt`（NoteCardV2 的 category pill + 传回调）

- [ ] **Step 1：VM 加 changeNoteCategory**

在 `AppViewModel.kt` 里加（选 `setPinned` 附近合适位置）：

```kotlin
    fun changeNoteCategory(noteId: String, category: NoteCategory) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val note = container.noteRepository.getNote(noteId) ?: return@launch
                if (note.category == category) return@launch
                val updated = note.copy(
                    category = category,
                    colorToken = defaultColorFor(category, note.priority),
                )
                container.noteRepository.saveNote(updated)
                runCatching { syncIfEnabled(updated) }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "切换类型失败。")
            }
        }
    }
```

注意 `defaultColorFor` 和 `saveNote` 已经存在。`saveNote` 会更新 updatedAt 和 status=LOCAL_ONLY，触发 WebDAV 重推。这是**期望行为**——类型变化是真实内容变化，应该同步。

- [ ] **Step 2：YDocApp 透传回调**

和 `onSaveNewNote` / `onAddAttachmentsToNote` 的传递路径一致。

**2a. 行 236 附近加绑定：**

找到：
```kotlin
        onSaveNewNote = viewModel::saveNewNote,
        onAddAttachmentsToNote = viewModel::addAttachmentsToNote,
        onRemoveAttachmentFromNote = viewModel::removeAttachmentFromNote,
```

在后面加：
```kotlin
        onChangeNoteCategory = viewModel::changeNoteCategory,
```

**2b. YDocScreen 签名加参数（约行 325 那段）：**

```kotlin
    onSaveNewNote: (String, List<NoteAttachment>) -> Unit,
    onAddAttachmentsToNote: (String, List<NoteAttachment>) -> Unit,
    onRemoveAttachmentFromNote: (String, String) -> Unit,
    onChangeNoteCategory: (String, NoteCategory) -> Unit,
```

**2c. 在 NoteCardV2 的调用点（搜 `NoteCardV2(` 找到传参处）加：**

```kotlin
onChangeCategory = { newCategory -> onChangeNoteCategory(note.id, newCategory) },
```

放在 `onEdit` / `onArchive` 附近即可。

**2d. NoteCardV2 函数签名加参数（约行 2688）：**

```kotlin
private fun NoteCardV2(
    note: Note,
    ...
    onEdit: () -> Unit,
    onChangeCategory: (NoteCategory) -> Unit,
    onArchive: () -> Unit,
    ...
```

- [ ] **Step 3：改 category StatusPill 为可点击 dropdown**

找到 `ui/YDocApp.kt:2799` 附近：

```kotlin
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(label = note.category.toChinese(), color = accent.copy(alpha = 0.18f))
                StatusPill(label = note.priority.toChinese(), color = MaterialTheme.colorScheme.secondaryContainer)
                ...
```

把第一个 StatusPill 替换成 Box + DropdownMenu（第二个 priority pill 不动）：

```kotlin
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // category pill 可点击切类型——主界面省一次"进编辑卡改类型再保存"
                var categoryMenuOpen by remember(note.id) { mutableStateOf(false) }
                Box {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.18f),
                        modifier = Modifier.clickable(enabled = !selectionMode) {
                            categoryMenuOpen = true
                        },
                    ) {
                        Text(
                            text = note.category.toChinese(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false },
                    ) {
                        NoteCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        cat.toChinese() + if (cat == note.category) "（当前）" else "",
                                        fontWeight = if (cat == note.category) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    categoryMenuOpen = false
                                    if (cat != note.category) onChangeCategory(cat)
                                },
                            )
                        }
                    }
                }
                StatusPill(label = note.priority.toChinese(), color = MaterialTheme.colorScheme.secondaryContainer)
                ...
```

注意：保留下面原来的 `if (isVoiceNote) StatusPill(...)` 和 `if (note.tags.isNotEmpty()) { ... }` 等逻辑，不动。

- [ ] **Step 4：编译**

```bash
./gradlew assembleDebug
```

期望：`BUILD SUCCESSFUL`。编译失败最常见原因是第二处 NoteCardV2 调用——如果 YDocApp 里有多个 NoteCardV2 入口（归档 / 回收站各一份），每个都要加 `onChangeCategory =`。如果报 `No value passed for parameter`，搜 `NoteCardV2(` 把每处都补齐。

- [ ] **Step 5：commit**

```bash
git add app/src/main/java/com/ydoc/app/ui/AppViewModel.kt \
        app/src/main/java/com/ydoc/app/ui/YDocApp.kt
git commit -m "feat(inbox): 主界面 category pill 支持点开 dropdown 直接切类型"
```

---

## 执行顺序 & 验收

| 顺序 | Task | 耗时 | 优先级 |
|------|------|------|--------|
| 1 | A.1 转圈 bug | ~15 分钟 | 必做（阻塞） |
| 2 | A.2 全屏预览 | ~20 分钟 | 必做（阻塞） |
| 3 | B.1 快速栏加图 | ~15 分钟 | 高（用户主诉） |
| 4 | B.2 卡片切类型 | ~25 分钟 | 中 |

**真机回归清单**（连手机 `adb logcat -v time YDOC_RELAY:D YDOC_OCR:D *:E`）：

- [ ] 保存一张纯风景图（没文字）→ 20 秒内缩略图右下角转圈消失，点「i」按钮能看到 AI 描述或「识别失败」提示（二选一，不会永久转圈）
- [ ] 断网保存一张截图 → OCR 文字立即出现，Vision 转圈 → 连回网络 Worker 自动补跑 → 转圈消失
- [ ] 点任意附件缩略图 → App 内弹出黑底全屏预览，图片 Fit 显示，OCR 文字（如有）显示在下方。点图或右上角 × 关闭
- [ ] 主界面底部「写一个」右上角有相册图标 → 点一下跳 PhotoPicker → 选 2 张 → 返回主界面 inbox 立即出现一条新图片笔记（没有文字）
- [ ] 任意笔记的 category pill（"普通" / "待办" / "任务" / "提醒"）→ 点一下弹菜单 → 选另一个类型 → 菜单关闭 → pill 文字即时变化 + 颜色 dot 跟着换
- [ ] 多选模式下点 pill 不应该弹菜单（应该照常切换选中态，不拦截）

---

## Self-Review

**1. Spec coverage**：
- 问题 1（快速记录栏加图）→ B.1 整组 task
- 问题 2（永久转圈）→ A.1 整组（analyzedAt 字段 + 3 个 Worker return 路径 + UI 判断）
- 问题 3（打不开 / 转圈）→ A.2 整组（FullImagePreviewDialog + NoteCardV2 替换）
- 问题 4（主界面切类型）→ B.2 整组（VM 方法 + 4 层回调传递 + UI dropdown）

**2. Placeholder scan**：已检查无 TBD / 「add appropriate handling」类。所有 Step 含具体代码块或命令。

**3. Type consistency**：
- `NoteAttachment.analyzedAt: Long = 0` — 在 Model、Worker、UI 三处名字和类型一致
- `changeNoteCategory(noteId: String, category: NoteCategory)` — VM 方法名和参数顺序，调用点一致
- `onChangeNoteCategory: (String, NoteCategory) -> Unit` — YDocApp 层回调签名匹配 VM
- `onChangeCategory: (NoteCategory) -> Unit` — NoteCardV2 内层回调（只传 category，noteId 在调用点闭包绑定）
- `FullImagePreviewDialog(attachment, onDismiss)` — Dialog 签名和 NoteCardV2 调用点一致
- `onPickImages: (List<NoteAttachment>) -> Unit` — QuickCaptureBar 签名和调用点传 `{ atts -> onSaveNewNote("", atts) }` 匹配

**4. 潜在坑**：
- A.1 里 `analyzedAt` 对已存在的 attachment 默认值为 0，UI 会对老数据转圈。但 Worker 下次扫到就会填好（OCR Worker 完成不填 analyzedAt，Vision Worker 完成才填——意味着老数据永远要等 Vision Worker 跑一次才会停转圈）。如果 relay 未配，下次保存 / 启动不会自动重排 Vision Worker，老数据会一直转圈。**缓解**：用户如果 relay 一直没配，建议加个「重新识别」入口——但这超出本 plan 范围，留到 next iteration。
- B.2 的 `saveNote` 会把 note 推回 LOCAL_ONLY，triggered WebDAV 重推。用户频繁切类型会引起同步抖动。可接受，因为类型变化是真的内容变化。
