import { describe, it, expect, vi, beforeEach } from 'vitest'
import { confirmDialog, askConfirm, resolveConfirm, setConfirmLoading } from '../useConfirmDialog'

function resetState() {
  Object.assign(confirmDialog, {
    visible: false,
    title: '确认操作',
    message: '',
    subtitle: '',
    confirmText: '确认',
    cancelText: '取消',
    secondaryText: '',
    type: 'info',
    loading: false,
    resolver: null,
    active: false
  })
}

describe('useConfirmDialog', () => {
  beforeEach(() => {
    resetState()
  })

  describe('askConfirm', () => {
    it('opens dialog with provided options', async () => {
      const promise = askConfirm({
        title: '删除确认',
        message: '确定删除？',
        subtitle: '此操作不可撤销',
        confirmText: '删除',
        cancelText: '算了',
        danger: true
      })

      expect(confirmDialog.visible).toBe(true)
      expect(confirmDialog.title).toBe('删除确认')
      expect(confirmDialog.message).toBe('确定删除？')
      expect(confirmDialog.subtitle).toBe('此操作不可撤销')
      expect(confirmDialog.confirmText).toBe('删除')
      expect(confirmDialog.cancelText).toBe('算了')
      expect(confirmDialog.type).toBe('danger')
      expect(confirmDialog.active).toBe(true)
      expect(confirmDialog.resolver).toBeTypeOf('function')

      resolveConfirm(true)
      expect(await promise).toBe(true)
    })

    it('uses default options when none provided', async () => {
      const promise = askConfirm()

      expect(confirmDialog.title).toBe('确认操作')
      expect(confirmDialog.confirmText).toBe('确认')
      expect(confirmDialog.cancelText).toBe('取消')
      expect(confirmDialog.type).toBe('info')

      resolveConfirm(false)
      expect(await promise).toBe(false)
    })

    it('sets warning type when warning option is true', async () => {
      askConfirm({ warning: true })
      expect(confirmDialog.type).toBe('warning')
      resolveConfirm(false)
    })

    it('danger takes precedence over warning', async () => {
      askConfirm({ danger: true, warning: true })
      expect(confirmDialog.type).toBe('danger')
      resolveConfirm(false)
    })

    it('rejects concurrent calls with false and logs warning', async () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

      askConfirm({ title: '第一次' })
      expect(confirmDialog.active).toBe(true)

      const promise2 = askConfirm({ title: '第二次' })
      const result = await promise2

      expect(result).toBe(false)
      expect(warnSpy).toHaveBeenCalledOnce()
      expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('已有确认弹窗'))

      warnSpy.mockRestore()
      resolveConfirm(false)
    })

    it('supports secondary button resolve', async () => {
      const promise = askConfirm({ secondaryText: '备份并删除' })
      resolveConfirm('secondary')
      expect(await promise).toBe('secondary')
    })
  })

  describe('resolveConfirm', () => {
    it('resets dialog state after resolving', () => {
      askConfirm({})
      resolveConfirm(true)

      expect(confirmDialog.visible).toBe(false)
      expect(confirmDialog.resolver).toBeNull()
      expect(confirmDialog.active).toBe(false)
      expect(confirmDialog.loading).toBe(false)
    })

    it('resolves the pending promise', async () => {
      const promise = askConfirm({})
      resolveConfirm(false)
      expect(await promise).toBe(false)
    })
  })

  describe('setConfirmLoading', () => {
    it('toggles loading state', () => {
      setConfirmLoading(true)
      expect(confirmDialog.loading).toBe(true)

      setConfirmLoading(false)
      expect(confirmDialog.loading).toBe(false)
    })
  })
})
