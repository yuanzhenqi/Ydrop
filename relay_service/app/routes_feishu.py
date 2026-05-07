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
    """返给客户端的配置。app_secret 不回传，只回 secret_set 标志。
    webhook_url 是预拼好的 webhook 完整地址（含 secret），用户可直接复制到飞书 Automation。"""

    enabled: bool = False
    app_id: str = ""
    secret_set: bool = False
    app_token: str = ""
    table_id: str = ""
    sync_interval: int = 300
    webhook_url: str = ""


class FeishuSettingsUpdate(BaseModel):
    """部分更新。app_secret 留空表示不改；显式传空字符串如何区分需要业务约定，
    这里采用『空字符串 = 不改』，要清空 secret 必须前端先调 disable。"""

    enabled: Optional[bool] = None
    app_id: Optional[str] = None
    app_secret: Optional[str] = None
    app_token: Optional[str] = None
    table_id: Optional[str] = None
    sync_interval: Optional[int] = None


class FeishuTestResult(BaseModel):
    ok: bool
    message: str = ""
    app_name: str = ""
    revision: int = 0
    time_zone: str = ""


class FeishuInitTableResult(BaseModel):
    ok: bool
    message: str = ""
    created: list[str] = []
    skipped: list[str] = []
    errors: list[dict] = []


class FeishuPushAllResult(BaseModel):
    ok: bool
    message: str = ""
    pushed: int = 0
    failed: int = 0


class FeishuPullResult(BaseModel):
    ok: bool
    message: str = ""
    pulled_updated: int = 0
    pulled_created: int = 0
    trashed_local: int = 0
    errors: list[str] = []


class FeishuConflictItem(BaseModel):
    id: int
    note_id: str
    occurred_at: int
    note_title: str | None = None
    prev_title: str | None = None
    prev_content: str | None = None
    prev_category: str | None = None
    prev_priority: str | None = None
    prev_tags_json: str | None = None
    prev_is_archived: int = 0
    prev_updated_at: int | None = None
    new_title: str | None = None
    new_updated_at: int | None = None


class FeishuConflictResolveBody(BaseModel):
    choice: str  # 'local' | 'remote'


# ─── Endpoints ───


@router.get("/settings", response_model=FeishuSettings)
async def get_feishu_settings():
    cfg = await settings_store.get_feishu_config()
    # 拼接完整 webhook URL（PUBLIC_BASE_URL + path + secret）方便用户直接复制
    from .config import get_settings as _gs
    public_base = _gs().public_base_url.rstrip("/")
    webhook_url = (
        f"{public_base}/api/feishu/webhook/{cfg.get('webhook_secret', '')}"
        if cfg.get("webhook_secret") else ""
    )
    return FeishuSettings(
        enabled=cfg["enabled"],
        app_id=cfg["app_id"],
        secret_set=bool(cfg["app_secret"]),
        app_token=cfg["app_token"],
        table_id=cfg["table_id"],
        sync_interval=cfg.get("sync_interval", 300),
        webhook_url=webhook_url,
    )


def _sanitize_id(raw: str) -> str:
    """剥掉用户从 URL 里复制时常带的 &view=... ?... # 等后缀。
    table_id / app_token 都是纯字母数字，遇到 & ? # 就截断。"""
    s = raw.strip()
    for sep in ("&", "?", "#", " ", "\n"):
        if sep in s:
            s = s.split(sep, 1)[0]
    return s


@router.put("/settings", response_model=FeishuSettings)
async def update_feishu_settings(body: FeishuSettingsUpdate):
    updates: dict = {}
    if body.enabled is not None:
        updates["feishu.enabled"] = bool(body.enabled)
    if body.app_id is not None:
        updates["feishu.app_id"] = _sanitize_id(body.app_id)
    # 空字符串视作不动；要清空就传 enabled=false 即可让 connector 短路
    if body.app_secret:
        updates["feishu.app_secret"] = body.app_secret.strip()
    if body.app_token is not None:
        updates["feishu.app_token"] = _sanitize_id(body.app_token)
    if body.table_id is not None:
        updates["feishu.table_id"] = _sanitize_id(body.table_id)
    if body.sync_interval is not None:
        # 0 = 仅手动；下限 60s 防止过于频繁压垮飞书 API
        v = int(body.sync_interval)
        if v < 0:
            v = 0
        elif 0 < v < 60:
            v = 60
        updates["feishu.sync_interval"] = v
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


