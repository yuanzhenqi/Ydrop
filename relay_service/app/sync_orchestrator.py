"""Bidirectional WebDAV sync — Python port of SyncOrchestrator.kt."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from urllib.parse import quote

from .config import get_settings
from .database import get_db
from .markdown_format import extract_id_from_filename, file_name, parse_from_markdown, render
from .webdav_client import WebDavClient

logger = logging.getLogger("sync")

_last_sync_at: int | None = None
_last_pushed = 0
_last_pulled = 0
_last_errors = 0
_running = False


def get_sync_status() -> dict:
    return {
        "last_sync_at": _last_sync_at,
        "pushed": _last_pushed,
        "pulled": _last_pulled,
        "errors": _last_errors,
        "running": _running,
    }


async def sync_bidirectional() -> dict:
    global _last_sync_at, _last_pushed, _last_pulled, _last_errors, _running

    settings = get_settings()
    if not settings.webdav_base_url:
        logger.info("WebDAV not configured, skipping sync")
        return get_sync_status()

    _running = True
    pushed = 0
    pulled = 0
    errors = 0

    try:
        client = WebDavClient()
        db = await get_db()

        # 1. List remote files
        remote_files = await client.list_remote()
        logger.info("Found %d remote files", len(remote_files))

        # 2. Pull and parse each remote file
        remote_by_id: dict[str, dict] = {}  # note_id -> {rfi, content, parsed}
        for rfi in remote_files:
            try:
                content = await client.pull(rfi.path)
                from .markdown_format import parse_from_markdown as pfm
                parsed = pfm(content, rfi.path)
                if parsed and parsed.get("id"):
                    remote_by_id[parsed["id"]] = {
                        "rfi": rfi,
                        "content": content,
                        "parsed": parsed,
                    }
            except Exception as e:
                logger.error("Pull failed for %s: %s", rfi.path, e)
                errors += 1

        # 3. Get local state
        local_rows = await db.execute_fetchall("SELECT * FROM notes")
        local_by_id = {r["id"]: r for r in local_rows}

        tombstone_rows = await db.execute_fetchall("SELECT note_id FROM tombstones")
        tombstone_ids = {r[0] for r in tombstone_rows}

        logger.info("Local notes: %d, tombstones: %d, remote parsed: %d",
                     len(local_by_id), len(tombstone_ids), len(remote_by_id))

        # 4. Process remote notes
        for remote_id, remote_data in remote_by_id.items():
            rfi = remote_data["rfi"]
            remote_note = remote_data["parsed"]
            local = local_by_id.get(remote_id)

            if local is not None:
                # Local exists
                if local["is_trashed"]:
                    try:
                        await client.delete_path(rfi.path)
                    except Exception as e:
                        logger.error("Delete trashed remote failed %s: %s", remote_id, e)
                        errors += 1
                    continue

                remote_updated = remote_note["updated_at"]
                local_updated = local["updated_at"]
                local_synced = local["last_synced_at"] or 0
                http_last_mod = rfi.last_modified or 0

                remote_changed = (
                    remote_note["content"] != local["content"]
                    or remote_note["title"] != local["title"]
                    or remote_note["category"] != local["category"]
                    or remote_note["priority"] != local["priority"]
                    or remote_note["is_archived"] != bool(local["is_archived"])
                    or rfi.path != local["remote_path"]
                )

                if http_last_mod > local_synced or remote_updated > local_updated:
                    if remote_changed:
                        await _upsert_from_remote(db, remote_note, rfi.path)
                        pulled += 1
                        logger.info("PULLED id=%s", remote_id)
                elif local_updated > remote_updated and local["status"] != "SYNCED":
                    if await _push_note(db, client, local):
                        pushed += 1
                    else:
                        errors += 1
            else:
                # Local doesn't exist
                if remote_id not in tombstone_ids:
                    await _upsert_from_remote(db, remote_note, rfi.path)
                    pulled += 1
                    logger.info("PULLED new remote id=%s", remote_id)

        # 5. Push local notes not in remote
        for note_id, local in local_by_id.items():
            if note_id in tombstone_ids:
                continue
            if local["is_trashed"]:
                continue
            if local["status"] == "SYNCED" and note_id in remote_by_id:
                continue
            if note_id not in remote_by_id:
                if await _push_note(db, client, local):
                    pushed += 1
                else:
                    errors += 1

        # 6. Clean up tombstoned remotes
        for tid in tombstone_ids:
            if tid in remote_by_id:
                try:
                    await client.delete_path(remote_by_id[tid]["rfi"].path)
                except Exception as e:
                    logger.error("Tombstone remote delete failed %s: %s", tid, e)

        await client.close()
        await db.commit()

    except Exception as e:
        logger.error("Sync failed: %s", e, exc_info=True)
        errors += 1
    finally:
        _running = False
        _last_sync_at = int(time.time() * 1000)
        _last_pushed = pushed
        _last_pulled = pulled
        _last_errors = errors

    logger.info("Sync complete: pushed=%d pulled=%d errors=%d", pushed, pulled, errors)
    return get_sync_status()


async def _upsert_from_remote(db, note: dict, remote_path: str) -> None:
    now = int(time.time() * 1000)
    tags_json = json.dumps(note.get("tags", []), ensure_ascii=False)

    await db.execute(
        """INSERT OR REPLACE INTO notes
           (id, title, content, source, category, priority, color_token, status,
            created_at, updated_at, last_synced_at, remote_path, last_pulled_at,
            is_archived, archived_at, is_trashed, trashed_at, tags_json,
            transcript, audio_path, relay_url, transcription_status)
           VALUES (?, ?, ?, ?, ?, ?, ?, 'SYNCED', ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, ?, ?, ?, ?)""",
        [
            note["id"], note["title"], note["content"], note["source"],
            note["category"], note["priority"], note["color_token"],
            note["created_at"], note["updated_at"], now, remote_path, now,
            1 if note["is_archived"] else 0, note.get("archived_at"),
            tags_json, note.get("transcript"), note.get("audio_path"),
            note.get("relay_url"), note.get("transcription_status", "NOT_STARTED"),
        ],
    )


async def _push_note(db, client: WebDavClient, local_row) -> bool:
    try:
        note_dict = _row_to_dict(local_row)
        md_content = render(note_dict)
        fn = file_name(note_dict)

        folder = client.inbox_folder() if not local_row["is_archived"] else client.archive_folder()
        remote_path = f"{folder}/{fn}"

        # Delete old remote if path changed
        old_path = local_row["remote_path"]
        if old_path and old_path != remote_path:
            try:
                await client.delete_path(old_path)
            except Exception:
                pass

        await db.execute("UPDATE notes SET status = 'SYNCING' WHERE id = ?", [local_row["id"]])
        await client.push(remote_path, md_content)

        now = int(time.time() * 1000)
        await db.execute(
            "UPDATE notes SET status = 'SYNCED', last_synced_at = ?, remote_path = ?, sync_error = NULL WHERE id = ?",
            [now, remote_path, local_row["id"]],
        )
        logger.info("PUSHED id=%s to %s", local_row["id"], remote_path)
        return True
    except Exception as e:
        await db.execute(
            "UPDATE notes SET status = 'FAILED', sync_error = ? WHERE id = ?",
            [str(e)[:200], local_row["id"]],
        )
        logger.error("PUSH FAILED id=%s: %s", local_row["id"], e)
        return False


def _row_to_dict(row) -> dict:
    return {
        "id": row["id"],
        "title": row["title"],
        "content": row["content"],
        "source": row["source"],
        "category": row["category"],
        "priority": row["priority"],
        "color_token": row["color_token"],
        "status": row["status"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "is_archived": bool(row["is_archived"]),
        "is_trashed": bool(row["is_trashed"]),
        "tags": json.loads(row["tags_json"] or "[]"),
        "transcript": row["transcript"],
        "audio_path": row["audio_path"],
        "relay_url": row["relay_url"],
        "sync_error": row["sync_error"],
        "transcription_status": row["transcription_status"] or "NOT_STARTED",
    }


async def sync_loop() -> None:
    """Background loop — call from FastAPI lifespan."""
    settings = get_settings()
    interval = settings.webdav_sync_interval
    if not settings.webdav_base_url:
        logger.info("WebDAV not configured, sync loop disabled")
        return
    logger.info("Sync loop started, interval=%ds", interval)
    while True:
        await asyncio.sleep(interval)
        try:
            await sync_bidirectional()
        except Exception as e:
            logger.error("Sync loop error: %s", e, exc_info=True)
