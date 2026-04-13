import type { Note, NoteListResponse, Reminder, ReminderListResponse, AiSuggestion, SyncStatus } from './types'

function getToken(): string {
  if (typeof window === 'undefined') return ''
  return localStorage.getItem('ydrop_token') || ''
}

export function setToken(token: string) {
  localStorage.setItem('ydrop_token', token)
}

export function hasToken(): boolean {
  return !!getToken()
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken()
  const res = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...((options.headers as Record<string, string>) || {}),
    },
  })
  if (res.status === 204) return undefined as unknown as T
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    throw new Error(`HTTP ${res.status}: ${body}`)
  }
  return res.json()
}

// ── Notes ──

export async function fetchNotes(params: Record<string, string> = {}): Promise<NoteListResponse> {
  const qs = new URLSearchParams(params).toString()
  return request<NoteListResponse>(`/api/notes${qs ? `?${qs}` : ''}`)
}

export async function fetchNote(id: string): Promise<Note> {
  return request<Note>(`/api/notes/${id}`)
}

export async function createNote(data: { content: string; category?: string; priority?: string; tags?: string[] }): Promise<Note> {
  return request<Note>('/api/notes', { method: 'POST', body: JSON.stringify(data) })
}

export async function updateNote(id: string, data: Record<string, unknown>): Promise<Note> {
  return request<Note>(`/api/notes/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}

export async function archiveNote(id: string): Promise<Note> {
  return request<Note>(`/api/notes/${id}/archive`, { method: 'POST' })
}

export async function unarchiveNote(id: string): Promise<Note> {
  return request<Note>(`/api/notes/${id}/unarchive`, { method: 'POST' })
}

export async function trashNote(id: string): Promise<Note> {
  return request<Note>(`/api/notes/${id}/trash`, { method: 'POST' })
}

export async function restoreNote(id: string): Promise<Note> {
  return request<Note>(`/api/notes/${id}/restore`, { method: 'POST' })
}

export async function deleteNotePermanently(id: string): Promise<void> {
  return request<void>(`/api/notes/${id}`, { method: 'DELETE' })
}

// ── AI ──

export async function triggerAiAnalysis(noteId: string): Promise<AiSuggestion> {
  return request<AiSuggestion>(`/api/notes/${noteId}/ai-analyze`, { method: 'POST' })
}

export async function fetchSuggestions(noteId: string): Promise<AiSuggestion[]> {
  return request<AiSuggestion[]>(`/api/notes/${noteId}/suggestions`)
}

// ── Reminders ──

export async function fetchReminders(params: Record<string, string> = {}): Promise<ReminderListResponse> {
  const qs = new URLSearchParams(params).toString()
  return request<ReminderListResponse>(`/api/reminders${qs ? `?${qs}` : ''}`)
}

export async function createReminder(data: { note_id: string; title: string; scheduled_at: number; recurrence?: string | null }): Promise<Reminder> {
  return request<Reminder>('/api/reminders', { method: 'POST', body: JSON.stringify(data) })
}

export async function cancelReminder(id: string): Promise<Reminder> {
  return request<Reminder>(`/api/reminders/${id}/cancel`, { method: 'POST' })
}

// ── AI Chat & Organize ──

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface ChatFilter {
  category?: string
  priority?: string
  tag?: string
  from?: number
  to?: number
  include_archived?: boolean
  max_notes?: number
}

export interface ChatResponse {
  answer: string
  referenced_note_ids: string[]
  referenced_count: number
}

export async function aiChat(messages: ChatMessage[], filter?: ChatFilter): Promise<ChatResponse> {
  return request<ChatResponse>('/api/ai/chat', {
    method: 'POST',
    body: JSON.stringify({ messages, filter }),
  })
}

export interface ClusterSuggestion {
  cluster_id: string
  theme: string
  note_ids: string[]
  suggested_action: 'merge' | 'convert_to_task' | 'keep'
  suggested_title?: string
  reason: string
}

export interface BatchOrganizeResponse {
  total_analyzed: number
  clusters: ClusterSuggestion[]
}

export async function aiBatchOrganize(noteIds?: string[]): Promise<BatchOrganizeResponse> {
  return request<BatchOrganizeResponse>('/api/ai/batch-organize', {
    method: 'POST',
    body: JSON.stringify({ note_ids: noteIds, max_notes: 50 }),
  })
}

// ── Sync ──

export async function fetchSyncStatus(): Promise<SyncStatus> {
  return request<SyncStatus>('/api/sync/status')
}

export async function triggerSync(): Promise<SyncStatus> {
  return request<SyncStatus>('/api/sync/trigger', { method: 'POST' })
}
