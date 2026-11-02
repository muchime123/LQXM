import { describe, expect, it } from 'vitest'
import { previewWebsiteHtml, savedWebsiteHtml, websiteMediaUrls } from '../websiteRichTextMedia'

describe('官网文章图片草稿预览', () => {
  it('编辑时使用本地预览，保存时恢复官网媒体地址', () => {
    const html = '<p><img src="/api/website/media/12/content"><img src="/api/website/media/34/content"></p>'
    const previews = new Map([
      ['/api/website/media/12/content', 'blob:http://localhost/first'],
      ['/api/website/media/34/content', 'blob:http://localhost/second']
    ])

    expect(websiteMediaUrls(html)).toEqual(['/api/website/media/12/content', '/api/website/media/34/content'])
    expect(savedWebsiteHtml(previewWebsiteHtml(html, previews), previews)).toBe(html)
  })

  it('不改写仍使用旧路径的历史图片', () => {
    const html = '<img src="/api/files/company?name=legacy.jpg">'
    expect(websiteMediaUrls(html)).toEqual([])
    expect(savedWebsiteHtml(html, new Map())).toBe(html)
  })
})
