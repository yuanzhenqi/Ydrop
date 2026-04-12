'use client'
import { create } from 'zustand'
import type { NoteCategory, NotePriority } from '@/lib/types'

interface AppState {
  searchQuery: string
  categoryFilter: NoteCategory | null
  tagFilter: string | null
  setSearchQuery: (q: string) => void
  setCategoryFilter: (c: NoteCategory | null) => void
  setTagFilter: (t: string | null) => void
  clearFilters: () => void
}

export const useAppStore = create<AppState>((set) => ({
  searchQuery: '',
  categoryFilter: null,
  tagFilter: null,
  setSearchQuery: (q) => set({ searchQuery: q }),
  setCategoryFilter: (c) => set({ categoryFilter: c }),
  setTagFilter: (t) => set({ tagFilter: t }),
  clearFilters: () => set({ searchQuery: '', categoryFilter: null, tagFilter: null }),
}))
