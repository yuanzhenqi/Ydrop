'use client'

import { useState, useRef, useEffect, useCallback } from 'react'
import {
  aiChat,
  fetchChatSessions,
  fetchChatSession,
  deleteChatSession,
} from '@/lib/api'
import type { ChatMessage, ChatSessionSummary } from '@/lib/api'
import { formatTime } from '@/lib/date'
import { Send, Sparkles, User, Plus, MessageSquare, Trash2 } from 'lucide-react'

export default function ChatPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [providerWarning, setProviderWarning] = useState<string | null>(null)
  const [usedProvider, setUsedProvider] = useState(false)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [sessions, setSessions] = useState<ChatSessionSummary[]>([])
  const scrollRef = useRef<HTMLDivElement>(null)

  const loadSessions = useCallback(async () => {
    try {
      setSessions(await fetchChatSessions())
    } catch {}
  }, [])

  useEffect(() => {
    loadSessions()
  }, [loadSessions])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages])

  const handleSend = async () => {
    if (!input.trim() || loading) return
    const userMsg: ChatMessage = { role: 'user', content: input.trim() }
    const newMessages = [...messages, userMsg]
    setMessages(newMessages)
    setInput('')
    setLoading(true)
    setProviderWarning(null)
    try {
      const res = await aiChat(newMessages, undefined, sessionId)
      setMessages([...newMessages, { role: 'assistant', content: res.answer }])
      setUsedProvider(!!res.used_provider)
      if (res.provider_error) setProviderWarning(res.provider_error)
      if (res.session_id) setSessionId(res.session_id)
      await loadSessions()
    } catch (e) {
      setMessages([...newMessages, { role: 'assistant', content: `出错了：${e instanceof Error ? e.message : e}` }])
    } finally {
      setLoading(false)
    }
  }

  const handleNewSession = () => {
    setMessages([])
    setSessionId(null)
    setProviderWarning(null)
    setUsedProvider(false)
  }

  const handleLoadSession = async (id: string) => {
    try {
      const s = await fetchChatSession(id)
      setSessionId(id)
      setMessages(s.messages.map((m) => ({ role: m.role as 'user' | 'assistant', content: m.content })))
      setProviderWarning(null)
      setUsedProvider(s.messages.some((m) => m.used_provider))
    } catch (e) {
      console.error(e)
    }
  }

  const handleDeleteSession = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation()
    if (!confirm('删除这个会话？')) return
    await deleteChatSession(id)
    if (id === sessionId) handleNewSession()
    await loadSessions()
  }

  const quickPrompts = [
    '这周我记录了哪些待办？',
    '总结一下我最近的笔记',
    '我有哪些紧急任务？',
    '最近有哪些关于工作的笔记？',
  ]

  return (
    <div className="max-w-[1200px] mx-auto px-4 py-6 h-screen flex flex-col">
      <div className="grid grid-cols-1 md:grid-cols-[260px_1fr] gap-4 flex-1 min-h-0">
        {/* 左侧：历史会话 */}
        <aside className="hidden md:flex flex-col gap-2 min-h-0">
          <button
            onClick={handleNewSession}
            className="flex items-center justify-center gap-1.5 py-2 bg-emerald-600 text-white text-sm rounded-lg hover:bg-emerald-700"
          >
            <Plus className="w-3.5 h-3.5" /> 新会话
          </button>
          <div className="flex-1 overflow-y-auto space-y-1 min-h-0">
            {sessions.length === 0 ? (
              <p className="text-xs text-gray-400 text-center py-4">暂无历史会话</p>
            ) : (
              sessions.map((s) => {
                const active = s.id === sessionId
                return (
                  <button
                    key={s.id}
                    onClick={() => handleLoadSession(s.id)}
                    className={`w-full text-left px-3 py-2 rounded-lg text-sm group transition-colors ${
                      active ? 'bg-emerald-50 text-emerald-700' : 'hover:bg-gray-50 text-gray-700'
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      <MessageSquare className="w-3.5 h-3.5 flex-shrink-0" />
                      <span className="flex-1 truncate text-xs">{s.title || '（无标题）'}</span>
                      <button
                        onClick={(e) => handleDeleteSession(s.id, e)}
                        className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-500 flex-shrink-0"
                      >
                        <Trash2 className="w-3 h-3" />
                      </button>
                    </div>
                    <div className="text-[10px] text-gray-400 mt-0.5 pl-5">
                      {formatTime(s.updated_at)} · {s.message_count} 条
                    </div>
                  </button>
                )
              })
            )}
          </div>
        </aside>

        {/* 右侧：对话区 */}
        <div className="flex flex-col min-h-0">
          <div className="flex items-center justify-between mb-4">
            <h1 className="text-xl font-bold flex items-center gap-2">
              <Sparkles className="w-5 h-5 text-purple-600" /> AI 问答
              {usedProvider && (
                <span className="text-xs font-normal px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-600">
                  LLM 模式
                </span>
              )}
            </h1>
          </div>

          {providerWarning && (
            <div className="mb-3 rounded-lg px-3 py-2 text-xs bg-amber-50 text-amber-800 border border-amber-200">
              <div className="font-medium mb-0.5">AI Provider 调用失败，已回退到启发式匹配</div>
              <div className="text-amber-700 font-mono break-all">{providerWarning}</div>
              <div className="mt-1 text-amber-600">
                请检查
                <a href="/settings" className="underline ml-1">AI 配置</a>
              </div>
            </div>
          )}

          <div ref={scrollRef} className="flex-1 overflow-y-auto space-y-3 mb-4">
            {messages.length === 0 ? (
              <div className="text-center py-12 space-y-4">
                <p className="text-gray-400">基于你的笔记问任何问题</p>
                <div className="flex flex-wrap justify-center gap-2">
                  {quickPrompts.map((p) => (
                    <button
                      key={p}
                      onClick={() => setInput(p)}
                      className="text-xs px-3 py-1.5 rounded-full bg-gray-100 hover:bg-gray-200 text-gray-700"
                    >
                      {p}
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              messages.map((m, i) => (
                <div key={i} className={`flex gap-2 ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                  {m.role === 'assistant' && (
                    <div className="w-7 h-7 rounded-full bg-purple-100 flex items-center justify-center flex-shrink-0">
                      <Sparkles className="w-4 h-4 text-purple-600" />
                    </div>
                  )}
                  <div
                    className={`max-w-[80%] rounded-2xl px-4 py-2.5 text-sm whitespace-pre-wrap ${
                      m.role === 'user' ? 'bg-emerald-600 text-white' : 'bg-white border text-gray-800'
                    }`}
                  >
                    {m.content}
                  </div>
                  {m.role === 'user' && (
                    <div className="w-7 h-7 rounded-full bg-gray-100 flex items-center justify-center flex-shrink-0">
                      <User className="w-4 h-4 text-gray-600" />
                    </div>
                  )}
                </div>
              ))
            )}
            {loading && (
              <div className="flex gap-2">
                <div className="w-7 h-7 rounded-full bg-purple-100 flex items-center justify-center">
                  <Sparkles className="w-4 h-4 text-purple-600 animate-pulse" />
                </div>
                <div className="bg-white border rounded-2xl px-4 py-2.5 text-sm text-gray-400">思考中...</div>
              </div>
            )}
          </div>

          <div className="flex gap-2 items-end">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="提问..."
              rows={1}
              className="flex-1 resize-none rounded-2xl border px-4 py-2.5 text-sm outline-none focus:border-emerald-400"
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  handleSend()
                }
              }}
            />
            <button
              onClick={handleSend}
              disabled={!input.trim() || loading}
              className="p-3 rounded-2xl bg-emerald-600 text-white disabled:opacity-40 hover:bg-emerald-700"
            >
              <Send className="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
