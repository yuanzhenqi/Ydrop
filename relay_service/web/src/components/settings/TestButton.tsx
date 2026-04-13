'use client'

import { useState } from 'react'
import { CheckCircle2, XCircle, Loader2, PlugZap } from 'lucide-react'
import type { TestResult } from '@/lib/types'

interface TestButtonProps {
  onTest: () => Promise<TestResult>
  label?: string
}

export function TestButton({ onTest, label = '测试连接' }: TestButtonProps) {
  const [state, setState] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')
  const [message, setMessage] = useState('')

  const handleClick = async () => {
    setState('loading')
    setMessage('')
    try {
      const r = await onTest()
      setState(r.ok ? 'success' : 'error')
      setMessage(r.message)
    } catch (e) {
      setState('error')
      setMessage(e instanceof Error ? e.message : String(e))
    }
    setTimeout(() => {
      if (state !== 'loading') setState('idle')
    }, 5000)
  }

  return (
    <div className="flex items-center gap-2">
      <button
        onClick={handleClick}
        disabled={state === 'loading'}
        className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-50 text-blue-700 text-sm rounded-lg hover:bg-blue-100 disabled:opacity-50"
      >
        {state === 'loading' ? (
          <Loader2 className="w-3.5 h-3.5 animate-spin" />
        ) : state === 'success' ? (
          <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600" />
        ) : state === 'error' ? (
          <XCircle className="w-3.5 h-3.5 text-red-500" />
        ) : (
          <PlugZap className="w-3.5 h-3.5" />
        )}
        {label}
      </button>
      {message && (
        <span className={`text-xs ${state === 'error' ? 'text-red-600' : 'text-emerald-600'}`}>
          {message}
        </span>
      )}
    </div>
  )
}
