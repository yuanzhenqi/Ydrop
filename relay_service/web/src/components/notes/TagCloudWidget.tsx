'use client'

import { useMemo } from 'react'
import { useRouter } from 'next/navigation'
import { useAppStore } from '@/store'
import type { Note } from '@/lib/types'
import { Tag, Settings2 } from 'lucide-react'

interface TagCloudWidgetProps {
  notes: Note[]
  maxTags?: number
  showManageLink?: boolean
  compact?: boolean
}

export function TagCloudWidget({ notes, maxTags = 20, showManageLink = true, compact = false }: TagCloudWidgetProps) {
  const router = useRouter()
  const { tagFilter, setTagFilter } = useAppStore()

  const tags = useMemo(() => {
    const map = new Map<string, number>()
    for (const n of notes) {
      for (const t of n.tags) {
        map.set(t, (map.get(t) || 0) + 1)
      }
    }
    return Array.from(map.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, maxTags)
  }, [notes, maxTags])

  const max = tags[0]?.[1] ?? 0

  const size = (count: number) => {
    const ratio = max > 0 ? count / max : 0
    if (compact) {
      if (ratio > 0.75) return 'text-sm font-semibold'
      if (ratio > 0.5) return 'text-sm'
      return 'text-xs'
    }
    if (ratio > 0.75) return 'text-base font-bold'
    if (ratio > 0.5) return 'text-sm font-semibold'
    if (ratio > 0.25) return 'text-sm'
    return 'text-xs'
  }

  return (
    <div className="bg-white rounded-2xl border p-4 space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-700 flex items-center gap-1.5">
          <Tag className="w-4 h-4" /> 标签
          <span className="text-xs font-normal text-gray-400">{tags.length}</span>
        </h3>
        {showManageLink && (
          <button
            onClick={() => router.push('/tags')}
            title="管理标签"
            className="text-gray-400 hover:text-gray-700 p-1"
          >
            <Settings2 className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {tags.length === 0 ? (
        <p className="text-xs text-gray-400">暂无标签</p>
      ) : (
        <div className="flex flex-wrap gap-1.5 items-baseline">
          {tags.map(([tag, count]) => {
            const active = tagFilter === tag
            return (
              <button
                key={tag}
                onClick={() => setTagFilter(active ? null : tag)}
                className={`${size(count)} px-2 py-0.5 rounded transition-colors ${
                  active
                    ? 'bg-emerald-600 text-white'
                    : 'text-emerald-700 hover:bg-emerald-50'
                }`}
              >
                #{tag}
                <span className={`ml-0.5 text-[10px] ${active ? 'text-white/70' : 'text-gray-400'}`}>
                  {count}
                </span>
              </button>
            )
          })}
        </div>
      )}

      {tagFilter && (
        <button
          onClick={() => setTagFilter(null)}
          className="text-xs text-gray-500 hover:text-gray-700"
        >
          清除筛选 ×
        </button>
      )}
    </div>
  )
}
