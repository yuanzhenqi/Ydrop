'use client'

import { useState, useRef, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useNotes } from '@/hooks/useNotes'
import { useAppStore } from '@/store'
import { useKeyboardShortcuts } from '@/hooks/useKeyboardShortcuts'
import { archiveNote, trashNote, triggerAiAnalysis } from '@/lib/api'
import { NoteCard } from '@/components/notes/NoteCard'
import { QuickCapture } from '@/components/notes/QuickCapture'
import { SearchBar } from '@/components/common/SearchBar'
import { Inbox, Keyboard } from 'lucide-react'

export default function InboxPage() {
  const router = useRouter()
  const { searchQuery, categoryFilter, tagFilter, setSearchQuery } = useAppStore()
  const [selectedIdx, setSelectedIdx] = useState<number>(-1)
  const [showHelp, setShowHelp] = useState(false)
  const searchInputRef = useRef<HTMLInputElement>(null)

  const params: Record<string, string> = { trashed: 'false', archived: 'false' }
  if (searchQuery) params.q = searchQuery
  if (categoryFilter) params.category = categoryFilter
  if (tagFilter) params.tag = tagFilter

  const { notes, isLoading, mutate } = useNotes(params)

  // Reset selection when notes change
  useEffect(() => {
    if (selectedIdx >= notes.length) setSelectedIdx(-1)
  }, [notes.length, selectedIdx])

  const handleAction = async (action: () => Promise<unknown>) => {
    await action()
    mutate()
  }

  const selectedNote = selectedIdx >= 0 ? notes[selectedIdx] : null

  useKeyboardShortcuts([
    {
      key: 'j',
      handler: () => setSelectedIdx((i) => Math.min(notes.length - 1, i + 1)),
      description: '下一条',
    },
    {
      key: 'k',
      handler: () => setSelectedIdx((i) => Math.max(0, i - 1)),
      description: '上一条',
    },
    {
      key: 'e',
      handler: () => selectedNote && router.push(`/note?id=${selectedNote.id}`),
      description: '编辑',
    },
    {
      key: 'a',
      handler: () => selectedNote && handleAction(() => archiveNote(selectedNote.id)),
      description: '归档',
    },
    {
      key: 'd',
      handler: () => selectedNote && handleAction(() => trashNote(selectedNote.id)),
      description: '删除',
    },
    {
      key: '/',
      handler: () => searchInputRef.current?.focus(),
      description: '聚焦搜索',
    },
    {
      key: '?',
      shift: true,
      handler: () => setShowHelp((s) => !s),
      description: '显示/隐藏快捷键',
    },
    {
      key: 'Escape',
      handler: () => {
        setSelectedIdx(-1)
        setShowHelp(false)
        if (searchQuery) setSearchQuery('')
      },
      description: '取消选中 / 清除搜索',
    },
  ])

  // Scroll selected into view
  useEffect(() => {
    if (selectedIdx < 0) return
    const el = document.querySelector(`[data-note-id="${notes[selectedIdx]?.id}"]`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [selectedIdx, notes])

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <Inbox className="w-5 h-5" /> 收件箱
        </h1>
        <div className="flex items-center gap-3 text-sm text-gray-400">
          <span>{notes.length} 条</span>
          <button
            onClick={() => setShowHelp((s) => !s)}
            title="键盘快捷键"
            className="p-1 rounded hover:bg-gray-100"
          >
            <Keyboard className="w-4 h-4" />
          </button>
        </div>
      </div>

      {showHelp && (
        <div className="bg-gray-50 border rounded-xl p-3 text-xs space-y-1 text-gray-600">
          <div className="font-semibold text-gray-700 mb-1">键盘快捷键</div>
          <div className="grid grid-cols-2 gap-x-4 gap-y-1">
            <span><kbd className="px-1.5 py-0.5 bg-white border rounded text-xs">j</kbd> / <kbd className="px-1.5 py-0.5 bg-white border rounded text-xs">k</kbd> 上下选择</span>
            <span><kbd className="px-1.5 py-0.5 bg-white border rounded text-xs">e</kbd> 编辑</span>
            <span><kbd className="px-1.5 py-0.5 bg-white border rounded text-xs">a</kbd> 归档</span>
            <span><kbd className="px-1.5 py-0.5 bg-white border rounded text-xs">d</kbd> 删除</span>
            <span><kbd className="px-1.5 py-0.5 bg-white border rounded text-xs">/</kbd> 搜索</span>
            <span><kbd className="px-1.5 py-0.5 bg-white border rounded text-xs">Esc</kbd> 取消</span>
          </div>
        </div>
      )}

      <QuickCapture onSaved={() => mutate()} />
      <SearchBar inputRef={searchInputRef} />

      {isLoading ? (
        <div className="text-center text-gray-400 py-12">加载中...</div>
      ) : notes.length === 0 ? (
        <div className="text-center text-gray-400 py-12">
          {searchQuery ? '没有匹配的记录' : '收件箱为空，开始记录吧'}
        </div>
      ) : (
        <div className="space-y-3">
          {notes.map((note, idx) => (
            <NoteCard
              key={note.id}
              note={note}
              section="inbox"
              selected={idx === selectedIdx}
              onEdit={(id) => router.push(`/note?id=${id}`)}
              onArchive={(id) => handleAction(() => archiveNote(id))}
              onTrash={(id) => handleAction(() => trashNote(id))}
              onCopy={(id) => {
                const n = notes.find((n) => n.id === id)
                if (n) navigator.clipboard.writeText(n.content)
              }}
              onAiAnalyze={(id) => handleAction(() => triggerAiAnalysis(id))}
            />
          ))}
        </div>
      )}
    </div>
  )
}
