/** URL 抽取 + 归一化（对齐 Android LinkPreviewWorker.canonicalizeUrl + extractUrls）。
 * 主要用于 web 端做 URL 比对 / 跳转前清理；服务端笔记保存后自己跑一遍 extract+canonicalize+fetch，
 * 这里的实现仅给前端独立场景使用（如复制链接前清掉追踪参数）。 */

const URL_REGEX = /https?:\/\/[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+/gi
const TRAILING_PUNCT = ['.', ',', ';', ':', '，', '。', '、', '；', '：', '）', ')', ']', '」', '』', '"', "'"]

const TRACKING_PARAM_PREFIXES = [
  'utm_', 'spm', 'share', 'from', 'ref', 'ref_', 'scene', 'src', 'sourceid',
  'session_id', 'weibo_id', '_t', 'fr',
]

export function extractUrls(text: string): string[] {
  if (!text) return []
  const out: string[] = []
  // 用 Array.from 兼容 ES5 target，避免 --downlevelIteration 标志
  const matches = Array.from(text.matchAll(URL_REGEX))
  for (const m of matches) {
    let url = m[0]
    while (url.length > 0 && TRAILING_PUNCT.includes(url[url.length - 1])) {
      url = url.slice(0, -1)
    }
    if (url.length > 8) out.push(url)
  }
  return out
}

export function canonicalizeUrl(raw: string): string {
  try {
    const u = new URL(raw)
    const scheme = u.protocol.replace(':', '').toLowerCase()
    const host = u.hostname.toLowerCase()
    const port = u.port ? `:${u.port}` : ''
    let path = u.pathname || ''
    if (path.length > 1 && path.endsWith('/')) path = path.replace(/\/+$/, '')
    const search = new URLSearchParams(u.search.replace(/^\?/, ''))
    const cleaned: string[] = []
    Array.from(search.entries()).forEach(([k, v]) => {
      const key = k.toLowerCase()
      if (!key) return
      if (TRACKING_PARAM_PREFIXES.some((p) => key === p || key.startsWith(p))) return
      cleaned.push(v ? `${k}=${v}` : k)
    })
    const query = cleaned.length ? `?${cleaned.join('&')}` : ''
    const frag = u.hash || ''
    return `${scheme}://${host}${port}${path}${query}${frag}`
  } catch {
    return raw
  }
}
