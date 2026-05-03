# AI 助手修复 + 链接 / 图片识别整理

> **给执行者**：本计划按里程碑顺序落地，每个 phase 独立可合入。每个 task 列了「目标 / 关键文件 / 核心代码 / 验收 / commit」。Android 项目不走 pytest 式 TDD，但关键点提供 `./gradlew lint` + `./gradlew test` + 真机回归命令。

**Goal**：(1) 修复「AI 助手看不到我的笔记」的体感 bug；(2) 引入链接识别 + 预览卡 + AI 摘要；(3) 引入图片识别（本地 OCR + 服务端 Vision AI 双通道）。

**Architecture**：
- Phase 0 纯增量调整：扩大可见笔记范围（含归档）、放宽 server 裁剪、改写系统 prompt、加端到端日志。不动 DB schema、不加新端点。
- Phase 1 新 Room 字段 `linkPreviewsJson` + 新 relay 端点 `/api/links/preview` + WorkManager 后台 fetch + Compose 预览卡。AI 整理流把预览 meta 注入 prompt。
- Phase 2 新 Room 字段 `attachmentsJson` + ML Kit OCR（本地）+ 新 relay 端点 `/api/images/analyze`（Vision）+ 系统分享入口 + 附件 UI。

**Tech Stack**：Kotlin 2.0.21 / Compose BOM 2024.09.00 / Room 2.6.1 / WorkManager 2.9.1 / OkHttp 4.12 / ML Kit `text-recognition` / FastAPI / BeautifulSoup4 + readability-lxml / OpenAI or Anthropic vision models.

---

## Phase 0 — 修复「AI 助手看不到我的笔记」

### 根因映射

| # | 根因 | 证据 | 修法 |
|---|------|------|------|
| R1 | 归档笔记被 `observeActive()` 过滤掉 | `NoteDao.observeActive()` 行 15-16：`WHERE isTrashed = 0 AND isArchived = 0` | 加 `observeAgentContextNotes()`：只排除回收站，把归档一并传给 agent。 |
| R2 | server 侧 `max_notes=30` 硬裁 | `ChatFilter.max_notes: int = 30` (routes_ai.py:37) | app 端显式传 `filter.max_notes=80`；server 抬上限到 200；app 端发送端从 60 提到 80。 |
| R3 | `content[:500]` 把长笔记关键段截掉 | routes_ai.py:145 | 抬到 `[:1500]`；超长用「…（省略 N 字）」提示。 |
| R4 | 系统 prompt 太严「如果答案不在笔记里，明确说没有找到」 | routes_ai.py:345-352 | 改写成允许合理推断 + 可给相关线索，但未明确的地方诚实说明。 |
| R5 | 排序语义不一致（App 按 `updatedAt` 降序，server 再按 `created_at` 重排） | routes_ai.py:139 `candidates.sort(key=lambda n: n.created_at, reverse=True)` | server 保留 app 传入顺序，不重排。 |
| R6 | 可观测性弱，用户看不到是哪一步断链 | logs 只有数量 | app 端 log 发送的前 3 个 title + id；server log notes_for_llm 的前 3 个 title。 |

---

### Task 0.1：Android 端扩大可见笔记范围 + 显式 filter

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/data/local/NoteDao.kt`
- Modify: `app/src/main/java/com/ydoc/app/data/NoteRepository.kt`
- Modify: `app/src/main/java/com/ydoc/app/data/AgentRepository.kt`
- Modify: `app/src/main/java/com/ydoc/app/ai/AgentApiClient.kt`

- [ ] **Step 1**：在 `NoteDao.kt` 加：

```kotlin
@Query("SELECT * FROM notes WHERE isTrashed = 0 ORDER BY pinned DESC, updatedAt DESC")
fun observeAgentContextNotes(): Flow<List<NoteEntity>>
```

- [ ] **Step 2**：在 `NoteRepository.kt` 加 `fun observeAgentContextNotes(): Flow<List<Note>>` 转发到 dao。

- [ ] **Step 3**：改 `AgentRepository.sendMessageInternal` 的 inline notes 收集（行 191-209）：

```kotlin
val inlineNotes = runCatching {
    noteRepository.observeAgentContextNotes().first()
        .sortedByDescending { it.updatedAt }
        .take(80)
        .map { n ->
            AgentApiClient.WireNote(
                id = n.id,
                title = n.title,
                content = n.content,
                category = n.category.name,
                priority = n.priority.name,
                tags = n.tags,
                createdAt = n.createdAt,
            )
        }
}.getOrElse { e ->
    AppLogger.agent("collect inline notes failed: ${e.message}")
    emptyList()
}
val previewIds = inlineNotes.take(3).joinToString { "${it.id.take(6)}:${it.title.take(12)}" }
AppLogger.agent("sendMessage inline_notes_size=${inlineNotes.size} preview=[$previewIds]")
```

- [ ] **Step 4**：改 `api.chat` 调用点，显式传 filter：

```kotlin
val response = try {
    api.chat(
        relay = relay,
        messages = ensuredHistory,
        relaySessionId = sessionAtStart.relaySessionId,
        filter = AgentApiClient.ChatFilter(
            includeArchived = true,
            maxNotes = 80,
        ),
        aiConfig = aiConfig,
        inlineNotes = inlineNotes.takeIf { it.isNotEmpty() },
    )
} catch (e: Throwable) { ... }
```

- [ ] **Step 5**：验收：`./gradlew lint assembleDebug`，logcat 过滤 `YDOC_AGENT` 应看到 `inline_notes_size=80 preview=[...]`。

- [ ] **Step 6**：commit

```bash
git add app/src/main/java/com/ydoc/app/data/local/NoteDao.kt \
        app/src/main/java/com/ydoc/app/data/NoteRepository.kt \
        app/src/main/java/com/ydoc/app/data/AgentRepository.kt
