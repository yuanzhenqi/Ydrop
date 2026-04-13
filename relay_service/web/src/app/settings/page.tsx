'use client'

import { useEffect, useState } from 'react'
import {
  fetchAppSettings,
  updateAppSettings,
  testWebDavConnection,
  testAiConnection,
  fetchSyncStatus,
  triggerSync,
  hasToken,
  setToken,
} from '@/lib/api'
import type { AppSettings, SyncStatus } from '@/lib/types'
import { formatTime } from '@/lib/date'
import { SettingsSection } from '@/components/settings/SettingsSection'
import { SettingsField, TextInput, NumberSelect } from '@/components/settings/SettingsField'
import { SettingsToggle } from '@/components/settings/SettingsToggle'
import { TestButton } from '@/components/settings/TestButton'
import { Settings, Key, RefreshCw, Cloud, Sparkles, CheckCircle2, XCircle, Save } from 'lucide-react'

type Tab = 'general' | 'webdav' | 'ai'

const SYNC_INTERVAL_OPTIONS = [
  { value: 60, label: '1 分钟' },
  { value: 300, label: '5 分钟' },
  { value: 900, label: '15 分钟' },
  { value: 3600, label: '60 分钟' },
]

const ENDPOINT_MODE_OPTIONS: { value: 'AUTO' | 'OPENAI' | 'ANTHROPIC'; label: string }[] = [
  { value: 'AUTO', label: '自动' },
  { value: 'OPENAI', label: 'OpenAI' },
  { value: 'ANTHROPIC', label: 'Anthropic' },
]

