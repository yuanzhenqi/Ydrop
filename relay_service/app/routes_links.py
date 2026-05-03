"""链接预览：拉 og meta + 首屏正文，可选让 LLM 做 150 字中文摘要。

App 端笔记里常常只有一个 URL，如果 AI 整理看到的是裸链接，产出的
title/category/priority 会非常糟。这个端点把链接变成「可读上下文」供 AI 管道使用，
同时前端也能拿来渲染预览卡片。
"""

from __future__ import annotations

import logging
import re
import urllib.parse
import urllib.request
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
MAX_FETCH_BYTES = 512 * 1024  # 只拉 512KB，足够 og meta + 首屏正文，防止巨图/巨 HTML


def _ascii_safe_url(url: str) -> str:
    """把 URL 的 path/query/fragment 里非 ASCII 字符 pct-encoded，scheme + host 原样。

    urllib.request.urlopen 底层 HTTP 串要用 ASCII 发送；URL 里带中文（比如用户在笔记里
    写 "https://x.com/搜索?q=你好"）会直接报 UnicodeEncodeError。用 urllib.parse.quote
    把 path/query 里除 URL 合法字符以外的字符转成 %XX。
    """
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return url
    # safe 字符集：RFC 3986 reserved + unreserved 里在 path/query 里合法的字符。
    # `safe` 不加会把 /?&= 也都转义，那就没法访问了。
    safe_path = urllib.parse.quote(parts.path, safe="/%:@!$&'()*+,;=-._~")
    safe_query = urllib.parse.quote(parts.query, safe="/%:@!$&'()*+,;=-._~?")
    safe_fragment = urllib.parse.quote(parts.fragment, safe="/%:@!$&'()*+,;=-._~?#")
    return urllib.parse.urlunsplit(
        (parts.scheme, parts.netloc, safe_path, safe_query, safe_fragment)
    )


@router.post("/preview", response_model=LinkPreviewResponse)
async def preview(body: LinkPreviewRequest) -> LinkPreviewResponse:
    if not re.match(r"^https?://", body.url, re.IGNORECASE):
        raise HTTPException(400, "url must be http(s)")

    # 客户端正则如果意外放过了含中文的 URL，这里再兜底一次：
    # 把 path/query/fragment 里的非 ASCII 字符 pct-encoded，scheme + host 保持不变。
    # 之前直接把原始含中文 URL 扔给 urllib.urlopen 会抛 'ascii' codec can't encode characters。
    safe_url = _ascii_safe_url(body.url)

    try:
        req = urllib.request.Request(safe_url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=8) as resp:
            raw = resp.read(MAX_FETCH_BYTES)
            ctype = resp.headers.get("Content-Type", "")
            charset = "utf-8"
            m = re.search(r"charset=([\w\-]+)", ctype)
            if m:
                charset = m.group(1)
            html = raw.decode(charset, errors="ignore")
    except Exception as e:
        logger.warning("preview fetch failed url=%s: %s", body.url, e)
        # 用原始 url 返回给客户端（保留用户看到的原文），error 字段带出失败原因
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
                    "用中文把下面这段网页正文总结成 150 字左右的摘要，突出可行动信息和要点，"
                    "不要复述标题，不要使用列表符号。\n\n"
                    f"标题：{meta['title']}\n正文：{main_text}"
                )
                summary = call_llm(
                    [{"role": "user", "content": prompt}],
                    ai_cfg,
                    response_format="text",
                )[:400]
        except Exception as e:
            logger.warning("summary failed url=%s: %s", body.url, e)

    return LinkPreviewResponse(
        url=body.url,
        title=meta["title"],
        description=meta["description"],
        image_url=meta["image_url"],
        site_name=meta["site_name"],
        summary=summary,
    )


def _extract_meta(html: str, base_url: str) -> dict:
    try:
        from bs4 import BeautifulSoup
        soup = BeautifulSoup(html, "lxml")
    except Exception:
        # lxml 没装或坏了也不让整个端点挂，降级用 html.parser。
        from bs4 import BeautifulSoup
        soup = BeautifulSoup(html, "html.parser")

    def _m(prop: str) -> str:
        el = soup.find("meta", attrs={"property": prop}) or soup.find(
            "meta", attrs={"name": prop}
        )
        return (el.get("content") if el else "") or ""

    title = _m("og:title") or (soup.title.string if soup.title else "") or ""
    desc = _m("og:description") or _m("description") or ""
    image = _m("og:image") or ""
    # og:site_name 不稳定，不到就退回用 host 名
    fallback_site = base_url.split("/")[2] if "://" in base_url else ""
    site = _m("og:site_name") or fallback_site

    # title 兜底：og:title / <title> 都空时，用 host + 第一段 path 当 title。
    # 不兜底的话客户端会把空 title 渲染成空预览框，被用户当成"404"。
    title_str = title.strip()[:200]
    if not title_str:
        try:
            parts = urllib.parse.urlsplit(base_url)
            host = parts.netloc or ""
            first_seg = ""
            if parts.path:
                segs = [s for s in parts.path.split("/") if s]
                if segs:
                    first_seg = "/" + segs[0]
            title_str = (host + first_seg).strip("/")[:200] or base_url[:200]
        except Exception:
            title_str = base_url[:200]

    return {
        "title": title_str,
        "description": desc.strip()[:300],
        "image_url": image.strip()[:500],
        "site_name": site.strip()[:80],
    }


def _extract_main_text(html: str) -> str:
    """优先用 readability 抽正文；失败时退化到整页 text，保证不会彻底空。"""
    try:
        from readability import Document
        from bs4 import BeautifulSoup
        doc = Document(html)
        return BeautifulSoup(doc.summary(), "lxml").get_text(" ", strip=True)
    except Exception:
        try:
            from bs4 import BeautifulSoup
            return BeautifulSoup(html, "lxml").get_text(" ", strip=True)
        except Exception:
            from bs4 import BeautifulSoup
            return BeautifulSoup(html, "html.parser").get_text(" ", strip=True)
