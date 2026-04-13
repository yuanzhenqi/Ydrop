'use client'

import type { Note } from '@/lib/types'
import { TagCloudWidget } from '@/components/notes/TagCloudWidget'
import { CategoryStats } from './CategoryStats'
import { SyncStatusWidget } from './SyncStatusWidget'
import { UpcomingReminders } from './UpcomingReminders'

export function InboxSidebar({ notes }: { notes: Note[] }) {
  return (
    <aside className="space-y-4">
      <CategoryStats notes={notes} />
      <TagCloudWidget notes={notes} maxTags={30} />
      <UpcomingReminders />
      <SyncStatusWidget />
    </aside>
  )
}
