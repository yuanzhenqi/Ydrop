'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { Inbox, Archive, Trash2, Calendar, Settings, Zap, Sparkles, FolderCog } from 'lucide-react'

const NAV = [
  { href: '/inbox', label: '收件箱', icon: Inbox },
  { href: '/chat', label: 'AI 问答', icon: Sparkles },
  { href: '/organize', label: '批量整理', icon: FolderCog },
  { href: '/calendar', label: '日历', icon: Calendar },
  { href: '/archive', label: '归档', icon: Archive },
  { href: '/trash', label: '回收站', icon: Trash2 },
  { href: '/settings', label: '设置', icon: Settings },
]

export function Sidebar() {
  const pathname = usePathname()

  return (
    <aside className="w-56 bg-white border-r border-gray-200 flex flex-col">
      <div className="px-5 py-4 flex items-center gap-2">
        <Zap className="w-5 h-5 text-emerald-600" />
        <span className="font-bold text-lg">Ydrop</span>
      </div>
      <nav className="flex-1 px-3 space-y-1">
        {NAV.map(({ href, label, icon: Icon }) => {
          const active = pathname === href || pathname.startsWith(href + '/')
          return (
            <Link
              key={href}
              href={href}
              className={`relative flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-all active:scale-[0.98] ${
                active
                  ? 'bg-emerald-50 text-emerald-700 font-medium'
                  : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              {active && (
                <span className="absolute left-0 top-1.5 bottom-1.5 w-0.5 rounded-full bg-emerald-600 animate-fade-in" />
              )}
              <Icon className="w-4 h-4" />
              {label}
            </Link>
          )
        })}
      </nav>
      <div className="px-5 py-3 text-xs text-gray-400">Ydrop Web v0.1</div>
    </aside>
  )
}
