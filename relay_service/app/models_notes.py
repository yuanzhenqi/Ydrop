from __future__ import annotations

from pydantic import BaseModel, Field


class NoteCreate(BaseModel):
    content: str
    category: str = "NOTE"
    priority: str = "MEDIUM"
    tags: list[str] = Field(default_factory=list)


class NoteUpdate(BaseModel):
    title: str | None = None
    content: str | None = None
    category: str | None = None
    priority: str | None = None
    color_token: str | None = None
    tags: list[str] | None = None


class NoteResponse(BaseModel):
    id: str
    title: str
    content: str
    original_content: str | None = None
    source: str
    category: str
    priority: str
    color_token: str
    status: str
    created_at: int
    updated_at: int
    last_synced_at: int | None = None
    sync_error: str | None = None
    pinned: bool = False
    remote_path: str | None = None
    is_archived: bool = False
    archived_at: int | None = None
    is_trashed: bool = False
    trashed_at: int | None = None
    tags: list[str] = Field(default_factory=list)
    transcript: str | None = None
    audio_path: str | None = None
    relay_url: str | None = None
    transcription_status: str = "NOT_STARTED"


class NoteListResponse(BaseModel):
    items: list[NoteResponse]
    total: int


class ReminderCreate(BaseModel):
    note_id: str
    title: str
    scheduled_at: int
    source: str = "MANUAL"
    recurrence: str | None = None  # DAILY / WEEKLY / MONTHLY / WEEKDAYS / null


class ReminderResponse(BaseModel):
    id: str
    note_id: str
    title: str
    scheduled_at: int
    source: str
    status: str
    delivery_targets: list[str] = Field(default_factory=lambda: ["LOCAL_NOTIFICATION"])
    recurrence: str | None = None
    created_at: int
    updated_at: int


class ReminderListResponse(BaseModel):
    items: list[ReminderResponse]
    total: int


class AiSuggestionResponse(BaseModel):
    id: str
    note_id: str
    status: str
    summary: str = ""
    suggested_title: str | None = None
    suggested_category: str | None = None
    suggested_priority: str | None = None
    todo_items: list[str] = Field(default_factory=list)
    extracted_entities: list[dict] = Field(default_factory=list)
    reminder_candidates: list[dict] = Field(default_factory=list)
    error_message: str | None = None
    created_at: int
    updated_at: int


class SyncStatusResponse(BaseModel):
    last_sync_at: int | None = None
    pushed: int = 0
    pulled: int = 0
    errors: int = 0
    running: bool = False
