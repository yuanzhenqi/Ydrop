from __future__ import annotations

import asyncio
import json
import time
import uuid

from fastapi import APIRouter, Body, Depends, HTTPException, Query
from pydantic import BaseModel

from .ai import analyze_note
from .auth import require_relay_token
from .database import get_db
from .markdown_format import default_color_for
from .models import AiAnalyzeRequest
from .models_notes import (
    AiSuggestionResponse,
    NoteCreate,
    NoteListResponse,
    NoteResponse,
    NoteUpdate,
)

router = APIRouter(prefix="/api/notes", dependencies=[Depends(require_relay_token)])


def _trigger_push(note_id: str) -> None:
    """Fire-and-forget: 创建后台任务推送到 WebDAV。"""
    from .sync_orchestrator import push_single_note
    asyncio.create_task(push_single_note(note_id))


def _trigger_delete_remote(note_id: str) -> None:
    from .sync_orchestrator import delete_remote_by_id
    asyncio.create_task(delete_remote_by_id(note_id))


def _row_to_note(row) -> NoteResponse:
    return NoteResponse(
        id=row["id"],
        title=row["title"],
        content=row["content"],
        original_content=row["original_content"],
        source=row["source"],
        category=row["category"],
        priority=row["priority"],
        color_token=row["color_token"],
        status=row["status"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
        last_synced_at=row["last_synced_at"],
        sync_error=row["sync_error"],
        pinned=bool(row["pinned"]),
        remote_path=row["remote_path"],
        is_archived=bool(row["is_archived"]),
        archived_at=row["archived_at"],
        is_trashed=bool(row["is_trashed"]),
        trashed_at=row["trashed_at"],
        tags=json.loads(row["tags_json"] or "[]"),
        transcript=row["transcript"],
        audio_path=row["audio_path"],
        relay_url=row["relay_url"],
        transcription_status=row["transcription_status"] or "NOT_STARTED",
    )


@router.get("", response_model=NoteListResponse)
async def list_notes(
    q: str | None = None,
    category: str | None = None,
    priority: str | None = None,
    tag: str | None = None,
    archived: bool | None = None,
    trashed: bool | None = None,
    limit: int = Query(default=100, le=500),
    offset: int = 0,
):
    db = await get_db()
    conditions = []
    params: list = []

    # 默认排除已归档/已删除的笔记，显式传 true 才包含
    if trashed is True:
        conditions.append("is_trashed = 1")
    else:
        conditions.append("is_trashed = 0")

    if archived is True:
        conditions.append("is_archived = 1")
    elif trashed is not True:
        # 只在不是查回收站的情况下，默认排除归档
        conditions.append("is_archived = 0")

    if category:
        conditions.append("category = ?")
        params.append(category.upper())
    if priority:
        conditions.append("priority = ?")
        params.append(priority.upper())
    if q:
        conditions.append("(title LIKE ? OR content LIKE ?)")
        params.extend([f"%{q}%", f"%{q}%"])
    if tag:
        conditions.append("tags_json LIKE ?")
        params.append(f'%"{tag}"%')

    where = " AND ".join(conditions) if conditions else "1=1"

    count_row = await db.execute_fetchall(f"SELECT COUNT(*) as c FROM notes WHERE {where}", params)
    total = count_row[0][0] if count_row else 0

    rows = await db.execute_fetchall(
        f"SELECT * FROM notes WHERE {where} ORDER BY pinned DESC, updated_at DESC LIMIT ? OFFSET ?",
        params + [limit, offset],
    )
    items = [_row_to_note(r) for r in rows]
    return NoteListResponse(items=items, total=total)


@router.get("/{note_id}", response_model=NoteResponse)
async def get_note(note_id: str):
    db = await get_db()
    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    if not rows:
        raise HTTPException(status_code=404, detail="Note not found")
    return _row_to_note(rows[0])


@router.post("", response_model=NoteResponse, status_code=201)
async def create_note(body: NoteCreate):
    db = await get_db()
    note_id = str(uuid.uuid4())
    now = int(time.time() * 1000)
    content = body.content.strip()
    title = content.split("\n")[0][:36] if content else ""
    cat = body.category.upper() if body.category else "NOTE"
    pri = body.priority.upper() if body.priority else "MEDIUM"
    color = default_color_for(cat, pri)

    await db.execute(
        """INSERT INTO notes (id, title, content, source, category, priority, color_token,
           status, created_at, updated_at, tags_json)
           VALUES (?, ?, ?, 'TEXT', ?, ?, ?, 'LOCAL_ONLY', ?, ?, ?)""",
        [note_id, title, content, cat, pri, color, now, now, json.dumps(body.tags, ensure_ascii=False)],
    )
    await db.commit()

    _trigger_push(note_id)

    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    return _row_to_note(rows[0])


@router.put("/{note_id}", response_model=NoteResponse)
async def update_note(note_id: str, body: NoteUpdate):
    db = await get_db()
    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    if not rows:
        raise HTTPException(status_code=404, detail="Note not found")

    now = int(time.time() * 1000)
    updates = ["updated_at = ?", "status = 'LOCAL_ONLY'"]
    params: list = [now]

    if body.content is not None:
        updates.append("content = ?")
        params.append(body.content.strip())
        title = body.content.strip().split("\n")[0][:36]
        updates.append("title = ?")
        params.append(title)
    if body.title is not None:
        updates.append("title = ?")
        params.append(body.title)
    if body.category is not None:
        updates.append("category = ?")
        params.append(body.category.upper())
    if body.priority is not None:
        updates.append("priority = ?")
        params.append(body.priority.upper())
    if body.color_token is not None:
        updates.append("color_token = ?")
        params.append(body.color_token.upper())
    elif body.category is not None or body.priority is not None:
        cat = body.category.upper() if body.category else rows[0]["category"]
        pri = body.priority.upper() if body.priority else rows[0]["priority"]
        updates.append("color_token = ?")
        params.append(default_color_for(cat, pri))
    if body.tags is not None:
        updates.append("tags_json = ?")
        params.append(json.dumps(body.tags, ensure_ascii=False))

    params.append(note_id)
    await db.execute(f"UPDATE notes SET {', '.join(updates)} WHERE id = ?", params)
    await db.commit()

    _trigger_push(note_id)

    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    return _row_to_note(rows[0])


@router.post("/{note_id}/archive", response_model=NoteResponse)
async def archive_note(note_id: str):
    db = await get_db()
    now = int(time.time() * 1000)
    cursor = await db.execute(
        "UPDATE notes SET is_archived = 1, archived_at = ?, updated_at = ?, status = 'LOCAL_ONLY' WHERE id = ? AND is_trashed = 0",
        [now, now, note_id],
    )
    if cursor.rowcount == 0:
        raise HTTPException(status_code=404, detail="Note not found")
    await db.commit()
    _trigger_push(note_id)
    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    return _row_to_note(rows[0])


@router.post("/{note_id}/unarchive", response_model=NoteResponse)
async def unarchive_note(note_id: str):
    db = await get_db()
    now = int(time.time() * 1000)
    cursor = await db.execute(
        "UPDATE notes SET is_archived = 0, archived_at = NULL, updated_at = ?, status = 'LOCAL_ONLY' WHERE id = ?",
        [now, note_id],
    )
    if cursor.rowcount == 0:
        raise HTTPException(status_code=404, detail="Note not found")
    await db.commit()
    _trigger_push(note_id)
    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    return _row_to_note(rows[0])


@router.post("/{note_id}/trash", response_model=NoteResponse)
async def trash_note(note_id: str):
    db = await get_db()
    now = int(time.time() * 1000)
    cursor = await db.execute(
        "UPDATE notes SET is_trashed = 1, trashed_at = ?, updated_at = ?, status = 'LOCAL_ONLY' WHERE id = ?",
        [now, now, note_id],
    )
    if cursor.rowcount == 0:
        raise HTTPException(status_code=404, detail="Note not found")
    await db.commit()
    # trashed 的笔记，push_single_note 内部会识别并走 delete 分支
    _trigger_delete_remote(note_id)
    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    return _row_to_note(rows[0])


@router.post("/{note_id}/restore", response_model=NoteResponse)
async def restore_note(note_id: str):
    db = await get_db()
    now = int(time.time() * 1000)
    cursor = await db.execute(
        "UPDATE notes SET is_trashed = 0, trashed_at = NULL, updated_at = ?, status = 'LOCAL_ONLY' WHERE id = ?",
        [now, note_id],
    )
    if cursor.rowcount == 0:
        raise HTTPException(status_code=404, detail="Note not found")
    await db.commit()
    _trigger_push(note_id)
    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    return _row_to_note(rows[0])


@router.delete("/{note_id}", status_code=204)
async def delete_note_permanently(note_id: str):
    db = await get_db()
    now = int(time.time() * 1000)
    # 先记住 remote_path 再删 notes
    rows = await db.execute_fetchall("SELECT remote_path FROM notes WHERE id = ?", [note_id])
    remote_path = rows[0]["remote_path"] if rows else None
    await db.execute("DELETE FROM notes WHERE id = ?", [note_id])
    await db.execute("DELETE FROM ai_suggestions WHERE note_id = ?", [note_id])
    await db.execute("DELETE FROM reminders WHERE note_id = ?", [note_id])
    await db.execute("INSERT OR REPLACE INTO tombstones (note_id, deleted_at) VALUES (?, ?)", [note_id, now])
    await db.commit()
    # 远端删除（在 notes 行已移除后，delete_remote_by_id 会走扫描兜底）
    if remote_path:
        # 直接删，不扫描
        import asyncio as _asyncio
        async def _del():
            from .sync_orchestrator import WebDavClient
            from . import settings_store
            cfg = await settings_store.get_webdav_config()
            if not cfg.get("base_url"):
                return
            client = await WebDavClient.from_store()
            try:
                await client.delete_path(remote_path)
            except Exception:
                pass
            finally:
                await client.close()
        _asyncio.create_task(_del())


class ClientContext(BaseModel):
    """客户端携带的时间上下文，用于 AI 解析相对时间（如"明天上午 9 点"）"""
    current_time_epoch_ms: int | None = None
    current_timezone: str | None = None
    current_time_text: str | None = None


@router.post("/{note_id}/ai-analyze", response_model=AiSuggestionResponse)
async def trigger_ai_analysis(note_id: str, ctx: ClientContext | None = Body(default=None)):
    db = await get_db()
    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    if not rows:
        raise HTTPException(status_code=404, detail="Note not found")
    note = rows[0]
    now = int(time.time() * 1000)
    suggestion_id = str(uuid.uuid4())

    await db.execute(
        """INSERT OR REPLACE INTO ai_suggestions
           (id, note_id, status, summary, created_at, updated_at)
           VALUES (?, ?, 'RUNNING', '', ?, ?)""",
        [suggestion_id, note_id, now, now],
    )
    await db.commit()

    try:
        # 优先用客户端传来的时间上下文，兜底用服务器时间（在 _resolve_time_context）
        c = ctx or ClientContext()
        request = AiAnalyzeRequest(
            noteId=note_id,
            title=note["title"],
            content=note["content"],
            source=note["source"],
            category=note["category"],
            priority=note["priority"],
            transcript=note["transcript"],
            trigger="MANUAL",
            model="",
            currentTimeText=c.current_time_text,
            currentTimezone=c.current_timezone,
            currentTimeEpochMs=c.current_time_epoch_ms,
        )
        result = analyze_note(request)

        await db.execute(
            """UPDATE ai_suggestions SET
               status = 'READY', summary = ?, suggested_title = ?, suggested_category = ?,
               suggested_priority = ?, todo_items_json = ?, extracted_entities_json = ?,
               reminder_candidates_json = ?, updated_at = ?
               WHERE id = ?""",
            [
                result.summary,
                result.suggestedTitle,
                result.suggestedCategory,
                result.suggestedPriority,
                json.dumps([t for t in result.todoItems], ensure_ascii=False),
                json.dumps([e.model_dump() for e in result.extractedEntities], ensure_ascii=False),
                json.dumps([r.model_dump() for r in result.reminderCandidates], ensure_ascii=False),
                int(time.time() * 1000),
                suggestion_id,
            ],
        )
        await db.commit()
    except Exception as e:
        await db.execute(
            "UPDATE ai_suggestions SET status = 'FAILED', error_message = ?, updated_at = ? WHERE id = ?",
            [str(e)[:200], int(time.time() * 1000), suggestion_id],
        )
        await db.commit()

    srows = await db.execute_fetchall("SELECT * FROM ai_suggestions WHERE id = ?", [suggestion_id])
    return _row_to_suggestion(srows[0])


@router.get("/{note_id}/suggestions", response_model=list[AiSuggestionResponse])
async def get_suggestions(note_id: str):
    db = await get_db()
    rows = await db.execute_fetchall(
        "SELECT * FROM ai_suggestions WHERE note_id = ? ORDER BY updated_at DESC", [note_id]
    )
    return [_row_to_suggestion(r) for r in rows]


def _row_to_suggestion(row) -> AiSuggestionResponse:
    return AiSuggestionResponse(
        id=row["id"],
        note_id=row["note_id"],
        status=row["status"],
        summary=row["summary"],
        suggested_title=row["suggested_title"],
        suggested_category=row["suggested_category"],
        suggested_priority=row["suggested_priority"],
        todo_items=json.loads(row["todo_items_json"] or "[]"),
        extracted_entities=json.loads(row["extracted_entities_json"] or "[]"),
        reminder_candidates=json.loads(row["reminder_candidates_json"] or "[]"),
        error_message=row["error_message"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )
