'use client'

import { useState } from 'react'
import type { Note } from '@/lib/types'
import { COLOR_MAP, CATEGORY_LABELS, PRIORITY_LABELS } from '@/lib/constants'
import { formatTime } from '@/lib/date'
import { Archive, ArchiveRestore, Trash2, RotateCcw, Pencil, Copy, Sparkles, ChevronDown, ChevronUp } from 'lucide-react'

interface NoteCardProps {
  note: Note
  section: 'inbox' | 'archive' | 'trash'
  onEdit: (id: string) => void
  onArchive?: (id: string) => void
  onUnarchive?: (id: string) => void
  onTrash: (id: string) => void
  onRestore?: (id: string) => void
  onDelete?: (id: string) => void
  onCopy: (id: string) => void
  onAiAnalyze?: (id: string) => void
}

export function NoteCard({ note, section, onEdit, onArchive, onUnarchive, onTrash, onRestore, onDelete, onCopy, onAiAnalyze }: NoteCardProps) {
  const [expanded, setExpanded] = useState(false)
  const color = COLOR_MAP[note.color_token] || COLOR_MAP.SAGE

  return (
    <div
      className="bg-white rounded-2xl border border-gray-100 overflow-hidden transition-shadow hover:shadow-sm cursor-pointer"
      style={{ borderLeftWidth: 4, borderLeftColor: color.border }}
      onClick={() => setExpanded(!expanded)}
    >
      <div className="px-4 py-3 space-y-2">
        {/* Header */}
        <div className="flex items-center gap-2">
          <span className="font-semibold text-sm flex-1 truncate">{note.title || '无标题'}</span>
          <span className="text-xs text-gray-400 whitespace-nowrap">{formatTime(note.updated_at)}</span>
          {expanded ? <ChevronUp className="w-3.5 h-3.5 text-gray-400" /> : <ChevronDown className="w-3.5 h-3.5 text-gray-400" />}
        </div>

        {/* Preview */}
        <p className={`text-sm text-gray-500 ${expanded ? '' : 'line-clamp-2'}`}>
          {note.content}
        </p>

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
        </div>

        {/* Expanded actions */}
        {expanded && (
          <div className="flex items-center gap-1.5 pt-2 border-t border-gray-100" onClick={(e) => e.stopPropagation()}>
            {section !== 'trash' && (
              <>
                {onAiAnalyze && (
                  <ActionBtn icon={Sparkles} label="AI 整理" onClick={() => onAiAnalyze(note.id)} className="text-purple-600 bg-purple-50" />
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
        )}
      </div>
    </div>
  )
}

function ActionBtn({ icon: Icon, label, onClick, className = '' }: { icon: any; label: string; onClick: () => void; className?: string }) {
  return (
    <button
      onClick={onClick}
      title={label}
      className={`p-1.5 rounded-lg hover:opacity-80 transition-opacity ${className || 'text-gray-500 bg-gray-50'}`}
    >
      <Icon className="w-4 h-4" />
    </button>
  )
}
