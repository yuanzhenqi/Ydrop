"""运行时配置存储：SQLite key-value，首次启动从 .env 迁移。"""

from __future__ import annotations

import logging
import time
from typing import Any

from .config import get_settings
from .database import get_db

logger = logging.getLogger("settings_store")

# ─── key 命名约定 ───
# webdav.base_url, webdav.username, webdav.password, webdav.folder,
# webdav.auto_sync, webdav.sync_interval, webdav.enabled
# ai.enabled, ai.base_url, ai.token, ai.model, ai.endpoint_mode,
# ai.prompt_supplement, ai.auto_run_on_text_save, ai.auto_retry_on_failure

# 从 env 迁移的映射：env_key -> (store_key, type)
ENV_MIGRATION_MAP = {
    "WEBDAV_BASE_URL": ("webdav.base_url", "str"),
    "WEBDAV_USERNAME": ("webdav.username", "str"),
    "WEBDAV_PASSWORD": ("webdav.password", "str"),
    "WEBDAV_FOLDER": ("webdav.folder", "str"),
    "WEBDAV_SYNC_INTERVAL": ("webdav.sync_interval", "int"),
    "AI_PROVIDER_BASE_URL": ("ai.base_url", "str"),
    "AI_PROVIDER_API_KEY": ("ai.token", "str"),
    "AI_PROVIDER_MODEL": ("ai.model", "str"),
}

# 默认值（当 store 和 env 都没有时）
DEFAULTS: dict[str, Any] = {
    "webdav.base_url": "",
    "webdav.username": "",
    "webdav.password": "",
    "webdav.folder": "ydoc/inbox",
    "webdav.auto_sync": True,
    "webdav.sync_interval": 300,
    "webdav.enabled": False,
    "ai.enabled": False,
    "ai.base_url": "",
    "ai.token": "",
    "ai.model": "gpt-4o",
    "ai.endpoint_mode": "AUTO",  # AUTO / OPENAI / ANTHROPIC
    "ai.prompt_supplement": "",
    "ai.auto_run_on_text_save": True,
    "ai.auto_retry_on_failure": True,
}


def _coerce(value: str | None, type_hint: str) -> Any:
    if value is None:
        return None
    if type_hint == "int":
        try:
            return int(value)
        except (ValueError, TypeError):
            return None
    if type_hint == "bool":
        return value.lower() in ("1", "true", "yes", "on")
    return value


