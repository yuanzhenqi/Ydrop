import type { NoteCategory, NotePriority, NoteColorToken } from './types'

export const CATEGORY_LABELS: Record<NoteCategory, string> = {
  NOTE: '普通',
  TODO: '待办',
  TASK: '任务',
  REMINDER: '提醒',
}

export const PRIORITY_LABELS: Record<NotePriority, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  URGENT: '紧急',
}

export const CATEGORIES: NoteCategory[] = ['NOTE', 'TODO', 'TASK', 'REMINDER']
export const PRIORITIES: NotePriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT']

export const COLOR_MAP: Record<NoteColorToken, { bg: string; border: string; text: string }> = {
  SAGE: { bg: '#e8f5e9', border: '#6C8E7B', text: '#2e7d32' },
  AMBER: { bg: '#fff8e1', border: '#D89A2B', text: '#e65100' },
  SKY: { bg: '#e3f2fd', border: '#4F86C6', text: '#1565c0' },
  ROSE: { bg: '#fce4ec', border: '#C9656C', text: '#c62828' },
}
