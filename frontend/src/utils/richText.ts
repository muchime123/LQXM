import type { SolutionContentSection } from '@/types/website'

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function stripTags(html: string): string {
  if (typeof document === 'undefined') return html
  const div = document.createElement('div')
  div.innerHTML = html
  return (div.textContent || '').replace(/\s+/g, ' ').trim()
}

/** 将旧的"结构化小节"数据转成富文本 HTML，用于老文章编辑预填与阅读端兼容渲染。 */
export function sectionsToHtml(sections: SolutionContentSection[] | null | undefined): string {
  if (!sections?.length) return ''
  return sections
    .map((section) => {
      const parts: string[] = []
      if (section.title?.trim()) parts.push(`<h2>${escapeHtml(section.title.trim())}</h2>`)
      if (section.imageUrl?.trim()) {
        parts.push(`<p><img src="${escapeHtml(section.imageUrl.trim())}" alt="${escapeHtml(section.title?.trim() || '')}" /></p>`)
      }
      if (section.body?.trim()) parts.push(`<p>${escapeHtml(section.body.trim())}</p>`)
      if (section.points?.length) {
        const points = section.points.map((point) => `<li>${escapeHtml(point.trim())}</li>`).filter((li) => li.length > 5)
        if (points.length) parts.push(`<ul>${points.join('')}</ul>`)
      }
      return parts.join('')
    })
    .join('')
}

/** 从富文本 HTML 提取 h2/h3 标题作为预览条目（搜索展示用）。 */
export function htmlToPreviewItems(html: string): Array<{ title: string; description: string; image: string }> {
  const items: Array<{ title: string; description: string; image: string }> = []
  if (!html) return items
  const regex = /<h[23][^>]*>([\s\S]*?)<\/h[23]>/g
  let match: RegExpExecArray | null
  while ((match = regex.exec(html)) !== null) {
    const title = stripTags(match[1])
    if (title) items.push({ title, description: '', image: '' })
  }
  return items
}
