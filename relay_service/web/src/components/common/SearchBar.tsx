'use client'

import type { RefObject } from 'react'
import { Search, X } from 'lucide-react'
import { useAppStore } from '@/store'

export function SearchBar({ inputRef }: { inputRef?: RefObject<HTMLInputElement> }) {
  const { searchQuery, setSearchQuery } = useAppStore()

  return (
    <div className="relative">
      <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
      <input
        ref={inputRef}
        type="text"
        value={searchQuery}
        onChange={(e) => setSearchQuery(e.target.value)}
        placeholder="搜索标题、内容或标签... 按 / 聚焦"
        className="w-full pl-9 pr-8 py-2 text-sm border rounded-xl bg-white outline-none focus:border-emerald-400 transition-colors"
      />
      {searchQuery && (
        <button onClick={() => setSearchQuery('')} className="absolute right-3 top-1/2 -translate-y-1/2">
          <X className="w-4 h-4 text-gray-400 hover:text-gray-600" />
        </button>
      )}
    </div>
  )
}
