'use client'

import { useState, useRef, useEffect, useMemo } from 'react'
import { X } from 'lucide-react'

interface TagInputProps {
  value: string[]
  onChange: (tags: string[]) => void
  suggestions?: string[]
  placeholder?: string
  max?: number
}

export function TagInput({ value, onChange, suggestions = [], placeholder = '输入标签', max = 10 }: TagInputProps) {
  const [input, setInput] = useState('')
  const [focused, setFocused] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  // 排除已选中的，按输入模糊匹配
  const filtered = useMemo(() => {
    const selected = new Set(value)
    const q = input.trim().toLowerCase()
    return suggestions
      .filter((t) => !selected.has(t))
      .filter((t) => !q || t.toLowerCase().includes(q))
      .slice(0, 8)
  }, [suggestions, value, input])

  const addTag = (tag: string) => {
    const t = tag.trim()
    if (!t || value.includes(t) || value.length >= max) return
    onChange([...value, t])
    setInput('')
  }

  const removeTag = (tag: string) => {
    onChange(value.filter((t) => t !== tag))
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',' || e.key === '，') {
      e.preventDefault()
      addTag(input)
    } else if (e.key === 'Backspace' && !input && value.length > 0) {
      e.preventDefault()
      removeTag(value[value.length - 1])
    }
  }

  return (
    <div className="relative">
      <div
        className="flex flex-wrap gap-1.5 items-center min-h-[34px] px-2 py-1 border rounded-lg bg-gray-50 focus-within:border-emerald-400"
        onClick={() => inputRef.current?.focus()}
      >
        {value.map((tag) => (
          <span
            key={tag}
            className="inline-flex items-center gap-0.5 px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-700 text-xs"
          >
            #{tag}
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); removeTag(tag) }}
              className="hover:bg-emerald-200 rounded-full"
            >
              <X className="w-3 h-3" />
            </button>
          </span>
        ))}
        <input
          ref={inputRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => setFocused(true)}
          onBlur={() => setTimeout(() => setFocused(false), 150)}
          placeholder={value.length === 0 ? placeholder : ''}
          className="flex-1 min-w-[80px] outline-none bg-transparent text-xs py-0.5"
        />
      </div>

      {/* 建议下拉 */}
      {focused && filtered.length > 0 && (
        <div className="absolute top-full left-0 right-0 mt-1 bg-white border rounded-lg shadow-lg p-1.5 z-10">
          <div className="text-[10px] text-gray-400 px-2 pb-1">已有标签（点击添加）</div>
          <div className="flex flex-wrap gap-1">
            {filtered.map((t) => (
              <button
                key={t}
                type="button"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => addTag(t)}
                className="text-xs px-2 py-0.5 rounded-full bg-gray-100 hover:bg-emerald-100 hover:text-emerald-700 text-gray-600"
              >
                #{t}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
