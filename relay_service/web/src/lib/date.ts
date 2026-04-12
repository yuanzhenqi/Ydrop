export function formatTime(epochMs: number): string {
  const d = new Date(epochMs)
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  if (isToday) return `${hh}:${mm}`
  const month = d.getMonth() + 1
  const day = d.getDate()
  return `${month}/${day} ${hh}:${mm}`
}

export function formatDate(epochMs: number): string {
  const d = new Date(epochMs)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
