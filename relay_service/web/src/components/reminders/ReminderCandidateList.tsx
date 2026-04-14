'use client'

import { useState } from 'react'
import { createReminder } from '@/lib/api'
import { resolveScheduledAt } from '@/lib/time'
import { useToast } from '@/components/common/Toast'
import { formatTime } from '@/lib/date'
import { Bell, BellPlus, Check, Clock } from 'lucide-react'

interface ReminderCandidate {
  title: string
  scheduledAt: number
  scheduledAtIso?: string
  reason?: string
}

interface ReminderCandidateListProps {
  noteId: string
  candidates: ReminderCandidate[]
  compact?: boolean
}

export function ReminderCandidateList({ noteId, candidates, compact = false }: ReminderCandidateListProps) {
  const { toast } = useToast()
  const [createdIds, setCreatedIds] = useState<Set<number>>(new Set())
  const [creatingIds, setCreatingIds] = useState<Set<number>>(new Set())

  if (candidates.length === 0) return null

  const now = Date.now()

  const handleCreate = async (c: ReminderCandidate, idx: number) => {
    const scheduled = resolveScheduledAt(c)
    if (scheduled <= now) {
      toast('error', 'AI 建议的时间已过，请手动设置提醒')
      return
    }
    setCreatingIds((prev) => { const s = new Set(prev); s.add(idx); return s })
    try {
      await createReminder({
        note_id: noteId,
        title: c.title,
        scheduled_at: scheduled,
        source: 'AI',
      })
      setCreatedIds((prev) => { const s = new Set(prev); s.add(idx); return s })
      toast('success', `已创建提醒：${formatTime(scheduled)}`)
    } catch (e) {
      toast('error', `创建失败：${e instanceof Error ? e.message : e}`)
    } finally {
      setCreatingIds((prev) => { const s = new Set(prev); s.delete(idx); return s })
    }
  }

  return (
    <div className={compact ? 'space-y-1' : 'space-y-2'}>
      {!compact && (
        <p className="text-xs font-medium text-gray-600 flex items-center gap-1">
          <Bell className="w-3 h-3" /> 提醒候选
        </p>
      )}
      {candidates.map((c, i) => {
        const scheduled = resolveScheduledAt(c)
        const isPast = scheduled <= now
        const isCreated = createdIds.has(i)
        const isCreating = creatingIds.has(i)

        return (
          <div
            key={i}
            className={`flex items-center gap-2 text-xs ${compact ? 'py-0.5' : 'bg-white border rounded-lg px-2.5 py-1.5'}`}
          >
            <div className="flex-1 min-w-0">
              <div className="text-gray-800 truncate">{c.title}</div>
              <div className={`text-[10px] flex items-center gap-1 ${isPast ? 'text-red-500' : 'text-gray-400'}`}>
                <Clock className="w-2.5 h-2.5" />
                {formatTime(scheduled)}
                {isPast && ' · 已过期'}
                {c.reason && !compact && ` · ${c.reason}`}
              </div>
            </div>
            {isCreated ? (
              <span className="text-xs text-emerald-600 flex items-center gap-0.5 flex-shrink-0">
                <Check className="w-3 h-3" /> 已创建
              </span>
            ) : (
              <button
                onClick={() => handleCreate(c, i)}
                disabled={isPast || isCreating}
                className="flex items-center gap-1 px-2 py-0.5 rounded text-xs bg-emerald-50 text-emerald-700 hover:bg-emerald-100 active:scale-95 transition-all disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0"
              >
                {isCreating ? (
                  <svg className="w-3 h-3 animate-spin" viewBox="0 0 24 24" fill="none">
                    <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" className="opacity-25" />
                    <path d="M4 12a8 8 0 018-8" stroke="currentColor" strokeWidth="4" className="opacity-75" />
                  </svg>
                ) : (
                  <BellPlus className="w-3 h-3" />
                )}
                创建提醒
              </button>
            )}
          </div>
        )
      })}
    </div>
  )
}
