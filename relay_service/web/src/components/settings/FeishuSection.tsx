'use client'

import { useEffect, useState } from 'react'
import { fetchFeishuSettings, updateFeishuSettings, testFeishuConnection } from '@/lib/api'
import type { FeishuSettings } from '@/lib/types'
import { SettingsSection } from './SettingsSection'
import { SettingsField, TextInput } from './SettingsField'
import { SettingsToggle } from './SettingsToggle'
import { TestButton } from './TestButton'
import { ExternalLink, AlertCircle } from 'lucide-react'

interface Props {
  onToast: (type: 'success' | 'error', message: string) => void
}

/** 飞书 Bitable 双向同步配置（Phase 1 Step 1：仅配置 + 联通测试）。
 *
 * 用法（用户视角）：
 * 1. 飞书开发者后台建一个「自建应用」，拿 app_id / app_secret
 * 2. 应用「权限管理」勾上 base:app:read（或 bitable:app:readonly）
 * 3. 在飞书的多维表格右上角『...』 → 添加应用 → 选刚建的应用
 * 4. 把多维表格 URL 里的 app_token 复制过来（形如 BAS5xxxxxxxxxxxxxxxxx）
 * 5. 点「测试连接」看到表格名称就 OK
 */
export function FeishuSection({ onToast }: Props) {
  const [settings, setSettings] = useState<FeishuSettings | null>(null)
  const [draft, setDraft] = useState({ app_id: '', app_secret: '', app_token: '', table_id: '' })
  const [saving, setSaving] = useState(false)
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string; appName?: string } | null>(null)

  useEffect(() => {
    refresh()
  }, [])

  async function refresh() {
    try {
      const s = await fetchFeishuSettings()
      setSettings(s)
      setDraft({ app_id: s.app_id, app_secret: '', app_token: s.app_token, table_id: s.table_id })
    } catch (e) {
      onToast('error', '加载飞书配置失败：' + (e instanceof Error ? e.message : String(e)))
    }
  }

  async function save(extra: Partial<FeishuSettings> = {}) {
    setSaving(true)
    try {
      const updated = await updateFeishuSettings({
        app_id: draft.app_id,
        app_secret: draft.app_secret || undefined, // 空字符串 = 不改
        app_token: draft.app_token,
        table_id: draft.table_id,
        ...extra,
      })
      setSettings(updated)
      setDraft((d) => ({ ...d, app_secret: '' })) // 保存后清空 secret 输入框
      onToast('success', '飞书配置已保存')
    } catch (e) {
      onToast('error', '保存失败：' + (e instanceof Error ? e.message : String(e)))
    } finally {
      setSaving(false)
    }
  }

  async function handleTest() {
    // 先保存当前 draft，再测试。否则用户填了但没保存的字段不会被测到。
    await save()
    try {
      const r = await testFeishuConnection()
      setTestResult({ ok: r.ok, message: r.message, appName: r.app_name })
      if (r.ok) onToast('success', `连接成功：${r.app_name || 'Bitable'}`)
      // 适配 TestButton 通用 TestResult 形状（{ok, message}）
      return { ok: r.ok, message: r.ok ? `连接成功：${r.app_name || 'Bitable'}` : r.message }
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      setTestResult({ ok: false, message: msg })
      onToast('error', '测试失败：' + msg)
      return { ok: false, message: msg }
    }
  }

  if (!settings) return null

  return (
    <SettingsSection
      title="飞书多维表格"
      description="双向同步 Ydrop 笔记到飞书 Bitable，团队可见 / 协作。需要在飞书开发者后台建一个自建应用并把它添加到目标多维表格。"
    >
      <SettingsToggle
        label="启用飞书同步"
        description="开关后端 connector。关闭时所有飞书相关 sync 路径都短路。"
        value={settings.enabled}
        onChange={(v) => {
          setSettings({ ...settings, enabled: v })
          save({ enabled: v })
        }}
      />

      <SettingsField label="App ID" hint="飞书自建应用的唯一标识，cli_ 开头">
        <TextInput
          value={draft.app_id}
          onChange={(v) => setDraft((d) => ({ ...d, app_id: v }))}
          placeholder="cli_xxxxxxxxxxxxxxxx"
        />
      </SettingsField>

      <SettingsField
        label={`App Secret${settings.secret_set ? '（已配置）' : ''}`}
        hint={settings.secret_set ? '留空则保留现有值；要更换请输入新的 secret' : '飞书应用的 secret 凭据，本地加密存储'}
      >
        <TextInput
          value={draft.app_secret}
          onChange={(v) => setDraft((d) => ({ ...d, app_secret: v }))}
          placeholder={settings.secret_set ? '••••••••（保留现有）' : '从飞书开发者后台 → 凭证与基础信息复制'}
          type="password"
        />
      </SettingsField>

      <SettingsField
        label="App Token（多维表格 ID）"
        hint="多维表格 URL 里的那段 base/<这里>，形如 BAS5xxxxxxxxxxxxxxxxx"
      >
        <TextInput
          value={draft.app_token}
          onChange={(v) => setDraft((d) => ({ ...d, app_token: v }))}
          placeholder="BAS5xxxxxxxxxxxxxxxxx"
        />
      </SettingsField>

      <SettingsField
        label="Table ID（具体表）"
        hint="同一个多维表格里可以有多张表。Step 1 暂不验证此字段；后续同步会用到"
      >
        <TextInput
          value={draft.table_id}
          onChange={(v) => setDraft((d) => ({ ...d, table_id: v }))}
          placeholder="tblxxxxxxxxxxxxxxxxx"
        />
      </SettingsField>

      <div className="flex items-center gap-3 pt-1">
        <button
          onClick={() => save()}
          disabled={saving}
          className="px-3 py-1.5 text-sm rounded-lg bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50"
        >
          {saving ? '保存中...' : '保存'}
        </button>
        <TestButton onTest={handleTest} />
        <a
          href="https://open.feishu.cn/app"
          target="_blank"
          rel="noopener noreferrer"
          className="text-xs text-gray-500 hover:text-emerald-600 inline-flex items-center gap-1 ml-auto"
        >
          打开飞书开发者后台 <ExternalLink className="w-3 h-3" />
        </a>
      </div>

      {testResult && (
        <div
          className={`mt-2 rounded-lg px-3 py-2 text-xs flex items-start gap-2 ${
            testResult.ok ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700'
          }`}
        >
          <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
          <div>
            <div className="font-semibold">{testResult.ok ? '连接成功' : '连接失败'}</div>
            {testResult.appName && <div>表格名：{testResult.appName}</div>}
            <div className="text-[11px] mt-0.5 break-all">{testResult.message}</div>
          </div>
        </div>
      )}
    </SettingsSection>
  )
}