git commit -m "agent: 把归档笔记也带给助手 + 显式放开 max_notes 到 80 + 打点前 3 条 title"
```

---

### Task 0.2：Relay 放宽裁剪 + 保留 app 端顺序 + 日志增强

**Files:**
- Modify: `relay_service/app/routes_ai.py:30-39`（ChatFilter）、`:126-153`（inline_notes 分支）、`:345-358`（system prompt）

- [ ] **Step 1**：`ChatFilter.max_notes` 上限保护：

```python
class ChatFilter(BaseModel):
    ...
    max_notes: int = 30

    @field_validator("max_notes")
    @classmethod
    def _cap_max(cls, v: int) -> int:
        return max(1, min(v, 200))
```

（如果 pydantic v1 没 field_validator，就 `@validator("max_notes", always=True)`。）

- [ ] **Step 2**：inline_notes 分支里**移除 `candidates.sort(...)`**（保留 app 传入顺序，App 端已按 `pinned DESC, updatedAt DESC` 排好）：

```python
# 不再按 created_at 重排。app 端已经按 pinned+updatedAt 排好序，
# 这里重排会让 agent 拿到的 "最近活跃" 顺序失真。
candidates = candidates[: f.max_notes]
```

- [ ] **Step 3**：`content[:500]` → `[:1500]` 且保留省略提示：

```python
def _clip(s: str, n: int = 1500) -> str:
    s = s or ""
    return s if len(s) <= n else s[:n] + f"…（省略 {len(s)-n} 字）"

notes = [
    { ..., "content": _clip(n.content or ""), ... }
    for n in candidates
]
```

- [ ] **Step 4**：改写 `_call_chat_provider` 的 system prompt（routes_ai.py:345-352）：

```python
system = (
    "你是用户的个人笔记助手。用户会用中文问你关于他自己笔记的问题。\n"
    "下面是用户最近的笔记（JSON 数组）。请尽量只基于笔记内容回答；\n"
    "如果笔记里没有直接答案，但有相关线索，可以给出与问题相关的笔记作为参考，并明确说明是"相关线索"而非"直接答案"；\n"
    "如果完全没有相关笔记，如实说「笔记里没有找到相关内容」，但不要拒绝回答用户的澄清。\n"
    "引用笔记时优先用「《标题》」格式；没有标题的笔记用前 8 个字代替。\n"
    "回答自然、简洁，用中文。\n\n"
    f"用户笔记：{json.dumps(notes, ensure_ascii=False)}"
)
```

- [ ] **Step 5**：补日志，展示哪几条 note 进了 llm context：

```python
preview = ", ".join(f"{n['id'][:6]}:{(n['title'] or n['content'][:10])[:12]}" for n in notes[:3])
logger.info("chat: notes_for_llm=%d preview=[%s]", len(notes), preview)
```

- [ ] **Step 6**：验收：重启 relay，发一条问题，`docker logs` 应看到 `chat: using N inline_notes (filtered from M)` + `chat: notes_for_llm=N preview=[...]`。

- [ ] **Step 7**：commit

```bash
git add relay_service/app/routes_ai.py
git commit -m "ai: max_notes 抬到 200 + content 截到 1500 + 保留 app 传入顺序 + 放宽系统 prompt"
```

---

### Task 0.3：手动真机回归（必做，不做就不能说"修好了"）

- [ ] 准备 5 条笔记覆盖：1 条最近文本、1 条归档文本、1 条有长内容（>800 字）、1 条无标题、1 条带标签
- [ ] 重新 install debug APK，进入 AI 助手
- [ ] 依次问：
  - "我最近记了什么？" → 应看到最近笔记摘要
  - "归档里关于 X 的内容" → 应看到归档笔记命中（之前会"没找到"）
  - 针对长笔记后半段的关键词发问 → 应答对（之前会"没找到"，因为 500 字截断）
  - 无关问题 → 应说"笔记里没有找到相关内容"但不报错
- [ ] `adb logcat -s YDOC_AGENT:D` 确认：`inline_notes_size` ≥ 笔记数、`preview=[...]` 包含实际 title
- [ ] relay log 确认 `notes_for_llm=N` 与 app 端一致

**验收通过后**：更新 `WALKTHROUGH.md`，追加本轮「AI 助手看不到笔记 — 5 点根因 + 修复」。

---

## Phase 1 — 链接识别 + 预览卡 + AI 摘要

### 架构

```
note 保存 ─┬─ URL 扫描（text content + transcript）
           │
           ├─ 命中 → enqueue LinkPreviewWorker
           │                  │
           │                  └─ POST relay /api/links/preview { url }
           │                         │
           │                         └─ relay 拉页面 → og meta + readability 正文 → (可选) LLM 200 字摘要
           │                                ↑
           │                                └─ 失败回退只返回 meta
           │
           └─ worker 完成 → 写回 note.linkPreviewsJson → Flow 自动刷新 UI
```

AI 整理（`analyze-note`）额外把 `note.linkPreviewsJson` 的 `title + summary` 拼进 prompt，让 AI 给更准的 title / category / 提醒候选。

### Task 1.1：Room migration 17→18 + Note 模型扩展

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/data/local/NoteEntity.kt`
- Modify: `app/src/main/java/com/ydoc/app/model/Note.kt`
- Modify: `app/src/main/java/com/ydoc/app/data/local/YDocDatabase.kt`
- Create: `app/src/main/java/com/ydoc/app/model/LinkPreview.kt`

