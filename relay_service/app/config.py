from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    relay_token: str
    public_base_url: str
    ai_provider_base_url: str
    ai_provider_api_key: str
    ai_provider_model: str
    storage_dir: Path
    metadata_path: Path
    max_upload_mb: int
    default_ttl_minutes: int
    max_ttl_minutes: int
    cleanup_interval_seconds: int
    # Web 端新增
    sqlite_db_path: str
    webdav_base_url: str
    webdav_username: str
    webdav_password: str
    webdav_folder: str
    webdav_sync_interval: int
    static_dir: Path


def get_settings() -> Settings:
    storage_dir = Path(os.getenv("RELAY_STORAGE_DIR", "./storage/temp")).resolve()
    metadata_path = Path(os.getenv("RELAY_METADATA_PATH", "./storage/metadata.json")).resolve()
    storage_dir.mkdir(parents=True, exist_ok=True)
    metadata_path.parent.mkdir(parents=True, exist_ok=True)

    return Settings(
        relay_token=os.getenv("RELAY_TOKEN", "change-me"),
        public_base_url=os.getenv("PUBLIC_BASE_URL", "http://localhost:8787").rstrip("/"),
        ai_provider_base_url=os.getenv("AI_PROVIDER_BASE_URL", "").rstrip("/"),
        ai_provider_api_key=os.getenv("AI_PROVIDER_API_KEY", ""),
        ai_provider_model=os.getenv("AI_PROVIDER_MODEL", "gpt-4.1-mini"),
        storage_dir=storage_dir,
        metadata_path=metadata_path,
        max_upload_mb=int(os.getenv("MAX_UPLOAD_MB", "50")),
        default_ttl_minutes=int(os.getenv("DEFAULT_TTL_MINUTES", "1440")),
        max_ttl_minutes=int(os.getenv("MAX_TTL_MINUTES", "1440")),
        cleanup_interval_seconds=int(os.getenv("CLEANUP_INTERVAL_SECONDS", "600")),
        sqlite_db_path=os.getenv("SQLITE_DB_PATH", "./storage/ydrop.db"),
        webdav_base_url=os.getenv("WEBDAV_BASE_URL", ""),
        webdav_username=os.getenv("WEBDAV_USERNAME", ""),
        webdav_password=os.getenv("WEBDAV_PASSWORD", ""),
        webdav_folder=os.getenv("WEBDAV_FOLDER", "ydoc/inbox"),
        webdav_sync_interval=int(os.getenv("WEBDAV_SYNC_INTERVAL", "300")),
        static_dir=Path(os.getenv("STATIC_DIR", "./static")).resolve(),
    )
