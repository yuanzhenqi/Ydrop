"""Markdown serializer/deserializer compatible with Android MarkdownFormatter.kt."""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from typing import Any


# ── Chinese label mappings (must match MarkdownFormatter.kt) ──

CATEGORY_TO_LABEL = {"NOTE": "普通", "TODO": "待办", "TASK": "任务", "REMINDER": "提醒"}
LABEL_TO_CATEGORY = {v: k for k, v in CATEGORY_TO_LABEL.items()}

PRIORITY_TO_LABEL = {"LOW": "低", "MEDIUM": "中", "HIGH": "高", "URGENT": "紧急"}
LABEL_TO_PRIORITY = {v: k for k, v in PRIORITY_TO_LABEL.items()}

SOURCE_TO_LABEL = {"TEXT": "文字", "VOICE": "语音"}
LABEL_TO_SOURCE = {v: k for k, v in SOURCE_TO_LABEL.items()}


def default_color_for(category: str, priority: str) -> str:
    if priority == "URGENT":
        return "ROSE"
    if category == "TODO":
        return "AMBER"
    if category == "TASK":
        return "SKY"
    if category == "REMINDER":
        return "ROSE"
    return "SAGE"


# ── Serialization ──

def _format_utc(epoch_ms: int) -> str:
    dt = datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _format_display(epoch_ms: int) -> str:
    dt = datetime.fromtimestamp(epoch_ms / 1000)
    return dt.strftime("%Y-%m-%d %H:%M")


def render(note: dict[str, Any]) -> str:
    cat_label = CATEGORY_TO_LABEL.get(note["category"], "普通")
    pri_label = PRIORITY_TO_LABEL.get(note["priority"], "中")
    src_label = SOURCE_TO_LABEL.get(note["source"], "文字")

    lines: list[str] = []
    lines.append("---")
    lines.append(f"id: {note['id']}")
    lines.append(f"createdAt: {_format_utc(note['created_at'])}")
    lines.append(f"updatedAt: {_format_utc(note['updated_at'])}")
    lines.append(f"source: {src_label}")
    lines.append(f"category: {cat_label}")
    lines.append(f"priority: {pri_label}")
    lines.append(f"archived: {str(note.get('is_archived', False)).lower()}")
    tags = note.get("tags", [])
    if tags:
        lines.append(f"tags: {','.join(tags)}")
    lines.append(f"status: {note.get('status', 'local_only').lower()}")
    ts_status = note.get("transcription_status", "NOT_STARTED")
    lines.append(f"transcriptionStatus: {ts_status.lower()}")
    if note.get("audio_path"):
        lines.append(f'audioPath: "{note["audio_path"]}"')
    if note.get("relay_url"):
        lines.append(f'relayUrl: "{note["relay_url"]}"')
    if note.get("sync_error"):
        lines.append(f'syncError: "{note["sync_error"][:120]}"')
    # attachments 跨端同步（A+ 方案）：图片元数据（含 remoteUrl）写进 frontmatter，
    # 让 Android / Web 互相看到对方上传的图。OCR 文本太大不带；description/keywords 等 ≤1KB 可控。
    serialized_atts = _serialize_attachments_for_frontmatter(note.get("attachments", []))
    if serialized_atts:
        lines.append(f"attachments: {serialized_atts}")
    lines.append("---")
    lines.append("")

    lines.append(f"# {note['title']}")
    lines.append("")

    created_disp = _format_display(note["created_at"])
    updated_disp = _format_display(note["updated_at"])
    lines.append(f"> 类型：{cat_label}　优先级：{pri_label}　来源：{src_label}")
    lines.append(f"> 创建：{created_disp}　最后更新：{updated_disp}")
    lines.append("")

    lines.append("## 记录内容")
    lines.append("")

    content = note.get("content", "").strip()
    transcript = note.get("transcript")
    if note["source"] == "VOICE" and transcript and content != transcript:
        lines.append(content)
    elif note["source"] == "VOICE" and transcript:
        lines.append(transcript.strip())
    else:
        lines.append(content)

    return "\n".join(lines) + "\n"


