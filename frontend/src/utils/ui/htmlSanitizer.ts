const BLOCKED_TAGS = new Set([
  'script',
  'style',
  'iframe',
  'object',
  'embed',
  'link',
  'meta',
  'base',
  'form',
  'input',
  'button',
  'textarea',
  'select',
  'option'
])

const ALLOWED_ATTRS = new Set([
  'alt',
  'class',
  'colspan',
  'height',
  'href',
  'id',
  'rowspan',
  'src',
  'style',
  'target',
  'title',
  'width'
])

const URL_ATTRS = new Set(['href', 'src'])

export function sanitizeHtml(html: string): string {
  if (!html) return ''
  if (typeof document === 'undefined') return html

  const template = document.createElement('template')
  template.innerHTML = html
  sanitizeNode(template.content)
  return template.innerHTML
}

function sanitizeNode(root: ParentNode): void {
  const elements = Array.from(root.querySelectorAll('*'))
  for (const element of elements) {
    const tag = element.tagName.toLowerCase()
    if (BLOCKED_TAGS.has(tag)) {
      element.remove()
      continue
    }

    for (const attr of Array.from(element.attributes)) {
      const name = attr.name.toLowerCase()
      const value = attr.value.trim()

      if (name.startsWith('on') || !ALLOWED_ATTRS.has(name)) {
        element.removeAttribute(attr.name)
        continue
      }

      if (URL_ATTRS.has(name) && !isSafeUrl(value)) {
        element.removeAttribute(attr.name)
        continue
      }

      if (name === 'style' && !isSafeStyle(value)) {
        element.removeAttribute(attr.name)
      }
    }

    if (element.getAttribute('target') === '_blank') {
      element.setAttribute('rel', 'noopener noreferrer')
    }
  }
}

function isSafeUrl(value: string): boolean {
  if (!value) return false
  const normalized = value.replace(/[\u0000-\u001F\u007F\s]+/g, '').toLowerCase()
  if (normalized.startsWith('javascript:') || normalized.startsWith('vbscript:')) return false
  if (normalized.startsWith('data:')) return normalized.startsWith('data:image/')
  return true
}

function isSafeStyle(value: string): boolean {
  const normalized = value.replace(/[\u0000-\u001F\u007F\s]+/g, '').toLowerCase()
  return !normalized.includes('expression(') && !normalized.includes('javascript:') && !normalized.includes('@import')
}
