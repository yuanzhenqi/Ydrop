/** 时间工具：客户端时间上下文 + AI 时间候选解析（对齐 Android 端逻辑） */

export interface ClientTimeContext {
  current_time_epoch_ms: number
  current_timezone: string
  current_time_text: string
}

/** 收集当前浏览器时间上下文，发给后端做 AI 时间解析参考 */
export function getClientTimeContext(): ClientTimeContext {
  const now = Date.now()
  const d = new Date(now)
  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    current_time_epoch_ms: now,
    current_timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    current_time_text: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`,
  }
}

interface ReminderCandidateLike {
  scheduledAt: number
  scheduledAtIso?: string
}

/** 优先用 scheduledAtIso 在浏览器本地时区解析；失败时回退到 scheduledAt epoch ms */
export function resolveScheduledAt(candidate: ReminderCandidateLike): number {
  if (candidate.scheduledAtIso) {
    const normalized = candidate.scheduledAtIso.trim().replace(' ', 'T')
    const parsed = new Date(normalized).getTime()
    if (!isNaN(parsed) && parsed > 0) return parsed
  }
  return candidate.scheduledAt || 0
}
