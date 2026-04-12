'use client'
import useSWR from 'swr'
import { fetchNotes } from '@/lib/api'
import type { NoteListResponse } from '@/lib/types'

export function useNotes(params: Record<string, string> = {}) {
  const key = `/api/notes?${new URLSearchParams(params).toString()}`
  const { data, error, isLoading, mutate } = useSWR<NoteListResponse>(key, () => fetchNotes(params), {
    refreshInterval: 5000,
  })
  return { notes: data?.items || [], total: data?.total || 0, error, isLoading, mutate }
}
