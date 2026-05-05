'use client'

import type { LinkPreview } from '@/lib/types'
import { ExternalLink, AlertCircle } from 'lucide-react'

interface Props {
  preview: LinkPreview
}

/** 链接预览卡（对齐 Android v1.1.0 J Phase 1 LinkPreviewCard.kt 渲染逻辑）。
 * - 有 og:image 优先显示左侧缩略图 + 文字；
 * - 没图就纯文字；
 * - error 态降级成 chip 样式但仍可点开浏览器；
 * - title / summary / description 三层 fallback。
 */
export function LinkPreviewCard({ preview }: Props) {
  const hostname = (() => {
    try {
      return new URL(preview.url).hostname
    } catch {
      return preview.site_name || preview.url
    }
  })()

  if (preview.error) {
    return (
      <a
        href={preview.url}
        target="_blank"
        rel="noopener noreferrer"
        onClick={(e) => e.stopPropagation()}
        className="flex items-center gap-2 px-3 py-2 rounded-lg border border-amber-200 bg-amber-50 text-xs text-amber-700 hover:bg-amber-100 transition-colors"
        title={preview.error}
      >
        <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" />
        <span className="flex-1 truncate">{preview.url}</span>
        <ExternalLink className="w-3 h-3 flex-shrink-0" />
      </a>
    )
  }

  const title = preview.title || hostname
  const desc = preview.summary || preview.description || ''

  return (
    <a
      href={preview.url}
      target="_blank"
      rel="noopener noreferrer"
      onClick={(e) => e.stopPropagation()}
      className="flex gap-3 p-3 rounded-lg border border-gray-200 bg-gray-50/60 hover:bg-gray-100/80 hover:border-gray-300 transition-colors"
    >
      {preview.image_url && (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={preview.image_url}
          alt=""
          className="w-16 h-16 rounded object-cover flex-shrink-0 bg-gray-200"
          onError={(e) => {
            ;(e.target as HTMLImageElement).style.display = 'none'
          }}
        />
      )}
      <div className="flex-1 min-w-0 space-y-0.5">
        <div className="flex items-center gap-1.5 text-[11px] text-gray-500">
          <span className="truncate">{preview.site_name || hostname}</span>
          <ExternalLink className="w-3 h-3 flex-shrink-0" />
        </div>
        <div className="text-sm font-medium text-gray-900 line-clamp-1">{title}</div>
        {desc && <p className="text-xs text-gray-600 line-clamp-2 leading-relaxed">{desc}</p>}
      </div>
    </a>
  )
}