export default function SettingsPage() {
  const [tab, setTab] = useState<Tab>('general')
  const [tokenInput, setTokenInput] = useState('')
  const [authenticated, setAuthenticated] = useState(false)
  const [syncStatus, setSyncStatus] = useState<SyncStatus | null>(null)
  const [syncing, setSyncing] = useState(false)

  const [appSettings, setAppSettings] = useState<AppSettings | null>(null)
  const [draftWebdav, setDraftWebdav] = useState<any>({})
  const [draftAi, setDraftAi] = useState<any>({})
  const [saving, setSaving] = useState(false)
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)

  // Initial load
  useEffect(() => {
    const auth = hasToken()
    setAuthenticated(auth)
    if (auth) {
      refreshAll()
    }
  }, [])

  const refreshAll = async () => {
    try {
      const [s, sync] = await Promise.all([fetchAppSettings(), fetchSyncStatus()])
      setAppSettings(s)
      setSyncStatus(sync)
      // 重置 draft 为当前值
      setDraftWebdav({})
      setDraftAi({})
    } catch (e) {
      console.error(e)
    }
  }

  const handleSetToken = () => {
    if (!tokenInput.trim()) return
    setToken(tokenInput.trim())
    setAuthenticated(true)
    setTokenInput('')
    refreshAll()
  }

  const handleSync = async () => {
    setSyncing(true)
    try {
      const s = await triggerSync()
      setSyncStatus(s)
      showToast('success', `同步完成：推送 ${s.pushed}、拉取 ${s.pulled}`)
    } catch (e) {
      showToast('error', `同步失败：${e instanceof Error ? e.message : e}`)
    } finally {
      setSyncing(false)
    }
  }

  const showToast = (type: 'success' | 'error', message: string) => {
    setToast({ type, message })
    setTimeout(() => setToast(null), 3000)
  }

  const handleSave = async () => {
    setSaving(true)
    try {
      const updated = await updateAppSettings({
        webdav: Object.keys(draftWebdav).length ? draftWebdav : undefined,
        ai: Object.keys(draftAi).length ? draftAi : undefined,
      })
      setAppSettings(updated)
      setDraftWebdav({})
      setDraftAi({})
      showToast('success', '设置已保存')
    } catch (e) {
      showToast('error', `保存失败：${e instanceof Error ? e.message : e}`)
    } finally {
      setSaving(false)
    }
  }

  const hasChanges = Object.keys(draftWebdav).length > 0 || Object.keys(draftAi).length > 0

  // 合并显示值：draft 覆盖 remote
  const wv = {
    ...appSettings?.webdav,
    ...draftWebdav,
  }
  const av = {
    ...appSettings?.ai,
    ...draftAi,
  }

  const updateWebdav = (key: string, value: any) => setDraftWebdav({ ...draftWebdav, [key]: value })
  const updateAi = (key: string, value: any) => setDraftAi({ ...draftAi, [key]: value })

  // 未认证展示独立界面
  if (!authenticated) {
    return (
      <div className="max-w-md mx-auto px-4 py-12 space-y-4">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <Key className="w-5 h-5" /> 初始化
        </h1>
        <p className="text-sm text-gray-600">请输入服务端的 Relay Token 来开始使用。</p>
        <div className="bg-white rounded-2xl border p-4 space-y-3">
          <TextInput
            value={tokenInput}
            onChange={setTokenInput}
            placeholder="输入 Relay Token"
            type="password"
          />
          <button
            onClick={handleSetToken}
            disabled={!tokenInput.trim()}
            className="w-full py-2 bg-emerald-600 text-white text-sm rounded-lg hover:bg-emerald-700 disabled:opacity-50"
          >
            保存并继续
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <Settings className="w-5 h-5" /> 设置
        </h1>
        {hasChanges && (
          <button
            onClick={handleSave}
            disabled={saving}
            className="flex items-center gap-1.5 px-4 py-1.5 bg-emerald-600 text-white text-sm rounded-lg hover:bg-emerald-700 disabled:opacity-50"
          >
            <Save className="w-3.5 h-3.5" />
            {saving ? '保存中...' : '保存更改'}
          </button>
        )}
      </div>

      {/* Tab 切换 */}
      <div className="flex items-center gap-1 bg-white border rounded-xl p-1">
        <TabBtn active={tab === 'general'} onClick={() => setTab('general')} icon={Settings}>
          通用
        </TabBtn>
        <TabBtn active={tab === 'webdav'} onClick={() => setTab('webdav')} icon={Cloud}>
          WebDAV 同步
          {appSettings?.server_info.webdav_configured && <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 ml-1" />}
        </TabBtn>
        <TabBtn active={tab === 'ai'} onClick={() => setTab('ai')} icon={Sparkles}>
          AI 整理
          {appSettings?.server_info.ai_configured && <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 ml-1" />}
        </TabBtn>
      </div>

      {toast && (
        <div className={`rounded-lg px-4 py-2 text-sm flex items-center gap-2 ${
          toast.type === 'success' ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700'
        }`}>
          {toast.type === 'success' ? <CheckCircle2 className="w-4 h-4" /> : <XCircle className="w-4 h-4" />}
          {toast.message}
        </div>
      )}

      {/* 通用 Tab */}
      {tab === 'general' && (
        <>
          <SettingsSection title="认证" description="Relay Token 用于访问 API">
            <div className="flex items-center gap-2 text-sm">
              <CheckCircle2 className="w-4 h-4 text-emerald-600" />
              <span className="text-gray-700">已配置 Token</span>
              <button
                onClick={() => {
                  if (confirm('确定要清除 Token 吗？')) {
                    localStorage.removeItem('ydrop_token')
                    setAuthenticated(false)
                  }
                }}
                className="ml-auto text-xs text-red-500 hover:underline"
              >
                清除
              </button>
            </div>
          </SettingsSection>

          <SettingsSection title="同步状态" description="WebDAV 自动同步运行情况">
            {syncStatus && (
              <div className="text-sm space-y-1 text-gray-700">
                <div>
                  上次同步：
                  <span className="font-medium">
                    {syncStatus.last_sync_at ? formatTime(syncStatus.last_sync_at) : '从未'}
                  </span>
                </div>
                <div className="text-xs text-gray-500">
                  推送 {syncStatus.pushed} · 拉取 {syncStatus.pulled} · 错误 {syncStatus.errors}
                  {syncStatus.running && <span className="ml-2 text-blue-600">（运行中）</span>}
                </div>
              </div>
            )}
            <button
              onClick={handleSync}
              disabled={syncing}
              className="flex items-center gap-1.5 px-4 py-1.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${syncing ? 'animate-spin' : ''}`} />
              {syncing ? '同步中...' : '立即同步'}
            </button>
          </SettingsSection>

          <SettingsSection title="服务端信息">
            <div className="text-sm space-y-1 text-gray-600">
              <div>版本：{appSettings?.server_info.version || '-'}</div>
              <div>WebDAV：{appSettings?.server_info.webdav_configured ? '✓ 已配置' : '✗ 未配置'}</div>
              <div>AI：{appSettings?.server_info.ai_configured ? '✓ 已配置' : '✗ 未配置'}</div>
            </div>
          </SettingsSection>
        </>
      )}

      {/* WebDAV Tab */}
      {tab === 'webdav' && appSettings && (
        <SettingsSection title="WebDAV 同步" description="跨设备同步笔记，和 Android 端共享同一目录">
          <SettingsToggle
            label="启用 WebDAV 同步"
            description="关闭后不会自动同步，仅本地保存"
            value={wv.enabled ?? false}
            onChange={(v) => updateWebdav('enabled', v)}
          />

          <SettingsField label="WebDAV 地址" hint="如 https://nas.example.com/dav">
            <TextInput
              value={wv.base_url ?? ''}
              onChange={(v) => updateWebdav('base_url', v)}
              placeholder="https://nas.example.com/dav"
              type="url"
            />
          </SettingsField>

          <div className="grid grid-cols-2 gap-3">
            <SettingsField label="用户名">
              <TextInput
                value={wv.username ?? ''}
                onChange={(v) => updateWebdav('username', v)}
                placeholder="用户名"
              />
            </SettingsField>
            <SettingsField label="密码" hint={appSettings.webdav.password_set ? '已设置，留空不修改' : '未设置'}>
              <TextInput
                value={draftWebdav.password ?? ''}
                onChange={(v) => updateWebdav('password', v)}
                placeholder={appSettings.webdav.password_set ? '••••••••' : '密码'}
                type="password"
              />
            </SettingsField>
          </div>

          <SettingsField label="云端目录" hint="笔记存放的文件夹路径">
            <TextInput
              value={wv.folder ?? ''}
              onChange={(v) => updateWebdav('folder', v)}
              placeholder="ydoc/inbox"
            />
          </SettingsField>

          <SettingsToggle
            label="自动同步"
            description="按设定间隔定时同步"
            value={wv.auto_sync ?? true}
            onChange={(v) => updateWebdav('auto_sync', v)}
          />

          <SettingsField label="同步间隔">
            <NumberSelect
              value={wv.sync_interval ?? 300}
              onChange={(v) => updateWebdav('sync_interval', v)}
              options={SYNC_INTERVAL_OPTIONS}
            />
          </SettingsField>

          <div className="pt-2 border-t">
            <TestButton onTest={testWebDavConnection} />
          </div>
        </SettingsSection>
      )}

      {/* AI Tab */}
      {tab === 'ai' && appSettings && (
        <SettingsSection title="AI 整理" description="配置 LLM 服务用于 AI 分析、问答、批量整理">
          <SettingsToggle
            label="启用 AI"
            description="关闭后所有 AI 接口走启发式兜底"
            value={av.enabled ?? false}
            onChange={(v) => updateAi('enabled', v)}
          />

          <SettingsField label="AI Base URL" hint="兼容 OpenAI /v1 格式的网关地址">
            <TextInput
              value={av.base_url ?? ''}
              onChange={(v) => updateAi('base_url', v)}
              placeholder="https://api.openai.com/v1"
              type="url"
            />
          </SettingsField>

          <SettingsField label="AI Token" hint={appSettings.ai.token_set ? '已设置，留空不修改' : '未设置'}>
            <TextInput
              value={draftAi.token ?? ''}
              onChange={(v) => updateAi('token', v)}
              placeholder={appSettings.ai.token_set ? '••••••••' : 'sk-...'}
              type="password"
            />
          </SettingsField>

          <SettingsField label="模型名称">
            <TextInput
              value={av.model ?? ''}
              onChange={(v) => updateAi('model', v)}
              placeholder="gpt-4o / claude-sonnet-4-5 / glm-5.1"
            />
          </SettingsField>

          <SettingsField label="协议模式" hint="AUTO 会自动探测；不同厂商兼容层">
            <div className="flex gap-2">
              {ENDPOINT_MODE_OPTIONS.map((o) => (
                <button
                  key={o.value}
                  onClick={() => updateAi('endpoint_mode', o.value)}
                  className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${
                    av.endpoint_mode === o.value
                      ? 'bg-emerald-600 text-white border-emerald-600'
                      : 'bg-white text-gray-600 hover:bg-gray-50'
                  }`}
                >
                  {o.label}
                </button>
              ))}
            </div>
          </SettingsField>

          <SettingsField label="补充指令" hint="附加到默认 prompt 末尾，支持变量 {{current_time}}、{{current_timezone}}">
            <textarea
              value={av.prompt_supplement ?? ''}
              onChange={(e) => updateAi('prompt_supplement', e.target.value)}
              rows={3}
              placeholder="（可选）自定义 prompt 补充..."
              className="w-full text-sm border rounded-lg px-3 py-2 outline-none focus:border-emerald-400 resize-none"
            />
          </SettingsField>

          <SettingsToggle
            label="文本保存后自动整理"
            description="新建/编辑笔记后自动触发 AI 分析"
            value={av.auto_run_on_text_save ?? true}
            onChange={(v) => updateAi('auto_run_on_text_save', v)}
          />

          <SettingsToggle
            label="超时/网络错误自动重试"
            description="最多重试 5 次，指数退避"
            value={av.auto_retry_on_failure ?? true}
            onChange={(v) => updateAi('auto_retry_on_failure', v)}
          />

          <div className="pt-2 border-t">
            <TestButton onTest={testAiConnection} />
          </div>
        </SettingsSection>
      )}
    </div>
  )
}

function TabBtn({
  active,
  onClick,
  icon: Icon,
  children,
}: {
  active: boolean
  onClick: () => void
  icon: any
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      className={`flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-sm transition-colors ${
        active ? 'bg-emerald-50 text-emerald-700 font-medium' : 'text-gray-600 hover:bg-gray-50'
      }`}
    >
      <Icon className="w-4 h-4" />
      {children}
    </button>
  )
}
