function escapeHtml(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/* 旧数据正文可能用空行分段、单换行断行；转成多个 <p> 而不是压成一段 */
function bodyToHtml(body) {
  const text = String(body == null ? '' : body).replace(/\r\n?/g, '\n').trim()
  if (!text) return ''
  return text
    .split(/\n{2,}/)
    .map((paragraph) => paragraph.trim())
    .filter(Boolean)
    .map((paragraph) => `<p>${escapeHtml(paragraph).replace(/\n/g, '<br />')}</p>`)
    .join('')
}

/** 将旧的"结构化小节"数据转成富文本 HTML，用于老文章阅读端兼容渲染。 */
export function sectionsToHtml(sections) {
  if (!Array.isArray(sections) || sections.length === 0) return ''
  return sections
    .map((section) => {
      const parts = []
      if (section.title && String(section.title).trim()) parts.push(`<h2>${escapeHtml(section.title.trim())}</h2>`)
      if (section.imageUrl && String(section.imageUrl).trim()) {
        parts.push(`<p><img src="${escapeHtml(section.imageUrl.trim())}" alt="${escapeHtml(section.title ? section.title.trim() : '')}" /></p>`)
      }
      if (section.body && String(section.body).trim()) parts.push(bodyToHtml(section.body))
      if (Array.isArray(section.points) && section.points.length) {
        const points = section.points
          .map((point) => {
            const text = String(point == null ? '' : point).trim()
            return text ? `<li>${escapeHtml(text)}</li>` : ''
          })
          .filter(Boolean)
        if (points.length) parts.push(`<ul>${points.join('')}</ul>`)
      }
      return parts.join('')
    })
    .join('')
}