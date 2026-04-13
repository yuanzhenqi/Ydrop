from __future__ import annotations

from fastapi import APIRouter, Depends

from .auth import require_relay_token
from .models_notes import SyncStatusResponse
from .sync_orchestrator import get_sync_status, sync_bidirectional

router = APIRouter(prefix="/api/sync", dependencies=[Depends(require_relay_token)])


@router.get("/status", response_model=SyncStatusResponse)
async def sync_status():
    s = get_sync_status()
    return SyncStatusResponse(**s)


@router.post("/trigger", response_model=SyncStatusResponse)
async def trigger_sync():
    s = await sync_bidirectional()
    return SyncStatusResponse(**s)