def _serialize_attachments_for_frontmatter(attachments: list) -> str:
    """把 attachments 列表序列化成 JSON 单行字符串塞进 frontmatter。

    跳过没 remoteUrl 的项（纯本地的 attachment 跨同步意义不大），ocrText 不带（太长）。
    与 Android MarkdownFormatter.kt 同形态：单行 JSON、camelCase 字段名。
    """
    if not attachments:
        return ""
    out = []
    for a in attachments:
        if not isinstance(a, dict):
            # 也支持 Pydantic AttachmentItem
            try:
                a = a.model_dump()
            except Exception:
                continue
        # 跨同步必须有 remoteUrl，否则跳过——本地 attachment 跨端不可用
        remote_url = a.get("remote_url") or a.get("remoteUrl") or ""
        if not remote_url:
            continue
        out.append({
            "id": a.get("id", ""),
            "type": a.get("type", "IMAGE"),
            "remoteUrl": remote_url,
            "description": (a.get("description") or "")[:600],
            "keywords": list(a.get("keywords") or [])[:10],
            "actionableItems": list(a.get("actionable_items") or a.get("actionableItems") or [])[:10],
            "dates": list(a.get("dates") or [])[:10],
            "createdAt": a.get("created_at") or a.get("createdAt") or 0,
        })
    if not out:
        return ""
    return json.dumps(out, ensure_ascii=False, separators=(",", ":"))


def _parse_attachments_from_frontmatter(value: str | None) -> list[dict]:
    """解析 frontmatter 里 attachments 字段；camelCase → snake_case 兼容 SQLite 列。"""
    if not value:
        return []
    try:
        raw = json.loads(value)
        if not isinstance(raw, list):
            return []
        out = []
        for item in raw:
            if not isinstance(item, dict):
                continue
            out.append({
                "id": item.get("id", ""),
                "type": item.get("type", "IMAGE"),
                "remote_url": item.get("remoteUrl") or item.get("remote_url") or "",
                "description": item.get("description", ""),
                "keywords": list(item.get("keywords") or []),
                "actionable_items": list(item.get("actionableItems") or item.get("actionable_items") or []),
                "dates": list(item.get("dates") or []),
                "created_at": item.get("createdAt") or item.get("created_at") or 0,
            })
        return out
    except Exception:
        return []


def file_name(note: dict[str, Any]) -> str:
    dt = datetime.fromtimestamp(note["created_at"] / 1000)
    date_str = dt.strftime("%Y-%m-%d_%H-%M")
    src_label = SOURCE_TO_LABEL.get(note["source"], "文字")
    slug = re.sub(r'[\\/:*?"<>|\s]+', "_", note["title"])[:20].rstrip("_")
    short_id = note["id"][-6:]
    return f"{date_str}_{src_label}_{slug}_{short_id}.md"


def extract_id_from_filename(filename: str) -> str | None:
    name = filename.removesuffix(".md")
    last_us = name.rfind("_")
    if last_us < 0:
        return None
    short_id = name[last_us + 1:]
    return short_id if len(short_id) == 6 else None


# ── Deserialization ──

def _parse_frontmatter(content: str) -> dict[str, str] | None:
    trimmed = content.lstrip()
    if not trimmed.startswith("---"):
        return None
    end = trimmed.find("---", 3)
    if end < 0:
        return None
    body = trimmed[3:end].strip()
    result: dict[str, str] = {}
    for line in body.splitlines():
        idx = line.find(":")
        if idx > 0:
            key = line[:idx].strip()
            value = line[idx + 1:].strip().strip('"')
            if key:
                result[key] = value
    return result or None


