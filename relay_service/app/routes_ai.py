"""AI 第二阶段：基于笔记的问答 + 批量整理"""

from __future__ import annotations

import json
import logging
import time
import urllib.request
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

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


class ChatRequest(BaseModel):
    messages: list[ChatMessage]
    filter: Optional[ChatFilter] = None


class ChatResponse(BaseModel):
    answer: str
    referenced_note_ids: list[str] = Field(default_factory=list)
    referenced_count: int = 0
    provider_error: Optional[str] = None
    used_provider: bool = False


@router.post("/chat", response_model=ChatResponse)
async def chat(body: ChatRequest):
    """基于用户笔记的问答。把符合筛选条件的笔记作为 context 喂给 LLM。"""
    f = body.filter or ChatFilter()
    db = await get_db()

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
            "content": r["content"][:500],
            "category": r["category"],
            "priority": r["priority"],
            "tags": json.loads(r["tags_json"] or "[]"),
            "created_at": r["created_at"],
        }
        for r in rows
    ]

    from . import settings_store
    ai_cfg = await settings_store.get_ai_config()

    used_provider = False
    provider_error: Optional[str] = None
    if ai_cfg["enabled"] and ai_cfg["base_url"] and ai_cfg["token"]:
        try:
            answer = _call_chat_provider(body.messages, notes, ai_cfg)
            used_provider = True
        except Exception as e:
            logger.error("Chat provider failed: %s", e)
            provider_error = str(e)[:250]
            answer = _heuristic_chat_answer(body.messages, notes)
    else:
        answer = _heuristic_chat_answer(body.messages, notes)

    return ChatResponse(
        answer=answer,
        referenced_note_ids=[n["id"] for n in notes],
        referenced_count=len(notes),
        provider_error=provider_error,
        used_provider=used_provider,
    )


def _call_chat_provider(messages: list[ChatMessage], notes: list[dict], cfg: dict) -> str:
    from .ai_provider import call_llm

    system = (
        "你是用户的个人笔记助手。用户会用中文问你关于他自己笔记的问题。\n"
        "下面是用户最近的笔记（JSON 数组，按更新时间倒序）。请只基于这些笔记内容回答，不要编造信息。\n"
        "如果答案不在笔记里，明确说「没有找到相关笔记」。\n"
        "引用笔记时用「《标题》」格式标注。\n"
        "回答简洁，用自然中文，不要堆砌术语。\n\n"
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
    ai_cfg = await settings_store.get_ai_config()
    clusters = _call_organize_provider(notes, ai_cfg) if (ai_cfg["enabled"] and ai_cfg["base_url"] and ai_cfg["token"]) else _heuristic_organize(notes)

    return BatchOrganizeResponse(total_analyzed=len(notes), clusters=clusters)


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
