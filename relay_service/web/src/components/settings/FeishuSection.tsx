'use client'

import { useEffect, useState } from 'react'
import { fetchFeishuSettings, updateFeishuSettings, testFeishuConnection, initFeishuTable, feishuPushAll, feishuPull } from '@/lib/api'
import type { FeishuSettings, FeishuInitTableResult, FeishuPushAllResult, FeishuPullResult } from '@/lib/types'
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
  const [initResult, setInitResult] = useState<FeishuInitTableResult | null>(null)
  const [initing, setIniting] = useState(false)
  const [pushing, setPushing] = useState(false)
  const [pushResult, setPushResult] = useState<FeishuPushAllResult | null>(null)
  const [pulling, setPulling] = useState(false)
  const [pullResult, setPullResult] = useState<FeishuPullResult | null>(null)

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

  async function handleInitTable() {
    setIniting(true)
    try {
      const r = await initFeishuTable()
      setInitResult(r)
      if (r.ok && r.created.length === 0 && r.skipped.length > 0) {
        onToast('success', '所有 Ydrop 标准列已存在，无需新建')
      } else if (r.ok) {
        onToast('success', `初始化完成：${r.message}`)
      } else {
        onToast('error', `初始化部分失败：${r.message}`)
      }
    } catch (e) {
      onToast('error', '初始化失败：' + (e instanceof Error ? e.message : String(e)))
    } finally {
      setIniting(false)
    }
  }

  async function handlePushAll() {
    setPushing(true)
    setPushResult(null)
    try {
      const r = await feishuPushAll()
      setPushResult(r)
      if (r.ok) {
        onToast(r.failed === 0 ? 'success' : 'error', r.message)
      } else {
        onToast('error', r.message || '推送失败')
      }
    } catch (e) {
      onToast('error', '推送失败：' + (e instanceof Error ? e.message : String(e)))
    } finally {
      setPushing(false)
    }
  }

  async function handlePull() {
    setPulling(true)
    setPullResult(null)
    try {
      const r = await feishuPull()
      setPullResult(r)
      if (r.ok) {
        onToast(r.errors.length === 0 ? 'success' : 'error', r.message)
      } else {
        onToast('error', r.message || '拉取失败')
      }
    } catch (e) {
      onToast('error', '拉取失败：' + (e instanceof Error ? e.message : String(e)))
    } finally {
      setPulling(false)
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
        hint="同一个多维表格里可以有多张表。从 URL 里复制 ?table=tblxxxx 那段；带 &view= 后缀也行（自动剥）"
      >
        <TextInput
          value={draft.table_id}
          onChange={(v) => setDraft((d) => ({ ...d, table_id: v }))}
          placeholder="tblxxxxxxxxxxxxxxxxx"
        />
      </SettingsField>

      <SettingsField
        label="自动拉取间隔（秒）"
        hint="后台每 N 秒从飞书拉一次。最小 60，0 = 禁用定时只走手动。默认 300（5 分钟）"
      >
        <input
          type="number"
          min={0}
          max={86400}
          value={settings.sync_interval ?? 300}
          onChange={(e) => {
            const v = Number(e.target.value || 0)
            setSettings({ ...settings, sync_interval: v })
            save({ sync_interval: v })
          }}
          className="w-32 text-sm border rounded-lg px-2 py-1.5 outline-none focus:border-emerald-400"
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

      {/* 初始化表结构 — 在 Bitable 里建好 9 列标准 schema（同名列跳过） */}
      <div className="pt-3 mt-2 border-t space-y-2">
        <div className="text-xs text-gray-500 leading-relaxed">
          点「初始化 Ydrop 表结构」会在你这张 Bitable 自动创建 Ydrop 需要的 9 列：标题 / 内容 / 类型 / 优先级 / 标签 / 已归档 / 创建时间 / 更新时间 / ydrop_id。
          已有同名列直接跳过，不会改你已有的列结构。
          <br />
          <span className="text-amber-600">⚠ 需要应用拥有 <code>bitable:app</code> 写权限（仅 readonly 不够）。</span>
        </div>
        <button
          onClick={handleInitTable}
          disabled={initing}
          className="px-3 py-1.5 text-sm rounded-lg bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {initing ? '初始化中...' : '初始化 Ydrop 表结构'}
        </button>

        {initResult && (
          <div
            className={`rounded-lg px-3 py-2 text-xs space-y-1 ${
              initResult.ok ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-700'
            }`}
          >
            {initResult.created.length > 0 && (
              <div>
                ✓ 新建：{initResult.created.join('、')}
              </div>
            )}
            {initResult.skipped.length > 0 && (
              <div className="text-gray-500">
                · 跳过（已有）：{initResult.skipped.join('、')}
              </div>
            )}
            {initResult.errors.length > 0 && (
              <div className="text-red-600">
                ✗ 失败：
                <ul className="list-disc list-inside">
                  {initResult.errors.map((err, i) => (
                    <li key={i}>
                      <strong>{err.name}</strong> (code={err.code})：{err.msg}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </div>

      {/* 一键推送已有笔记 — 用于首次接入或表结构改动后批量同步 */}
      <div className="pt-3 mt-2 border-t space-y-2">
        <div className="text-xs text-gray-500 leading-relaxed">
          点「立即推送所有笔记」会把当前 inbox + archive 的所有笔记同步到飞书。
          已经推过的会更新（按 ydrop_id 映射）；后续创建/编辑/归档/删除笔记自动触发推送，无需手动。
        </div>
        <button
          onClick={handlePushAll}
          disabled={pushing || !settings.enabled}
          className="px-3 py-1.5 text-sm rounded-lg bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50"
          title={!settings.enabled ? '请先启用飞书同步' : ''}
        >
          {pushing ? '推送中...' : '立即推送所有笔记到飞书'}
        </button>
        {pushResult && (
          <div
            className={`rounded-lg px-3 py-2 text-xs ${
              pushResult.ok && pushResult.failed === 0
                ? 'bg-emerald-50 text-emerald-700'
                : 'bg-amber-50 text-amber-700'
            }`}
          >
            {pushResult.message} (推送 {pushResult.pushed}，失败 {pushResult.failed})
          </div>
        )}
      </div>

      {/* 反向拉取：从飞书 Bitable 同步回 Ydrop */}
      <div className="pt-3 mt-2 border-t space-y-2">
        <div className="text-xs text-gray-500 leading-relaxed">
          点「从飞书拉取」会把 Bitable 端的所有 record 同步回 Ydrop：
          按 ydrop_id 比对 updated_at 决定方向（last-write-wins）；
          Bitable 端新建的 record（无 ydrop_id）会自动创建本地笔记并把生成的 ydrop_id 写回飞书；
          Bitable 端删除的 record 会让本地笔记移回收站。
        </div>
        <button
          onClick={handlePull}
          disabled={pulling || !settings.enabled}
          className="px-3 py-1.5 text-sm rounded-lg bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-50"
          title={!settings.enabled ? '请先启用飞书同步' : ''}
        >
          {pulling ? '拉取中...' : '从飞书拉取（双向同步）'}
        </button>
        {pullResult && (
          <div
            className={`rounded-lg px-3 py-2 text-xs ${
              pullResult.ok && pullResult.errors.length === 0
                ? 'bg-emerald-50 text-emerald-700'
                : 'bg-amber-50 text-amber-700'
            }`}
          >
            {pullResult.message}
            {pullResult.errors.length > 0 && (
              <ul className="list-disc list-inside mt-1 text-red-600">
                {pullResult.errors.slice(0, 5).map((err, i) => (
                  <li key={i}>{err}</li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </SettingsSection>
  )
}
