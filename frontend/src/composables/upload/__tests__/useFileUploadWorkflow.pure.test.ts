import { describe, expect, it } from 'vitest'
import { filesFromClipboardEvent, isEditableElement, matchesAccept } from '../useFileUploadWorkflow'

function createFile(name: string, type: string): File {
  return new File(['content'], name, { type })
}

describe('useFileUploadWorkflow - 纯函数', () => {
  describe('matchesAccept', () => {
    describe('无 accept 参数', () => {
      it('未传 accept 时接受所有文件', () => {
        expect(matchesAccept(createFile('a.txt', 'text/plain'))).toBe(true)
      })

      it('空字符串接受所有文件', () => {
        expect(matchesAccept(createFile('a.txt', 'text/plain'), '')).toBe(true)
      })

      it('仅含空白逗号时接受所有文件', () => {
        expect(matchesAccept(createFile('a.txt', 'text/plain'), ' , , ')).toBe(true)
      })
    })

    describe('通配符模式', () => {
      it('*/* 接受所有文件', () => {
        expect(matchesAccept(createFile('a.txt', 'text/plain'), '*/*')).toBe(true)
        expect(matchesAccept(createFile('a.png', 'image/png'), '*/*')).toBe(true)
      })
    })

    describe('MIME 组通配 image/*', () => {
      it('匹配 image/* 类型文件', () => {
        expect(matchesAccept(createFile('a.png', 'image/png'), 'image/*')).toBe(true)
        expect(matchesAccept(createFile('a.jpg', 'image/jpeg'), 'image/*')).toBe(true)
      })

      it('拒绝非 image 类型', () => {
        expect(matchesAccept(createFile('a.txt', 'text/plain'), 'image/*')).toBe(false)
      })
    })

    describe('扩展名模式 .ext', () => {
      it('匹配 .png 扩展名', () => {
        expect(matchesAccept(createFile('a.png', ''), '.png')).toBe(true)
        expect(matchesAccept(createFile('a.PNG', ''), '.png')).toBe(true)
      })

      it('拒绝不匹配的扩展名', () => {
        expect(matchesAccept(createFile('a.txt', ''), '.png')).toBe(false)
      })

      it('多扩展名任一匹配即可', () => {
        expect(matchesAccept(createFile('a.jpg', ''), '.png,.jpg,.gif')).toBe(true)
        expect(matchesAccept(createFile('a.doc', ''), '.png,.jpg,.gif')).toBe(false)
      })
    })

    describe('精确 MIME 模式', () => {
      it('匹配 application/pdf', () => {
        expect(matchesAccept(createFile('a.pdf', 'application/pdf'), 'application/pdf')).toBe(true)
      })

      it('拒绝不匹配的 MIME', () => {
        expect(matchesAccept(createFile('a.txt', 'text/plain'), 'application/pdf')).toBe(false)
      })
    })

    describe('混合模式', () => {
      it('MIME 组 + 扩展名混合', () => {
        expect(
          matchesAccept(createFile('a.xlsx', 'application/vnd.ms-excel'), 'image/*,.xlsx')
        ).toBe(true)
        expect(matchesAccept(createFile('a.png', 'image/png'), 'image/*,.xlsx')).toBe(true)
        expect(matchesAccept(createFile('a.doc', 'application/msword'), 'image/*,.xlsx')).toBe(false)
      })

      it('模式大小写不敏感', () => {
        expect(matchesAccept(createFile('a.png', 'image/png'), 'IMAGE/*')).toBe(true)
        expect(matchesAccept(createFile('a.png', 'IMAGE/PNG'), 'image/*')).toBe(true)
      })
    })
  })

  describe('filesFromClipboardEvent', () => {
    function createClipboardEvent(files: { name: string; type: string }[]): ClipboardEvent {
      const dataTransfer = new DataTransfer()
      for (const f of files) {
        dataTransfer.items.add(createFile(f.name, f.type))
      }
      return new ClipboardEvent('paste', { clipboardData: dataTransfer })
    }

    it('从剪贴板事件提取文件', () => {
      const event = createClipboardEvent([
        { name: 'a.png', type: 'image/png' },
        { name: 'b.txt', type: 'text/plain' }
      ])
      const result = filesFromClipboardEvent(event)
      expect(result).toHaveLength(2)
      expect(result.map(f => f.name)).toEqual(['a.png', 'b.txt'])
    })

    it('按 accept 过滤文件', () => {
      const event = createClipboardEvent([
        { name: 'a.png', type: 'image/png' },
        { name: 'b.txt', type: 'text/plain' }
      ])
      const result = filesFromClipboardEvent(event, 'image/*')
      expect(result).toHaveLength(1)
      expect(result[0].name).toBe('a.png')
    })

    it('无文件时返回空数组', () => {
      const event = createClipboardEvent([])
      expect(filesFromClipboardEvent(event)).toEqual([])
    })

    it('event 为 null 时返回空数组', () => {
      expect(filesFromClipboardEvent(null as unknown as ClipboardEvent)).toEqual([])
    })
  })

  describe('isEditableElement', () => {
    it('input 为可编辑元素', () => {
      const el = document.createElement('input')
      expect(isEditableElement(el)).toBe(true)
    })

    it('textarea 为可编辑元素', () => {
      const el = document.createElement('textarea')
      expect(isEditableElement(el)).toBe(true)
    })

    it('select 为可编辑元素', () => {
      const el = document.createElement('select')
      expect(isEditableElement(el)).toBe(true)
    })

    it('div 默认不可编辑', () => {
      const el = document.createElement('div')
      expect(isEditableElement(el)).toBe(false)
    })

    it('contentEditable 的元素为可编辑', () => {
      const el = document.createElement('div')
      el.contentEditable = 'true'
      expect(isEditableElement(el)).toBe(true)
    })

    it('null 返回 false', () => {
      expect(isEditableElement(null)).toBe(false)
    })
  })
})