@router.post("/init-table", response_model=FeishuInitTableResult)
async def init_ydrop_table():
    """按 YDROP_SCHEMA 把缺失的标准列在用户的 Bitable 里建出来。同名列跳过、不会改用户已有列。

    需要 app 拥有 bitable:app 写权限（仅 readonly 不够）。
    """
    cfg = await settings_store.get_feishu_config()
    if not cfg["app_id"] or not cfg["app_secret"]:
        return FeishuInitTableResult(ok=False, message="缺少 app_id 或 app_secret")
    if not cfg["app_token"] or not cfg["table_id"]:
        return FeishuInitTableResult(ok=False, message="缺少 app_token 或 table_id")

    client = FeishuClient(app_id=cfg["app_id"], app_secret=cfg["app_secret"])
    try:
        result = await client.init_ydrop_table(cfg["app_token"], cfg["table_id"])
        # 任一字段创建失败也认为整体 ok=true，但带错误清单让用户看
        msg_parts = []
        if result["created"]:
            msg_parts.append(f"已建 {len(result['created'])} 列：{', '.join(result['created'])}")
        if result["skipped"]:
            msg_parts.append(f"跳过 {len(result['skipped'])} 列已存在：{', '.join(result['skipped'])}")
        if result["errors"]:
            err_brief = ", ".join(f"{e['name']}({e['code']})" for e in result["errors"])
            msg_parts.append(f"⚠ {len(result['errors'])} 列建失败：{err_brief}")
        return FeishuInitTableResult(
            ok=not result["errors"],
            message=" / ".join(msg_parts) or "无变更",
            created=result["created"],
            skipped=result["skipped"],
            errors=result["errors"],
        )
    except FeishuError as e:
        logger.warning("feishu init_ydrop_table failed: %s", e)
        hint = _diagnose_error_code(e.code)
        return FeishuInitTableResult(ok=False, message=f"{e}（{hint}）" if hint else str(e))
    except Exception as e:
        logger.error("feishu init_ydrop_table unexpected: %s", e, exc_info=True)
        return FeishuInitTableResult(ok=False, message=f"未预期错误：{e}")
    finally:
        await client.close()


@router.post("/debug/push-note/{note_id}")
async def debug_push_note(note_id: str):
    """诊断用：手动把一条笔记 push 到飞书，返回详细结果。"""
    from .feishu_orchestrator import push_note, _get_mapping_full, _load_note_dict
    note = await _load_note_dict(note_id)
    if note is None:
        return {"ok": False, "message": f"本地找不到 note {note_id}"}
    mapping_before = await _get_mapping_full(note_id)
    ok = await push_note(note_id)
    mapping_after = await _get_mapping_full(note_id)
    return {
        "ok": ok,
        "note_id": note_id,
        "title": note.get("title", ""),
        "category": note.get("category", ""),
        "is_archived": note.get("is_archived", False),
        "is_trashed": note.get("is_trashed", False),
        "mapping_before": mapping_before,
        "mapping_after": mapping_after,
    }


@router.get("/conflicts", response_model=list[FeishuConflictItem])
async def list_feishu_conflicts(only_unresolved: bool = True, limit: int = 50):
    """列出从飞书拉取时被覆盖的本地版本快照。默认只看未解决的。"""
    from .feishu_orchestrator import list_conflicts
    items = await list_conflicts(only_unresolved=only_unresolved, limit=limit)
    return [FeishuConflictItem(**item) for item in items]


@router.post("/conflicts/{conflict_id}/resolve")
async def resolve_feishu_conflict(conflict_id: int, body: FeishuConflictResolveBody):
    """解决冲突：local = 回滚到本地版本（同时推回飞书 + WebDAV）；remote = 仅接受现状。"""
    from .feishu_orchestrator import resolve_conflict
    result = await resolve_conflict(conflict_id, body.choice)
    return result


@router.post("/sync/pull", response_model=FeishuPullResult)
async def pull_from_feishu_endpoint():
    """从飞书 Bitable 拉取所有 record，按 ydrop_id 与本地笔记 last_write_wins 合并；
    Bitable 端新建（无 ydrop_id）的 record 会创建本地笔记 + 回写 ydrop_id；
    本地有 mapping 但远端缺失的笔记移回收站。"""
    from .feishu_orchestrator import pull_from_feishu
    result = await pull_from_feishu()
    return FeishuPullResult(
        ok=result.get("ok", False),
        message=result.get("message", ""),
        pulled_updated=result.get("pulled_updated", 0),
        pulled_created=result.get("pulled_created", 0),
        trashed_local=result.get("trashed_local", 0),
        errors=result.get("errors", []),
    )


@router.post("/sync/push-all", response_model=FeishuPushAllResult)
async def push_all_to_feishu():
    """把当前所有非回收站笔记一次性推到飞书 Bitable（首次接入用）。

    内部调 feishu_orchestrator.push_all_active：每条笔记 create 或 update。
    需要表已经初始化过 Ydrop schema（init-table）。
    """
    from .feishu_orchestrator import push_all_active
    result = await push_all_active()
    return FeishuPushAllResult(
        ok=result.get("ok", False),
        message=result.get("message", ""),
        pushed=result.get("pushed", 0),
        failed=result.get("failed", 0),
    )


def _diagnose_error_code(code: int) -> str:
    """把飞书常见错误码翻译成可执行的提示。"""
    return {
        99991663: "token 过期，刷新一次再试",
        99991668: "应用未启用，请到飞书开发者后台启用应用",
        99991672: "应用未开通多维表格权限。去开发者后台 → 权限管理 → 添加 bitable:app:readonly（或 bitable:app / base:app:read），然后版本管理 → 创建版本并发布",
        91402: "权限不足。请在「应用 → 权限管理」勾上 base:app:read 或 bitable:app:readonly",
        91403: "应用对该多维表格只有「查看」权限。请到多维表格右上角『...』→ 协作管理 → 把 Ydrop 应用的权限改成「可编辑」（或先移除再以「可编辑」身份重新添加）",
        1254000: "多维表格不存在或被删除",
        1254001: "app_token 格式不正确",
        1254002: "应用未授权访问该多维表格。在多维表格右上角『...』→ 添加应用 → 选你这个应用",
    }.get(code, "")
