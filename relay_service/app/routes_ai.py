"""AI 第二阶段：基于笔记的问答 + 批量整理"""

from __future__ import annotations

import json
import logging
import time
import urllib.request
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, field_validator

from .auth import require_relay_token
from .config import get_settings
from .database import get_db

logger = logging.getLogger("ai_chat")

router = APIRouter(prefix="/api/ai", dependencies=[Depends(require_relay_token)])


# ─── 问答 ───

class ChatMessage(BaseModel):
    role: str  # user / assistant
    content: str


class ChatFilter(BaseModel):
    category: Optional[str] = None
    priority: Optional[str] = None
    tag: Optional[str] = None
    from_ts: Optional[int] = Field(default=None, alias="from")
    to_ts: Optional[int] = Field(default=None, alias="to")
    include_archived: bool = False
    max_notes: int = 30

    model_config = {"populate_by_name": True}

    @field_validator("max_notes")
    @classmethod
    def _cap_max_notes(cls, v: int) -> int:
        # 下限 1 上限 200：太低会丢 context，太高会把 provider 的 token 预算炸掉
        return max(1, min(v, 200))


class AiConfigPayload(BaseModel):
    """App 端发过来的 AI provider 配置；服务端 settings_store 里没配时用这个。"""
    base_url: str
    token: str
    model: str = "gpt-4o-mini"
    endpoint_mode: str = "AUTO"  # AUTO | OPENAI | ANTHROPIC


class InlineNote(BaseModel):
    """App 端直接把本地笔记随请求带过来的载荷。Android 端走 WebDAV/NAS 不同步到 relay DB，
    所以不能指望 relay 的 `notes` 表里有 app 用户的笔记；inline 带过来就能让 LLM 读到。"""
    id: str
    title: str = ""
    content: str = ""
    category: str = "NOTE"
    priority: str = "MEDIUM"
    tags: list[str] = Field(default_factory=list)
    created_at: int = 0


class ChatRequest(BaseModel):
    messages: list[ChatMessage]
    filter: Optional[ChatFilter] = None
    session_id: Optional[str] = None  # 空则创建新 session
    ai_config: Optional[AiConfigPayload] = None  # 优先用 request 里的，没带才读 relay 自己的 settings
    inline_notes: Optional[list[InlineNote]] = None  # 优先用这个当 context；没传才回退到 relay DB 里查
    # 客户端当前时间锚点：让 LLM 能解析"今天/本周/最近 N 天"这类相对时间表达。
    # 不传时 server 端用 datetime.now(UTC) 兜底，但客户端时区会丢，建议传齐三个。
    current_time_epoch_ms: Optional[int] = None
    current_timezone: Optional[str] = None
    current_time_text: Optional[str] = None  # 客户端格式化好的本地时间文本，避免 server 再做时区换算


class ChatResponse(BaseModel):
    answer: str
    referenced_note_ids: list[str] = Field(default_factory=list)
    referenced_count: int = 0
    provider_error: Optional[str] = None
    used_provider: bool = False
    session_id: str = ""
    session_title: str = ""


class ChatSessionSummary(BaseModel):
    id: str
    title: str
    created_at: int
    updated_at: int
    message_count: int


class ChatSessionDetail(BaseModel):
    id: str
    title: str
    created_at: int
    updated_at: int
    messages: list[dict]


class OrganizeRunSummary(BaseModel):
    id: str
    total_analyzed: int
    cluster_count: int
    created_at: int


class OrganizeRunDetail(BaseModel):
    id: str
    total_analyzed: int
    clusters: list[dict]
    applied_cluster_ids: list[str]
    created_at: int


