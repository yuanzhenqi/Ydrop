export type NoteSource = 'TEXT' | 'VOICE'
export type NoteCategory = 'NOTE' | 'TODO' | 'TASK' | 'REMINDER'
export type NotePriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export type NoteColorToken = 'SAGE' | 'AMBER' | 'SKY' | 'ROSE'
export type NoteStatus = 'LOCAL_ONLY' | 'SYNCING' | 'SYNCED' | 'FAILED'

export interface Note {
  id: string
  title: string
  content: string
  original_content?: string
  source: NoteSource
  category: NoteCategory
  priority: NotePriority
  color_token: NoteColorToken
  status: NoteStatus
  created_at: number
  updated_at: number
  last_synced_at?: number
  sync_error?: string
  pinned: boolean
  remote_path?: string
  is_archived: boolean
  archived_at?: number
  is_trashed: boolean
  trashed_at?: number
  tags: string[]
  transcript?: string
  audio_path?: string
  relay_url?: string
  transcription_status: string
}

export interface NoteListResponse {
  items: Note[]
  total: number
}

export interface Reminder {
  id: string
  note_id: string
  title: string
  scheduled_at: number
  source: string
  status: string
  delivery_targets: string[]
  recurrence?: string | null
  created_at: number
  updated_at: number
}

export type RecurrenceRule = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'WEEKDAYS' | null

export interface ReminderListResponse {
  items: Reminder[]
  total: number
}

export interface AiSuggestion {
  id: string
  note_id: string
  status: string
  summary: string
  suggested_title?: string
  suggested_category?: string
  suggested_priority?: string
  todo_items: string[]
  extracted_entities: { label: string; value: string }[]
  reminder_candidates: { title: string; scheduledAt: number; reason?: string }[]
  error_message?: string
  created_at: number
  updated_at: number
}

export interface SyncStatus {
  last_sync_at?: number
  pushed: number
  pulled: number
  errors: number
  running: boolean
}
