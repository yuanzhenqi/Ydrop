'use client'
import useSWR from 'swr'
import { fetchReminders } from '@/lib/api'
import type { ReminderListResponse } from '@/lib/types'

export function useReminders(params: Record<string, string> = {}) {
  const key = `/api/reminders?${new URLSearchParams(params).toString()}`
  const { data, error, isLoading, mutate } = useSWR<ReminderListResponse>(key, () => fetchReminders(params), {
    refreshInterval: 10000,
  })
  return { reminders: data?.items || [], total: data?.total || 0, error, isLoading, mutate }
}