@router.post("/chat", response_model=ChatResponse)
async def chat(body: ChatRequest):
    """基于用户笔记的问答。把符合筛选条件的笔记作为 context 喂给 LLM。"""
    logger.info(
        "chat incoming: msgs=%d roles=%s session=%s ai_cfg=%s",
        len(body.messages),
        [m.role for m in body.messages[:5]],
        body.session_id or "-",
        "yes" if (body.ai_config and body.ai_config.base_url and body.ai_config.token) else "no",
    )
    f = body.filter or ChatFilter()
    db = await get_db()

    # 优先用 app 端 inline 带过来的笔记当 context（Android 端笔记不同步到 relay DB，
    # 必须走 inline 才能让 LLM 看到）。没传才回落到 relay DB 自己的 notes 表。
    if body.inline_notes:
        # 按 filter 在 server 侧再筛一刀，保持和 DB 路径同样的语义
        candidates = list(body.inline_notes)
        if f.category:
            candidates = [n for n in candidates if (n.category or "").upper() == f.category.upper()]
        if f.priority:
            candidates = [n for n in candidates if (n.priority or "").upper() == f.priority.upper()]
        if f.tag:
            candidates = [n for n in candidates if f.tag in (n.tags or [])]
        if f.from_ts is not None:
            candidates = [n for n in candidates if n.created_at >= f.from_ts]
        if f.to_ts is not None:
            candidates = [n for n in candidates if n.created_at <= f.to_ts]
        # 不再按 created_at 重排：app 端已经按 pinned+updatedAt 排好序并 take(80)，
        # 这里重排会把"最近活跃"的顺序弄乱，让 LLM 看到的笔记头尾相反。
        candidates = candidates[: f.max_notes]
        notes = [
            {
                "id": n.id,
                "title": n.title,
                "content": _clip_note_content(n.content or ""),
                "category": n.category,
                "priority": n.priority,
                "tags": list(n.tags or []),
                "created_at": n.created_at,
            }
            for n in candidates
        ]
        logger.info("chat: using %d inline_notes (filtered from %d)", len(notes), len(body.inline_notes))
    else:
        conditions = ["is_trashed = 0"]
        params: list = []
        if not f.include_archived:
            conditions.append("is_archived = 0")
        if f.category:
            conditions.append("category = ?")
            params.append(f.category.upper())
        if f.priority:
            conditions.append("priority = ?")
            params.append(f.priority.upper())
        if f.tag:
            conditions.append("tags_json LIKE ?")
            params.append(f'%"{f.tag}"%')
        if f.from_ts is not None:
            conditions.append("created_at >= ?")
            params.append(f.from_ts)
        if f.to_ts is not None:
            conditions.append("created_at <= ?")
            params.append(f.to_ts)

        where = " AND ".join(conditions)
        rows = await db.execute_fetchall(
            f"SELECT id, title, content, category, priority, tags_json, created_at FROM notes WHERE {where} ORDER BY updated_at DESC LIMIT ?",
            params + [f.max_notes],
        )

        notes = [
            {
                "id": r["id"],
                "title": r["title"],
                "content": _clip_note_content(r["content"] or ""),
                "category": r["category"],
                "priority": r["priority"],
                "tags": json.loads(r["tags_json"] or "[]"),
                "created_at": r["created_at"],
            }
            for r in rows
        ]

    # 优先用 request 里 app 端发过来的 AI 配置；没带才回落到 relay 自己的 settings_store。
    # 这样 app 用户只在 App 里配一次 AI 就行，不用再去改 relay 的 .env。
    if body.ai_config and body.ai_config.base_url and body.ai_config.token:
        ai_cfg = {
            "enabled": True,
            "base_url": body.ai_config.base_url,
            "token": body.ai_config.token,
            "model": body.ai_config.model or "gpt-4o-mini",
            "endpoint_mode": (body.ai_config.endpoint_mode or "AUTO").upper(),
        }
        logger.info(
            "chat: using request ai_config base_url=%s model=%s mode=%s",
            ai_cfg["base_url"], ai_cfg["model"], ai_cfg["endpoint_mode"],
        )
    else:
        from . import settings_store
        ai_cfg = await settings_store.get_ai_config()
        logger.info(
            "chat: using relay ai_config base_url=%s enabled=%s",
            ai_cfg.get("base_url", ""), ai_cfg.get("enabled", False),
        )

    # 调用 provider 前打一条预览日志，方便对照客户端日志快速定位链路断点。
    preview = ", ".join(
        f"{(n['id'] or '')[:6]}:{(n['title'] or (n['content'] or '')[:10])[:12]}"
        for n in notes[:3]
    )
    logger.info("chat: notes_for_llm=%d preview=[%s]", len(notes), preview)

    # 当前时间锚点：客户端传的优先，没传就用 server 的 UTC 兜底（但缺时区可能让 LLM 算偏）。
    time_ctx = _build_time_context(
        body.current_time_epoch_ms,
        body.current_timezone,
        body.current_time_text,
    )
    logger.info(
        "chat: time_ctx now=%s tz=%s ms=%s",
        time_ctx["current_time"], time_ctx["current_timezone"], time_ctx["current_time_ms"],
    )

    used_provider = False
    provider_error: Optional[str] = None
    if ai_cfg["enabled"] and ai_cfg["base_url"] and ai_cfg["token"]:
        try:
            answer = _call_chat_provider(body.messages, notes, ai_cfg, time_ctx)
            used_provider = True
        except Exception as e:
            logger.error("Chat provider failed: %s", e)
            provider_error = str(e)[:250]
            answer = _heuristic_chat_answer(body.messages, notes)
    else:
        answer = _heuristic_chat_answer(body.messages, notes)

    # ─── 持久化到 ai_chat_sessions / ai_chat_messages ───
    import uuid as _uuid
    now = int(time.time() * 1000)
    session_id = body.session_id
    session_title = ""

    if not session_id:
        # 新建会话：用第一条用户消息前 30 字作 title
        first_user_msg = next((m.content for m in body.messages if m.role == "user"), "新会话")
        session_title = first_user_msg.strip().replace("\n", " ")[:30]
        session_id = str(_uuid.uuid4())
        await db.execute(
            "INSERT INTO ai_chat_sessions (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
            [session_id, session_title, now, now],
        )
    else:
        rows = await db.execute_fetchall("SELECT title FROM ai_chat_sessions WHERE id = ?", [session_id])
        if not rows:
            # session_id 无效，新建
            first_user_msg = next((m.content for m in body.messages if m.role == "user"), "新会话")
            session_title = first_user_msg.strip().replace("\n", " ")[:30]
            await db.execute(
                "INSERT INTO ai_chat_sessions (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                [session_id, session_title, now, now],
            )
        else:
            session_title = rows[0]["title"] or ""
            await db.execute("UPDATE ai_chat_sessions SET updated_at = ? WHERE id = ?", [now, session_id])

    # 存 user 消息（取最后一条 user）+ assistant 回答
    last_user = next((m for m in reversed(body.messages) if m.role == "user"), None)
    if last_user:
        await db.execute(
            "INSERT INTO ai_chat_messages (id, session_id, role, content, used_provider, created_at) VALUES (?, ?, 'user', ?, 0, ?)",
            [str(_uuid.uuid4()), session_id, last_user.content, now],
        )
    await db.execute(
        """INSERT INTO ai_chat_messages (id, session_id, role, content, referenced_note_ids, provider_error, used_provider, created_at)
           VALUES (?, ?, 'assistant', ?, ?, ?, ?, ?)""",
        [
            str(_uuid.uuid4()), session_id, answer,
            json.dumps([n["id"] for n in notes]),
            provider_error, 1 if used_provider else 0, now + 1,
        ],
    )
    await db.commit()

    return ChatResponse(
        answer=answer,
        referenced_note_ids=[n["id"] for n in notes],
        referenced_count=len(notes),
        provider_error=provider_error,
        used_provider=used_provider,
        session_id=session_id,
        session_title=session_title,
    )


