'use client'

import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { CheckCircle2, XCircle, Info } from 'lucide-react'

export type ToastType = 'success' | 'error' | 'info'

interface ToastItem {
  id: number
  type: ToastType
  message: string
  action?: { label: string; onClick: () => void }
  exiting?: boolean
}

interface ToastContextValue {
  toast: (type: ToastType, message: string, action?: ToastItem['action']) => void
}

const ToastContext = createContext<ToastContextValue | null>(null)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])

  const toast = useCallback<ToastContextValue['toast']>((type, message, action) => {
    const id = Date.now() + Math.random()
    setItems((prev) => [...prev, { id, type, message, action }])
    // 4 秒后启动退出动画
    setTimeout(() => {
      setItems((prev) => prev.map((i) => (i.id === id ? { ...i, exiting: true } : i)))
    }, 4000)
    // 4.25 秒后移除
    setTimeout(() => {
      setItems((prev) => prev.filter((i) => i.id !== id))
    }, 4250)
  }, [])

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div className="fixed top-4 left-1/2 -translate-x-1/2 z-50 space-y-2 pointer-events-none">
        {items.map((i) => (
          <ToastCard key={i.id} item={i} />
        ))}
      </div>
    </ToastContext.Provider>
  )
}

function ToastCard({ item }: { item: ToastItem }) {
  const colors: Record<ToastType, string> = {
    success: 'bg-emerald-600 text-white',
    error: 'bg-red-600 text-white',
    info: 'bg-gray-800 text-white',
  }
  const Icon = item.type === 'success' ? CheckCircle2 : item.type === 'error' ? XCircle : Info
  return (
    <div
      className={`flex items-center gap-2 min-w-[240px] max-w-md px-4 py-2.5 rounded-xl shadow-lg pointer-events-auto ${colors[item.type]} ${
        item.exiting ? 'animate-toast-out' : 'animate-toast-in'
      }`}
    >
      <Icon className="w-4 h-4 flex-shrink-0" />
      <span className="flex-1 text-sm">{item.message}</span>
      {item.action && (
        <button
          onClick={item.action.onClick}
          className="text-sm font-medium px-2 py-0.5 rounded bg-white/20 hover:bg-white/30"
        >
          {item.action.label}
        </button>
      )}
    </div>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be inside ToastProvider')
  return ctx
}
