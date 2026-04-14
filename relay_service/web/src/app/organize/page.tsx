'use client'

import { useCallback, useEffect, useState } from 'react'
import {
  aiBatchOrganize,
  fetchNote,
  updateNote,
  fetchOrganizeRuns,
  fetchOrganizeRun,
  deleteOrganizeRun,
} from '@/lib/api'
import type { ClusterSuggestion, OrganizeRunSummary } from '@/lib/api'
import { formatTime } from '@/lib/date'
import { FolderCog, Sparkles, Check, X, Merge, ArrowRight, History, Trash2, ChevronDown, ChevronRight } from 'lucide-react'

export default function OrganizePage() {
  const [clusters, setClusters] = useState<ClusterSuggestion[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [applied, setApplied] = useState<Set<string>>(new Set())
  const [runs, setRuns] = useState<OrganizeRunSummary[]>([])
  const [showHistory, setShowHistory] = useState(false)

  const loadRuns = useCallback(async () => {
    try {
      setRuns(await fetchOrganizeRuns())
    } catch {}
  }, [])

  useEffect(() => {
    loadRuns()
  }, [loadRuns])

  const handleAnalyze = async () => {
    setLoading(true)
    try {
      const res = await aiBatchOrganize()
      setClusters(res.clusters)
      setTotal(res.total_analyzed)
      setApplied(new Set())
      await loadRuns()
    } finally {
      setLoading(false)
    }
  }

  const handleLoadRun = async (id: string) => {
    const r = await fetchOrganizeRun(id)
    setClusters(r.clusters)
    setTotal(r.total_analyzed)
    setApplied(new Set(r.applied_cluster_ids))
  }

  const handleDeleteRun = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation()
    if (!confirm('删除这条历史记录？')) return
    await deleteOrganizeRun(id)
    await loadRuns()
  }

  const handleApply = async (cluster: ClusterSuggestion) => {
    if (cluster.suggested_action === 'convert_to_task') {
      await Promise.all(
        cluster.note_ids.map((id) => updateNote(id, { category: 'TASK' }))
      )
    } else if (cluster.suggested_action === 'merge' && cluster.suggested_title) {
      const notes = await Promise.all(cluster.note_ids.map(fetchNote))
      const merged = notes.map((n) => `## ${n.title}\n${n.content}`).join('\n\n---\n\n')
      await updateNote(cluster.note_ids[0], {
        title: cluster.suggested_title,
        content: merged,
      })
    }
    setApplied((prev) => { const next = new Set(prev); next.add(cluster.cluster_id); return next })
  }

  const actionLabels: Record<string, string> = {
    merge: '合并',
    convert_to_task: '转任务',
    keep: '保持独立',
  }
  const actionColors: Record<string, string> = {
    merge: 'bg-purple-50 text-purple-700',
    convert_to_task: 'bg-sky-50 text-sky-700',
    keep: 'bg-gray-50 text-gray-600',
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <FolderCog className="w-5 h-5" /> 批量整理
        </h1>
        <button
          onClick={handleAnalyze}
          disabled={loading}
          className="flex items-center gap-1.5 px-4 py-1.5 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700 disabled:opacity-50"
        >
          <Sparkles className={`w-3.5 h-3.5 ${loading ? 'animate-pulse' : ''}`} />
          {loading ? '分析中...' : 'AI 分析我的笔记'}
        </button>
      </div>

      {total > 0 && (
        <p className="text-sm text-gray-500">分析了 {total} 条收件箱笔记，识别出 {clusters.length} 组聚类</p>
      )}

      {/* 历史折叠面板 */}
      <div className="bg-white rounded-2xl border">
        <button
          onClick={() => setShowHistory((s) => !s)}
          className="w-full flex items-center gap-2 px-4 py-3 text-sm text-gray-700 hover:bg-gray-50"
        >
          {showHistory ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
          <History className="w-4 h-4" />
          历史整理记录
          <span className="ml-auto text-xs text-gray-400">{runs.length} 条</span>
        </button>
        {showHistory && (
          <div className="border-t px-2 py-2 space-y-1 max-h-60 overflow-y-auto">
            {runs.length === 0 ? (
              <p className="text-xs text-gray-400 text-center py-3">暂无历史</p>
            ) : (
              runs.map((r) => (
                <div
                  key={r.id}
                  onClick={() => handleLoadRun(r.id)}
                  className="flex items-center gap-2 px-3 py-2 rounded-lg hover:bg-gray-50 cursor-pointer text-sm group"
                >
                  <div className="flex-1">
                    <div className="text-xs text-gray-400">{formatTime(r.created_at)}</div>
                    <div className="text-sm text-gray-700">
                      分析 {r.total_analyzed} 条 · {r.cluster_count} 组聚类
                    </div>
                  </div>
                  <button
                    onClick={(e) => handleDeleteRun(r.id, e)}
                    className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-500"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              ))
            )}
          </div>
        )}
      </div>

      {clusters.length === 0 && !loading && (
        <div className="text-center py-16 text-gray-400">
          点击右上角按钮让 AI 分析你的笔记并给出整理建议
        </div>
      )}

      <div className="space-y-3">
        {clusters.map((c) => {
          const isApplied = applied.has(c.cluster_id)
          return (
            <div key={c.cluster_id} className="bg-white rounded-2xl border p-4 space-y-3">
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1">
                  <h3 className="font-semibold text-sm flex items-center gap-2">
                    {c.suggested_action === 'merge' && <Merge className="w-4 h-4 text-purple-600" />}
                    {c.suggested_action === 'convert_to_task' && <ArrowRight className="w-4 h-4 text-sky-600" />}
                    {c.theme}
                  </h3>
                  <p className="text-xs text-gray-500 mt-1">{c.reason}</p>
                </div>
                <span className={`text-xs px-2 py-1 rounded-full whitespace-nowrap ${actionColors[c.suggested_action]}`}>
                  {actionLabels[c.suggested_action]}
                </span>
              </div>

              {c.suggested_title && (
                <p className="text-sm text-gray-700 border-l-2 border-purple-300 pl-3">
                  建议标题：<span className="font-medium">{c.suggested_title}</span>
                </p>
              )}

              <p className="text-xs text-gray-400">涉及 {c.note_ids.length} 条笔记</p>

              {c.suggested_action !== 'keep' && (
                <div className="flex items-center gap-2 pt-2 border-t">
                  {isApplied ? (
                    <span className="text-xs text-emerald-600 flex items-center gap-1">
                      <Check className="w-3.5 h-3.5" /> 已应用
                    </span>
                  ) : (
                    <>
                      <button
                        onClick={() => handleApply(c)}
                        className="flex items-center gap-1.5 px-3 py-1 bg-emerald-600 text-white text-xs rounded-lg hover:bg-emerald-700"
                      >
                        <Check className="w-3 h-3" /> 应用
                      </button>
                      <button
                        onClick={() => setApplied((prev) => { const n = new Set(prev); n.add(c.cluster_id); return n })}
                        className="flex items-center gap-1.5 px-3 py-1 bg-gray-100 text-gray-600 text-xs rounded-lg hover:bg-gray-200"
                      >
                        <X className="w-3 h-3" /> 忽略
                      </button>
                    </>
                  )}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
