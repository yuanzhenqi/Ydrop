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
TABLES_PATH = "/open-apis/bitable/v1/apps/{app_token}/tables"
FIELDS_PATH = "/open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/fields"
RECORDS_PATH = "/open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/records"
RECORD_ITEM_PATH = "/open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/records/{record_id}"

# ─── Ydrop 在 Bitable 里的标准列 schema ───
# 选项 / 类型尽量与 markdown_format.py 保持一致（中文 label）。
# 单选 color 0-54，自由发挥；多选 / 复选框 / 日期不需要预设值。

_CATEGORY_OPTIONS = [
    {"name": "普通", "color": 0},
    {"name": "待办", "color": 1},
    {"name": "任务", "color": 2},
    {"name": "提醒", "color": 3},
]

_PRIORITY_OPTIONS = [
    {"name": "低", "color": 5},
    {"name": "中", "color": 0},
    {"name": "高", "color": 6},
    {"name": "紧急", "color": 4},
]

# Ydrop 标准字段 schema。键名是中文显示名，值是 create-field 请求体（去掉 field_name）。
# 顺序固定：第一项会作为 primary 主索引（标题）。
YDROP_SCHEMA: list[dict] = [
    {"field_name": "标题", "type": 1},  # 文本 — 主索引
    {"field_name": "内容", "type": 1},  # 多行文本（type=1 即可，UI 自适应换行）
    {"field_name": "类型", "type": 3, "property": {"options": _CATEGORY_OPTIONS}},
    {"field_name": "优先级", "type": 3, "property": {"options": _PRIORITY_OPTIONS}},
    {"field_name": "标签", "type": 4},  # 多选；不预设 options，运行时可以传新值
    {"field_name": "已归档", "type": 7},  # 复选框
    {"field_name": "创建时间", "type": 5},  # 日期（毫秒时间戳）
    {"field_name": "更新时间", "type": 5},
    {"field_name": "ydrop_id", "type": 1},  # 隐藏 ID 映射主键
]

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

    # ─── Bitable: helpers (POST/PUT/DELETE) ───

    async def _bearer_post(self, path: str, body: dict | None = None) -> dict:
        return await self._bearer_call("POST", path, body)

    async def _bearer_put(self, path: str, body: dict | None = None) -> dict:
        return await self._bearer_call("PUT", path, body)

    async def _bearer_delete(self, path: str) -> dict:
        return await self._bearer_call("DELETE", path, None)

    async def _bearer_call(self, method: str, path: str, body: dict | None) -> dict:
        token = await self.get_tenant_access_token()
        client = await self._get_client()
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        r = await client.request(method, self.base_url + path, headers=headers, json=body if body is not None else {})
        if r.status_code == 401:
            token = await self.get_tenant_access_token(force_refresh=True)
            headers["Authorization"] = f"Bearer {token}"
            r = await client.request(method, self.base_url + path, headers=headers, json=body if body is not None else {})
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

    # ─── Bitable: 表与字段管理 ───

    async def list_tables(self, app_token: str) -> list[dict]:
        """列出多维表格里所有的 table。诊断 table_id 是否正确时用。"""
        out: list[dict] = []
        page_token = ""
        while True:
            qs = "?page_size=100" + (f"&page_token={page_token}" if page_token else "")
            data = await self._bearer_get(TABLES_PATH.format(app_token=app_token) + qs)
            out.extend(data.get("items") or [])
            if not data.get("has_more"):
                break
            page_token = data.get("page_token") or ""
            if not page_token:
                break
        return out

    async def list_fields(self, app_token: str, table_id: str) -> list[dict]:
        """列出表的所有字段（自动翻页）。"""
        out: list[dict] = []
        page_token = ""
        while True:
            qs = "?page_size=100" + (f"&page_token={page_token}" if page_token else "")
            data = await self._bearer_get(FIELDS_PATH.format(app_token=app_token, table_id=table_id) + qs)
            items = data.get("items") or []
            out.extend(items)
            if not data.get("has_more"):
                break
            page_token = data.get("page_token") or ""
            if not page_token:
                break
        return out

    async def create_field(self, app_token: str, table_id: str, field_def: dict) -> dict:
        """创建一个字段。field_def 直接是 create_field 的请求体（含 field_name/type/property）。"""
        return await self._bearer_post(
            FIELDS_PATH.format(app_token=app_token, table_id=table_id), field_def
        )

    async def init_ydrop_table(self, app_token: str, table_id: str) -> dict:
        """按 YDROP_SCHEMA 把缺失的列建出来。已有同名列直接跳过（不验证类型，避免误判）。

        返回 {created: [name], skipped: [name], errors: [{name, msg}]}。
        """
        try:
            existing = await self.list_fields(app_token, table_id)
        except FeishuError as e:
            # TableIdNotFound 时帮用户列出实际能看到的 table_id，方便定位
            if e.code in (1254041, 1254040):
                try:
                    tables = await self.list_tables(app_token)
                    table_brief = ", ".join(
                        f"{t.get('table_id','?')}({t.get('name','')})" for t in tables[:6]
                    )
                    hint = (
                        f"table_id 不存在或应用没访问权限。"
                        f"该应用能看到的 table 有：{table_brief or '(空)'}"
                    )
                    raise FeishuError(e.code, e.msg, hint) from e
                except FeishuError:
                    raise
                except Exception:
                    raise e
            raise FeishuError(e.code, e.msg, "无法列出现有字段，请先确认 bitable:app 写权限") from e
        existing_names = {f.get("field_name", "") for f in existing}

        created, skipped, errors = [], [], []
        for spec in YDROP_SCHEMA:
            name = spec["field_name"]
            if name in existing_names:
                skipped.append(name)
                continue
            try:
                await self.create_field(app_token, table_id, spec)
                created.append(name)
            except FeishuError as e:
                errors.append({"name": name, "code": e.code, "msg": str(e)})
        return {"created": created, "skipped": skipped, "errors": errors}

    # ─── Bitable: 列出 records（自动翻页）───

    async def list_records(self, app_token: str, table_id: str, page_size: int = 200) -> list[dict]:
        """列出表里所有 record。每条含 record_id / fields / created_time / last_modified_time。

        ⚠ 关键：必须带 automatic_fields=true！否则飞书默认不返回 last_modified_time / created_time
        系统字段，我们的 last_write_wins 判定会拿到 None → 错误 fallback 到当下时间 → echo loop。
        page_size 上限 500；这里 200 以减少单次响应体积，给慢网容差。
        """
        out: list[dict] = []
        page_token = ""
        while True:
            qs = f"?page_size={page_size}&automatic_fields=true" + (f"&page_token={page_token}" if page_token else "")
            data = await self._bearer_get(RECORDS_PATH.format(app_token=app_token, table_id=table_id) + qs)
            out.extend(data.get("items") or [])
            if not data.get("has_more"):
                break
            page_token = data.get("page_token") or ""
            if not page_token:
                break
        return out

    # ─── Bitable: 单条 record ───

    async def get_record(self, app_token: str, table_id: str, record_id: str) -> Optional[dict]:
        """读单条 record。404 (1254043 / 1254040) 返回 None；其它错误抛 FeishuError。

        必须 automatic_fields=true 让 last_modified_time / created_time 一起返回，否则
        webhook 单条快路径无法做时间戳比较（echo loop 重灾区）。"""
        try:
            data = await self._bearer_get(
                RECORD_ITEM_PATH.format(app_token=app_token, table_id=table_id, record_id=record_id)
                + "?automatic_fields=true"
            )
            return data.get("record") or None
        except FeishuError as e:
            if e.code in (1254043, 1254040):
                return None
            raise

    # ─── Bitable: record CRUD ───

    async def create_record(self, app_token: str, table_id: str, fields: dict) -> dict:
        """创建一条 record。返回 {record_id, fields, ...}"""
        data = await self._bearer_post(
            RECORDS_PATH.format(app_token=app_token, table_id=table_id),
            {"fields": fields},
        )
        return data.get("record") or {}

    async def update_record(self, app_token: str, table_id: str, record_id: str, fields: dict) -> dict:
        data = await self._bearer_put(
            RECORD_ITEM_PATH.format(app_token=app_token, table_id=table_id, record_id=record_id),
            {"fields": fields},
        )
        return data.get("record") or {}

    async def delete_record(self, app_token: str, table_id: str, record_id: str) -> bool:
        """删除 record。404 视为成功（已经不在了）。"""
        try:
            data = await self._bearer_delete(
                RECORD_ITEM_PATH.format(app_token=app_token, table_id=table_id, record_id=record_id)
            )
            return bool(data.get("deleted", True))
        except FeishuError as e:
            if e.code in (1254043, 1254040):  # record 不存在 / table 不存在
                logger.info("delete_record: record %s already gone", record_id)
                return True
            raise

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