def _parse_timestamp(value: str | None) -> int | None:
    if not value:
        return None
    try:
        dt = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        return int(dt.timestamp() * 1000)
    except (ValueError, TypeError):
        return None


def _skip_frontmatter(content: str) -> str:
    trimmed = content.lstrip()
    if not trimmed.startswith("---"):
        return content
    end = trimmed.find("---", 3)
    return trimmed[end + 3:] if end >= 0 else content


def _extract_title(content: str) -> str:
    after = _skip_frontmatter(content)
    for line in after.strip().splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return "Imported note"


def _extract_body(content: str) -> str:
    after = _skip_frontmatter(content)
    lines = after.strip().splitlines()
    body_lines: list[str] = []
    i = 0
    skip_prefixes = ("# ", "## ", "> ", "**AI", "**原始内容")
    # Skip header lines
    while i < len(lines):
        line = lines[i]
        if line.startswith(skip_prefixes) or line.strip() == "---":
            i += 1
            continue
        if not line.strip() and not body_lines:
            i += 1
            continue
        break
    # Collect body lines
    while i < len(lines):
        line = lines[i]
        if line.startswith(skip_prefixes) or line.strip() == "---":
            i += 1
            continue
        if not line.strip() and not body_lines:
            i += 1
            continue
        body_lines.append(line)
        i += 1
    return "\n".join(body_lines).strip()


def _extract_transcript(body: str) -> str | None:
    marker = "**AI 转写文本：**"
    idx = body.find(marker)
    if idx < 0:
        return None
    after = body[idx + len(marker):].strip()
    end_markers = ["**原始内容：**", "## "]
    end = len(after)
    for m in end_markers:
        pos = after.find(m)
        if 0 < pos < end:
            end = pos
    result = after[:end].strip()
    return result or None


def parse_from_markdown(content: str, remote_path: str = "") -> dict[str, Any] | None:
    fm = _parse_frontmatter(content)
    if not fm:
        return None
    note_id = fm.get("id")
    if not note_id:
        return None
    created_at = _parse_timestamp(fm.get("createdAt"))
    updated_at = _parse_timestamp(fm.get("updatedAt"))
    if created_at is None or updated_at is None:
        return None

    source = LABEL_TO_SOURCE.get(fm.get("source", ""), "TEXT")
    category = LABEL_TO_CATEGORY.get(fm.get("category", ""), "NOTE")
    priority = LABEL_TO_PRIORITY.get(fm.get("priority", ""), "MEDIUM")

    archived_str = fm.get("archived", "false").lower()
    is_archived = archived_str in ("true", "1", "yes")
    if not is_archived and "archive" in remote_path.lower().split("/"):
        is_archived = True

    tags_str = fm.get("tags", "")
    tags = [t.strip() for t in tags_str.split(",") if t.strip()] if tags_str else []

    title = _extract_title(content)
    body = _extract_body(content)
    is_voice = source == "VOICE"
    transcript = _extract_transcript(body) if is_voice else None
    note_content = transcript if (is_voice and transcript) else body

    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)

    return {
        "id": note_id,
        "title": title,
        "content": note_content,
        "original_content": None,
        "source": source,
        "category": category,
        "priority": priority,
        "color_token": default_color_for(category, priority),
        "status": "SYNCED",
        "created_at": created_at,
        "updated_at": updated_at,
        "last_synced_at": now_ms,
        "sync_error": None,
        "pinned": False,
        "remote_path": remote_path,
        "last_pulled_at": now_ms,
        "is_archived": is_archived,
        "archived_at": updated_at if is_archived else None,
        "is_trashed": False,
        "trashed_at": None,
        "tags": tags,
        "transcript": transcript,
        "audio_path": fm.get("audioPath"),
        "relay_url": fm.get("relayUrl"),
        "transcription_status": (fm.get("transcriptionStatus") or "not_started").upper(),
        "attachments": _parse_attachments_from_frontmatter(fm.get("attachments")),
    }
