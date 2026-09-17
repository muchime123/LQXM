import { mount } from '@vue/test-utils'
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import FilePreviewBody from '../FilePreviewBody.vue'

describe('FilePreviewBody', () => {
  it('shows an explicit loading state before preview content', () => {
    const wrapper = mount(FilePreviewBody, {
      props: { loading: true, url: '/files/report.pdf', fileName: 'report.pdf' }
    })
    expect(wrapper.get('.preview-loading').text()).toContain('正在加载预览')
    expect(wrapper.find('iframe').exists()).toBe(false)
  })

  it('shows actionable error recovery links without leaking unsafe window access', () => {
    const wrapper = mount(FilePreviewBody, {
      props: {
        url: '/files/report.pdf',
        fileName: 'report.pdf',
        errorMessage: '文件读取失败，请稍后重试'
      }
    })
    expect(wrapper.get('.unsupported-hint').text()).toBe('文件读取失败，请稍后重试')
    const links = wrapper.findAll('.preview-actions a')
    expect(links).toHaveLength(2)
    expect(links[0].attributes('target')).toBe('_blank')
    expect(links[0].attributes('rel')).toBe('noopener')
    expect(links[1].attributes('download')).toBeDefined()
  })

  it('supports bounded image zoom, rotation, reset, and load failure feedback', async () => {
    const wrapper = mount(FilePreviewBody, {
      props: { url: '/files/image.png', fileName: 'image.png', title: '产品主图' }
    })
    const image = wrapper.get('img')
    expect(image.attributes('alt')).toBe('产品主图')

    await wrapper.get('button[title="放大"]').trigger('click')
    await wrapper.get('button[title="向右旋转"]').trigger('click')
    expect(wrapper.get('img').attributes('style')).toContain('scale(1.1) rotate(90deg)')

    await wrapper.get('button[title="重置"]').trigger('click')
    expect(wrapper.get('img').attributes('style')).toContain('scale(1) rotate(0deg)')

    await wrapper.get('img').trigger('error')
    expect(wrapper.get('.unsupported-hint').text()).toContain('图片加载失败')
  })

  // ====== Office 预览异步轮询 ======

  describe('office preview polling', () => {
    let fetchSpy: ReturnType<typeof vi.spyOn>

    beforeEach(() => {
      vi.useFakeTimers()
      fetchSpy = vi.spyOn(globalThis, 'fetch')
    })

    afterEach(() => {
      vi.useRealTimers()
      fetchSpy.mockRestore()
    })

    /** 构造 ApiResponse 包装的 OfficePreviewStatus 响应。 */
    function mockStatusResponse(data: Record<string, unknown>): void {
      fetchSpy.mockResolvedValue({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({
            code: 200,
            message: 'ok',
            data: {
              status: 'pending',
              documentId: 123,
              startedAt: null,
              updatedAt: null,
              errorMessage: null,
              pdfReady: false,
              ...data
            },
            timestamp: ''
          })
      } as Response)
    }

    it.each([['report.docx'], ['sheet.xlsx'], ['slides.pptx'], ['old.doc']])(
      'shows loading overlay with progress bar and empty iframe src for %s, then loads PDF after polling done',
      async (fileName) => {
        mockStatusResponse({ status: 'done', pdfReady: true })
        const wrapper = mount(FilePreviewBody, {
          props: {
            url: '/api/file-assets/product/assets/123/preview',
            fileName
          }
        })

        // 初始：overlay 显示进度条 + 阶段文案，iframe src 空（轮询 pending 中）
        expect(wrapper.find('.preview-iframe-overlay').exists()).toBe(true)
        expect(wrapper.find('.preview-progress-bar').exists()).toBe(true)
        expect(wrapper.find('.preview-progress-fill').exists()).toBe(true)
        expect(wrapper.get('.preview-office-phase').text()).toBe('正在启动转换器...')
        expect(wrapper.get('iframe.preview-pdf').attributes('src')).toBe('')

        // 轮询完成（flush microtasks）
        await vi.advanceTimersByTimeAsync(0)

        // done 后：iframe src = preview-pdf URL
        expect(wrapper.get('iframe.preview-pdf').attributes('src')).toBe(
          '/api/file-assets/product/assets/123/preview-pdf'
        )
      }
    )

    it('polls the status URL (does not fetch the source file directly)', async () => {
      mockStatusResponse({ status: 'done', pdfReady: true })
      mount(FilePreviewBody, {
        props: {
          url: '/api/file-assets/product/assets/123/preview',
          fileName: 'report.docx'
        }
      })
      await vi.advanceTimersByTimeAsync(0)
      expect(fetchSpy).toHaveBeenCalled()
      const calledUrl = String(fetchSpy.mock.calls[0]?.[0])
      expect(calledUrl).toContain('/preview-pdf/status')
      // 不直接 fetch 源文件
      expect(calledUrl).not.toMatch(/\/preview(\?|$)/)
    })

    it('shows download fallback when conversion reports error', async () => {
      mockStatusResponse({ status: 'error', errorMessage: '转换失败：soffice 未安装' })
      const wrapper = mount(FilePreviewBody, {
        props: {
          url: '/api/file-assets/product/assets/123/preview',
          fileName: 'report.docx'
        }
      })
      await vi.advanceTimersByTimeAsync(0)

      expect(wrapper.get('.unsupported-hint').text()).toContain('转换失败')
      const links = wrapper.findAll('.preview-actions a')
      expect(links).toHaveLength(2)
      // 下载链接指向原文（/preview），便于用户拿到原文件
      expect(links[1].attributes('download')).toBeDefined()
      expect(links[1].attributes('href')).toBe('/api/file-assets/product/assets/123/preview')
    })

    it('falls back to download when iframe errors after polling done', async () => {
      mockStatusResponse({ status: 'done', pdfReady: true })
      const wrapper = mount(FilePreviewBody, {
        props: {
          url: '/api/file-assets/product/assets/123/preview',
          fileName: 'report.docx'
        }
      })
      // 轮询完成
      await vi.advanceTimersByTimeAsync(0)
      expect(wrapper.get('iframe.preview-pdf').attributes('src')).toBe(
        '/api/file-assets/product/assets/123/preview-pdf'
      )

      // done 后 iframe 加载失败 → 下载兜底
      await wrapper.get('iframe.preview-pdf').trigger('error')
      expect(wrapper.get('.unsupported-hint').text()).toContain('预览加载失败')
      const links = wrapper.findAll('.preview-actions a')
      expect(links).toHaveLength(2)
      expect(links[1].attributes('href')).toBe('/api/file-assets/product/assets/123/preview')
    })

    it('cancels polling on unmount (no further fetch after unmount)', async () => {
      mockStatusResponse({ status: 'pending' })
      const wrapper = mount(FilePreviewBody, {
        props: {
          url: '/api/file-assets/product/assets/123/preview',
          fileName: 'report.docx'
        }
      })
      await vi.advanceTimersByTimeAsync(0)

      const callsBefore = fetchSpy.mock.calls.length
      wrapper.unmount()

      // 卸载后推进时间，不应有新的 fetch
      await vi.advanceTimersByTimeAsync(10_000)
      expect(fetchSpy.mock.calls.length).toBe(callsBefore)
    })
  })
})