def _encode(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


async def init_and_migrate() -> None:
    """首次启动：如果 app_settings 表为空，把 .env 配置写入作为初始值。"""
    db = await get_db()
    rows = await db.execute_fetchall("SELECT COUNT(*) FROM app_settings")
    count = rows[0][0] if rows else 0
    if count > 0:
        logger.info("app_settings has %d rows, skip env migration", count)
        return

    logger.info("app_settings empty, migrating from .env")
    env_settings = get_settings()
    now = int(time.time() * 1000)

    migrations: list[tuple[str, str]] = []
    # 从 env_settings 对应字段读
    env_field_map = {
        "webdav.base_url": env_settings.webdav_base_url,
        "webdav.username": env_settings.webdav_username,
        "webdav.password": env_settings.webdav_password,
        "webdav.folder": env_settings.webdav_folder,
        "webdav.sync_interval": env_settings.webdav_sync_interval,
        "webdav.enabled": bool(env_settings.webdav_base_url),
        "ai.base_url": env_settings.ai_provider_base_url,
        "ai.token": env_settings.ai_provider_api_key,
        "ai.model": env_settings.ai_provider_model,
        "ai.enabled": bool(env_settings.ai_provider_base_url and env_settings.ai_provider_api_key),
    }
    for key, val in env_field_map.items():
        encoded = _encode(val) if val not in ("", None) else None
        if encoded is not None:
            migrations.append((key, encoded))

    # 补齐默认值
    for key, default in DEFAULTS.items():
        if key not in dict(migrations):
            encoded = _encode(default)
            if encoded is not None:
                migrations.append((key, encoded))

    for key, value in migrations:
        await db.execute(
            "INSERT OR REPLACE INTO app_settings (key, value, updated_at) VALUES (?, ?, ?)",
            [key, value, now],
        )
    await db.commit()
    logger.info("Migrated %d settings from .env to app_settings", len(migrations))


async def get_value(key: str, type_hint: str = "str") -> Any:
    """读取单个 key。优先 store，其次 DEFAULTS。"""
    db = await get_db()
    rows = await db.execute_fetchall("SELECT value FROM app_settings WHERE key = ?", [key])
    if rows and rows[0][0] is not None:
        return _coerce(rows[0][0], type_hint)
    # fallback to defaults
    default = DEFAULTS.get(key)
    if default is not None:
        return default
    return None


async def get_all() -> dict[str, Any]:
    """读取所有配置，返回扁平 dict。缺失的 key 用默认值。"""
    db = await get_db()
    rows = await db.execute_fetchall("SELECT key, value FROM app_settings")
    result: dict[str, Any] = {}
    for r in rows:
        result[r["key"]] = r["value"]
    # 补齐默认值
    for key, default in DEFAULTS.items():
        if key not in result:
            result[key] = _encode(default)
    return result


async def set_value(key: str, value: Any) -> None:
    db = await get_db()
    encoded = _encode(value)
    now = int(time.time() * 1000)
    if encoded is None:
        await db.execute("DELETE FROM app_settings WHERE key = ?", [key])
    else:
        await db.execute(
            "INSERT OR REPLACE INTO app_settings (key, value, updated_at) VALUES (?, ?, ?)",
            [key, encoded, now],
        )
    await db.commit()


async def set_many(items: dict[str, Any]) -> None:
    """批量更新。value 为 None 或空字符串的 key 会被跳过（保留原值，用于密码字段）。
    但显式传入 False / 0 / 不为空的字符串会写入。
    """
    db = await get_db()
    now = int(time.time() * 1000)
    for key, value in items.items():
        if value is None:
            continue
        # Password fields: empty string means "keep existing"
        if key in ("webdav.password", "ai.token") and value == "":
            continue
        encoded = _encode(value)
        if encoded is None:
            continue
        await db.execute(
            "INSERT OR REPLACE INTO app_settings (key, value, updated_at) VALUES (?, ?, ?)",
            [key, encoded, now],
        )
    await db.commit()


# ─── 方便的分组读取 ───

async def get_webdav_config() -> dict[str, Any]:
    return {
        "base_url": await get_value("webdav.base_url", "str") or "",
        "username": await get_value("webdav.username", "str") or "",
        "password": await get_value("webdav.password", "str") or "",
        "folder": await get_value("webdav.folder", "str") or "ydoc/inbox",
        "auto_sync": await get_value("webdav.auto_sync", "bool") if await get_value("webdav.auto_sync", "bool") is not None else True,
        "sync_interval": await get_value("webdav.sync_interval", "int") or 300,
        "enabled": await get_value("webdav.enabled", "bool") if await get_value("webdav.enabled", "bool") is not None else False,
    }


async def get_ai_config() -> dict[str, Any]:
    return {
        "enabled": await get_value("ai.enabled", "bool") if await get_value("ai.enabled", "bool") is not None else False,
        "base_url": await get_value("ai.base_url", "str") or "",
        "token": await get_value("ai.token", "str") or "",
        "model": await get_value("ai.model", "str") or "gpt-4o",
        "endpoint_mode": await get_value("ai.endpoint_mode", "str") or "AUTO",
        "prompt_supplement": await get_value("ai.prompt_supplement", "str") or "",
        "auto_run_on_text_save": await get_value("ai.auto_run_on_text_save", "bool") if await get_value("ai.auto_run_on_text_save", "bool") is not None else True,
        "auto_retry_on_failure": await get_value("ai.auto_retry_on_failure", "bool") if await get_value("ai.auto_retry_on_failure", "bool") is not None else True,
    }
