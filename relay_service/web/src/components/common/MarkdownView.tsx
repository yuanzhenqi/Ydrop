'use client'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

export function MarkdownView({ content }: { content: string }) {
  return (
    <div className="prose prose-sm max-w-none text-gray-800">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: ({ children }) => <h1 className="text-lg font-bold mt-3 mb-2">{children}</h1>,
          h2: ({ children }) => <h2 className="text-base font-semibold mt-3 mb-1">{children}</h2>,
          h3: ({ children }) => <h3 className="text-sm font-semibold mt-2 mb-1">{children}</h3>,
          p: ({ children }) => <p className="mb-2 leading-relaxed">{children}</p>,
          ul: ({ children }) => <ul className="list-disc list-inside mb-2 space-y-0.5">{children}</ul>,
          ol: ({ children }) => <ol className="list-decimal list-inside mb-2 space-y-0.5">{children}</ol>,
          li: ({ children }) => <li>{children}</li>,
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noopener noreferrer" className="text-emerald-600 hover:underline">
              {children}
            </a>
          ),
          code: ({ children }) => (
            <code className="bg-gray-100 px-1 py-0.5 rounded text-xs font-mono">{children}</code>
          ),
          pre: ({ children }) => <pre className="bg-gray-100 p-2 rounded overflow-x-auto text-xs">{children}</pre>,
          blockquote: ({ children }) => (
            <blockquote className="border-l-4 border-gray-200 pl-3 italic text-gray-600 my-2">{children}</blockquote>
          ),
          hr: () => <hr className="my-3 border-gray-200" />,
          input: ({ type, checked, disabled }) =>
            type === 'checkbox' ? (
              <input type="checkbox" checked={!!checked} disabled={disabled} className="mr-1.5 align-middle" readOnly />
            ) : null,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