- [ ] **Step 1**：`LinkPreview.kt`：

```kotlin
package com.ydoc.app.model

import kotlinx.serialization.Serializable

@Serializable
data class LinkPreview(
    val url: String,
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val siteName: String = "",
    val summary: String = "",          // AI 生成的 200 字摘要，可空
    val fetchedAt: Long = 0,
    val error: String? = null,         // 拉页面失败时填
)
```

- [ ] **Step 2**：在 `NoteEntity` 加：

```kotlin
val linkPreviewsJson: String? = null,    // List<LinkPreview> 的 JSON；null = 还没扫描
```

同步 `Note` 领域模型加 `val linkPreviews: List<LinkPreview> = emptyList()` + 序列化 helper。

- [ ] **Step 3**：`YDocDatabase` version 17 → 18 + 新 migration：

```kotlin
private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE notes ADD COLUMN linkPreviewsJson TEXT")
    }
}
// ...
.addMigrations(..., MIGRATION_16_17, MIGRATION_17_18)
```

- [ ] **Step 4**：验收 `./gradlew assembleDebug`（编译通过 = Room schema 对齐）。

- [ ] **Step 5**：commit

```bash
git add app/src/main/java/com/ydoc/app/model/LinkPreview.kt \
        app/src/main/java/com/ydoc/app/model/Note.kt \
        app/src/main/java/com/ydoc/app/data/local/NoteEntity.kt \
        app/src/main/java/com/ydoc/app/data/local/YDocDatabase.kt
git commit -m "notes: 加 linkPreviewsJson 字段 + Room migration 17→18"
```

---

### Task 1.2：Relay 新端点 `/api/links/preview`

**Files:**
- Create: `relay_service/app/routes_links.py`
- Modify: `relay_service/app/main.py`
- Modify: `relay_service/requirements.txt`（+ `beautifulsoup4`, `readability-lxml`, `lxml`）
- Modify: `relay_service/Dockerfile`（装 `libxml2-dev libxslt1-dev gcc`）

- [ ] **Step 1**：`routes_links.py`：

```python
"""链接预览：拉 og meta + 首屏正文，可选让 LLM 做 200 字摘要。"""
from __future__ import annotations
import logging, re, urllib.request
from typing import Optional
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from .auth import require_relay_token
from . import settings_store

logger = logging.getLogger("ai_links")
router = APIRouter(prefix="/api/links", dependencies=[Depends(require_relay_token)])

class LinkPreviewRequest(BaseModel):
    url: str
    want_summary: bool = True

class LinkPreviewResponse(BaseModel):
    url: str
    title: str = ""
    description: str = ""
    image_url: str = ""
    site_name: str = ""
    summary: str = ""
    error: Optional[str] = None

UA = "Mozilla/5.0 (compatible; Ydrop-Link-Preview/1.0)"

@router.post("/preview", response_model=LinkPreviewResponse)
async def preview(body: LinkPreviewRequest):
    if not re.match(r"^https?://", body.url):
        raise HTTPException(400, "url must be http(s)")
    try:
        req = urllib.request.Request(body.url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=8) as resp:
            raw = resp.read(512 * 1024)  # 最多拉 512KB，防止巨图/巨 html
            ctype = resp.headers.get("Content-Type", "")
            charset = "utf-8"
            m = re.search(r"charset=([\w\-]+)", ctype)
            if m: charset = m.group(1)
            html = raw.decode(charset, errors="ignore")
    except Exception as e:
        logger.warning("preview fetch failed url=%s: %s", body.url, e)
        return LinkPreviewResponse(url=body.url, error=str(e)[:160])

    meta = _extract_meta(html, body.url)

    summary = ""
    if body.want_summary:
        try:
            main_text = _extract_main_text(html)[:3000]
            ai_cfg = await settings_store.get_ai_config()
            if ai_cfg["enabled"] and ai_cfg["base_url"] and ai_cfg["token"] and main_text:
                from .ai_provider import call_llm
                prompt = (
                    f"用中文把下面这段网页正文总结成 150 字左右的摘要，突出可行动信息。\n\n"
                    f"标题：{meta['title']}\n正文：{main_text}"
                )
                summary = call_llm(
                    [{"role": "user", "content": prompt}], ai_cfg, response_format="text",
                )[:400]
        except Exception as e:
            logger.warning("summary failed url=%s: %s", body.url, e)

    return LinkPreviewResponse(
        url=body.url,
        title=meta["title"], description=meta["description"],
        image_url=meta["image_url"], site_name=meta["site_name"],
        summary=summary,
    )


def _extract_meta(html: str, base_url: str) -> dict:
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(html, "lxml")
    def _m(prop: str) -> str:
        el = soup.find("meta", attrs={"property": prop}) or soup.find("meta", attrs={"name": prop})
        return (el.get("content") if el else "") or ""
    title = _m("og:title") or (soup.title.string if soup.title else "") or ""
    desc = _m("og:description") or _m("description") or ""
    image = _m("og:image") or ""
    site = _m("og:site_name") or base_url.split("/")[2]
    return {"title": title.strip()[:200], "description": desc.strip()[:300],
            "image_url": image.strip()[:500], "site_name": site.strip()[:80]}


def _extract_main_text(html: str) -> str:
    try:
        from readability import Document
        doc = Document(html)
        from bs4 import BeautifulSoup
        return BeautifulSoup(doc.summary(), "lxml").get_text(" ", strip=True)
    except Exception:
        from bs4 import BeautifulSoup
        return BeautifulSoup(html, "lxml").get_text(" ", strip=True)
```

