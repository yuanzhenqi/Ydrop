"""链接预览后台抓取（Web 对齐 Android v1.1.0 J Phase 1）。

笔记保存 / WebDAV pull 后 fire-and-forget 调用 schedule_for_note(note_id)：
1. 提取 content + title + transcript 中的 URL；
2. 用 canonicalize_url 归一化 + distinct + take 5；
3. 已成功抓过且 < 7 天 TTL 的复用旧 cache，不重抓；
4. 调 routes_links.fetch_preview_internal 拉新预览；
5. 写回 SQLite notes.link_previews_json；**不**触发 WebDAV 推（链接预览是 server-local cache，
   跨同步会让 Android 端反复重抓）。
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
import urllib.parse
from typing import Iterable

logger = logging.getLogger("link_preview")

MAX_URLS = 5
CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000  # 7 天

# RFC 3986 ASCII URL 字符集；遇到中文 / 全角符号自动终止。
URL_REGEX = re.compile(r"https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+", re.IGNORECASE)
TRAILING_PUNCT = ".,;:，。、；：）)]」』\"'"

# 与 Android LinkPreviewWorker.canonicalizeUrl 同集追踪参数前缀
TRACKING_PARAM_PREFIXES = (
    "utm_", "spm", "share", "from", "ref", "ref_", "scene", "src", "sourceid",
    "session_id", "weibo_id", "_t", "fr",
)


def extract_urls(text: str) -> list[str]:
    """从文本里扫 URL，剥行尾常见标点后返回"""
    out: list[str] = []
    if not text:
        return out
    for m in URL_REGEX.finditer(text):
        url = m.group(0)
        # 剥末尾标点（半角 + 全角）
        while url and url[-1] in TRAILING_PUNCT:
            url = url[:-1]
        if len(url) > 8:
            out.append(url)
    return out


def canonicalize_url(raw: str) -> str:
    """归一化 URL 用于 dedup：小写 host、去 trailing /、剥追踪参数。失败返回原串。

    与 Android LinkPreviewWorker.canonicalizeUrl 等价。"""
    try:
        parts = urllib.parse.urlsplit(raw)
        scheme = (parts.scheme or "").lower()
        host = (parts.hostname or "").lower()
        if not scheme or not host:
            return raw
        port = f":{parts.port}" if parts.port else ""
        # path: 去 trailing / 但保留根路径
        path = parts.path or ""
        if len(path) > 1 and path.endswith("/"):
            path = path.rstrip("/")
        # query: 剥追踪参数（按前缀匹配 + 完整匹配）
        query_pairs = []
        if parts.query:
            for kv in parts.query.split("&"):
                key = kv.split("=", 1)[0].lower()
                if not key:
                    continue
                if any(key == p or (p.endswith("_") and key.startswith(p)) or key.startswith(p) for p in TRACKING_PARAM_PREFIXES):
                    continue
                query_pairs.append(kv)
        cleaned_query = "&".join(query_pairs)
        # fragment 保留
        frag = parts.fragment or ""
        return urllib.parse.urlunsplit((scheme, host + port, path, cleaned_query, frag))
    except Exception:
        return raw


async def schedule_for_note(note_id: str) -> None:
    """fire-and-forget：起一个后台任务抓笔记里的链接预览。"""
    asyncio.create_task(_run(note_id))


async def _run(note_id: str) -> None:
    try:
        await _fetch_and_persist(note_id)
    except Exception as e:
        logger.warning("link preview worker failed note=%s: %s", note_id, e)


async def _fetch_and_persist(note_id: str) -> None:
    from .database import get_db
    from .routes_links import fetch_preview_internal

    db = await get_db()
    rows = await db.execute_fetchall(
        "SELECT title, content, transcript, link_previews_json FROM notes WHERE id = ?",
        [note_id],
    )
    if not rows:
        return
    row = rows[0]
    text = "\n".join(filter(None, [row["title"], row["content"], row["transcript"]]))

    raw_urls = extract_urls(text)
    canonical = []
    seen: set[str] = set()
    for u in (canonicalize_url(x) for x in raw_urls):
        if u not in seen:
            seen.add(u)
            canonical.append(u)
        if len(canonical) >= MAX_URLS:
            break

    if not canonical:
        # 用户把 URL 删了又保存，旧的预览要清空
        existing_raw = row["link_previews_json"]
        if existing_raw and existing_raw != "[]":
            await db.execute(
                "UPDATE notes SET link_previews_json = '[]' WHERE id = ?",
                [note_id],
            )
            await db.commit()
        return

    # 复用 cache：已存在且无 error 且 < 7 天的直接保留，不重抓。
    existing_by_url: dict[str, dict] = {}
    try:
        for it in json.loads(row["link_previews_json"] or "[]"):
            if isinstance(it, dict) and it.get("url"):
                existing_by_url[it["url"]] = it
    except Exception:
        pass

    now = int(time.time() * 1000)
    new_previews: list[dict] = []
    for url in canonical:
        cached = existing_by_url.get(url)
        if cached and not cached.get("error") and cached.get("fetched_at", 0) > 0 and now - cached["fetched_at"] < CACHE_TTL_MS:
            new_previews.append(cached)
            continue
        try:
            resp = await fetch_preview_internal(url, want_summary=True)
            new_previews.append({
                "url": resp.url or url,
                "title": resp.title,
                "description": resp.description,
                "image_url": resp.image_url,
                "site_name": resp.site_name,
                "summary": resp.summary,
                "fetched_at": now,
                "error": resp.error,
            })
        except Exception as e:
            new_previews.append({
                "url": url, "title": "", "description": "", "image_url": "",
                "site_name": "", "summary": "", "fetched_at": now,
                "error": str(e)[:160],
            })

    encoded = json.dumps(new_previews, ensure_ascii=False)
    # 故意不动 updated_at / status / last_synced_at——预览是后台副作用，
    # 跨 WebDAV 同步推它没意义（Android 端有自己的 LinkPreviewWorker 重抓）。
    await db.execute(
        "UPDATE notes SET link_previews_json = ? WHERE id = ?",
        [encoded, note_id],
    )
    await db.commit()
    logger.info("link preview note=%s wrote %d previews", note_id, len(new_previews))


def _filter_pairs(query: str) -> Iterable[str]:
    """utility 留作未来扩展，目前 canonicalize_url 内联了"""
    yield from (kv for kv in query.split("&") if kv)
