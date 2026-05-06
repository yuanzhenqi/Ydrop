"""Ydrop ↔ 飞书 Bitable 单向同步（Phase 1 Step 2b）。

只做 Ydrop → Bitable 这一向：
- push_note：create 新 record 或 update 已有 record
- delete_note：从 Bitable 删 record（笔记彻底删 / 移回收站时调）

反向（Bitable → Ydrop）留给 Step 3：轮询 + last_modified_time 比较 + Webhook 反推。

设计：
- mapping 存 SQLite feishu_mappings 表（note_id PK ↔ record_id）
- push 失败不阻塞主流程：只记日志，下次再试（笔记任意编辑都会触发推送）
- 笔记 trash → 删 Bitable record；后续 restore 会重新 create 一条新 record（可接受，因为 record_id 是黑盒）
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any, Optional

import json as _json
import uuid as _uuid

from . import settings_store
from .database import get_db
from .feishu_client import FeishuClient, FeishuError
from .feishu_mapping import bitable_fields_to_note_dict, note_to_bitable_fields

logger = logging.getLogger("feishu_sync")


# ─── 内部 helpers ───


async def _get_enabled_config() -> Optional[dict]:
    """读飞书配置；只要任一关键字段缺失就返回 None（外面短路）"""
    cfg = await settings_store.get_feishu_config()
    if not cfg["enabled"]:
        return None
    if not (cfg["app_id"] and cfg["app_secret"] and cfg["app_token"] and cfg["table_id"]):
        return None
    return cfg


async def _get_mapping(note_id: str) -> Optional[str]:
    db = await get_db()
    rows = await db.execute_fetchall("SELECT record_id FROM feishu_mappings WHERE note_id = ?", [note_id])
    return rows[0]["record_id"] if rows else None


async def _save_mapping(note_id: str, record_id: str) -> None:
    db = await get_db()
    now = int(time.time() * 1000)
    await db.execute(
        "INSERT OR REPLACE INTO feishu_mappings (note_id, record_id, last_synced_at) VALUES (?, ?, ?)",
        [note_id, record_id, now],
    )
    await db.commit()


async def _drop_mapping(note_id: str) -> None:
    db = await get_db()
    await db.execute("DELETE FROM feishu_mappings WHERE note_id = ?", [note_id])
    await db.commit()


async def _load_note_dict(note_id: str) -> Optional[dict]:
    db = await get_db()
    rows = await db.execute_fetchall("SELECT * FROM notes WHERE id = ?", [note_id])
    if not rows:
        return None
    r = rows[0]
    return {
        "id": r["id"],
        "title": r["title"],
        "content": r["content"],
        "category": r["category"],
        "priority": r["priority"],
        "tags": json.loads(r["tags_json"] or "[]"),
        "created_at": r["created_at"],
        "updated_at": r["updated_at"],
        "is_archived": bool(r["is_archived"]),
        "is_trashed": bool(r["is_trashed"]),
    }


# ─── 对外 API ───


async def push_note(note_id: str) -> bool:
    """把一条笔记推到飞书：有 mapping 就 update，没有就 create + 存 mapping。

    返回 True/False。失败只记日志，不抛出（fire-and-forget 语义）。
    """
    cfg = await _get_enabled_config()
    if cfg is None:
        return False
    note = await _load_note_dict(note_id)
    if note is None:
        logger.info("push_note skip: note %s not found", note_id)
        return False
    if note["is_trashed"]:
        # trash 走 delete_note 分支，push 不该被 trash 笔记触发
        logger.info("push_note skip: %s is trashed", note_id)
        return False

    fields = note_to_bitable_fields(note)
    record_id = await _get_mapping(note_id)
    client = FeishuClient(app_id=cfg["app_id"], app_secret=cfg["app_secret"])
    try:
        if record_id:
            try:
                await client.update_record(cfg["app_token"], cfg["table_id"], record_id, fields)
                await _save_mapping(note_id, record_id)  # 刷新 last_synced_at
                logger.info("feishu UPDATE note=%s record=%s", note_id, record_id)
                return True
            except FeishuError as e:
                # record 不在了：清 mapping 走 create 分支
                if e.code in (1254043, 1254040):
                    logger.info("feishu record %s gone, recreating", record_id)
                    await _drop_mapping(note_id)
                    record_id = None
                else:
                    raise
        if not record_id:
            record = await client.create_record(cfg["app_token"], cfg["table_id"], fields)
            new_id = record.get("record_id", "")
            if not new_id:
                logger.warning("feishu create returned empty record_id for note=%s", note_id)
                return False
            await _save_mapping(note_id, new_id)
            logger.info("feishu CREATE note=%s record=%s", note_id, new_id)
            return True
    except FeishuError as e:
        logger.warning("feishu push_note failed note=%s: %s", note_id, e)
        return False
    except Exception as e:
        logger.error("feishu push_note unexpected note=%s: %s", note_id, e, exc_info=True)
        return False
    finally:
        await client.close()
    return False


async def delete_note(note_id: str) -> bool:
    """从飞书删除该笔记对应的 record + 清 mapping。无 mapping 静默成功。"""
    cfg = await _get_enabled_config()
    if cfg is None:
        return False
    record_id = await _get_mapping(note_id)
    if not record_id:
        return True
    client = FeishuClient(app_id=cfg["app_id"], app_secret=cfg["app_secret"])
    try:
        await client.delete_record(cfg["app_token"], cfg["table_id"], record_id)
        await _drop_mapping(note_id)
        logger.info("feishu DELETE note=%s record=%s", note_id, record_id)
        return True
    except FeishuError as e:
        logger.warning("feishu delete_note failed note=%s: %s", note_id, e)
        return False
    except Exception as e:
        logger.error("feishu delete_note unexpected note=%s: %s", note_id, e, exc_info=True)
        return False
    finally:
        await client.close()


async def pull_from_feishu() -> dict:
    """反向拉：Bitable → Ydrop。每条 record 按 ydrop_id 找本地笔记，
    last_write_wins 决定方向；本地有 mapping 但远端缺失的笔记移本地回收站；
    远端无 ydrop_id 的 record 创建新本地笔记 + 回写 ydrop_id 到 Bitable。

    返回 {ok, message, pulled_updated, pulled_created, trashed_local, errors}.
    """
    cfg = await _get_enabled_config()
    if cfg is None:
        return {"ok": False, "message": "飞书未配置或未启用"}

    db = await get_db()
    client = FeishuClient(app_id=cfg["app_id"], app_secret=cfg["app_secret"])
    pulled_updated = 0
    pulled_created = 0
    trashed_local = 0
    errors: list[str] = []

    try:
        records = await client.list_records(cfg["app_token"], cfg["table_id"])
    except FeishuError as e:
        await client.close()
        return {"ok": False, "message": f"列出 records 失败：{e}"}

    seen_record_ids: set[str] = set()

    for rec in records:
        try:
            rid = rec.get("record_id", "")
            if not rid:
                continue
            seen_record_ids.add(rid)
            fields = rec.get("fields") or {}
            last_mod_ms = int(rec.get("last_modified_time") or 0)
            remote = bitable_fields_to_note_dict(fields, rid, last_mod_ms)

            ydrop_id = remote["id"]
            if not ydrop_id:
                # 飞书端新建的 record，没绑 ydrop_id：生成一个 + 回写
                new_id = _uuid.uuid4().hex
                remote["id"] = new_id
                await _upsert_local_from_remote(remote, is_new=True)
                pulled_created += 1
                # 回写 ydrop_id 到飞书 record，让下次能命中 mapping
                try:
                    await client.update_record(
                        cfg["app_token"], cfg["table_id"], rid, {"ydrop_id": new_id}
                    )
                    await _save_mapping(new_id, rid)
                except FeishuError as e:
                    errors.append(f"回写 ydrop_id 到 record={rid} 失败：{e}")
                continue

            # 已有 ydrop_id：last_write_wins
            local = await _load_note_dict(ydrop_id)
            if local is None:
                # 本地没了（可能被彻底删过），跳过避免复活
                continue
            if remote["updated_at"] > local["updated_at"]:
                await _upsert_local_from_remote(remote, is_new=False)
                pulled_updated += 1
            await _save_mapping(ydrop_id, rid)
        except Exception as e:
            errors.append(f"处理 record={rec.get('record_id','?')} 异常：{e}")
            logger.error("pull_from_feishu record failed: %s", e, exc_info=True)

    # 远端少了的 mapping → 本地移回收站（用户在 Bitable 删 record 的语义）
    rows = await db.execute_fetchall("SELECT note_id, record_id FROM feishu_mappings")
    for row in rows:
        if row["record_id"] not in seen_record_ids:
            try:
                now = int(asyncio.get_event_loop().time() * 1000)
                # 用 SQLite 时间戳一致，避免循环引入 time
                import time as _time
                now = int(_time.time() * 1000)
                await db.execute(
                    "UPDATE notes SET is_trashed = 1, trashed_at = ?, updated_at = ?, status = 'LOCAL_ONLY' "
                    "WHERE id = ? AND is_trashed = 0",
                    [now, now, row["note_id"]],
                )
                if db.total_changes > 0:
                    trashed_local += 1
                # mapping 也清掉，下次再创建会走 create 分支
                await db.execute("DELETE FROM feishu_mappings WHERE note_id = ?", [row["note_id"]])
            except Exception as e:
                errors.append(f"trash 本地 note={row['note_id']} 失败：{e}")
    await db.commit()
    await client.close()

    return {
        "ok": True,
        "message": f"拉取完成：更新 {pulled_updated}，新增 {pulled_created}，本地回收 {trashed_local}",
        "pulled_updated": pulled_updated,
        "pulled_created": pulled_created,
        "trashed_local": trashed_local,
        "errors": errors,
    }


async def _upsert_local_from_remote(remote: dict, is_new: bool) -> None:
    """把从 Bitable 拉回来的 dict 写进 SQLite notes 表。
    - is_new=True：INSERT，需要补 source/color_token/status 等默认值
    - is_new=False：仅更新可变字段（title/content/category/priority/tags/is_archived/updated_at）
    """
    from .markdown_format import default_color_for
    db = await get_db()
    if is_new:
        color = default_color_for(remote["category"], remote["priority"])
        await db.execute(
            """INSERT OR REPLACE INTO notes
               (id, title, content, source, category, priority, color_token, status,
                created_at, updated_at, last_synced_at,
                is_archived, archived_at, is_trashed, trashed_at, tags_json,
                transcription_status)
               VALUES (?, ?, ?, 'TEXT', ?, ?, ?, 'LOCAL_ONLY', ?, ?, ?, ?, ?, 0, NULL, ?, 'NOT_STARTED')""",
            [
                remote["id"], remote["title"], remote["content"],
                remote["category"], remote["priority"], color,
                remote["created_at"], remote["updated_at"], remote["updated_at"],
                1 if remote["is_archived"] else 0,
                remote["updated_at"] if remote["is_archived"] else None,
                _json.dumps(remote["tags"], ensure_ascii=False),
            ],
        )
    else:
        from .markdown_format import default_color_for
        color = default_color_for(remote["category"], remote["priority"])
        await db.execute(
            """UPDATE notes SET title = ?, content = ?, category = ?, priority = ?,
               color_token = ?, tags_json = ?, is_archived = ?, archived_at = ?,
               updated_at = ?, status = 'LOCAL_ONLY'
               WHERE id = ?""",
            [
                remote["title"], remote["content"], remote["category"], remote["priority"],
                color,
                _json.dumps(remote["tags"], ensure_ascii=False),
                1 if remote["is_archived"] else 0,
                remote["updated_at"] if remote["is_archived"] else None,
                remote["updated_at"],
                remote["id"],
            ],
        )


async def push_all_active() -> dict:
    """一键把所有非回收站的笔记推到飞书（首次接入用）。返回统计。"""
    cfg = await _get_enabled_config()
    if cfg is None:
        return {"ok": False, "message": "飞书未配置或未启用", "pushed": 0, "failed": 0}
    db = await get_db()
    rows = await db.execute_fetchall("SELECT id FROM notes WHERE is_trashed = 0 ORDER BY updated_at DESC")
    pushed = 0
    failed = 0
    for r in rows:
        ok = await push_note(r["id"])
        if ok:
            pushed += 1
        else:
            failed += 1
    return {"ok": True, "message": f"推送完成：成功 {pushed}，失败 {failed}", "pushed": pushed, "failed": failed}


# ─── 触发器：fire-and-forget ───


def trigger_push(note_id: str) -> None:
    """供 routes_notes 调用：异步推送，不等结果不抛错。"""
    asyncio.create_task(_trigger_push_safely(note_id))


def trigger_delete(note_id: str) -> None:
    asyncio.create_task(_trigger_delete_safely(note_id))


async def _trigger_push_safely(note_id: str) -> None:
    try:
        await push_note(note_id)
    except Exception as e:
        logger.warning("trigger_push exception note=%s: %s", note_id, e)


async def _trigger_delete_safely(note_id: str) -> None:
    try:
        await delete_note(note_id)
    except Exception as e:
        logger.warning("trigger_delete exception note=%s: %s", note_id, e)
