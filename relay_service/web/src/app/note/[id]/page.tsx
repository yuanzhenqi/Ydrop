'use client'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { fetchNote, updateNote, fetchSuggestions, triggerAiAnalysis, archiveNote, trashNote } from '@/lib/api'
import { CATEGORIES, PRIORITIES, CATEGORY_LABELS, PRIORITY_LABELS, COLOR_MAP } from '@/lib/constants'
import { formatTime } from '@/lib/date'
import type { Note, AiSuggestion, NoteCategory, NotePriority } from '@/lib/types'
import { ArrowLeft, Save, Sparkles, Archive, Trash2 } from 'lucide-react'

export default function NoteDetailPage() {
  const { id } = useParams<{ id: string }>()
  const router = useRouter()
  const [note, setNote] = useState<Note | null>(null)
  const [content, setContent] = useState('')
  const [category, setCategory] = useState<NoteCategory>('NOTE')
  const [priority, setPriority] = useState<NotePriority>('MEDIUM')
  const [tags, setTags] = useState('')
  const [saving, setSaving] = useState(false)
  const [suggestion, setSuggestion] = useState<AiSuggestion | null>(null)

  useEffect(() => {
    if (!id) return
    fetchNote(id).then((n) => {
      setNote(n)
      setContent(n.content)
      setCategory(n.category)
      setPriority(n.priority)
      setTags(n.tags.join(', '))
    })
    fetchSuggestions(id).then((s) => {
      if (s.length > 0) setSuggestion(s[0])
    })
  }, [id])

  const handleSave = async () => {
    if (!id || saving) return
    setSaving(true)
    try {
      const tagList = tags.split(/[,，\s]+/).filter(Boolean)
      const updated = await updateNote(id, { content, category, priority, tags: tagList })
      setNote(updated)
    } finally {
      setSaving(false)
    }
  }

  const handleAi = async () => {
    if (!id) return
    const s = await triggerAiAnalysis(id)
    setSuggestion(s)
  }

  if (!note) return <div className="flex items-center justify-center h-full text-gray-400">加载中...</div>

  const color = COLOR_MAP[note.color_token] || COLOR_MAP.SAGE

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
      <div className="flex items-center gap-3">
        <button onClick={() => router.back()} className="p-2 rounded-lg hover:bg-gray-100">
          <ArrowLeft className="w-4 h-4" />
        </button>
        <div className="w-3 h-3 rounded-full" style={{ backgroundColor: color.border }} />
        <h1 className="text-lg font-bold flex-1 truncate">{note.title || '无标题'}</h1>
        <span className="text-xs text-gray-400">{formatTime(note.updated_at)}</span>
      </div>

      <div className="bg-white rounded-2xl border p-4 space-y-4">
        <textarea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          rows={8}
          className="w-full resize-none text-sm outline-none"
        />

        <div className="flex items-center gap-2 flex-wrap">
          <select value={category} onChange={(e) => setCategory(e.target.value as NoteCategory)} className="text-xs border rounded-lg px-2 py-1 bg-gray-50">
            {CATEGORIES.map((c) => <option key={c} value={c}>{CATEGORY_LABELS[c]}</option>)}
          </select>
          <select value={priority} onChange={(e) => setPriority(e.target.value as NotePriority)} className="text-xs border rounded-lg px-2 py-1 bg-gray-50">
            {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_LABELS[p]}</option>)}
          </select>
          <input
            value={tags}
            onChange={(e) => setTags(e.target.value)}
            placeholder="标签"
            className="text-xs border rounded-lg px-2 py-1 flex-1 min-w-[100px] bg-gray-50"
          />
        </div>

        <div className="flex items-center gap-2 pt-2 border-t">
          <button onClick={handleSave} disabled={saving} className="flex items-center gap-1.5 px-4 py-1.5 bg-emerald-600 text-white text-sm rounded-lg hover:bg-emerald-700 disabled:opacity-50">
            <Save className="w-3.5 h-3.5" />
            {saving ? '保存中...' : '保存'}
          </button>
          <button onClick={handleAi} className="flex items-center gap-1.5 px-3 py-1.5 bg-purple-50 text-purple-600 text-sm rounded-lg hover:bg-purple-100">
            <Sparkles className="w-3.5 h-3.5" /> AI 整理
          </button>
          <div className="flex-1" />
          <button onClick={async () => { await archiveNote(id); router.back() }} className="p-1.5 rounded-lg text-gray-500 hover:bg-gray-100">
            <Archive className="w-4 h-4" />
          </button>
          <button onClick={async () => { await trashNote(id); router.back() }} className="p-1.5 rounded-lg text-red-500 hover:bg-red-50">
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* AI Suggestion Panel */}
      {suggestion && suggestion.status === 'READY' && (
        <div className="bg-purple-50 rounded-2xl border border-purple-100 p-4 space-y-3">
          <h3 className="text-sm font-semibold text-purple-700">AI 建议</h3>
          {suggestion.summary && <p className="text-sm text-gray-700">{suggestion.summary}</p>}
          {suggestion.suggested_title && <p className="text-xs text-gray-500">标题建议：{suggestion.suggested_title}</p>}
          {suggestion.suggested_category && <p className="text-xs text-gray-500">分类建议：{CATEGORY_LABELS[suggestion.suggested_category as NoteCategory] || suggestion.suggested_category}</p>}
          {suggestion.todo_items.length > 0 && (
            <div>
              <p className="text-xs font-medium text-gray-600 mb-1">待办提取：</p>
              <ul className="text-xs text-gray-600 space-y-0.5">
                {suggestion.todo_items.map((t, i) => <li key={i}>- {t}</li>)}
              </ul>
            </div>
          )}
          {suggestion.reminder_candidates.length > 0 && (
            <div>
              <p className="text-xs font-medium text-gray-600 mb-1">提醒候选：</p>
              {suggestion.reminder_candidates.map((r, i) => (
                <p key={i} className="text-xs text-gray-600">{r.title} — {formatTime(r.scheduledAt)}</p>
              ))}
            </div>
          )}
        </div>
      )}
      {suggestion && suggestion.status === 'RUNNING' && (
        <div className="bg-purple-50 rounded-2xl border border-purple-100 p-4 text-sm text-purple-600">
          AI 正在整理这条笔记...
        </div>
      )}
    </div>
  )
}
