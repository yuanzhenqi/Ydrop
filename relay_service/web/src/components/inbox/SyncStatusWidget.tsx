'use client'

import { useEffect, useState } from 'react'
import { fetchSyncStatus, triggerSync } from '@/lib/api'
import type { SyncStatus } from '@/lib/types'
import { formatTime } from '@/lib/date'
import { RefreshCw, Cloud } from 'lucide-react'

export function SyncStatusWidget() {
  const [status, setStatus] = useState<SyncStatus | null>(null)
  const [syncing, setSyncing] = useState(false)

  useEffect(() => {
    fetchSyncStatus().then(setStatus).catch(() => {})
    const t = setInterval(() => {
      fetchSyncStatus().then(setStatus).catch(() => {})
    }, 15000)
    return () => clearInterval(t)
  }, [])

  const handleSync = async () => {
    setSyncing(true)
    try {
      const r = await triggerSync()
      setStatus(r)
    } finally {
      setSyncing(false)
    }
  }

  const ok = status && status.errors === 0 && status.last_sync_at
  const never = !status?.last_sync_at

  return (
    <div className="bg-white rounded-2xl border p-4 space-y-2">
      <h3 className="text-sm font-semibold text-gray-700 flex items-center gap-1.5">
        <Cloud className="w-4 h-4" /> 同步状态
        <span
          className={`ml-auto w-2 h-2 rounded-full ${
            never ? 'bg-gray-300' : ok ? 'bg-emerald-500' : 'bg-red-500'
          }`}
        />
      </h3>
      {status ? (
        <div className="text-xs space-y-0.5 text-gray-600">
          <div>上次：{status.last_sync_at ? formatTime(status.last_sync_at) : '—'}</div>
          <div className="text-gray-400">
            推送 {status.pushed} · 拉取 {status.pulled}
            {status.errors > 0 && <span className="text-red-500 ml-1">· 错误 {status.errors}</span>}
          </div>
        </div>
      ) : (
        <div className="text-xs text-gray-400">加载中...</div>
      )}
      <button
        onClick={handleSync}
        disabled={syncing}
        className="w-full flex items-center justify-center gap-1.5 py-1.5 text-xs rounded-lg bg-blue-50 text-blue-700 hover:bg-blue-100 disabled:opacity-50"
      >
        <RefreshCw className={`w-3 h-3 ${syncing ? 'animate-spin' : ''}`} />
        {syncing ? '同步中...' : '立即同步'}
      </button>
    </div>
  )
}
