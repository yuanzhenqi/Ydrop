'use client'

import { useMemo } from 'react'
import { useRouter } from 'next/navigation'
import { useReminders } from '@/hooks/useReminders'
import { formatTime } from '@/lib/date'
import { Bell, Repeat } from 'lucide-react'

const RECURRENCE_LABELS: Record<string, string> = {
  DAILY: '每天',
  WEEKDAYS: '工作日',
  WEEKLY: '每周',
  MONTHLY: '每月',
}

export function UpcomingReminders() {
  const router = useRouter()
  const { reminders } = useReminders({ status: 'SCHEDULED' })

  const upcoming = useMemo(() => {
    const now = Date.now()
    const horizon = now + 7 * 24 * 3600 * 1000
    return reminders
      .filter((r) => r.scheduled_at >= now && r.scheduled_at <= horizon)
      .sort((a, b) => a.scheduled_at - b.scheduled_at)
      .slice(0, 5)
  }, [reminders])

  return (
    <div className="bg-white rounded-2xl border p-4 space-y-2">
      <h3 className="text-sm font-semibold text-gray-700 flex items-center gap-1.5">
        <Bell className="w-4 h-4" /> 近期提醒
        <span className="text-xs font-normal text-gray-400">{upcoming.length}</span>
      </h3>
      {upcoming.length === 0 ? (
        <p className="text-xs text-gray-400">未来 7 天没有提醒</p>
      ) : (
        <div className="space-y-1.5">
          {upcoming.map((r) => (
            <button
              key={r.id}
              onClick={() => router.push('/calendar')}
              className="w-full text-left px-2 py-1.5 rounded-lg hover:bg-gray-50 space-y-0.5"
            >
              <div className="text-sm text-gray-800 truncate flex items-center gap-1">
                {r.title}
                {r.recurrence && (
                  <Repeat className="w-2.5 h-2.5 text-purple-500 flex-shrink-0" />
                )}
              </div>
              <div className="text-xs text-gray-400">
                {formatTime(r.scheduled_at)}
                {r.recurrence && ` · ${RECURRENCE_LABELS[r.recurrence] || r.recurrence}`}
              </div>
            </button>
          ))}
        </div>
      )}
      <button
        onClick={() => router.push('/calendar')}
        className="w-full text-xs text-gray-500 hover:text-gray-700 text-center pt-1 border-t"
      >
        打开日历 →
      </button>
    </div>
  )
}
