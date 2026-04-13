from __future__ import annotations

import aiosqlite
from pathlib import Path

from .config import get_settings

_db: aiosqlite.Connection | None = None

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS notes (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    original_content TEXT,
    source TEXT NOT NULL DEFAULT 'TEXT',
    category TEXT NOT NULL DEFAULT 'NOTE',
    priority TEXT NOT NULL DEFAULT 'MEDIUM',
    color_token TEXT NOT NULL DEFAULT 'SAGE',
    status TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_synced_at INTEGER,
    sync_error TEXT,
    pinned INTEGER NOT NULL DEFAULT 0,
    remote_path TEXT,
    last_pulled_at INTEGER,
    is_archived INTEGER NOT NULL DEFAULT 0,
    archived_at INTEGER,
    is_trashed INTEGER NOT NULL DEFAULT 0,
    trashed_at INTEGER,
    tags_json TEXT DEFAULT '[]',
    transcript TEXT,
    audio_path TEXT,
    relay_url TEXT,
    transcription_status TEXT DEFAULT 'NOT_STARTED'
);

CREATE INDEX IF NOT EXISTS idx_notes_updated_at ON notes(updated_at);
CREATE INDEX IF NOT EXISTS idx_notes_is_trashed ON notes(is_trashed);
CREATE INDEX IF NOT EXISTS idx_notes_is_archived ON notes(is_archived);

CREATE TABLE IF NOT EXISTS ai_suggestions (
    id TEXT PRIMARY KEY,
    note_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    summary TEXT NOT NULL DEFAULT '',
    suggested_title TEXT,
    suggested_category TEXT,
    suggested_priority TEXT,
    todo_items_json TEXT DEFAULT '[]',
    extracted_entities_json TEXT DEFAULT '[]',
    reminder_candidates_json TEXT DEFAULT '[]',
    error_message TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_suggestions_note_id ON ai_suggestions(note_id);

CREATE TABLE IF NOT EXISTS reminders (
    id TEXT PRIMARY KEY,
    note_id TEXT NOT NULL,
    title TEXT NOT NULL,
    scheduled_at INTEGER NOT NULL,
    source TEXT NOT NULL DEFAULT 'MANUAL',
    status TEXT NOT NULL DEFAULT 'SCHEDULED',
    delivery_targets_json TEXT DEFAULT '["LOCAL_NOTIFICATION"]',
    recurrence TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reminders_note_id ON reminders(note_id);
CREATE INDEX IF NOT EXISTS idx_reminders_scheduled_at ON reminders(scheduled_at);

CREATE TABLE IF NOT EXISTS tombstones (
    note_id TEXT PRIMARY KEY,
    deleted_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS app_settings (
    key TEXT PRIMARY KEY,
    value TEXT,
    updated_at INTEGER NOT NULL
);
"""


async def get_db() -> aiosqlite.Connection:
    global _db
    if _db is None:
        settings = get_settings()
        db_path = Path(settings.sqlite_db_path)
        db_path.parent.mkdir(parents=True, exist_ok=True)
        _db = await aiosqlite.connect(str(db_path))
        _db.row_factory = aiosqlite.Row
        await _db.execute("PRAGMA journal_mode=WAL")
        await _db.execute("PRAGMA foreign_keys=ON")
        await _db.executescript(SCHEMA_SQL)
        # Migrations for older DBs
        await _migrate(_db)
        await _db.commit()
    return _db


async def _migrate(db: aiosqlite.Connection) -> None:
    """Add missing columns on existing databases (idempotent)."""
    cur = await db.execute("PRAGMA table_info(reminders)")
    cols = {row[1] for row in await cur.fetchall()}
    if "recurrence" not in cols:
        await db.execute("ALTER TABLE reminders ADD COLUMN recurrence TEXT")


async def close_db() -> None:
    global _db
    if _db is not None:
        await _db.close()
        _db = None
