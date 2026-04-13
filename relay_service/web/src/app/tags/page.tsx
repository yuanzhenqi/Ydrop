'use client'

import { useMemo, useState } from 'react'
import { useRouter } from 'next/navigation'
import { useNotes } from '@/hooks/useNotes'
import { updateNote } from '@/lib/api'
import { useAppStore } from '@/store'
import { Tag as TagIcon, Edit2, Check, X } from 'lucide-react'

interface TagStat {
  name: string
  count: number
  noteIds: string[]
}

export default function TagsPage() {
  const router = useRouter()
  const { setTagFilter } = useAppStore()
  const { notes, mutate } = useNotes({ trashed: 'false' })
  const [renaming, setRenaming] = useState<string | null>(null)
  const [newName, setNewName] = useState('')

  const tagStats: TagStat[] = useMemo(() => {
    const map = new Map<string, TagStat>()
    for (const n of notes) {
      for (const t of n.tags) {
        const entry = map.get(t) || { name: t, count: 0, noteIds: [] }
        entry.count += 1
        entry.noteIds.push(n.id)
        map.set(t, entry)
      }
    }
    return Array.from(map.values()).sort((a, b) => b.count - a.count)
  }, [notes])

  const handleRename = async (oldName: string) => {
    const target = newName.trim()
    if (!target || target === oldName) {
      setRenaming(null)
      return
    }
    const stat = tagStats.find((t) => t.name === oldName)
    if (!stat) return
    // Update all affected notes
    for (const noteId of stat.noteIds) {
      const note = notes.find((n) => n.id === noteId)
      if (!note) continue
      const updatedTags = note.tags.map((t) => (t === oldName ? target : t))
      const dedup = Array.from(new Set(updatedTags))
      await updateNote(noteId, { tags: dedup })
    }
    setRenaming(null)
    setNewName('')
    mutate()
  }

  // Cloud tag size by count
  const sizeClass = (count: number, max: number) => {
    const ratio = max > 0 ? count / max : 0
    if (ratio > 0.75) return 'text-xl font-bold'
    if (ratio > 0.5) return 'text-lg font-semibold'
    if (ratio > 0.25) return 'text-base font-medium'
    return 'text-sm'
  }
  const maxCount = tagStats[0]?.count ?? 0

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
      <h1 className="text-xl font-bold flex items-center gap-2">
        <TagIcon className="w-5 h-5" /> 标签管理
        <span className="text-sm font-normal text-gray-400">{tagStats.length} 个标签</span>
      </h1>

      {tagStats.length === 0 ? (
        <div className="text-center text-gray-400 py-16">暂无标签，去收件箱给笔记加标签吧</div>
      ) : (
        <>
          {/* 标签云 */}
          <section className="bg-white rounded-2xl border p-4">
            <h2 className="text-sm font-semibold text-gray-700 mb-3">标签云</h2>
            <div className="flex flex-wrap gap-3 items-baseline">
              {tagStats.map((t) => (
                <button
                  key={t.name}
                  onClick={() => {
                    setTagFilter(t.name)
                    router.push('/inbox')
                  }}
                  className={`${sizeClass(t.count, maxCount)} text-emerald-700 hover:text-emerald-900 hover:underline`}
                >
                  #{t.name}
                  <span className="text-xs text-gray-400 ml-1">({t.count})</span>
                </button>
              ))}
            </div>
          </section>

          {/* 标签列表 */}
          <section className="bg-white rounded-2xl border p-4 space-y-2">
            <h2 className="text-sm font-semibold text-gray-700 mb-2">所有标签</h2>
            {tagStats.map((t) => (
              <div key={t.name} className="flex items-center gap-2 py-1.5 border-b last:border-0">
                {renaming === t.name ? (
                  <>
                    <input
                      value={newName}
                      onChange={(e) => setNewName(e.target.value)}
                      placeholder={t.name}
                      autoFocus
                      className="flex-1 text-sm border rounded-lg px-2 py-1 outline-none focus:border-emerald-400"
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') handleRename(t.name)
                        if (e.key === 'Escape') setRenaming(null)
                      }}
                    />
                    <button onClick={() => handleRename(t.name)} className="p-1 text-emerald-600 hover:bg-emerald-50 rounded">
                      <Check className="w-4 h-4" />
                    </button>
                    <button onClick={() => setRenaming(null)} className="p-1 text-gray-400 hover:bg-gray-50 rounded">
                      <X className="w-4 h-4" />
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      onClick={() => {
                        setTagFilter(t.name)
                        router.push('/inbox')
                      }}
                      className="flex-1 text-left text-sm text-emerald-700 hover:underline"
                    >
                      #{t.name}
                    </button>
                    <span className="text-xs text-gray-400">{t.count} 条</span>
                    <button
                      onClick={() => {
                        setRenaming(t.name)
                        setNewName(t.name)
                      }}
                      title="重命名"
                      className="p-1 text-gray-400 hover:text-gray-700 hover:bg-gray-50 rounded"
                    >
                      <Edit2 className="w-3.5 h-3.5" />
                    </button>
                  </>
                )}
              </div>
            ))}
          </section>
        </>
      )}
    </div>
  )
}