# ─── 会话管理端点 ───

@router.get("/sessions", response_model=list[ChatSessionSummary])
async def list_sessions():
    db = await get_db()
    rows = await db.execute_fetchall(
        """SELECT s.id, s.title, s.created_at, s.updated_at,
           (SELECT COUNT(*) FROM ai_chat_messages WHERE session_id = s.id) as msg_count
           FROM ai_chat_sessions s ORDER BY s.updated_at DESC LIMIT 100"""
    )
    return [
        ChatSessionSummary(
            id=r["id"], title=r["title"] or "", created_at=r["created_at"],
            updated_at=r["updated_at"], message_count=r["msg_count"],
        )
        for r in rows
    ]


@router.get("/sessions/{session_id}", response_model=ChatSessionDetail)
async def get_session(session_id: str):
    db = await get_db()
    s_rows = await db.execute_fetchall("SELECT * FROM ai_chat_sessions WHERE id = ?", [session_id])
    if not s_rows:
        raise HTTPException(404, "Session not found")
    s = s_rows[0]
    m_rows = await db.execute_fetchall(
        "SELECT role, content, referenced_note_ids, provider_error, used_provider, created_at FROM ai_chat_messages WHERE session_id = ? ORDER BY created_at ASC",
        [session_id],
    )
    messages = [
        {
            "role": m["role"],
            "content": m["content"],
            "referenced_note_ids": json.loads(m["referenced_note_ids"] or "[]"),
            "provider_error": m["provider_error"],
            "used_provider": bool(m["used_provider"]),
            "created_at": m["created_at"],
        }
        for m in m_rows
    ]
    return ChatSessionDetail(
        id=s["id"], title=s["title"] or "", created_at=s["created_at"],
        updated_at=s["updated_at"], messages=messages,
    )


