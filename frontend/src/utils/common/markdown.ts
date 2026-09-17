/**
 * Markdown 转 HTML 工具
 * 通用 Markdown 渲染与安全处理工具。
 */

interface MarkdownOptions {
  emptyText?: string
}

/**
 * 将简易 Markdown 转换为 HTML（支持标题、加粗、列表、表格、段落）
 * @param markdown - Markdown 文本
 * @param options - 选项
 * @param options.emptyText - 为空时显示的文本，默认 '暂无预览'
 * @returns HTML 字符串
 */
export function markdownToHtml(markdown: string = '', options: MarkdownOptions = {}): string {
  const { emptyText = '暂无预览' } = options
  if (!markdown) return `<p class="empty-line">${emptyText}</p>`

  const rows = markdown.split('\n')
  let html = ''
  let listOpen = false
  let table: string[] = []
  let inTable = false

  function flushTable(): void {
    if (table.length === 0) return
    html += '<table>\n'
    const [hdr, sep, ...body] = table
    if (hdr && sep && /^[\s|:-]+$/.test(sep)) {
      html += '<thead><tr>\n'
      hdr
        .split('|')
        .filter(Boolean)
        .forEach(h => {
          html += `  <th>${escapeHtml(h.trim())}</th>\n`
        })
      html += '</tr></thead>\n<tbody>\n'
      body.forEach(row => {
        if (!row.trim()) return
        html += '<tr>\n'
        row
          .split('|')
          .filter(Boolean)
          .forEach(c => {
            html += `  <td>${escapeHtml(c.trim())}</td>\n`
          })
        html += '</tr>\n'
      })
      html += '</tbody>\n'
    } else {
      table.forEach(row => {
        html += `<p>${escapeHtml(row)}</p>\n`
      })
    }
    html += '</table>\n'
    table = []
    inTable = false
  }

  function flushList(): void {
    if (listOpen) {
      html += '</ul>\n'
      listOpen = false
    }
  }

  for (const raw of rows) {
    const line = raw.trim()

    if (line.includes('|')) {
      if (!inTable) {
        flushList()
        inTable = true
      }
      table.push(line)
      continue
    } else if (inTable) {
      flushTable()
    }

    const hMatch = line.match(/^(#{1,6})\s+(.+)$/)
    if (hMatch) {
      flushList()
      const level = hMatch[1].length
      html += `<h${level}>${escapeHtml(hMatch[2])}</h${level}>\n`
      continue
    }

    const lMatch = line.match(/^[-*]\s+(.+)$/)
    if (lMatch) {
      if (!listOpen) {
        html += '<ul>\n'
        listOpen = true
      }
      html += `  <li>${inline(lMatch[1])}</li>\n`
      continue
    } else {
      flushList()
    }

    if (!line) {
      flushList()
      continue
    }

    html += `<p>${inline(line)}</p>\n`
  }

  flushTable()
  flushList()
  return html
}

function inline(s: string): string {
  return escapeHtml(s)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}
