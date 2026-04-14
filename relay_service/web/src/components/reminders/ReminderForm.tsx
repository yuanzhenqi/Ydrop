'use client'

import { useState } from 'react'
import { createReminder } from '@/lib/api'
import { X } from 'lucide-react'

interface ReminderFormProps {
  noteId: string
  initialDate?: Date  // 默认日期（来自日历选中）
  onClose: () => void
  onCreated: () => void
}

const RECURRENCES = [
  { value: '', label: '一次' },
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKDAYS', label: '工作日' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY', label: '每月' },
]

export function ReminderForm({ noteId, initialDate, onClose, onCreated }: ReminderFormProps) {
  const d = initialDate || new Date()
  const defaultDateStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  // Default to 9:00 tomorrow if initialDate is today and has passed noon
  const defaultTime = '09:00'

  const [title, setTitle] = useState('')
  const [date, setDate] = useState(defaultDateStr)
  const [time, setTime] = useState(defaultTime)
  const [recurrence, setRecurrence] = useState('')
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    if (!title.trim() || saving) return
    setSaving(true)
    try {
      const [y, m, day] = date.split('-').map(Number)
      const [h, min] = time.split(':').map(Number)
      const scheduled = new Date(y, m - 1, day, h, min, 0).getTime()
      await createReminder({
        note_id: noteId,
        title: title.trim(),
        scheduled_at: scheduled,
        recurrence: recurrence || null,
      })
      onCreated()
      onClose()
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/30 flex items-center justify-center z-50 p-4 animate-fade-in" onClick={onClose}>
      <div className="bg-white rounded-2xl p-5 w-full max-w-md space-y-4 animate-scale-in" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold">新建提醒</h3>
          <button onClick={onClose} className="p-1 rounded hover:bg-gray-100">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="space-y-3">
          <div>
            <label className="text-xs text-gray-500 mb-1 block">标题</label>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="提醒标题"
              autoFocus
              className="w-full text-sm border rounded-lg px-3 py-2 outline-none focus:border-emerald-400"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs text-gray-500 mb-1 block">日期</label>
              <input
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
                className="w-full text-sm border rounded-lg px-3 py-2 outline-none focus:border-emerald-400"
              />
            </div>
            <div>
              <label className="text-xs text-gray-500 mb-1 block">时间</label>
              <input
                type="time"
                value={time}
                onChange={(e) => setTime(e.target.value)}
                className="w-full text-sm border rounded-lg px-3 py-2 outline-none focus:border-emerald-400"
              />
            </div>
          </div>

          <div>
            <label className="text-xs text-gray-500 mb-1 block">重复</label>
            <div className="flex gap-1 flex-wrap">
              {RECURRENCES.map((r) => (
                <button
                  key={r.value}
                  onClick={() => setRecurrence(r.value)}
                  className={`text-xs px-3 py-1.5 rounded-lg border transition-colors ${
                    recurrence === r.value
                      ? 'bg-emerald-600 text-white border-emerald-600'
                      : 'bg-white text-gray-600 hover:bg-gray-50'
                  }`}
                >
                  {r.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="flex justify-end gap-2 pt-2">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm text-gray-600 rounded-lg hover:bg-gray-100"
          >
            取消
          </button>
          <button
            onClick={handleSave}
            disabled={!title.trim() || saving}
            className="px-4 py-2 text-sm bg-emerald-600 text-white rounded-lg hover:bg-emerald-700 disabled:opacity-50"
          >
            {saving ? '保存中...' : '创建提醒'}
          </button>
        </div>
      </div>
    </div>
  )
}
