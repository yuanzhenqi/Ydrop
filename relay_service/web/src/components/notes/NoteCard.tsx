'use client'

import { useState } from 'react'
import type { Note, AiSuggestion } from '@/lib/types'
import { COLOR_MAP, CATEGORY_LABELS, PRIORITY_LABELS } from '@/lib/constants'
import { formatTime } from '@/lib/date'
import { MarkdownView } from '@/components/common/MarkdownView'
import { Archive, ArchiveRestore, Trash2, RotateCcw, Pencil, Copy, Sparkles, ChevronDown, ChevronUp, Loader2 } from 'lucide-react'
import { ReminderCandidateList } from '@/components/reminders/ReminderCandidateList'

interface NoteCardProps {
  note: Note
  section: 'inbox' | 'archive' | 'trash'
  selected?: boolean
  suggestion?: AiSuggestion | null
  aiLoading?: boolean
  onEdit: (id: string) => void
  onArchive?: (id: string) => void
  onUnarchive?: (id: string) => void
  onTrash: (id: string) => void
  onRestore?: (id: string) => void
  onDelete?: (id: string) => void
  onCopy: (id: string) => void
  onAiAnalyze?: (id: string) => void
}

export function NoteCard({ note, section, selected, suggestion, aiLoading, onEdit, onArchive, onUnarchive, onTrash, onRestore, onDelete, onCopy, onAiAnalyze }: NoteCardProps) {
  const [expanded, setExpanded] = useState(false)
  const color = COLOR_MAP[note.color_token] || COLOR_MAP.SAGE

  const hasSuggestion = suggestion && suggestion.status === 'READY'
  const aiStatus = aiLoading ? 'running' : hasSuggestion ? 'ready' : suggestion?.status === 'FAILED' ? 'failed' : 'idle'

  return (
    <div
      className={`bg-white rounded-2xl border overflow-hidden transition-all hover:shadow-sm cursor-pointer animate-slide-up-fade ${
        selected ? 'border-emerald-400 ring-1 ring-emerald-100' : 'border-gray-100'
      }`}
      style={{ borderLeftWidth: 4, borderLeftColor: color.border }}
      onClick={() => setExpanded(!expanded)}
      data-note-id={note.id}
    >
      <div className="px-4 py-3 space-y-2">
        {/* Header */}
        <div className="flex items-center gap-2">
          <SyncDot status={note.status} syncError={note.sync_error} />
          <span className="font-semibold text-sm flex-1 truncate">{note.title || '无标题'}</span>
          <span className="text-xs text-gray-400 whitespace-nowrap">{formatTime(note.updated_at)}</span>
          {expanded ? <ChevronUp className="w-3.5 h-3.5 text-gray-400" /> : <ChevronDown className="w-3.5 h-3.5 text-gray-400" />}
        </div>

        {/* 展开/收起 grid 动画容器 */}
        <div className={`expand-wrapper ${expanded ? 'open' : 'closed'}`}>
          <div>
            <div className="pt-1">
              <MarkdownView content={note.content} />
            </div>
          </div>
        </div>
        {!expanded && (
          <p className="text-sm text-gray-500 line-clamp-2">{note.content}</p>
        )}

        {/* Pills */}
        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="text-xs px-2 py-0.5 rounded-full" style={{ backgroundColor: color.bg, color: color.text }}>
            {CATEGORY_LABELS[note.category]}
          </span>
          <span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">
            {PRIORITY_LABELS[note.priority]}
          </span>
          {note.source === 'VOICE' && (
            <span className="text-xs px-2 py-0.5 rounded-full bg-blue-50 text-blue-600">语音</span>
          )}
          {note.tags.slice(0, 3).map((tag) => (
            <span key={tag} className="text-xs px-2 py-0.5 rounded-full bg-gray-50 text-gray-500">
              #{tag}
            </span>
          ))}
          {hasSuggestion && (
            <span className="text-xs px-2 py-0.5 rounded-full bg-purple-50 text-purple-600 inline-flex items-center gap-0.5">
              <Sparkles className="w-2.5 h-2.5" /> AI
            </span>
          )}
        </div>

        {/* 展开态：AI 建议预览 + 操作按钮 */}
        {expanded && (
          <div className="space-y-2 animate-fade-in" onClick={(e) => e.stopPropagation()}>
            {/* AI 建议摘要（有建议且展开时显示） */}
            {hasSuggestion && (
              <div className="border-l-2 border-purple-300 bg-purple-50/50 pl-3 py-1.5 rounded-r text-xs text-gray-700 space-y-1">
                <div className="flex items-center gap-1 text-purple-700 font-medium">
                  <Sparkles className="w-3 h-3" /> AI 摘要
                </div>
                {suggestion!.summary && <p>{suggestion!.summary}</p>}
                {suggestion!.todo_items.length > 0 && (
                  <div>
                    <span className="text-purple-600">待办：</span>
                    {suggestion!.todo_items.slice(0, 3).join('；')}
                  </div>
                )}
              </div>
            )}
            {/* AI 提醒候选 */}
            {hasSuggestion && suggestion!.reminder_candidates.length > 0 && (
              <ReminderCandidateList noteId={note.id} candidates={suggestion!.reminder_candidates} compact />
            )}
            {suggestion?.status === 'FAILED' && (
              <div className="text-xs text-red-600 bg-red-50 px-2 py-1 rounded">
                AI 整理失败：{suggestion.error_message || '未知错误'}
              </div>
            )}

            {/* 操作按钮 */}
            <div className="flex items-center gap-1.5 pt-2 border-t border-gray-100">
              {section !== 'trash' && (
                <>
                  {onAiAnalyze && (
                    <ActionBtn
                      icon={aiStatus === 'running' ? Loader2 : Sparkles}
                      label={aiStatus === 'running' ? 'AI 分析中...' : 'AI 整理'}
                      onClick={() => onAiAnalyze(note.id)}
                      disabled={aiStatus === 'running'}
                      iconClassName={aiStatus === 'running' ? 'animate-spin' : ''}
                      className={
                        aiStatus === 'ready'
                          ? 'text-emerald-700 bg-emerald-50'
                          : aiStatus === 'failed'
                          ? 'text-red-600 bg-red-50'
                          : 'text-purple-600 bg-purple-50'
                      }
                    />
                  )}
                  <ActionBtn icon={Copy} label="复制" onClick={() => onCopy(note.id)} />
                  <ActionBtn icon={Pencil} label="编辑" onClick={() => onEdit(note.id)} />
                  {section === 'inbox' && onArchive && (
                    <ActionBtn icon={Archive} label="归档" onClick={() => onArchive(note.id)} />
                  )}
                  {section === 'archive' && onUnarchive && (
                    <ActionBtn icon={ArchiveRestore} label="取消归档" onClick={() => onUnarchive(note.id)} />
                  )}
                  <ActionBtn icon={Trash2} label="删除" onClick={() => onTrash(note.id)} className="text-red-500 bg-red-50" />
                </>
              )}
              {section === 'trash' && (
                <>
                  {onRestore && <ActionBtn icon={RotateCcw} label="恢复" onClick={() => onRestore(note.id)} className="text-emerald-600 bg-emerald-50" />}
                  {onDelete && <ActionBtn icon={Trash2} label="彻底删除" onClick={() => onDelete(note.id)} className="text-red-600 bg-red-50" />}
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function SyncDot({ status, syncError }: { status: string; syncError?: string }) {
  const map: Record<string, { color: string; label: string; pulse?: boolean }> = {
    SYNCED: { color: 'bg-emerald-500', label: '已同步' },
    SYNCING: { color: 'bg-blue-500', label: '同步中...', pulse: true },
    LOCAL_ONLY: { color: 'bg-gray-300', label: '待同步' },
    FAILED: { color: 'bg-red-500', label: syncError || '同步失败' },
  }
  const s = map[status] || map.LOCAL_ONLY
  return (
    <span
      className={`w-2 h-2 rounded-full flex-shrink-0 ${s.color} ${s.pulse ? 'animate-pulse' : ''}`}
      title={s.label}
    />
  )
}

function ActionBtn({
  icon: Icon,
  label,
  onClick,
  className = '',
  disabled = false,
  iconClassName = '',
}: {
  icon: any
  label: string
  onClick: () => void
  className?: string
  disabled?: boolean
  iconClassName?: string
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      className={`p-1.5 rounded-lg hover:opacity-80 active:scale-90 transition-all disabled:opacity-60 disabled:cursor-not-allowed ${className || 'text-gray-500 bg-gray-50'}`}
    >
      <Icon className={`w-4 h-4 ${iconClassName}`} />
    </button>
  )
}
