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

// ─── App Settings ───

export interface WebDavSettings {
  base_url: string
  username: string
  password_set: boolean
  folder: string
  auto_sync: boolean
  sync_interval: number
  enabled: boolean
}

export interface AiSettings {
  enabled: boolean
  base_url: string
  token_set: boolean
  model: string
  endpoint_mode: 'AUTO' | 'OPENAI' | 'ANTHROPIC'
  prompt_supplement: string
  auto_run_on_text_save: boolean
  auto_retry_on_failure: boolean
}

export interface ServerInfo {
  version: string
  webdav_configured: boolean
  ai_configured: boolean
}

export interface AppSettings {
  webdav: WebDavSettings
  ai: AiSettings
  server_info: ServerInfo
}

export interface SettingsUpdate {
  webdav?: Partial<WebDavSettings> & { password?: string }
  ai?: Partial<AiSettings> & { token?: string }
}

export interface TestResult {
  ok: boolean
  message: string
}

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
