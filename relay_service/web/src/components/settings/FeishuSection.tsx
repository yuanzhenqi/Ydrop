'use client'

import { useEffect, useState } from 'react'
import { fetchFeishuSettings, updateFeishuSettings, testFeishuConnection, initFeishuTable, feishuPushAll, feishuPull, fetchFeishuConflicts, resolveFeishuConflict } from '@/lib/api'
import type { FeishuSettings, FeishuInitTableResult, FeishuPushAllResult, FeishuPullResult, FeishuConflictItem } from '@/lib/types'
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
  const [conflicts, setConflicts] = useState<FeishuConflictItem[]>([])
  const [showConflicts, setShowConflicts] = useState(false)

  useEffect(() => {
    refresh()
    refreshConflicts()
    // 每 30s 刷一次冲突列表（不用 SWR 简化）
    const t = setInterval(refreshConflicts, 30000)
    return () => clearInterval(t)
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

  async function refreshConflicts() {
    try {
      const list = await fetchFeishuConflicts(true)
      setConflicts(list)
    } catch {
      // 忽略 — 没启用 / 网络问题时不刷
    }
  }

  async function handleResolveConflict(id: number, choice: 'local' | 'remote') {
    try {
      const r = await resolveFeishuConflict(id, choice)
      if (r.ok) {
        onToast('success', choice === 'local' ? '已回滚到本地版本' : '已接受飞书版本')
        await refreshConflicts()
      } else {
        onToast('error', r.message || '解决冲突失败')
      }
    } catch (e) {
      onToast('error', '解决冲突失败：' + (e instanceof Error ? e.message : String(e)))
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
      {/* 待解决冲突提示（仅在有冲突时显示） */}
      {conflicts.length > 0 && (
        <button
          onClick={() => setShowConflicts(true)}
          className="w-full text-left px-3 py-2 rounded-lg bg-amber-50 border border-amber-200 hover:bg-amber-100 text-xs text-amber-800 flex items-center gap-2"
        >
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          <span className="flex-1">
            ⚠ 飞书拉取时覆盖了 <strong>{conflicts.length}</strong> 条本地版本，点查看 / 回滚
          </span>
          <span className="text-amber-600">→</span>
        </button>
      )}

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
        hint="后台每 N 秒从飞书拉一次。最小 60，0 = 禁用定时只走手动。默认 300（5 分钟）。配了 webhook 后可以拉长到 30min 兜底"
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

      {/* Webhook URL 显示 + 复制 + 飞书 Automation 配置说明 */}
      <SettingsField
        label="实时反推 Webhook URL"
        hint="把这个 URL 配到飞书 Automation「发送 HTTP 请求」action，记录改动几秒内就同步回 Ydrop。请保密 — 包含 secret"
      >
        <div className="flex items-center gap-2">
          <input
            type="text"
            readOnly
            value={settings.webhook_url || '加载中...'}
            className="flex-1 text-xs font-mono border rounded-lg px-2 py-1.5 bg-gray-50"
          />
          <button
            onClick={() => {
              if (settings.webhook_url) {
                navigator.clipboard.writeText(settings.webhook_url)
                onToast('success', 'Webhook URL 已复制')
              }
            }}
            className="px-2 py-1 text-xs rounded-lg bg-gray-100 hover:bg-gray-200"
          >
            复制
          </button>
        </div>
        <details className="mt-2 text-xs text-gray-500">
          <summary className="cursor-pointer hover:text-emerald-600">飞书 Automation 配置示例（点开）</summary>
          <ol className="list-decimal list-inside space-y-1 mt-2 leading-relaxed">
            <li>飞书 Bitable 右上角「⚡ 自动化」→ 新建流程</li>
            <li>触发条件：「记录满足条件时」选「记录被新增 / 更新 / 删除」</li>
            <li>执行：「发送 HTTP 请求」</li>
            <li>请求方式选 POST，URL 粘上面那个</li>
            <li>请求体粘：<code className="bg-white px-1 rounded">{`{"record_id":"{{记录.记录ID}}"}`}</code>（精确单条，最快）</li>
            <li>请求头：<code className="bg-white px-1 rounded">Content-Type: application/json</code></li>
            <li>启用流程。改一条 record 测试，几秒内 Ydrop 这边应该看到变化</li>
          </ol>
        </details>
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

      {/* 冲突列表模态 */}
      {showConflicts && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
          onClick={() => setShowConflicts(false)}
        >
          <div
            className="bg-white rounded-2xl shadow-xl max-w-3xl w-full max-h-[80vh] overflow-hidden flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between p-4 border-b">
              <div className="text-base font-semibold">飞书覆盖冲突（{conflicts.length}）</div>
              <button
                onClick={() => setShowConflicts(false)}
                className="text-gray-400 hover:text-gray-700"
              >
                ✕
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-4 space-y-3 text-xs">
              {conflicts.length === 0 && (
                <div className="text-center text-gray-400 py-8">没有未解决的冲突</div>
              )}
              {conflicts.map((c) => {
                const tags = c.prev_tags_json ? JSON.parse(c.prev_tags_json) : []
                const isLikelyConflict = c.prev_updated_at && c.new_updated_at
                  && Math.abs(c.new_updated_at - c.prev_updated_at) < 60 * 60 * 1000
                return (
                  <div
                    key={c.id}
                    className={`rounded-lg border p-3 space-y-2 ${
                      isLikelyConflict ? 'border-amber-300 bg-amber-50' : 'border-gray-200 bg-gray-50'
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <div className="font-medium">
                        {c.note_title || c.prev_title || c.note_id.slice(0, 8)}
                        {isLikelyConflict && (
                          <span className="ml-2 text-amber-700">⚠ 可能是真冲突（双方近期都改过）</span>
                        )}
                      </div>
                      <div className="text-gray-400 text-[10px]">
                        {new Date(c.occurred_at).toLocaleString()}
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <div className="bg-white rounded px-2 py-1">
                        <div className="text-gray-500 mb-1">本地（覆盖前）</div>
                        <div className="font-medium truncate">{c.prev_title || '(无标题)'}</div>
                        <div className="text-gray-600 line-clamp-3 mt-1">{c.prev_content || ''}</div>
                        <div className="text-gray-400 mt-1 text-[10px]">
                          {c.prev_category} / {c.prev_priority}
                          {tags.length > 0 && ` · #${tags.join(' #')}`}
                        </div>
                      </div>
                      <div className="bg-white rounded px-2 py-1">
                        <div className="text-gray-500 mb-1">飞书（已覆盖）</div>
                        <div className="font-medium truncate">{c.new_title || c.note_title || '?'}</div>
                        <div className="text-gray-400 mt-1 text-[10px]">当前生效</div>
                      </div>
                    </div>
                    <div className="flex justify-end gap-2 pt-1">
                      <button
                        onClick={() => handleResolveConflict(c.id, 'remote')}
                        className="px-3 py-1 rounded-lg text-gray-600 hover:bg-gray-100"
                      >
                        接受飞书版本
                      </button>
                      <button
                        onClick={() => handleResolveConflict(c.id, 'local')}
                        className="px-3 py-1 rounded-lg bg-emerald-500 text-white hover:bg-emerald-600"
                      >
                        回滚到本地版本
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      )}
    </SettingsSection>
  )
}
