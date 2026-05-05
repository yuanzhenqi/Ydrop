'use client'

import { useState, useRef } from 'react'
import type { Attachment } from '@/lib/types'
import { Plus, X, Loader2, AlertCircle, Info } from 'lucide-react'

interface Props {
  attachments: Attachment[]
  /** 上传新附件回调；返回新 attachment 列表用于父组件 PUT 笔记。如果不提供，UI 隐藏「+ 加图」按钮（只读）。*/
  onUpload?: (file: File) => Promise<void>
  /** 删除单个附件回调；不提供则隐藏删除 X 按钮 */
  onRemove?: (attachmentId: string) => Promise<void>
}

/** 横滚缩略图行 + 上传按钮（A 方案：Vision AI 单通道，无本地 OCR）。
 * 点缩略图弹大图预览；点 i 按钮展开 description / keywords / actionable_items。
 */
export function NoteAttachmentRow({ attachments, onUpload, onRemove }: Props) {
  const [previewing, setPreviewing] = useState<Attachment | null>(null)
  const [showInfo, setShowInfo] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const inputRef = useRef<HTMLInputElement | null>(null)

  if (!attachments.length && !onUpload) return null

  const handleFile = async (file: File) => {
    if (!onUpload) return
    setUploading(true)
    try {
      await onUpload(file)
    } finally {
      setUploading(false)
      if (inputRef.current) inputRef.current.value = ''
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex gap-2 overflow-x-auto pb-1 -mx-1 px-1">
        {attachments.map((att) => (
          <div key={att.id} className="relative flex-shrink-0">
            <button
              onClick={() => setPreviewing(att)}
              className="block w-20 h-20 rounded-lg overflow-hidden bg-gray-100 border border-gray-200 hover:border-gray-300"
              title={att.description || '查看大图'}
            >
              {att.remote_url ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={att.remote_url}
                  alt=""
                  className="w-full h-full object-cover"
                  onError={(e) => {
                    ;(e.target as HTMLImageElement).style.display = 'none'
                  }}
                />
              ) : (
                <div className="w-full h-full flex items-center justify-center text-gray-400">
                  <AlertCircle className="w-5 h-5" />
                </div>
              )}
            </button>
            {att.error && (
              <div
                className="absolute top-1 left-1 bg-amber-100 text-amber-700 rounded-full p-0.5"
                title={`分析失败：${att.error}`}
              >
                <AlertCircle className="w-3 h-3" />
              </div>
            )}
            {(att.description || att.keywords.length > 0 || att.actionable_items.length > 0) && (
              <button
                onClick={() => setShowInfo(showInfo === att.id ? null : att.id)}
                className={`absolute bottom-1 left-1 rounded-full p-0.5 transition-colors ${
                  showInfo === att.id ? 'bg-emerald-500 text-white' : 'bg-white/90 text-gray-600 hover:bg-white'
                }`}
                title="查看 AI 描述"
              >
                <Info className="w-3 h-3" />
              </button>
            )}
            {onRemove && (
              <button
                onClick={(e) => {
                  e.stopPropagation()
                  onRemove(att.id)
                }}
                className="absolute -top-1 -right-1 bg-red-500 text-white rounded-full p-0.5 hover:bg-red-600 shadow-sm"
                title="删除"
              >
                <X className="w-3 h-3" />
              </button>
            )}
          </div>
        ))}
        {onUpload && (
          <button
            onClick={() => inputRef.current?.click()}
            disabled={uploading}
            className="flex-shrink-0 w-20 h-20 rounded-lg border-2 border-dashed border-gray-300 flex items-center justify-center text-gray-400 hover:border-emerald-400 hover:text-emerald-500 transition-colors disabled:opacity-50"
            title="上传图片"
          >
            {uploading ? <Loader2 className="w-5 h-5 animate-spin" /> : <Plus className="w-5 h-5" />}
          </button>
        )}
        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          className="hidden"
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) handleFile(f)
          }}
        />
      </div>

      {/* AI 描述展开面板 */}
      {showInfo &&
        (() => {
          const att = attachments.find((a) => a.id === showInfo)
          if (!att) return null
          return (
            <div className="rounded-lg bg-emerald-50 border border-emerald-100 p-3 text-xs space-y-1.5">
              {att.description && (
                <div>
                  <span className="font-semibold text-emerald-700">AI 描述：</span>
                  <span className="text-gray-700">{att.description}</span>
                </div>
              )}
              {att.keywords.length > 0 && (
                <div className="flex flex-wrap items-center gap-1">
                  <span className="font-semibold text-emerald-700">关键词：</span>
                  {att.keywords.map((kw) => (
                    <span key={kw} className="px-1.5 py-0.5 rounded-full bg-white text-emerald-700 border border-emerald-200">
                      {kw}
                    </span>
                  ))}
                </div>
              )}
              {att.actionable_items.length > 0 && (
                <div>
                  <span className="font-semibold text-emerald-700">待办：</span>
                  <ul className="list-disc list-inside text-gray-700">
                    {att.actionable_items.map((it, i) => (
                      <li key={i}>{it}</li>
                    ))}
                  </ul>
                </div>
              )}
              {att.dates.length > 0 && (
                <div>
                  <span className="font-semibold text-emerald-700">日期：</span>
                  <span className="text-gray-700">{att.dates.join('、')}</span>
                </div>
              )}
            </div>
          )
        })()}

      {/* 大图预览模态 */}
      {previewing && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4"
          onClick={() => setPreviewing(null)}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={previewing.remote_url}
            alt=""
            className="max-w-full max-h-full object-contain"
            onClick={(e) => e.stopPropagation()}
          />
          <button
            onClick={() => setPreviewing(null)}
            className="absolute top-4 right-4 bg-white/20 hover:bg-white/30 text-white rounded-full p-2"
          >
            <X className="w-5 h-5" />
          </button>
        </div>
      )}
    </div>
  )
}
