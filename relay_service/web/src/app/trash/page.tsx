'use client'

import { useNotes } from '@/hooks/useNotes'
import { restoreNote, deleteNotePermanently } from '@/lib/api'
import { NoteCard } from '@/components/notes/NoteCard'
import { Trash2 } from 'lucide-react'

export default function TrashPage() {
  const { notes, isLoading, mutate } = useNotes({ trashed: 'true' })

  const handleAction = async (action: () => Promise<unknown>) => {
    await action()
    mutate()
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <Trash2 className="w-5 h-5" /> 回收站
          <span className="text-sm font-normal text-gray-400">{notes.length} 条</span>
        </h1>
        {notes.length > 0 && (
          <button
            onClick={async () => {
              if (!confirm('确定清空回收站？此操作不可撤销。')) return
              for (const n of notes) await deleteNotePermanently(n.id)
              mutate()
            }}
            className="text-sm text-red-500 hover:text-red-700"
          >
            清空回收站
          </button>
        )}
      </div>

      {isLoading ? (
        <div className="text-center text-gray-400 py-12">加载中...</div>
      ) : notes.length === 0 ? (
        <div className="text-center text-gray-400 py-12">回收站为空</div>
      ) : (
        <div className="space-y-3">
          {notes.map((note) => (
            <NoteCard
              key={note.id}
              note={note}
              section="trash"
              onEdit={() => {}}
              onTrash={() => {}}
              onRestore={(id) => handleAction(() => restoreNote(id))}
              onDelete={(id) => handleAction(() => deleteNotePermanently(id))}
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
