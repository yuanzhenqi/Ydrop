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
