"""飞书 Automation 反推 webhook 端点。

不挂 require_relay_token —— 飞书发请求时无法带 Bearer token；用 URL path 里的 secret 验证。

用户在飞书 Bitable Automation 里配置：
- 触发条件：当记录创建 / 更新 / 删除时
- 执行操作：发送 HTTP 请求 → POST {webhook_url}
- 请求体（推荐）：{"record_id": "{{记录.记录ID}}"}（含 record_id 走单条快路径）
  没有也行，会触发全量 pull 兜底。

设计：
- 单条路径：解析 body record_id → pull_one_record（毫秒级）
- 兜底全量：record_id 缺失时调 pull_from_feishu（与 5min 定时一致）

所有操作 fire-and-forget，端点立即返 200，避免飞书 Automation 超时重试。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import APIRouter, Body, HTTPException

from . import settings_store

logger = logging.getLogger("feishu_webhook")
# 注意：这个 router 没有挂 dependencies，因为飞书 Automation 没法带 Bearer token。
# 安全靠 URL 路径里的 webhook_secret（首次 settings 时随机生成）。
router = APIRouter(prefix="/api/feishu/webhook")


@router.post("/{secret}")
async def feishu_webhook(secret: str, body: dict[str, Any] = Body(default_factory=dict)):
    """飞书 Automation 触发。验证 secret，然后异步 pull。"""
    cfg = await settings_store.get_feishu_config()
    expected = cfg.get("webhook_secret", "")
    if not expected or secret != expected:
        # 故意不返回 detail 避免泄露 secret 是否存在
        raise HTTPException(status_code=404, detail="not found")
    if not cfg.get("enabled"):
        return {"ok": True, "action": "ignored", "message": "feishu sync disabled"}

    record_id = _extract_record_id(body)
    # 完全 fire-and-forget：不等结果，让 webhook 立即返回
    if record_id:
        asyncio.create_task(_pull_single(record_id))
        logger.info("webhook -> single pull record=%s", record_id)
        return {"ok": True, "action": "single", "record_id": record_id}
    asyncio.create_task(_pull_full())
    logger.info("webhook -> full pull (no record_id in body)")
    return {"ok": True, "action": "full"}


def _extract_record_id(body: dict[str, Any]) -> str:
    """飞书 Automation 的 body 由用户自定义，常见写法包括：
    - {"record_id": "{{记录.记录ID}}"}
    - {"recordId": "..."} 或 {"record": {"id": "..."}} 等驼峰/嵌套形式
    挨个尝试常见 key，找到非空字符串就用。
    """
    if not isinstance(body, dict):
        return ""
    for key in ("record_id", "recordId", "id"):
        v = body.get(key)
        if isinstance(v, str) and v.strip():
            return v.strip()
    nested = body.get("record")
    if isinstance(nested, dict):
        for key in ("id", "record_id", "recordId"):
            v = nested.get(key)
            if isinstance(v, str) and v.strip():
                return v.strip()
    return ""


async def _pull_single(record_id: str) -> None:
    try:
        from .feishu_orchestrator import pull_one_record
        result = await pull_one_record(record_id)
        logger.info("webhook pull_one record=%s -> %s", record_id, result.get("message", "?"))
    except Exception as e:
        logger.error("webhook pull_one exception record=%s: %s", record_id, e, exc_info=True)


async def _pull_full() -> None:
    try:
        from .feishu_orchestrator import pull_from_feishu
        result = await pull_from_feishu()
        logger.info("webhook pull_full -> %s", result.get("message", "?"))
    except Exception as e:
        logger.error("webhook pull_full exception: %s", e, exc_info=True)
