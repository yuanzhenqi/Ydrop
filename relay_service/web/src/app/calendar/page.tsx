'use client'

import { useState } from 'react'
import { useReminders } from '@/hooks/useReminders'
import { useNotes } from '@/hooks/useNotes'
import { cancelReminder } from '@/lib/api'
import { formatTime } from '@/lib/date'
import { ReminderForm } from '@/components/reminders/ReminderForm'
import { Calendar, ChevronLeft, ChevronRight, X, Plus, Repeat } from 'lucide-react'

const RECURRENCE_LABELS: Record<string, string> = {
  DAILY: '每天',
  WEEKDAYS: '工作日',
  WEEKLY: '每周',
  MONTHLY: '每月',
}

export default function CalendarPage() {
  const [selectedDate, setSelectedDate] = useState<Date>(new Date())
  const { reminders, mutate } = useReminders()
  const { notes } = useNotes({ trashed: 'false' })
  const [showForm, setShowForm] = useState(false)
  const [formDate, setFormDate] = useState<Date | null>(null)

  const year = selectedDate.getFullYear()
  const month = selectedDate.getMonth()

  const firstDay = new Date(year, month, 1).getDay()
  const daysInMonth = new Date(year, month + 1, 0).getDate()

  const dayReminders = (day: number) => {
    const start = new Date(year, month, day).getTime()
    const end = start + 86400000
    return reminders.filter((r) => r.scheduled_at >= start && r.scheduled_at < end)
  }

  const [viewDay, setViewDay] = useState<number | null>(null)
  const selectedDayReminders = viewDay ? dayReminders(viewDay) : []

  const firstNoteId = notes[0]?.id

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <Calendar className="w-5 h-5" /> 日历
        </h1>
        {firstNoteId && (
          <button
            onClick={() => {
              setFormDate(viewDay ? new Date(year, month, viewDay) : new Date())
              setShowForm(true)
            }}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-emerald-600 text-white text-sm rounded-lg hover:bg-emerald-700"
          >
            <Plus className="w-3.5 h-3.5" /> 新建提醒
          </button>
        )}
      </div>

      {/* Month navigation */}
      <div className="flex items-center justify-between bg-white rounded-xl border p-3">
        <button onClick={() => setSelectedDate(new Date(year, month - 1, 1))} className="p-1 rounded hover:bg-gray-100">
          <ChevronLeft className="w-4 h-4" />
        </button>
        <span className="font-medium text-sm">{year} 年 {month + 1} 月</span>
        <button onClick={() => setSelectedDate(new Date(year, month + 1, 1))} className="p-1 rounded hover:bg-gray-100">
          <ChevronRight className="w-4 h-4" />
        </button>
      </div>

      {/* Month grid */}
      <div className="bg-white rounded-xl border p-3">
        <div className="grid grid-cols-7 text-center text-xs text-gray-400 mb-2">
          {['日', '一', '二', '三', '四', '五', '六'].map((d) => (
            <div key={d} className="py-1">{d}</div>
          ))}
        </div>
        <div className="grid grid-cols-7 gap-1">
          {Array.from({ length: firstDay }).map((_, i) => (
            <div key={`empty-${i}`} />
          ))}
          {Array.from({ length: daysInMonth }).map((_, i) => {
            const day = i + 1
            const hasReminders = dayReminders(day).length > 0
            const isSelected = viewDay === day
            const isToday = new Date().getDate() === day && new Date().getMonth() === month && new Date().getFullYear() === year
            return (
              <button
                key={day}
                onClick={() => setViewDay(isSelected ? null : day)}
                className={`aspect-square rounded-xl text-sm flex flex-col items-center justify-center transition-colors ${
                  isSelected ? 'bg-emerald-100 text-emerald-700 font-bold' : isToday ? 'bg-blue-50 font-medium' : 'hover:bg-gray-50'
                }`}
              >
                {day}
                {hasReminders && <div className="w-1 h-1 rounded-full bg-emerald-500 mt-0.5" />}
              </button>
            )
          })}
        </div>
      </div>

      {/* Day agenda */}
      {viewDay && (
        <div className="space-y-2">
          <h2 className="text-sm font-medium text-gray-600">
            {month + 1} 月 {viewDay} 日 — {selectedDayReminders.length} 条提醒
          </h2>
          {selectedDayReminders.length === 0 ? (
            <p className="text-sm text-gray-400">当天无提醒</p>
          ) : (
            selectedDayReminders.map((r) => (
              <div key={r.id} className="bg-white rounded-xl border p-3 flex items-center gap-3">
                <div className="flex-1">
                  <p className="text-sm font-medium flex items-center gap-1.5">
                    {r.title}
                    {r.recurrence && (
                      <span className="text-xs px-1.5 py-0.5 rounded bg-purple-50 text-purple-600 inline-flex items-center gap-0.5">
                        <Repeat className="w-3 h-3" /> {RECURRENCE_LABELS[r.recurrence] || r.recurrence}
                      </span>
                    )}
                  </p>
                  <p className="text-xs text-gray-400">{formatTime(r.scheduled_at)} · {r.status}</p>
                </div>
                {r.status === 'SCHEDULED' && (
                  <button
                    onClick={async () => { await cancelReminder(r.id); mutate() }}
                    className="p-1.5 rounded-lg text-red-500 hover:bg-red-50"
                    title="取消提醒"
                  >
                    <X className="w-4 h-4" />
                  </button>
                )}
              </div>
            ))
          )}
        </div>
      )}

      {showForm && firstNoteId && (
        <ReminderForm
          noteId={firstNoteId}
          initialDate={formDate || undefined}
          onClose={() => setShowForm(false)}
          onCreated={() => mutate()}
        />
      )}
    </div>
  )
}
