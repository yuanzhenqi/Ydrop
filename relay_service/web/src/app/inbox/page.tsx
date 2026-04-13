'use client'

import { useRouter } from 'next/navigation'
import { useNotes } from '@/hooks/useNotes'
import { useAppStore } from '@/store'
import { archiveNote, trashNote, triggerAiAnalysis } from '@/lib/api'
import { NoteCard } from '@/components/notes/NoteCard'
import { QuickCapture } from '@/components/notes/QuickCapture'
import { SearchBar } from '@/components/common/SearchBar'
import { Inbox } from 'lucide-react'

export default function InboxPage() {
  const router = useRouter()
  const { searchQuery, categoryFilter, tagFilter } = useAppStore()

  const params: Record<string, string> = { trashed: 'false', archived: 'false' }
  if (searchQuery) params.q = searchQuery
  if (categoryFilter) params.category = categoryFilter
  if (tagFilter) params.tag = tagFilter

  const { notes, isLoading, mutate } = useNotes(params)

  const handleAction = async (action: () => Promise<unknown>) => {
    await action()
    mutate()
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <Inbox className="w-5 h-5" /> 收件箱
        </h1>
        <span className="text-sm text-gray-400">{notes.length} 条记录</span>
      </div>

      <QuickCapture onSaved={() => mutate()} />
      <SearchBar />

      {isLoading ? (
        <div className="text-center text-gray-400 py-12">加载中...</div>
      ) : notes.length === 0 ? (
        <div className="text-center text-gray-400 py-12">
          {searchQuery ? '没有匹配的记录' : '收件箱为空，开始记录吧'}
        </div>
      ) : (
        <div className="space-y-3">
          {notes.map((note) => (
            <NoteCard
              key={note.id}
              note={note}
              section="inbox"
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
