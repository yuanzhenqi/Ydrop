'use client'

import { useState, useRef, useEffect } from 'react'
import { aiChat } from '@/lib/api'
import type { ChatMessage } from '@/lib/api'
import { Send, Sparkles, User } from 'lucide-react'

export default function ChatPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [lastRefCount, setLastRefCount] = useState(0)
  const scrollRef = useRef<HTMLDivElement>(null)

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
    try {
      const res = await aiChat(newMessages)
      setMessages([...newMessages, { role: 'assistant', content: res.answer }])
      setLastRefCount(res.referenced_count)
    } catch (e) {
      setMessages([...newMessages, { role: 'assistant', content: `出错了：${e instanceof Error ? e.message : e}` }])
    } finally {
      setLoading(false)
    }
  }

  const quickPrompts = [
    '这周我记录了哪些待办？',
    '总结一下我最近的笔记',
    '我有哪些紧急任务？',
    '最近有哪些关于工作的笔记？',
  ]

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 h-screen flex flex-col">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <Sparkles className="w-5 h-5 text-purple-600" /> AI 问答
        </h1>
        {lastRefCount > 0 && (
          <span className="text-xs text-gray-400">上次引用了 {lastRefCount} 条笔记</span>
        )}
      </div>

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
  )
}
