"""配置管理 API：GET / PUT + 测试连接"""

from __future__ import annotations

import json
import logging
import urllib.request
from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from . import settings_store
from .auth import require_relay_token
from .config import get_settings as get_env_settings
from .webdav_client import WebDavClient

logger = logging.getLogger("routes_settings")

router = APIRouter(prefix="/api/settings", dependencies=[Depends(require_relay_token)])


# ─── Response / Request 模型 ───


class WebDavSettings(BaseModel):
    base_url: str = ""
    username: str = ""
    password_set: bool = False
    folder: str = "ydoc/inbox"
    auto_sync: bool = True
    sync_interval: int = 300
    enabled: bool = False


class AiSettings(BaseModel):
    enabled: bool = False
    base_url: str = ""
    token_set: bool = False
    model: str = "gpt-4o"
    endpoint_mode: str = "AUTO"
    prompt_supplement: str = ""
    auto_run_on_text_save: bool = True
    auto_retry_on_failure: bool = True


class ServerInfo(BaseModel):
    version: str = "0.2.0"
    webdav_configured: bool = False
    ai_configured: bool = False


class SettingsResponse(BaseModel):
    webdav: WebDavSettings
    ai: AiSettings
    server_info: ServerInfo


class SettingsUpdate(BaseModel):
    webdav: Optional[dict] = None
    ai: Optional[dict] = None


class TestResult(BaseModel):
    ok: bool
    message: str = ""


# ─── 端点 ───


@router.get("", response_model=SettingsResponse)
async def get_settings_all():
    w = await settings_store.get_webdav_config()
    a = await settings_store.get_ai_config()

    return SettingsResponse(
        webdav=WebDavSettings(
            base_url=w["base_url"],
            username=w["username"],
            password_set=bool(w["password"]),
            folder=w["folder"],
            auto_sync=w["auto_sync"],
            sync_interval=w["sync_interval"],
            enabled=w["enabled"],
        ),
        ai=AiSettings(
            enabled=a["enabled"],
            base_url=a["base_url"],
            token_set=bool(a["token"]),
            model=a["model"],
            endpoint_mode=a["endpoint_mode"],
            prompt_supplement=a["prompt_supplement"],
            auto_run_on_text_save=a["auto_run_on_text_save"],
            auto_retry_on_failure=a["auto_retry_on_failure"],
        ),
        server_info=ServerInfo(
            webdav_configured=bool(w["base_url"]),
            ai_configured=bool(a["base_url"] and a["token"]),
        ),
    )


@router.put("", response_model=SettingsResponse)
async def update_settings(body: SettingsUpdate):
    """部分更新。webdav/ai 各自是 dict，字段缺失不改，password/token 空字符串不改。"""
    updates: dict = {}

    if body.webdav is not None:
        for k, v in body.webdav.items():
            if k in ("base_url", "username", "password", "folder"):
                updates[f"webdav.{k}"] = v
            elif k in ("auto_sync", "enabled"):
                updates[f"webdav.{k}"] = bool(v) if v is not None else None
            elif k == "sync_interval":
                updates["webdav.sync_interval"] = int(v) if v is not None else None

    if body.ai is not None:
        for k, v in body.ai.items():
            if k in ("base_url", "token", "model", "endpoint_mode", "prompt_supplement"):
                updates[f"ai.{k}"] = v
            elif k in ("enabled", "auto_run_on_text_save", "auto_retry_on_failure"):
                updates[f"ai.{k}"] = bool(v) if v is not None else None

    await settings_store.set_many(updates)
    return await get_settings_all()


@router.post("/test/webdav", response_model=TestResult)
async def test_webdav():
    """测试当前 store 中的 WebDAV 配置能否连通。"""
    try:
        client = await WebDavClient.from_store()
        if not client.configured:
            return TestResult(ok=False, message="WebDAV 地址未配置")
        await client.test_connection()
        await client.close()
        return TestResult(ok=True, message="连接成功")
    except Exception as e:
        return TestResult(ok=False, message=f"{type(e).__name__}: {str(e)[:200]}")


@router.post("/test/ai", response_model=TestResult)
async def test_ai():
    """测试 AI provider：发一个极简 ping。"""
    a = await settings_store.get_ai_config()
    if not a["base_url"] or not a["token"]:
        return TestResult(ok=False, message="AI base URL 或 token 未配置")

    body = {
        "model": a["model"],
        "messages": [{"role": "user", "content": "ping"}],
        "max_tokens": 5,
    }
    try:
        req = urllib.request.Request(
            a["base_url"].rstrip("/") + "/chat/completions",
            data=json.dumps(body).encode("utf-8"),
            method="POST",
            headers={
                "Authorization": f"Bearer {a['token']}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode("utf-8")
        j = json.loads(raw)
        if "choices" in j or "content" in j:
            return TestResult(ok=True, message="连接成功")
        return TestResult(ok=False, message=f"返回结构异常: {str(j)[:200]}")
    except Exception as e:
        return TestResult(ok=False, message=f"{type(e).__name__}: {str(e)[:200]}")
