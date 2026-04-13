'use client'

import { useEffect, useState } from 'react'
import { fetchSyncStatus, triggerSync, hasToken, setToken } from '@/lib/api'
import { formatTime } from '@/lib/date'
import type { SyncStatus } from '@/lib/types'
import { Settings, RefreshCw, Key } from 'lucide-react'

export default function SettingsPage() {
  const [tokenInput, setTokenInput] = useState('')
  const [syncStatus, setSyncStatus] = useState<SyncStatus | null>(null)
  const [syncing, setSyncing] = useState(false)
  const [authenticated, setAuthenticated] = useState(false)

  useEffect(() => {
    setAuthenticated(hasToken())
    if (hasToken()) {
      fetchSyncStatus().then(setSyncStatus).catch(() => {})
    }
  }, [])

  const handleSetToken = () => {
    if (tokenInput.trim()) {
      setToken(tokenInput.trim())
      setAuthenticated(true)
      setTokenInput('')
      fetchSyncStatus().then(setSyncStatus).catch(() => {})
    }
  }

  const handleSync = async () => {
    setSyncing(true)
    try {
      const s = await triggerSync()
      setSyncStatus(s)
    } catch (e) {
      console.error('Sync failed:', e)
    } finally {
      setSyncing(false)
    }
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-6">
      <h1 className="text-xl font-bold flex items-center gap-2">
        <Settings className="w-5 h-5" /> 设置
      </h1>

      {/* Auth */}
      <section className="bg-white rounded-2xl border p-4 space-y-3">
        <h2 className="text-sm font-semibold flex items-center gap-2">
          <Key className="w-4 h-4" /> 认证 Token
        </h2>
        {authenticated ? (
          <p className="text-sm text-emerald-600">已配置 Token</p>
        ) : (
          <div className="flex gap-2">
            <input
              type="password"
              value={tokenInput}
              onChange={(e) => setTokenInput(e.target.value)}
              placeholder="输入 Relay Token"
              className="flex-1 text-sm border rounded-lg px-3 py-2 outline-none focus:border-emerald-400"
              onKeyDown={(e) => e.key === 'Enter' && handleSetToken()}
            />
            <button onClick={handleSetToken} className="px-4 py-2 bg-emerald-600 text-white text-sm rounded-lg hover:bg-emerald-700">
              保存
            </button>
          </div>
        )}
      </section>

      {/* Sync */}
      <section className="bg-white rounded-2xl border p-4 space-y-3">
        <h2 className="text-sm font-semibold flex items-center gap-2">
          <RefreshCw className="w-4 h-4" /> WebDAV 同步
        </h2>
        {syncStatus ? (
          <div className="text-sm space-y-1">
            <p>上次同步：{syncStatus.last_sync_at ? formatTime(syncStatus.last_sync_at) : '从未'}</p>
            <p>推送：{syncStatus.pushed} · 拉取：{syncStatus.pulled} · 错误：{syncStatus.errors}</p>
          </div>
        ) : (
          <p className="text-sm text-gray-400">未获取同步状态</p>
        )}
        <button
          onClick={handleSync}
          disabled={syncing || !authenticated}
          className="flex items-center gap-1.5 px-4 py-1.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${syncing ? 'animate-spin' : ''}`} />
          {syncing ? '同步中...' : '立即同步'}
        </button>
      </section>
    </div>
  )
}
