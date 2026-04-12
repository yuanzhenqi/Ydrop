from __future__ import annotations

import json
import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query

from .auth import require_relay_token
from .database import get_db
from .models_notes import ReminderCreate, ReminderListResponse, ReminderResponse

router = APIRouter(prefix="/api/reminders", dependencies=[Depends(require_relay_token)])


def _row_to_reminder(row) -> ReminderResponse:
    return ReminderResponse(
        id=row["id"],
        note_id=row["note_id"],
        title=row["title"],
        scheduled_at=row["scheduled_at"],
        source=row["source"],
        status=row["status"],
        delivery_targets=json.loads(row["delivery_targets_json"] or '["LOCAL_NOTIFICATION"]'),
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


@router.get("", response_model=ReminderListResponse)
async def list_reminders(
    status: str | None = None,
    from_ts: int | None = Query(default=None, alias="from"),
    to_ts: int | None = Query(default=None, alias="to"),
):
    db = await get_db()
    conditions = []
    params: list = []

    if status:
        conditions.append("status = ?")
        params.append(status.upper())
    if from_ts is not None:
        conditions.append("scheduled_at >= ?")
        params.append(from_ts)
    if to_ts is not None:
        conditions.append("scheduled_at <= ?")
        params.append(to_ts)

    where = " AND ".join(conditions) if conditions else "1=1"
    rows = await db.execute_fetchall(
        f"SELECT * FROM reminders WHERE {where} ORDER BY scheduled_at ASC", params
    )
    items = [_row_to_reminder(r) for r in rows]
    return ReminderListResponse(items=items, total=len(items))


@router.post("", response_model=ReminderResponse, status_code=201)
async def create_reminder(body: ReminderCreate):
    db = await get_db()
    reminder_id = str(uuid.uuid4())
    now = int(time.time() * 1000)

    await db.execute(
        """INSERT INTO reminders (id, note_id, title, scheduled_at, source, status, delivery_targets_json, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, 'SCHEDULED', '["LOCAL_NOTIFICATION"]', ?, ?)""",
        [reminder_id, body.note_id, body.title, body.scheduled_at, body.source.upper(), now, now],
    )
    await db.commit()

    rows = await db.execute_fetchall("SELECT * FROM reminders WHERE id = ?", [reminder_id])
    return _row_to_reminder(rows[0])


@router.post("/{reminder_id}/cancel", response_model=ReminderResponse)
async def cancel_reminder(reminder_id: str):
    db = await get_db()
    now = int(time.time() * 1000)
    cursor = await db.execute(
        "UPDATE reminders SET status = 'CANCELLED', updated_at = ? WHERE id = ?",
        [now, reminder_id],
    )
    if cursor.rowcount == 0:
        raise HTTPException(status_code=404, detail="Reminder not found")
    await db.commit()
    rows = await db.execute_fetchall("SELECT * FROM reminders WHERE id = ?", [reminder_id])
    return _row_to_reminder(rows[0])


@router.delete("/{reminder_id}", status_code=204)
async def delete_reminder(reminder_id: str):
    db = await get_db()
    await db.execute("DELETE FROM reminders WHERE id = ?", [reminder_id])
    await db.commit()