@router.delete("/sessions/{session_id}", status_code=204)
async def delete_session(session_id: str):
    db = await get_db()
    await db.execute("DELETE FROM ai_chat_messages WHERE session_id = ?", [session_id])
    await db.execute("DELETE FROM ai_chat_sessions WHERE id = ?", [session_id])
    await db.commit()


def _clip_note_content(s: str, limit: int = 1500) -> str:
    """把笔记正文截到 limit 字再送进 LLM context。
    之前 500 字太狠，长笔记关键段会被砍掉，导致 AI 答"没找到"。
    超出时拼一个省略提示让模型知道后面还有内容。"""
    s = s or ""
    if len(s) <= limit:
        return s
    return s[:limit] + f"…（省略 {len(s) - limit} 字）"


def _build_time_context(
    epoch_ms: Optional[int],
    tz_name: Optional[str],
    text: Optional[str],
) -> dict:
    """把客户端传来的时间锚点（或 server 兜底）整理成 LLM 能直接用的字段。

    - 客户端传齐 epoch_ms / tz_name / text 时直接使用，避免 server 再换算时区。
    - 客户端没传时用 server 端 UTC 兜底，并记日志提醒（结果对中文相对时间的解析会偏）。
    """
    if epoch_ms is None:
        epoch_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    tz_label = (tz_name or "").strip() or "UTC"
    if text and text.strip():
        time_text = text.strip()
    else:
        # 没有客户端文本时退到 server 时区，至少给一个可读的当前时间锚点。
        time_text = datetime.fromtimestamp(epoch_ms / 1000).strftime("%Y-%m-%d %H:%M:%S")
    return {
        "current_time": time_text,
        "current_timezone": tz_label,
        "current_time_ms": str(epoch_ms),
    }


def _call_chat_provider(messages: list[ChatMessage], notes: list[dict], cfg: dict, time_ctx: dict) -> str:
    from .ai_provider import call_llm

    # 系统 prompt：原来的"如果答案不在笔记里，明确说「没有找到相关笔记」"太硬，
    # 会让模型对包含相关线索但不直接命中的问题也直接拒答。放宽成分层次：
    # 直接答案 → 相关线索 → 完全没有才说"找不到"。
    system = (
        "你是用户的个人笔记助手。用户会用中文问你关于他自己笔记的问题。\n"
        f"Current system time: {time_ctx['current_time']}\n"
        f"Current system timezone: {time_ctx['current_timezone']}\n"
        f"Current system Unix milliseconds: {time_ctx['current_time_ms']}\n"
        "TIME RESOLUTION RULES:\n"
        "- 笔记的 created_at 字段是 Unix 毫秒时间戳；\n"
        "- 用户问「今天 / 昨天 / 本周 / 最近 N 天 / 这个月」等相对时间时，请基于上面 current time 把范围算出来再过滤；\n"
        "- 中文相对词参考：今天=当天 0-24 时；昨天=前一天；本周=本周一 0:00 至本周日 24:00；\n"
        "  最近 N 天=current time 往前 N 天；这个月=本月 1 号 0:00 至月末。\n"
        "- 给笔记日期时优先用 YYYY-MM-DD 形式，让用户一眼看懂；不要直接抛出 epoch ms。\n\n"
        "回答原则：\n"
        "1. 有直接答案就基于笔记内容作答；\n"
        "2. 没有直接答案但有相关笔记，可以给出作为「相关线索」，并说明这是线索而非精确答案；\n"
        "3. 完全没有相关笔记，如实说「笔记里没有找到相关内容」，再引导用户补充信息；\n"
        "4. 不要编造笔记里没有的信息。\n"
        "引用笔记时优先用「《标题》」格式；标题为空时用正文前 8 个字代替。\n"
        "回答用自然中文，简洁、不堆砌术语。\n\n"
        f"用户笔记：{json.dumps(notes, ensure_ascii=False)}"
    )

    llm_messages = [
        {"role": "system", "content": system},
        *[{"role": m.role, "content": m.content} for m in messages],
    ]
    return call_llm(llm_messages, cfg, response_format="text")


