"""飞书 OpenAPI 异步客户端 + tenant_access_token 自动刷新。

文档：https://open.feishu.cn/document/server-docs/
- token：POST /open-apis/auth/v3/tenant_access_token/internal，body { app_id, app_secret }，过期 ≤ 7200s
- Bitable App 元信息：GET /open-apis/bitable/v1/apps/{app_token}，Bearer tenant_access_token
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Any, Optional

import httpx

logger = logging.getLogger("feishu_client")

DEFAULT_BASE = "https://open.feishu.cn"
TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal"
APP_INFO_PATH = "/open-apis/bitable/v1/apps/{app_token}"

# 飞书 token 实测 max 7200s。剩余 < REFRESH_AHEAD_S 时主动 refresh，避免请求中途过期被 99991663 拒。
REFRESH_AHEAD_S = 30 * 60  # 30 分钟


class FeishuError(Exception):
    """飞书 API 业务错误（包含飞书自己的 code/msg），区别于 httpx 的传输错误。"""

    def __init__(self, code: int, msg: str, hint: str = ""):
        super().__init__(f"飞书错误 code={code} msg={msg}{(' — ' + hint) if hint else ''}")
        self.code = code
        self.msg = msg


@dataclass
class _CachedToken:
    value: str
    expires_at: float  # epoch seconds


class FeishuClient:
    """单个飞书自建应用的 client。线程安全（asyncio.Lock）但不跨实例共享 token。"""

    def __init__(self, app_id: str, app_secret: str, base_url: str = DEFAULT_BASE):
        if not app_id or not app_secret:
            raise ValueError("app_id 和 app_secret 必填")
        self.app_id = app_id
        self.app_secret = app_secret
        self.base_url = base_url.rstrip("/")
        self._token: Optional[_CachedToken] = None
        self._token_lock = asyncio.Lock()
        self._client: Optional[httpx.AsyncClient] = None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=15.0, follow_redirects=True)
        return self._client

    async def close(self) -> None:
        if self._client and not self._client.is_closed:
            await self._client.aclose()

    # ─── token ───

    async def get_tenant_access_token(self, force_refresh: bool = False) -> str:
        """返回有效的 tenant_access_token，缓存 + 提前 30min 刷新。"""
        now = time.time()
        if not force_refresh and self._token and self._token.expires_at - now > REFRESH_AHEAD_S:
            return self._token.value
        async with self._token_lock:
            # 双重检查：等锁的并发请求不重复刷
            now = time.time()
            if not force_refresh and self._token and self._token.expires_at - now > REFRESH_AHEAD_S:
                return self._token.value
            client = await self._get_client()
            r = await client.post(
                self.base_url + TOKEN_PATH,
                json={"app_id": self.app_id, "app_secret": self.app_secret},
            )
            if r.status_code != 200:
                raise FeishuError(
                    r.status_code,
                    f"HTTP {r.status_code}",
                    "token 端点 HTTP 错误，请检查 base_url 是否能联通",
                )
            data = r.json()
            code = data.get("code", -1)
            if code != 0:
                raise FeishuError(
                    code,
                    data.get("msg", "unknown"),
                    "请检查 app_id / app_secret 是否正确（自建应用而非商店应用）",
                )
            token = data.get("tenant_access_token") or ""
            expire_s = int(data.get("expire", 7200))
            if not token:
                raise FeishuError(0, "empty token", "飞书返回 code=0 但无 token，开发者后台可能未启用应用")
            self._token = _CachedToken(value=token, expires_at=time.time() + expire_s)
            logger.info("feishu token refreshed app_id=%s expire_s=%s", self.app_id, expire_s)
            return token

    # ─── helpers ───

    async def _bearer_get(self, path: str) -> dict:
        token = await self.get_tenant_access_token()
        client = await self._get_client()
        r = await client.get(
            self.base_url + path,
            headers={"Authorization": f"Bearer {token}"},
        )
        if r.status_code == 401:
            # token 过期或权限不足；强制重新拿一次再重试
            logger.warning("feishu 401 on %s, force refresh + retry once", path)
            token = await self.get_tenant_access_token(force_refresh=True)
            r = await client.get(
                self.base_url + path,
                headers={"Authorization": f"Bearer {token}"},
            )
        # 飞书在 4xx 也会返回 JSON body 含真正的业务 code（比如 400 + code=99991672 = 缺权限）。
        # 优先从 body 解析飞书自己的 code/msg，HTTP status 退到次要位置——否则错误诊断映射全部失效。
        feishu_code = -1
        feishu_msg = ""
        try:
            data = r.json()
            feishu_code = data.get("code", -1) if isinstance(data, dict) else -1
            feishu_msg = data.get("msg", "") if isinstance(data, dict) else ""
        except Exception:
            data = {}
        if r.status_code != 200:
            if feishu_code > 0:
                raise FeishuError(feishu_code, feishu_msg or f"HTTP {r.status_code}", body_preview(r.text))
            raise FeishuError(r.status_code, f"HTTP {r.status_code}", body_preview(r.text))
        if feishu_code != 0:
            raise FeishuError(feishu_code, feishu_msg or "unknown", body_preview(r.text))
        return data.get("data") or {}

    # ─── Bitable: 联通测试 ───

    async def get_app_info(self, app_token: str) -> dict[str, Any]:
        """读取多维表格元信息。用于 settings page 的「测试连接」按钮。

        返回示例：{"name": "我的项目库", "revision": 12, "time_zone": "Asia/Shanghai", ...}
        """
        if not app_token:
            raise ValueError("app_token 不能为空")
        data = await self._bearer_get(APP_INFO_PATH.format(app_token=app_token))
        app = data.get("app") or {}
        return {
            "app_token": app.get("app_token", ""),
            "name": app.get("name", ""),
            "revision": app.get("revision", 0),
            "time_zone": app.get("time_zone", ""),
            "is_advanced": bool(app.get("is_advanced", False)),
            "formula_type": app.get("formula_type", 0),
        }


def body_preview(text: str, limit: int = 200) -> str:
    if not text:
        return "(empty body)"
    return text[:limit] + ("..." if len(text) > limit else "")