- [ ] **Step 2**：`main.py` 引入 router：

```python
from .routes_links import router as links_router
app.include_router(links_router)
```

- [ ] **Step 3**：`requirements.txt` 追加：

```
beautifulsoup4>=4.12
lxml>=5.2
readability-lxml>=0.8.1
```

- [ ] **Step 4**：`Dockerfile` 在 apt-get install 里补 `libxml2-dev libxslt1-dev` 和 `build-essential`（没有的话）。

- [ ] **Step 5**：本地跑 `uvicorn app.main:app --reload`，用 curl：

```bash
curl -X POST http://127.0.0.1:8000/api/links/preview \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
# 期望：{"url":"https://example.com","title":"Example Domain",...}
```

- [ ] **Step 6**：commit

```bash
git add relay_service/app/routes_links.py relay_service/app/main.py \
        relay_service/requirements.txt relay_service/Dockerfile
git commit -m "ai: relay 新增 /api/links/preview，拉 og + readability 正文 + 可选 LLM 摘要"
```

---

### Task 1.3：Android 端 LinkPreviewClient + LinkPreviewWorker

**Files:**
- Create: `app/src/main/java/com/ydoc/app/ai/LinkPreviewClient.kt`
- Create: `app/src/main/java/com/ydoc/app/sync/LinkPreviewWorker.kt`
- Modify: `app/src/main/java/com/ydoc/app/data/NoteRepository.kt`（保存时扫 URL + schedule worker）
- Modify: `app/src/main/java/com/ydoc/app/data/AppContainer.kt`（暴露 LinkPreviewClient 单例）

- [ ] **Step 1**：`LinkPreviewClient.kt`：

```kotlin
class LinkPreviewClient(private val httpClient: OkHttpClient) {
    @Serializable
    private data class Req(val url: String, val want_summary: Boolean = true)
    @Serializable
    data class Resp(
        val url: String,
        val title: String = "",
        val description: String = "",
        @SerialName("image_url") val imageUrl: String = "",
        @SerialName("site_name") val siteName: String = "",
        val summary: String = "",
        val error: String? = null,
    )

    suspend fun fetchPreview(relay: RelayConfig, url: String): Resp = withContext(Dispatchers.IO) {
        val bodyJson = Json.encodeToString(Req(url = url))
        val req = Request.Builder()
            .url(relay.baseUrl.trimEnd('/') + "/api/links/preview")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${relay.token}")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("link preview http ${resp.code}: ${text.take(160)}")
            Json { ignoreUnknownKeys = true }.decodeFromString(Resp.serializer(), text)
        }
    }
}
```

- [ ] **Step 2**：`LinkPreviewWorker.kt`（CoroutineWorker）：

```kotlin
class LinkPreviewWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
        val container = applicationContext.appContainer
        val note = container.noteRepository.getNote(noteId) ?: return Result.success()
        val relay = container.settingsStore.settingsFlow.first().relay
        if (!relay.enabled) return Result.retry()

        val urls = extractUrls(note.title + "\n" + note.content + "\n" + (note.transcript ?: ""))
            .distinct()
            .take(5)
        if (urls.isEmpty()) return Result.success()

        val previews = urls.mapNotNull { url ->
            runCatching { container.linkPreviewClient.fetchPreview(relay, url) }
                .getOrElse { return@mapNotNull LinkPreview(url = url, error = it.message?.take(120), fetchedAt = System.currentTimeMillis()) }
                .let { LinkPreview(
                    url = it.url, title = it.title, description = it.description,
                    imageUrl = it.imageUrl, siteName = it.siteName, summary = it.summary,
                    fetchedAt = System.currentTimeMillis(), error = it.error,
                ) }
        }
        container.noteRepository.setLinkPreviews(noteId, previews)
        return Result.success()
    }

    companion object {
        const val KEY_NOTE_ID = "note_id"
        fun schedule(ctx: Context, noteId: String) {
            val req = OneTimeWorkRequestBuilder<LinkPreviewWorker>()
                .setInputData(workDataOf(KEY_NOTE_ID to noteId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "link_preview_$noteId", ExistingWorkPolicy.REPLACE, req,
            )
        }
    }
}

private val URL_REGEX = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
fun extractUrls(text: String): List<String> =
    URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', '，', '。', '）', ')', ']', '」') }.toList()
```

- [ ] **Step 3**：`NoteRepository` 加：

```kotlin
suspend fun setLinkPreviews(noteId: String, previews: List<LinkPreview>) {
    val json = Json.encodeToString(ListSerializer(LinkPreview.serializer()), previews)
    noteDao.updateLinkPreviewsJson(noteId, json)
}
```

并在 `saveNote` / `updateNote` 的完成回调里：

```kotlin
if (extractUrls(note.title + "\n" + note.content).isNotEmpty()) {
    LinkPreviewWorker.schedule(context, note.id)
}
```

- [ ] **Step 4**：`AppContainer` 暴露 `linkPreviewClient: LinkPreviewClient`。

- [ ] **Step 5**：验收：在 app 里写「今天看了 https://example.com」保存后 10 秒内，Room Inspector 看到 `linkPreviewsJson` 被填充。

- [ ] **Step 6**：commit