def _heuristic_chat_answer(messages: list[ChatMessage], notes: list[dict]) -> str:
    """没配 provider 时的兜底：基于关键词匹配的启发式回答。"""
    if not notes:
        return "没有找到相关笔记。请先创建一些笔记，或调整筛选条件。"
    last_q = next((m.content for m in reversed(messages) if m.role == "user"), "")
    if not last_q:
        return "请提出具体问题。"

    keywords = [w for w in last_q.replace("？", "").replace("?", "").split() if len(w) > 1]
    matched = []
    for n in notes:
        text = f"{n['title']} {n['content']}"
        if any(k in text for k in keywords):
            matched.append(n)

    if not matched:
        matched = notes[:5]
        return f"（未配置 AI provider，返回最近 {len(matched)} 条笔记）\n" + "\n".join(
            f"- 《{n['title']}》（{n['category']}，{n['created_at']}）" for n in matched
        )

    return f"找到 {len(matched)} 条相关笔记：\n" + "\n".join(
        f"- 《{n['title']}》：{n['content'][:80]}..." for n in matched[:10]
    )


# ─── 批量整理 ───

class BatchOrganizeRequest(BaseModel):
    note_ids: Optional[list[str]] = None  # 不传则处理所有 inbox
    max_notes: int = 50


class ClusterSuggestion(BaseModel):
    cluster_id: str
    theme: str
    note_ids: list[str]
    suggested_action: str  # "merge" / "convert_to_task" / "keep"
    suggested_title: Optional[str] = None
    reason: str = ""


class BatchOrganizeResponse(BaseModel):
    total_analyzed: int
    clusters: list[ClusterSuggestion]


@router.post("/batch-organize", response_model=BatchOrganizeResponse)
async def batch_organize(body: BatchOrganizeRequest):
    """对多条笔记做聚类分析，建议合并/转类型。"""
    db = await get_db()
    if body.note_ids:
        placeholders = ",".join(["?"] * len(body.note_ids))
        rows = await db.execute_fetchall(
            f"SELECT id, title, content, category, tags_json, created_at FROM notes WHERE id IN ({placeholders}) AND is_trashed = 0",
            body.note_ids,
        )
    else:
        rows = await db.execute_fetchall(
            "SELECT id, title, content, category, tags_json, created_at FROM notes WHERE is_trashed = 0 AND is_archived = 0 ORDER BY updated_at DESC LIMIT ?",
            [body.max_notes],
        )

    notes = [
        {
            "id": r["id"],
            "title": r["title"],
            "content": r["content"][:300],
            "category": r["category"],
            "tags": json.loads(r["tags_json"] or "[]"),
            "created_at": r["created_at"],
        }
        for r in rows
    ]

    if len(notes) < 2:
        return BatchOrganizeResponse(total_analyzed=len(notes), clusters=[])

    from . import settings_store
    import uuid as _uuid
    ai_cfg = await settings_store.get_ai_config()
    clusters = _call_organize_provider(notes, ai_cfg) if (ai_cfg["enabled"] and ai_cfg["base_url"] and ai_cfg["token"]) else _heuristic_organize(notes)

    # 持久化本次分析
    run_id = str(_uuid.uuid4())
    now = int(time.time() * 1000)
    clusters_json = json.dumps([c.model_dump() for c in clusters], ensure_ascii=False)
    await db.execute(
        "INSERT INTO ai_organize_runs (id, total_analyzed, clusters_json, created_at) VALUES (?, ?, ?, ?)",
        [run_id, len(notes), clusters_json, now],
    )
    await db.commit()

    return BatchOrganizeResponse(total_analyzed=len(notes), clusters=clusters)


