"""飞书多维表格集成路由（Phase 1 Step 1：仅配置 + 联通测试）。

后续 Step 2-4 会再扩：
- POST /api/feishu/sync/trigger 手动拉/推
- POST /api/feishu/webhook/{token} 飞书 Automation 反推回调
- GET /api/feishu/mappings 查 note_id ↔ record_id 映射
"""

from __future__ import annotations

import logging
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from . import settings_store
from .auth import require_relay_token
from .feishu_client import FeishuClient, FeishuError

logger = logging.getLogger("feishu_routes")
router = APIRouter(prefix="/api/feishu", dependencies=[Depends(require_relay_token)])


# ─── Models ───


class FeishuSettings(BaseModel):
    """返给客户端的配置。app_secret 不回传，只回 secret_set 标志。"""

    enabled: bool = False
    app_id: str = ""
    secret_set: bool = False
    app_token: str = ""
    table_id: str = ""


class FeishuSettingsUpdate(BaseModel):
    """部分更新。app_secret 留空表示不改；显式传空字符串如何区分需要业务约定，
    这里采用『空字符串 = 不改』，要清空 secret 必须前端先调 disable。"""

    enabled: Optional[bool] = None
    app_id: Optional[str] = None
    app_secret: Optional[str] = None
    app_token: Optional[str] = None
    table_id: Optional[str] = None


class FeishuTestResult(BaseModel):
    ok: bool
    message: str = ""
    app_name: str = ""
    revision: int = 0
    time_zone: str = ""


# ─── Endpoints ───


@router.get("/settings", response_model=FeishuSettings)
async def get_feishu_settings():
    cfg = await settings_store.get_feishu_config()
    return FeishuSettings(
        enabled=cfg["enabled"],
        app_id=cfg["app_id"],
        secret_set=bool(cfg["app_secret"]),
        app_token=cfg["app_token"],
        table_id=cfg["table_id"],
    )


@router.put("/settings", response_model=FeishuSettings)
async def update_feishu_settings(body: FeishuSettingsUpdate):
    updates: dict = {}
    if body.enabled is not None:
        updates["feishu.enabled"] = bool(body.enabled)
    if body.app_id is not None:
        updates["feishu.app_id"] = body.app_id.strip()
    # 空字符串视作不动；要清空就传 enabled=false 即可让 connector 短路
    if body.app_secret:
        updates["feishu.app_secret"] = body.app_secret.strip()
    if body.app_token is not None:
        updates["feishu.app_token"] = body.app_token.strip()
    if body.table_id is not None:
        updates["feishu.table_id"] = body.table_id.strip()
    if updates:
        await settings_store.set_many(updates)
    return await get_feishu_settings()


@router.post("/test", response_model=FeishuTestResult)
async def test_feishu_connection():
    """三步验证：(1) app_id/secret 可换 token，(2) app_token 可读元信息，(3) table_id 暂不验证（Step 1 不做）。"""
    cfg = await settings_store.get_feishu_config()
    if not cfg["app_id"] or not cfg["app_secret"]:
        return FeishuTestResult(ok=False, message="缺少 app_id 或 app_secret")
    if not cfg["app_token"]:
        return FeishuTestResult(ok=False, message="缺少 app_token（多维表格应用 ID）")

    client = FeishuClient(app_id=cfg["app_id"], app_secret=cfg["app_secret"])
    try:
        # 这一行会同时验证 token 端点 + bitable 应用读取权限
        info = await client.get_app_info(cfg["app_token"])
        return FeishuTestResult(
            ok=True,
            message="连接成功",
            app_name=info.get("name", ""),
            revision=info.get("revision", 0),
            time_zone=info.get("time_zone", ""),
        )
    except FeishuError as e:
        # 飞书业务错误：把它的 code/msg 直接给用户看，方便定位
        logger.warning("feishu test failed: %s", e)
        hint = _diagnose_error_code(e.code)
        return FeishuTestResult(ok=False, message=f"{e}（{hint}）" if hint else str(e))
    except Exception as e:
        logger.error("feishu test unexpected error: %s", e, exc_info=True)
        return FeishuTestResult(ok=False, message=f"未预期错误：{e}")
    finally:
        await client.close()


def _diagnose_error_code(code: int) -> str:
    """把飞书常见错误码翻译成可执行的提示。"""
    return {
        99991663: "token 过期，刷新一次再试",
        99991668: "应用未启用，请到飞书开发者后台启用应用",
        99991672: "应用未开通多维表格权限。去开发者后台 → 权限管理 → 添加 bitable:app:readonly（或 bitable:app / base:app:read），然后版本管理 → 创建版本并发布",
        91402: "权限不足。请在「应用 → 权限管理」勾上 base:app:read 或 bitable:app:readonly",
        91403: "应用未被多维表格授权。请在多维表格右上角『...』→ 添加应用 → 选你这个应用",
        1254000: "多维表格不存在或被删除",
        1254001: "app_token 格式不正确",
        1254002: "应用未授权访问该多维表格。在多维表格右上角『...』→ 添加应用 → 选你这个应用",
    }.get(code, "")