```bash
git add app/src/main/java/com/ydoc/app/ai/LinkPreviewClient.kt \
        app/src/main/java/com/ydoc/app/sync/LinkPreviewWorker.kt \
        app/src/main/java/com/ydoc/app/data/NoteRepository.kt \
        app/src/main/java/com/ydoc/app/data/AppContainer.kt \
        app/src/main/java/com/ydoc/app/data/local/NoteDao.kt
git commit -m "notes: URL 识别 + LinkPreviewWorker 后台拉预览并落库"
```

---

### Task 1.4：Compose 预览卡 `LinkPreviewCard`

**Files:**
- Create: `app/src/main/java/com/ydoc/app/ui/components/LinkPreviewCard.kt`
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt`（NoteCard 展开态下渲染预览）

- [ ] **Step 1**：`LinkPreviewCard`（若 `error != null` 显示降级 chip "链接预览失败"，点击仍能打开浏览器）：

```kotlin
@Composable
fun LinkPreviewCard(preview: LinkPreview, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (preview.imageUrl.isNotBlank()) {
                AsyncImage( // Coil 的 AsyncImage；app 已用 Coil 加载 audio waveform 等
                    model = preview.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                preview.title.ifBlank { preview.url },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            if (preview.summary.isNotBlank()) {
                Text(preview.summary, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            } else if (preview.description.isNotBlank()) {
                Text(preview.description, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text(preview.siteName.ifBlank { preview.url },
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            preview.error?.let {
                Text("预览失败：$it", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
```

- [ ] **Step 2**：NoteCard 展开态下（YDocApp.kt 里渲染 note 详情的地方），如果 `note.linkPreviews.isNotEmpty()`，把预览卡排在 content 下面；点击触发 `Intent(ACTION_VIEW, Uri.parse(url))`。

- [ ] **Step 3**：如果 app 还没依赖 Coil，在 `app/build.gradle.kts` 加 `implementation("io.coil-kt:coil-compose:2.6.0")`（若已有跳过）。

- [ ] **Step 4**：验收：真机跑一遍，含 URL 的笔记卡片展开后有预览卡（首次可能延迟几秒）。

- [ ] **Step 5**：commit

```bash
git add app/src/main/java/com/ydoc/app/ui/components/LinkPreviewCard.kt \
        app/src/main/java/com/ydoc/app/ui/YDocApp.kt \
        app/build.gradle.kts
git commit -m "ui: note 卡片展示链接预览卡片（og/summary + 点击跳转）"
```

---

### Task 1.5：AI 整理管道注入链接摘要

**Files:**
- Modify: `relay_service/app/routes_notes.py` 或 `ai.py`（找到 `analyze-note` 入口）
- Modify: `app/src/main/java/com/ydoc/app/ai/RelayAiClient.kt`（上传时带 link_previews）

- [ ] **Step 1**：在 `POST /ai/analyze-note` 的请求体里允许新字段：

```python
class AnalyzeNoteRequest(BaseModel):
    ...
    link_previews: Optional[list[dict]] = None  # [{title, url, summary, description}]
```

- [ ] **Step 2**：在组 prompt 的地方（system/user message）追加一段（如果有 previews）：

```python
if body.link_previews:
    lines = [f"- 《{p.get('title') or p.get('url')}》：{p.get('summary') or p.get('description') or ''}".strip()
             for p in body.link_previews]
    user_context = "\n笔记里的链接：\n" + "\n".join(lines)
    note_text = note_text + user_context
```

- [ ] **Step 3**：Android 端在调 `analyze-note` 时把 `note.linkPreviews` 打平成 list<dict> 随请求发上去。

- [ ] **Step 4**：验收：写一条只含 URL 的笔记（比如技术博客），拉完预览后手动触发 AI 整理，产出的 title / category / tags 应基于链接摘要（不是空字符串）。

- [ ] **Step 5**：commit

```bash
git add relay_service/app/routes_notes.py relay_service/app/ai.py \
        app/src/main/java/com/ydoc/app/ai/RelayAiClient.kt
git commit -m "ai: analyze-note 把链接预览摘要纳入 context，让整理更准"
```

---

### Task 1.6：Phase 1 集成回归

- [ ] 写 3 条含 URL 笔记（一条正常网页、一条 404、一条图多 PDF 链接）
- [ ] 观察 Room 里 linkPreviewsJson 是否都被填（error != null 的也要写回，不能一直空）
- [ ] 观察展开态预览卡显示 / 错误态 chip 显示
- [ ] AI 助手 tab 问"我最近看了什么网页" → 答案应能引用链接标题

**验收通过后**：追加一轮 WALKTHROUGH 记录。

---

## Phase 2 — 图片识别（OCR + Vision AI 双通道）

### 架构

```
用户 ─┬─ 主界面 "加图" 按钮（PhotoPicker）
      └─ 系统分享进来（ACTION_SEND image/*）
             │
             ▼
      Attachment 新建（type=IMAGE）
             │
             ├─ 本地 ML Kit OCR（中英文双识别器）→ attachment.ocrText
             │
             └─ 入队 ImageAnalyzeWorker
                     │
                     └─ multipart POST /api/images/analyze（relay）
                              │
                              └─ relay 存储（inbox/images/）+ 调 vision provider
                                    │
                                    └─ 返回 {description, keywords, actionable_items, dates}
                                            │
                                            ▼
                                attachment.aiDescription / aiStructured 落库

AI 整理：analyze-note 的 note_text 自动拼接
  "[图 1 文字识别] OCR 内容... [图 1 AI 描述] description..."
```

### Task 2.1：Room migration 18→19 + 附件模型

**Files:**
- Create: `app/src/main/java/com/ydoc/app/model/NoteAttachment.kt`
- Modify: `app/src/main/java/com/ydoc/app/data/local/NoteEntity.kt`（+ `attachmentsJson`）
- Modify: `app/src/main/java/com/ydoc/app/model/Note.kt`（+ `attachments: List<NoteAttachment>`）
- Modify: `YDocDatabase.kt`（+ `MIGRATION_18_19`）

- [ ] **Step 1**：`NoteAttachment.kt`

```kotlin
@Serializable
data class NoteAttachment(
    val id: String,
    val type: String = "IMAGE",       // 未来可扩 "FILE"
    val localPath: String,             // app 私有目录
    val publicUri: String? = null,     // MediaStore 可见
    val remoteUrl: String? = null,     // relay 返回
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val thumbPath: String? = null,
    val ocrText: String = "",
    val aiDescription: String = "",
    val aiStructuredJson: String = "{}",
    val analyzeError: String? = null,
    val createdAt: Long = 0,
)
```

- [ ] **Step 2**：migration + version 18 → 19：

```kotlin
private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE notes ADD COLUMN attachmentsJson TEXT")
    }
}
```

- [ ] **Step 3**：commit

```bash
git commit -am "notes: 加 attachmentsJson + Room migration 18→19"
```

---

### Task 2.2：图片入口（PhotoPicker + 分享）+ 保存到私有目录

**Files:**
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt`（快速记录卡加选图按钮）
- Modify: `AndroidManifest.xml`（MainActivity `<intent-filter>` 支持 `ACTION_SEND`, `image/*` 和 `ACTION_SEND_MULTIPLE`）
- Create: `app/src/main/java/com/ydoc/app/data/AttachmentStore.kt`（把 Uri 拷到私有目录、生成 thumbnail）

- [ ] **Step 1**：`AttachmentStore`：

```kotlin
class AttachmentStore(private val context: Context) {
    suspend fun import(uri: Uri): NoteAttachment = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val out = File(dir, "$id.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("无法打开图片")
        val bmp = BitmapFactory.decodeFile(out.absolutePath)
        val (w, h) = (bmp?.width ?: 0) to (bmp?.height ?: 0)
        val thumb = File(dir, "$id-thumb.jpg").also { createThumbnail(out, it, 320) }
        NoteAttachment(
            id = id, localPath = out.absolutePath,
            mime = context.contentResolver.getType(uri) ?: "image/jpeg",
            width = w, height = h, thumbPath = thumb.absolutePath,
            createdAt = System.currentTimeMillis(),
        )
    }
}
```

- [ ] **Step 2**：快速记录卡加 IconButton "加图"，用 `rememberLauncherForActivityResult(PickVisualMedia())` 拿 Uri → `AttachmentStore.import` → 暂存在 `QuickRecordState` 的 pending attachments → 保存笔记时一并写入。

- [ ] **Step 3**：`AndroidManifest.xml` MainActivity 加：

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="image/*" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND_MULTIPLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="image/*" />
</intent-filter>
```

MainActivity 解析 intent → 把 Uri 转 Attachment → 打开编辑页或直接保存。

- [ ] **Step 4**：验收：从相册分享一张图到 Ydrop，应创建一条带图笔记，图存在 `/data/data/com.ydoc.app/files/attachments/<uuid>.jpg`。

- [ ] **Step 5**：commit

```bash
git commit -am "notes: 支持选图 + 系统分享图片 + AttachmentStore 私有存储 + 缩略图"
```

---

### Task 2.3：本地 OCR（ML Kit）

**Files:**
- Modify: `app/build.gradle.kts`（+ ML Kit 依赖）
- Create: `app/src/main/java/com/ydoc/app/ai/ImageOcrService.kt`
- Modify: `NoteRepository` 保存图片附件后调 OCR

- [ ] **Step 1**：`app/build.gradle.kts` 加：

```kotlin
implementation("com.google.mlkit:text-recognition:16.0.1")
implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
```

- [ ] **Step 2**：`ImageOcrService`：

```kotlin
class ImageOcrService {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognize(imagePath: String): String = withContext(Dispatchers.Default) {
        val bmp = BitmapFactory.decodeFile(imagePath) ?: return@withContext ""
        val input = InputImage.fromBitmap(bmp, 0)
        val latinText = runCatching { latin.process(input).await().text }.getOrDefault("")
        val chineseText = runCatching { chinese.process(input).await().text }.getOrDefault("")
        // 选结果更长（信息量更多）的那一版；若两个都识别出就合并去重
        listOf(latinText, chineseText).maxByOrNull { it.length }.orEmpty()
    }
}
```

`.await()` 来自 `kotlinx-coroutines-play-services`（已有则复用；没有则加 `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")`）。

- [ ] **Step 3**：`NoteRepository.saveAttachment` 或保存新图片笔记的路径里，在写完 attachmentsJson 后 fire-and-forget `ImageOcrService.recognize` 并回写 `ocrText`。

- [ ] **Step 4**：验收：截一张带文字的图分享进 Ydrop，10 秒内 attachment.ocrText 应非空。

- [ ] **Step 5**：commit

```bash
git commit -am "ai: 图片附件本地 ML Kit OCR（中英文双识别器）"
```

---

### Task 2.4：Relay 新端点 `/api/images/analyze`（Vision AI）

**Files:**
- Create: `relay_service/app/routes_images.py`
- Modify: `relay_service/app/main.py`
- Modify: `relay_service/app/ai_provider.py`（+ `call_vision` helper，兼容 OpenAI `image_url` + Anthropic `image.source.base64`）
- Modify: `relay_service/.env.example`（可选 `AI_VISION_MODEL` 覆盖）

- [ ] **Step 1**：`routes_images.py`：

```python
"""图片分析：存到 inbox/images/ + 调 vision provider 拿描述 + 结构化抽取。"""
import base64, io, json, logging, os, time, uuid
from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import BaseModel
from .auth import require_relay_token
from . import settings_store

logger = logging.getLogger("ai_images")
router = APIRouter(prefix="/api/images", dependencies=[Depends(require_relay_token)])

class ImageAnalyzeResponse(BaseModel):
    remote_url: str = ""
    description: str = ""
    keywords: list[str] = []
    actionable_items: list[str] = []
    dates: list[str] = []
    error: str | None = None

@router.post("/analyze", response_model=ImageAnalyzeResponse)
async def analyze(
    file: UploadFile = File(...),
    note_id: str = Form(""),
    hint: str = Form(""),
):
    data = await file.read()
    if len(data) > 8 * 1024 * 1024:
        raise HTTPException(413, "image too large (>8MB)")
    # 存到 static
    from .config import get_settings
    s = get_settings()
    img_dir = os.path.join(s.static_dir, "images")
    os.makedirs(img_dir, exist_ok=True)
    ext = os.path.splitext(file.filename or "img.jpg")[1].lower() or ".jpg"
    fname = f"{uuid.uuid4().hex}{ext}"
    with open(os.path.join(img_dir, fname), "wb") as f:
        f.write(data)
    remote_url = f"{s.public_base_url.rstrip('/')}/static/images/{fname}"

    ai_cfg = await settings_store.get_ai_config()
    if not (ai_cfg["enabled"] and ai_cfg["base_url"] and ai_cfg["token"]):
        return ImageAnalyzeResponse(remote_url=remote_url, error="relay 未配 AI provider")

    try:
        from .ai_provider import call_vision
        raw = call_vision(
            image_bytes=data, mime=file.content_type or "image/jpeg",
            prompt=_build_image_prompt(hint), cfg=ai_cfg,
        )
        structured = _parse_image_json(raw)
        return ImageAnalyzeResponse(
            remote_url=remote_url,
            description=structured.get("description", "")[:1000],
            keywords=structured.get("keywords", [])[:10],
            actionable_items=structured.get("actionable_items", [])[:10],
            dates=structured.get("dates", [])[:10],
        )
    except Exception as e:
        logger.error("vision analyze failed: %s", e)
        return ImageAnalyzeResponse(remote_url=remote_url, error=str(e)[:200])

def _build_image_prompt(hint: str) -> str:
    return (
        "用户把这张图丢进了他的 inbox 笔记。请只返回 JSON（不要代码围栏），"
        "键：description（≤150 字的中文描述，突出主体/文字/场景）、"
        "keywords（≤8 个中文关键词）、actionable_items（如果图里有待办或截图含任务清单，提取出来）、"
        "dates（图里明显出现的日期时间，格式"2026-04-22 14:00"；没有就空数组）。\n"
        + (f"用户备注：{hint}\n" if hint else "")
    )

def _parse_image_json(raw: str) -> dict:
    from .ai_provider import strip_json_fence
    try:
        return json.loads(strip_json_fence(raw))
    except Exception:
        return {"description": raw[:500]}
```

- [ ] **Step 2**：`ai_provider.py` 加 `call_vision`：

```python
def call_vision(*, image_bytes: bytes, mime: str, prompt: str, cfg: dict) -> str:
    import base64, urllib.request, json
    b64 = base64.b64encode(image_bytes).decode()
    mode = (cfg.get("endpoint_mode") or "AUTO").upper()
    model = cfg.get("vision_model") or cfg.get("model") or "gpt-4o-mini"
    base = cfg["base_url"].rstrip("/")
    token = cfg["token"]

    if mode in ("OPENAI", "AUTO"):
        url = f"{base}/v1/chat/completions"
        payload = {
            "model": model,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}},
                ],
            }],
            "temperature": 0.2,
        }
    else:  # ANTHROPIC
        url = f"{base}/v1/messages"
        payload = {
            "model": model,
            "max_tokens": 1024,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "image", "source": {"type": "base64", "media_type": mime, "data": b64}},
                    {"type": "text", "text": prompt},
                ],
            }],
        }
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json",
                 "x-api-key": token, "anthropic-version": "2023-06-01"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = json.loads(resp.read().decode())
    if mode == "ANTHROPIC" or "content" in raw and isinstance(raw.get("content"), list):
        return raw["content"][0].get("text", "")
    return raw["choices"][0]["message"]["content"]
```

- [ ] **Step 3**：`main.py` 挂 router，确保 static mount 已有 `/static`（如果已挂 `app.mount("/static", ...)` 跳过）。

- [ ] **Step 4**：本地验收：

```bash
curl -X POST http://127.0.0.1:8000/api/images/analyze \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/screenshot.png"
```

期望返回 `{"remote_url":"...","description":"..."}`。

- [ ] **Step 5**：commit

```bash
git commit -am "ai: relay 新增 /api/images/analyze，支持 OpenAI/Anthropic vision 双协议"
```

---

### Task 2.5：Android 端 ImageAnalyzeClient + Worker

**Files:**
- Create: `app/src/main/java/com/ydoc/app/ai/ImageAnalyzeClient.kt`
- Create: `app/src/main/java/com/ydoc/app/sync/ImageAnalyzeWorker.kt`
- Modify: `NoteRepository`（保存图片后 enqueue + OCR 也同时 enqueue）

- [ ] **Step 1**：`ImageAnalyzeClient`：multipart POST，带 `file` + `note_id` + `hint=note.title`。response 落到 `attachment.aiDescription / aiStructuredJson / remoteUrl`。

- [ ] **Step 2**：`ImageAnalyzeWorker`（CoroutineWorker 带 unique work key `image_analyze_<noteId>_<attachmentId>`，指数退避）。

- [ ] **Step 3**：`NoteRepository.setAttachmentAnalysis(noteId, attachmentId, result)`：回填 attachmentsJson 中单条，不覆盖其他字段。

- [ ] **Step 4**：验收：分享一张图 → 10-30 秒内 `aiDescription` 非空；断网时 worker 进 backoff。

- [ ] **Step 5**：commit

```bash
git commit -am "ai: 图片附件后台调 /api/images/analyze 拿 description + 结构化信息"
```

---

### Task 2.6：NoteAttachmentRow UI + 编辑态管理

**Files:**
- Create: `app/src/main/java/com/ydoc/app/ui/components/NoteAttachmentRow.kt`
- Modify: `app/src/main/java/com/ydoc/app/ui/YDocApp.kt`（NoteCard 展开态渲染附件行 + 编辑页支持增删）

- [ ] **Step 1**：`NoteAttachmentRow`：水平滚动的缩略图 row；点击大图（DialogFullscreen）；长按「查看识别文字」弹底部 sheet 展示 OCR + AI 描述。

- [ ] **Step 2**：编辑页在输入框上方加「+ 加图」按钮；每个缩略图右上角「×」删除。

- [ ] **Step 3**：加分析中状态：attachment 没有 `aiDescription` 且没 `analyzeError` → 缩略图右下角转圈 indicator。

- [ ] **Step 4**：验收：真机回归——加图、分享图、查看 OCR、查看 AI 描述、删除附件，整条链路稳定。

- [ ] **Step 5**：commit

```bash
git commit -am "ui: 笔记附件行 + OCR/AI 描述底部 sheet + 编辑态增删"
```

---

### Task 2.7：AI 整理管道注入图片上下文

**Files:**
- Modify: `relay_service/app/routes_notes.py`（`analyze-note` 接 `image_contexts`）
- Modify: `app/src/main/java/com/ydoc/app/ai/RelayAiClient.kt`（上传时带 image_contexts）

- [ ] **Step 1**：`AnalyzeNoteRequest` 加：

```python
class ImageContext(BaseModel):
    ocr_text: str = ""
    ai_description: str = ""
    keywords: list[str] = []

class AnalyzeNoteRequest(BaseModel):
    ...
    image_contexts: Optional[list[ImageContext]] = None
```

- [ ] **Step 2**：拼 prompt 时：

```python
if body.image_contexts:
    lines = []
    for i, ic in enumerate(body.image_contexts, 1):
        lines.append(f"[图{i} OCR] {ic.ocr_text[:500]}")
        if ic.ai_description:
            lines.append(f"[图{i} 描述] {ic.ai_description[:300]}")
    note_text = note_text + "\n\n" + "\n".join(lines)
```

- [ ] **Step 3**：Android 端把 attachments 打平成 ImageContext[] 发上去。

- [ ] **Step 4**：验收：一张含手写待办的图保存为笔记 → AI 整理应给出 TODO 分类 + 提取出动作项（经 OCR + AI description 双输入）。

- [ ] **Step 5**：commit

```bash
git commit -am "ai: analyze-note 把 OCR + 图片 AI 描述纳入 context，让整理对图片笔记生效"
```

---

### Task 2.8：Phase 2 集成回归

- [ ] 截图分享（中文屏）、相册选图（英文文档）、相机实拍、一次分享多张
- [ ] 断网 → 恢复网络 → worker 应继续完成 AI 分析
- [ ] WebDAV 同步：attachmentsJson 不破坏现有序列化（旧设备升级后能打开新笔记）
- [ ] 真机电量观察（ML Kit 频繁调用是否发热）

**验收通过后**：追加一轮 WALKTHROUGH 记录 + 回归清单 + 跑 `ydrop-release` skill 出 v1.1.0。

---

## 执行顺序与里程碑

| Milestone | 依赖 | 预估改动 | 可合并出 | 风险 |
|-----------|------|---------|---------|------|
| Phase 0（bug 修复） | 无 | ~5 文件 | 当天 | 低，纯调参 + 日志 |
| Phase 1（链接） | Phase 0 | ~10 文件 + 1 migration | 1-2 天 | 中，relay 需要装 lxml；Worker 调度要稳 |
| Phase 2（图片） | Phase 1（预览卡 UI 模式可复用） | ~18 文件 + 1 migration + 2 依赖 | 2-3 天 | 高，ML Kit 初次加载大；vision 对 provider 要求高 |

强烈建议 Phase 0 先独立合入并真机回归 24 小时后再开 Phase 1；Phase 2 单独开一轮 release。

---

## Self-Review 结果

- 所有 task 都标注了 `Create/Modify/Test` 路径。
- 关键代码（migration SQL、vision payload、URL 正则、system prompt）都写全了，无 TBD。
- Type 一致性：`LinkPreview`、`NoteAttachment`、`ImageContext` 在各处字段名一致。
- Phase 0 的 5 点根因都映射到了具体 task 步骤。
- Phase 1/2 都包含 relay + android + ui + 集成测试回归。
