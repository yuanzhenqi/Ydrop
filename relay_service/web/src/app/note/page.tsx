'use client'

import { useEffect, useMemo, useState, Suspense } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import { fetchNote, updateNote, fetchSuggestions, triggerAiAnalysis, archiveNote, trashNote } from '@/lib/api'
import { useNotes } from '@/hooks/useNotes'
import { CATEGORIES, PRIORITIES, CATEGORY_LABELS, PRIORITY_LABELS, COLOR_MAP } from '@/lib/constants'
import { formatTime } from '@/lib/date'
import type { Note, AiSuggestion, NoteCategory, NotePriority } from '@/lib/types'
import { ArrowLeft, Save, Sparkles, Archive, Trash2, Loader2, XCircle } from 'lucide-react'
import { ChipGroup } from '@/components/notes/ChipGroup'
import { TagInput } from '@/components/notes/TagInput'

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

function NoteDetailInner() {
  const searchParams = useSearchParams()
  const id = searchParams.get('id') || ''
  const router = useRouter()
  const [note, setNote] = useState<Note | null>(null)
  const [content, setContent] = useState('')
  const [category, setCategory] = useState<NoteCategory>('NOTE')
  const [priority, setPriority] = useState<NotePriority>('MEDIUM')
  const [tags, setTags] = useState<string[]>([])
  const [saving, setSaving] = useState(false)
  const [suggestion, setSuggestion] = useState<AiSuggestion | null>(null)
  const [aiLoading, setAiLoading] = useState(false)
  const [aiError, setAiError] = useState<string | null>(null)

  const { notes: allNotes } = useNotes({ trashed: 'false' })
  const existingTags = useMemo(() => {
    const s = new Set<string>()
    allNotes.forEach((n) => n.tags.forEach((t) => s.add(t)))
    return Array.from(s).sort()
  }, [allNotes])

  useEffect(() => {
    if (!id) return
    fetchNote(id).then((n) => {
      setNote(n)
      setContent(n.content)
      setCategory(n.category)
      setPriority(n.priority)
      setTags(n.tags)
    })
    fetchSuggestions(id).then((s) => {
      if (s.length > 0) setSuggestion(s[0])
    })
  }, [id])

  const handleSave = async () => {
    if (!id || saving) return
    setSaving(true)
    try {
      const updated = await updateNote(id, { content, category, priority, tags })
      setNote(updated)
    } finally {
      setSaving(false)
    }
  }

  const handleAi = async () => {
    if (!id || aiLoading) return
    setAiLoading(true)
    setAiError(null)
    try {
      const s = await triggerAiAnalysis(id)
      setSuggestion(s)
      // 如果仍在 RUNNING 状态（一般已 READY），轮询 3 次
      if (s.status === 'RUNNING') {
        for (let i = 0; i < 3; i++) {
          await new Promise((r) => setTimeout(r, 2000))
          const latest = await fetchSuggestions(id)
          if (latest.length > 0) {
            setSuggestion(latest[0])
            if (latest[0].status !== 'RUNNING') break
          }
        }
      }
    } catch (e) {
      setAiError(e instanceof Error ? e.message : String(e))
    } finally {
      setAiLoading(false)
    }
  }

  if (!id) return <div className="flex items-center justify-center h-full text-gray-400">缺少笔记 ID</div>
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

        <div className="space-y-2">
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
              <TagInput value={tags} onChange={setTags} suggestions={existingTags} />
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2 pt-2 border-t">
          <button onClick={handleSave} disabled={saving} className="flex items-center gap-1.5 px-4 py-1.5 bg-emerald-600 text-white text-sm rounded-lg hover:bg-emerald-700 disabled:opacity-50">
            <Save className="w-3.5 h-3.5" />
            {saving ? '保存中...' : '保存'}
          </button>
          <button
            onClick={handleAi}
            disabled={aiLoading}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-purple-50 text-purple-600 text-sm rounded-lg hover:bg-purple-100 disabled:opacity-50"
          >
            {aiLoading ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
            ) : (
              <Sparkles className="w-3.5 h-3.5" />
            )}
            {aiLoading ? 'AI 分析中...' : 'AI 整理'}
          </button>
          <div className="flex-1" />
          <button onClick={async () => { await archiveNote(id); router.back() }} className="p-1.5 rounded-lg text-gray-500 hover:bg-gray-100">
            <Archive className="w-4 h-4" />
          </button>
          <button onClick={async () => { await trashNote(id); router.back() }} className="p-1.5 rounded-lg text-red-500 hover:bg-red-50">
            <Trash2 className="w-4 h-4" />
          </button>
        </div>

        {aiError && (
          <div className="rounded-lg px-3 py-2 text-xs bg-red-50 text-red-700 border border-red-200 flex items-center gap-2">
            <XCircle className="w-3.5 h-3.5" />
            AI 整理失败：{aiError}
          </div>
        )}
      </div>

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
      {suggestion && suggestion.status === 'FAILED' && (
        <div className="bg-red-50 rounded-2xl border border-red-200 p-4 text-sm text-red-700 space-y-2">
          <div className="font-medium flex items-center gap-1.5">
            <XCircle className="w-4 h-4" /> AI 整理失败
          </div>
          {suggestion.error_message && (
            <div className="text-xs text-red-600 font-mono break-all">{suggestion.error_message}</div>
          )}
          <button onClick={handleAi} disabled={aiLoading} className="text-xs px-3 py-1 bg-red-100 hover:bg-red-200 rounded-lg">
            重试
          </button>
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

export default function NoteDetailPage() {
  return (
    <Suspense fallback={<div className="flex items-center justify-center h-full text-gray-400">加载中...</div>}>
      <NoteDetailInner />
    </Suspense>
  )
}
