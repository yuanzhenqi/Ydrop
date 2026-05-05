"""图片分析：multipart 上传 → 存 static/images → 调 vision provider → 返回结构化结果。

App 端笔记里的图片不走 WebDAV 同步，因此 relay DB 里没有用户图片；这里的流程是：
1. App 端把原图 + hint（一般是 note.title 或 note.content 前若干字）multipart 过来；
2. Relay 落地到 STATIC_DIR/images/，给一个可访问 URL；
3. 同步调 vision provider 做描述 + 结构化抽取；
4. 返回给 App 端，App 端合并进 NoteAttachment。
"""

from __future__ import annotations

import json
import logging
import os
import re
import uuid
from typing import Optional

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field

from . import settings_store
from .auth import require_relay_token
from .config import get_settings

logger = logging.getLogger("ai_images")
router = APIRouter(prefix="/api/images", dependencies=[Depends(require_relay_token)])

MAX_IMAGE_BYTES = 8 * 1024 * 1024  # 8MB；大多数手机截图 + 相机原图都在这以下


class ImageAnalyzeResponse(BaseModel):
    remote_url: str = ""
    description: str = ""
    keywords: list[str] = Field(default_factory=list)
    actionable_items: list[str] = Field(default_factory=list)
    dates: list[str] = Field(default_factory=list)
    error: Optional[str] = None


@router.post("/analyze", response_model=ImageAnalyzeResponse)
async def analyze(
    file: UploadFile = File(...),
    note_id: str = Form(""),
    hint: str = Form(""),
):
    data = await file.read()
    if not data:
        raise HTTPException(400, "empty image")
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(413, f"image too large (>{MAX_IMAGE_BYTES // (1024 * 1024)}MB)")

    # 1. 存盘
    s = get_settings()
    img_dir = os.path.join(str(s.static_dir), "images")
    os.makedirs(img_dir, exist_ok=True)
    ext = _ext_for(file.content_type, file.filename)
    fname = f"{uuid.uuid4().hex}{ext}"
    fpath = os.path.join(img_dir, fname)
    try:
        with open(fpath, "wb") as out:
            out.write(data)
    except OSError as e:
        logger.error("save image failed: %s", e)
        raise HTTPException(500, "failed to persist image")
    remote_url = f"{s.public_base_url.rstrip('/')}/static/images/{fname}"

    # 2. 调 vision
    ai_cfg = await settings_store.get_ai_config()
    if not (ai_cfg["enabled"] and ai_cfg["base_url"] and ai_cfg["token"]):
        logger.info("analyze: no provider configured, returning remote_url only note=%s", note_id)
        return ImageAnalyzeResponse(remote_url=remote_url, error="relay 未配 AI provider")
    if not ai_cfg.get("vision_enabled", True):
        # 用户模型不支持 vision；只存图返回 URL，不调 vision provider 避免报错。
        logger.info("analyze: vision_enabled=false, returning remote_url only note=%s", note_id)
        return ImageAnalyzeResponse(remote_url=remote_url)

    try:
        from .ai_provider import call_vision, strip_json_fence
        raw = call_vision(
            image_bytes=data,
            mime=file.content_type or "image/jpeg",
            prompt=_build_prompt(hint),
            cfg=ai_cfg,
        )
        structured = _parse_json(raw)
    except Exception as e:
        logger.error("vision analyze failed note=%s: %s", note_id, e)
        return ImageAnalyzeResponse(remote_url=remote_url, error=str(e)[:200])

    return ImageAnalyzeResponse(
        remote_url=remote_url,
        description=(structured.get("description") or "")[:1000],
        keywords=_cap_list(structured.get("keywords"), 10),
        actionable_items=_cap_list(structured.get("actionable_items"), 10),
        dates=_cap_list(structured.get("dates"), 10),
    )


def _ext_for(content_type: str | None, filename: str | None) -> str:
    if filename and "." in filename:
        ext = "." + filename.rsplit(".", 1)[1].lower()
        if 2 <= len(ext) <= 6:
            return ext
    if not content_type:
        return ".jpg"
    ct = content_type.lower()
    if "png" in ct:
        return ".png"
    if "webp" in ct:
        return ".webp"
    if "heic" in ct or "heif" in ct:
        return ".heic"
    return ".jpg"


def _build_prompt(hint: str) -> str:
    lines = [
        "用户把这张图丢进了他的 inbox 笔记。请只返回一个 JSON 对象（不要 markdown 围栏），",
        "键的要求：",
        "- description：≤150 字中文描述，突出主体 / 文字 / 场景 / 图中的任务信息。",
        "- keywords：≤8 个中文关键词，便于日后搜索。",
        "- actionable_items：如果图里包含明显的待办、任务清单、需要提醒的事，提取成短句数组；没有就空数组。",
        "- dates：图中明显出现的日期时间，格式 \"2026-04-22 14:00\"；没有就空数组。",
    ]
    hint = (hint or "").strip()
    if hint:
        lines.append(f"用户额外备注：{hint[:200]}")
    return "\n".join(lines)


def _parse_json(raw: str) -> dict:
    from .ai_provider import strip_json_fence
    stripped = strip_json_fence(raw)
    try:
        parsed = json.loads(stripped)
        if isinstance(parsed, dict):
            return parsed
    except Exception:
        pass
    # 降级：模型没吐 JSON，就把整段当 description 用
    return {"description": (raw or "")[:500]}


# 只允许 "<32-hex>.<2-4 letter ext>" 的文件名，避免路径穿越 / 其它恶意输入
_SAFE_FILENAME_RE = re.compile(r"^[a-f0-9]{32}\.[a-z0-9]{2,5}$")


@router.delete("/{filename}", status_code=204)
async def delete(filename: str):
    """删除 analyze 端点存下来的图片副本。客户端删除附件 / 彻底删除笔记时调。
    幂等：文件不存在也 204（避免客户端重试时报 404 污染日志）。
    """
    if not _SAFE_FILENAME_RE.match(filename):
        raise HTTPException(400, "invalid filename")
    s = get_settings()
    fpath = os.path.join(str(s.static_dir), "images", filename)
    # 防止 basename 绕过：resolved 路径必须在 static_dir/images/ 下
    images_root = os.path.abspath(os.path.join(str(s.static_dir), "images"))
    resolved = os.path.abspath(fpath)
    if not resolved.startswith(images_root + os.sep) and resolved != images_root:
        raise HTTPException(400, "invalid filename")
    try:
        os.remove(resolved)
        logger.info("deleted image: %s", filename)
    except FileNotFoundError:
        pass
    except OSError as e:
        logger.warning("failed to delete image %s: %s", filename, e)
        raise HTTPException(500, "delete failed")


def _cap_list(value, limit: int) -> list[str]:
    if not isinstance(value, list):
        return []
    out = []
    for item in value:
        if not isinstance(item, str):
            continue
        trimmed = item.strip()
        if trimmed:
            out.append(trimmed[:80])
        if len(out) >= limit:
            break
    return out
