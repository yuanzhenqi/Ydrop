'use client'

import { useMemo, useState } from 'react'
import { Send } from 'lucide-react'
import { createNote } from '@/lib/api'
import { CATEGORIES, PRIORITIES, CATEGORY_LABELS, PRIORITY_LABELS, COLOR_MAP } from '@/lib/constants'
import { useNotes } from '@/hooks/useNotes'
import type { NoteCategory, NotePriority } from '@/lib/types'
import { ChipGroup } from './ChipGroup'
import { TagInput } from './TagInput'

interface QuickCaptureProps {
  onSaved: () => void
}

const CATEGORY_COLOR: Record<NoteCategory, string> = {
  NOTE: COLOR_MAP.SAGE.border,
  TODO: COLOR_MAP.AMBER.border,
  TASK: COLOR_MAP.SKY.border,
  REMINDER: COLOR_MAP.ROSE.border,
}

const PRIORITY_COLOR: Record<NotePriority, string> = {
  LOW: '#9ca3af',
  MEDIUM: '#6b7280',
  HIGH: '#f59e0b',
  URGENT: '#ef4444',
}

export function QuickCapture({ onSaved }: QuickCaptureProps) {
  const [content, setContent] = useState('')
  const [category, setCategory] = useState<NoteCategory>('NOTE')
  const [priority, setPriority] = useState<NotePriority>('MEDIUM')
  const [tags, setTags] = useState<string[]>([])
  const [saving, setSaving] = useState(false)
  const [expanded, setExpanded] = useState(false)

  // 从已有笔记聚合标签用作自动补全
  const { notes } = useNotes({ trashed: 'false' })
  const existingTags = useMemo(() => {
    const s = new Set<string>()
    notes.forEach((n) => n.tags.forEach((t) => s.add(t)))
    return Array.from(s).sort()
  }, [notes])

  const handleSave = async () => {
    if (!content.trim() || saving) return
    setSaving(true)
    try {
      await createNote({ content: content.trim(), category, priority, tags })
      setContent('')
      setTags([])
      setExpanded(false)
      onSaved()
    } catch (e) {
      console.error('Save failed:', e)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="bg-white rounded-2xl border border-gray-200 p-4 space-y-3">
      <textarea
        value={content}
        onChange={(e) => setContent(e.target.value)}
        placeholder="写点什么..."
        rows={expanded ? 4 : 2}
        className="w-full resize-none text-sm outline-none placeholder-gray-400"
        onFocus={() => setExpanded(true)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSave()
        }}
      />
      {expanded && (
        <div className="space-y-2 animate-slide-down-fade">
          <div className="flex items-center gap-3 flex-wrap">
            <span className="text-xs text-gray-500 w-10 flex-shrink-0">类型</span>
            <ChipGroup<NoteCategory>
              options={CATEGORIES.map((c) => ({ value: c, label: CATEGORY_LABELS[c], color: CATEGORY_COLOR[c] }))}
              value={category}
              onChange={setCategory}
            />
          </div>
          <div className="flex items-center gap-3 flex-wrap">
            <span className="text-xs text-gray-500 w-10 flex-shrink-0">优先级</span>
            <ChipGroup<NotePriority>
              options={PRIORITIES.map((p) => ({ value: p, label: PRIORITY_LABELS[p], color: PRIORITY_COLOR[p] }))}
              value={priority}
              onChange={setPriority}
            />
          </div>
          <div className="flex items-start gap-3">
            <span className="text-xs text-gray-500 w-10 flex-shrink-0 pt-1.5">标签</span>
            <div className="flex-1">
              <TagInput
                value={tags}
                onChange={setTags}
                suggestions={existingTags}
                placeholder="输入标签，回车/逗号确认"
              />
            </div>
          </div>
        </div>
      )}
      <div className="flex justify-between items-center">
        <span className="text-xs text-gray-400">
          {expanded ? 'Ctrl+Enter 保存' : '点击展开更多选项'}
        </span>
        <button
          onClick={handleSave}
          disabled={!content.trim() || saving}
          className="flex items-center gap-1.5 px-4 py-1.5 bg-emerald-600 text-white text-sm rounded-lg hover:bg-emerald-700 active:scale-95 disabled:opacity-50 transition-all"
        >
          <Send className="w-3.5 h-3.5" />
          {saving ? '保存中...' : '保存'}
        </button>
      </div>
    </div>
  )
}
