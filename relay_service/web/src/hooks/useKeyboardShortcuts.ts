'use client'
import { useEffect } from 'react'

export interface Shortcut {
  key: string
  handler: () => void
  meta?: boolean  // require cmd/ctrl
  shift?: boolean
  description?: string
}

/** 全局键盘快捷键。focused 的输入框/textarea 不会触发（除非显式 meta）。 */
export function useKeyboardShortcuts(shortcuts: Shortcut[]) {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // Don't trigger in input/textarea unless meta-modified
      const tag = (e.target as HTMLElement)?.tagName
      const inEditable = tag === 'INPUT' || tag === 'TEXTAREA' || (e.target as HTMLElement)?.isContentEditable
      for (const s of shortcuts) {
        if (e.key !== s.key) continue
        if (s.meta && !(e.metaKey || e.ctrlKey)) continue
        if (s.shift && !e.shiftKey) continue
        if (!s.meta && inEditable) continue
        e.preventDefault()
        s.handler()
        return
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [shortcuts])
}
