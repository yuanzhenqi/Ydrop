'use client'

import { useMemo } from 'react'
import { useAppStore } from '@/store'
import type { Note, NoteCategory } from '@/lib/types'
import { CATEGORY_LABELS, COLOR_MAP } from '@/lib/constants'
import { PieChart } from 'lucide-react'

const CATEGORY_COLOR_TOKEN: Record<NoteCategory, keyof typeof COLOR_MAP> = {
  NOTE: 'SAGE',
  TODO: 'AMBER',
  TASK: 'SKY',
  REMINDER: 'ROSE',
}

export function CategoryStats({ notes }: { notes: Note[] }) {
  const { categoryFilter, setCategoryFilter } = useAppStore()

  const counts = useMemo(() => {
    const c: Record<NoteCategory, number> = { NOTE: 0, TODO: 0, TASK: 0, REMINDER: 0 }
    for (const n of notes) {
      c[n.category] = (c[n.category] || 0) + 1
    }
    return c
  }, [notes])

  const total = notes.length

  return (
    <div className="bg-white rounded-2xl border p-4 space-y-2">
      <h3 className="text-sm font-semibold text-gray-700 flex items-center gap-1.5">
        <PieChart className="w-4 h-4" /> 分类
        <span className="text-xs font-normal text-gray-400">{total}</span>
      </h3>
      <div className="space-y-1">
        {(Object.keys(counts) as NoteCategory[]).map((cat) => {
          const active = categoryFilter === cat
          const count = counts[cat]
          const pct = total > 0 ? Math.round((count / total) * 100) : 0
          const color = COLOR_MAP[CATEGORY_COLOR_TOKEN[cat]]
          return (
            <button
              key={cat}
              onClick={() => setCategoryFilter(active ? null : cat)}
              className={`w-full flex items-center gap-2 px-2 py-1.5 rounded-lg text-sm transition-colors ${
                active ? 'bg-gray-100 font-medium' : 'hover:bg-gray-50'
              }`}
            >
              <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: color.border }} />
              <span className="flex-1 text-left text-gray-700">{CATEGORY_LABELS[cat]}</span>
              <span className="text-xs text-gray-500">{count}</span>
              <span className="text-[10px] text-gray-400 w-8 text-right">{pct}%</span>
            </button>
          )
        })}
      </div>
      {categoryFilter && (
        <button
          onClick={() => setCategoryFilter(null)}
          className="text-xs text-gray-500 hover:text-gray-700 w-full text-left"
        >
          清除筛选 ×
        </button>
      )}
    </div>
  )
}
