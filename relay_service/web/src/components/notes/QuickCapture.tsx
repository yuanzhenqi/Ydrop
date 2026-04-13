'use client'

import { useState } from 'react'
import { Send, ChevronDown } from 'lucide-react'
import { createNote } from '@/lib/api'
import { CATEGORIES, PRIORITIES, CATEGORY_LABELS, PRIORITY_LABELS } from '@/lib/constants'
import type { NoteCategory, NotePriority } from '@/lib/types'

interface QuickCaptureProps {
  onSaved: () => void
}

export function QuickCapture({ onSaved }: QuickCaptureProps) {
  const [content, setContent] = useState('')
  const [category, setCategory] = useState<NoteCategory>('NOTE')
  const [priority, setPriority] = useState<NotePriority>('MEDIUM')
  const [tags, setTags] = useState('')
  const [saving, setSaving] = useState(false)
  const [expanded, setExpanded] = useState(false)

  const handleSave = async () => {
    if (!content.trim() || saving) return
    setSaving(true)
    try {
      const tagList = tags.split(/[,，\s]+/).filter(Boolean)
      await createNote({ content: content.trim(), category, priority, tags: tagList })
      setContent('')
      setTags('')
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
        <div className="flex items-center gap-2 flex-wrap">
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value as NoteCategory)}
            className="text-xs border rounded-lg px-2 py-1 bg-gray-50"
          >
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>{CATEGORY_LABELS[c]}</option>
            ))}
          </select>
          <select
            value={priority}
            onChange={(e) => setPriority(e.target.value as NotePriority)}
            className="text-xs border rounded-lg px-2 py-1 bg-gray-50"
          >
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>{PRIORITY_LABELS[p]}</option>
            ))}
          </select>
          <input
            value={tags}
            onChange={(e) => setTags(e.target.value)}
            placeholder="标签（逗号分隔）"
            className="text-xs border rounded-lg px-2 py-1 flex-1 min-w-[120px] bg-gray-50"
          />
        </div>
      )}
      <div className="flex justify-between items-center">
        <span className="text-xs text-gray-400">
          {expanded ? 'Ctrl+Enter 保存' : '点击展开更多选项'}
        </span>
        <button
          onClick={handleSave}
          disabled={!content.trim() || saving}
          className="flex items-center gap-1.5 px-4 py-1.5 bg-emerald-600 text-white text-sm rounded-lg hover:bg-emerald-700 disabled:opacity-50 transition-colors"
        >
          <Send className="w-3.5 h-3.5" />
          {saving ? '保存中...' : '保存'}
        </button>
      </div>
    </div>
  )
}
