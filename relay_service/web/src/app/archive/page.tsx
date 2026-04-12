'use client'

import { useRouter } from 'next/navigation'
import { useNotes } from '@/hooks/useNotes'
import { unarchiveNote, trashNote } from '@/lib/api'
import { NoteCard } from '@/components/notes/NoteCard'
import { Archive } from 'lucide-react'

export default function ArchivePage() {
  const router = useRouter()
  const { notes, isLoading, mutate } = useNotes({ archived: 'true', trashed: 'false' })

  const handleAction = async (action: () => Promise<unknown>) => {
    await action()
    mutate()
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
      <h1 className="text-xl font-bold flex items-center gap-2">
        <Archive className="w-5 h-5" /> 归档
        <span className="text-sm font-normal text-gray-400">{notes.length} 条</span>
      </h1>

      {isLoading ? (
        <div className="text-center text-gray-400 py-12">加载中...</div>
      ) : notes.length === 0 ? (
        <div className="text-center text-gray-400 py-12">暂无归档记录</div>
      ) : (
        <div className="space-y-3">
          {notes.map((note) => (
            <NoteCard
              key={note.id}
              note={note}
              section="archive"
              onEdit={(id) => router.push(`/note/${id}`)}
              onUnarchive={(id) => handleAction(() => unarchiveNote(id))}
              onTrash={(id) => handleAction(() => trashNote(id))}
              onCopy={(id) => {
                const n = notes.find((n) => n.id === id)
                if (n) navigator.clipboard.writeText(n.content)
              }}
            />
          ))}
        </div>
      )}
    </div>
  )
}
