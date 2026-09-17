import { describe, expect, it } from 'vitest'
import { sanitizeHtml } from '../ui/htmlSanitizer'

describe('sanitizeHtml', () => {
  it('removes executable tags and event handlers', () => {
    const html = '<p onclick="alert(1)">正文</p><script>alert(1)</script><iframe src="https://example.com"></iframe>'

    expect(sanitizeHtml(html)).toBe('<p>正文</p>')
  })

  it('removes unsafe urls but keeps document structure', () => {
    const html = '<table><tbody><tr><td colspan="2"><a href="javascript:alert(1)">link</a></td></tr></tbody></table>'

    expect(sanitizeHtml(html)).toBe('<table><tbody><tr><td colspan="2"><a>link</a></td></tr></tbody></table>')
  })

  it('keeps safe image data urls and safe inline styles', () => {
    const html = '<img src="data:image/png;base64,abc" style="width: 10px" onerror="alert(1)" alt="preview">'

    expect(sanitizeHtml(html)).toBe('<img src="data:image/png;base64,abc" style="width: 10px" alt="preview">')
  })
})
