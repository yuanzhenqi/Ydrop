"""Note dict ↔ Bitable record fields 双向转换。

字段映射依据 feishu_client.YDROP_SCHEMA：
- 标题 (text) ← note.title
- 内容 (text) ← note.content
- 类型 (single-select) ← CATEGORY_TO_LABEL[note.category]  (与 markdown_format 同表)
- 优先级 (single-select) ← PRIORITY_TO_LABEL[note.priority]
- 标签 (multi-select) ← note.tags
- 已归档 (checkbox) ← note.is_archived
- 创建时间 (date, 毫秒戳)
- 更新时间 (date, 毫秒戳)
- ydrop_id (text) ← note.id

只做 Ydrop → Bitable 这一向；反向（Step 3）会写 bitable_to_note。
"""

from __future__ import annotations

from typing import Any

# 与 markdown_format.py 保持同步，避免分裂
CATEGORY_TO_LABEL = {"NOTE": "普通", "TODO": "待办", "TASK": "任务", "REMINDER": "提醒"}
PRIORITY_TO_LABEL = {"LOW": "低", "MEDIUM": "中", "HIGH": "高", "URGENT": "紧急"}

LABEL_TO_CATEGORY = {v: k for k, v in CATEGORY_TO_LABEL.items()}
LABEL_TO_PRIORITY = {v: k for k, v in PRIORITY_TO_LABEL.items()}


def note_to_bitable_fields(note: dict[str, Any]) -> dict[str, Any]:
    """把 note dict 转换成 Bitable record 的 fields。

    note 应当来自 SQLite 一行（含 id/title/content/category/priority/tags/is_archived/created_at/updated_at）。
    缺失字段用合理默认值。"""
    return {
        "标题": (note.get("title") or "").strip() or "无标题",
        "内容": note.get("content") or "",
        "类型": CATEGORY_TO_LABEL.get(note.get("category", "NOTE"), "普通"),
        "优先级": PRIORITY_TO_LABEL.get(note.get("priority", "MEDIUM"), "中"),
        "标签": list(note.get("tags") or []),
        "已归档": bool(note.get("is_archived", False)),
        "创建时间": int(note.get("created_at") or 0),
        "更新时间": int(note.get("updated_at") or 0),
        "ydrop_id": note.get("id") or "",
    }


def _coerce_text(v: Any) -> str:
    """Bitable 文本字段返回的可能是 str，也可能是 [{type:'text', text:'...'}]（多行文本）。"""
    if v is None:
        return ""
    if isinstance(v, str):
        return v
    if isinstance(v, list):
        # 富文本 segment 数组
        return "".join(seg.get("text", "") if isinstance(seg, dict) else str(seg) for seg in v)
    if isinstance(v, dict):
        return v.get("text", "") or v.get("value", "") or ""
    return str(v)


def _coerce_select_label(v: Any) -> str:
    """单选返回的可能是 str（选项名）。多选是 list[str]。这里只取单选。"""
    if isinstance(v, str):
        return v
    if isinstance(v, list) and v:
        first = v[0]
        return _coerce_text(first)
    return ""


def _coerce_multi_select(v: Any) -> list[str]:
    if v is None:
        return []
    if isinstance(v, list):
        return [_coerce_text(x) for x in v if x]
    if isinstance(v, str):
        return [v]
    return []


def bitable_fields_to_note_dict(fields: dict, record_id: str, last_modified_ms: int) -> dict[str, Any]:
    """把 Bitable record.fields 转换回 note dict。

    返回 dict 含：id (来自 ydrop_id 或生成)、title、content、category、priority、tags、
    is_archived、created_at、updated_at。调用方负责把它写进 SQLite。
    last_modified_ms 由调用方从 record.last_modified_time 传入（毫秒）。
    """
    title = _coerce_text(fields.get("标题")).strip()
    content = _coerce_text(fields.get("内容"))
    category_label = _coerce_select_label(fields.get("类型"))
    priority_label = _coerce_select_label(fields.get("优先级"))
    tags = _coerce_multi_select(fields.get("标签"))
    is_archived = bool(fields.get("已归档") or False)
    created_at_raw = fields.get("创建时间")
    created_at = int(created_at_raw) if isinstance(created_at_raw, (int, float)) else last_modified_ms
    updated_at = last_modified_ms
    ydrop_id = _coerce_text(fields.get("ydrop_id")).strip()

    return {
        "id": ydrop_id,  # 空字符串表示 Bitable 端新建、没绑过 ydrop_id；caller 决定生成 uuid
        "title": title or "无标题",
        "content": content,
        "category": LABEL_TO_CATEGORY.get(category_label, "NOTE"),
        "priority": LABEL_TO_PRIORITY.get(priority_label, "MEDIUM"),
        "tags": tags,
        "is_archived": is_archived,
        "created_at": created_at,
        "updated_at": updated_at,
        "_record_id": record_id,
    }