@router.get("/organize-runs", response_model=list[OrganizeRunSummary])
async def list_organize_runs():
    db = await get_db()
    rows = await db.execute_fetchall(
        "SELECT id, total_analyzed, clusters_json, created_at FROM ai_organize_runs ORDER BY created_at DESC LIMIT 50"
    )
    return [
        OrganizeRunSummary(
            id=r["id"], total_analyzed=r["total_analyzed"],
            cluster_count=len(json.loads(r["clusters_json"] or "[]")),
            created_at=r["created_at"],
        )
        for r in rows
    ]


@router.get("/organize-runs/{run_id}", response_model=OrganizeRunDetail)
async def get_organize_run(run_id: str):
    db = await get_db()
    rows = await db.execute_fetchall("SELECT * FROM ai_organize_runs WHERE id = ?", [run_id])
    if not rows:
        raise HTTPException(404, "Run not found")
    r = rows[0]
    return OrganizeRunDetail(
        id=r["id"],
        total_analyzed=r["total_analyzed"],
        clusters=json.loads(r["clusters_json"] or "[]"),
        applied_cluster_ids=json.loads(r["applied_cluster_ids"] or "[]"),
        created_at=r["created_at"],
    )


@router.delete("/organize-runs/{run_id}", status_code=204)
async def delete_organize_run(run_id: str):
    db = await get_db()
    await db.execute("DELETE FROM ai_organize_runs WHERE id = ?", [run_id])
    await db.commit()


def _call_organize_provider(notes: list[dict], cfg: dict) -> list[ClusterSuggestion]:
    from .ai_provider import call_llm, strip_json_fence

    prompt = (
        "你是用户笔记整理助手。下面是用户的 inbox 笔记列表。\n"
        "请分析后返回聚类建议，识别出可以合并的相似笔记、可以转成任务的随手记、可以保持独立的笔记。\n"
        "只返回一个 JSON 对象，格式：{\"clusters\":[{\"cluster_id\":\"c1\",\"theme\":\"短描述\",\"note_ids\":[\"id1\",\"id2\"],\"suggested_action\":\"merge|convert_to_task|keep\",\"suggested_title\":\"建议标题\",\"reason\":\"理由\"}]}\n"
        "只返回 JSON，不要有其他文字。主题和理由用中文。\n\n"
        f"笔记：{json.dumps(notes, ensure_ascii=False)}"
    )
    try:
        content = call_llm([{"role": "user", "content": prompt}], cfg, response_format="json_object")
        result = json.loads(strip_json_fence(content))
        return [ClusterSuggestion(**c) for c in result.get("clusters", [])]
    except Exception as e:
        logger.error("Organize provider failed: %s", e)
        return _heuristic_organize(notes)


def _heuristic_organize(notes: list[dict]) -> list[ClusterSuggestion]:
    """启发式：按标签和分类聚类。"""
    clusters: list[ClusterSuggestion] = []
    # 按标签聚类
    tag_groups: dict[str, list[str]] = {}
    for n in notes:
        for t in n["tags"]:
            tag_groups.setdefault(t, []).append(n["id"])
    for i, (tag, ids) in enumerate(tag_groups.items()):
        if len(ids) >= 2:
            clusters.append(
                ClusterSuggestion(
                    cluster_id=f"tag-{i}",
                    theme=f"标签：{tag}",
                    note_ids=ids,
                    suggested_action="keep",
                    reason=f"{len(ids)} 条笔记共享标签 #{tag}",
                )
            )
    # 按 NOTE 分类聚合成任务
    note_category = [n["id"] for n in notes if n["category"] == "NOTE"]
    if len(note_category) >= 3:
        clusters.append(
            ClusterSuggestion(
                cluster_id="notes-bulk",
                theme="随手记汇总",
                note_ids=note_category,
                suggested_action="convert_to_task",
                suggested_title="整理这些随手记",
                reason=f"有 {len(note_category)} 条分类为普通的笔记，可以考虑汇总成一个整理任务",
            )
        )
    return clusters
