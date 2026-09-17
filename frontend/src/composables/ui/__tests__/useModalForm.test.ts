import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useModalForm } from '../useModalForm'
import * as useDirty from '../../common/useDirty'
import * as useToast from '../useToast'

interface TestForm {
  name: string
  age: number
}

function defaultForm(): TestForm {
  return { name: '', age: 0 }
}

describe('useModalForm', () => {
  const notifySpy = vi.fn()
  const errorWithDetailSpy = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(useToast, 'notify').mockImplementation(((message: unknown, type?: unknown) => {
      notifySpy(message, type)
    }) as typeof useToast.notify)
    ;(useToast.notify as { errorWithDetail?: unknown }).errorWithDetail = errorWithDetailSpy
    useDirty.clearAllDirty()
  })

  describe('openAdd', () => {
    it('resets form to defaults and opens modal', () => {
      const { modal, form, isNew, openAdd } = useModalForm<TestForm>({ defaultForm })

      form.name = 'existing'
      form.age = 99

      openAdd()

      expect(modal.visible).toBe(true)
      expect(modal.editId).toBeNull()
      expect(isNew.value).toBe(true)
      expect(form.name).toBe('')
      expect(form.age).toBe(0)
    })

    it('applies default overrides', () => {
      const { form, openAdd } = useModalForm<TestForm>({ defaultForm })

      openAdd({ name: 'preset' })

      expect(form.name).toBe('preset')
      expect(form.age).toBe(0)
    })
  })

  describe('openEdit', () => {
    it('sets editId and copies item fields', () => {
      const { modal, form, isNew, openEdit } = useModalForm<TestForm>({ defaultForm })

      openEdit({ id: 42, name: 'Alice', age: 30 })

      expect(modal.editId).toBe(42)
      expect(isNew.value).toBe(false)
      expect(form.name).toBe('Alice')
      expect(form.age).toBe(30)
    })

    it('uses custom mapToForm when provided', () => {
      const { form, openEdit } = useModalForm<TestForm>({ defaultForm })

      openEdit({ id: 1, name: 'Bob', age: 25 }, item => ({ name: item.name.toUpperCase(), age: item.age + 1 }))

      expect(form.name).toBe('BOB')
      expect(form.age).toBe(26)
    })
  })

  describe('closeModal', () => {
    it('hides modal and clears dirty state', () => {
      const { modal, closeModal } = useModalForm<TestForm>({ defaultForm, dirtyKey: 'test-form' })

      modal.visible = true
      useDirty.setDirty('test-form')

      closeModal()

      expect(modal.visible).toBe(false)
      expect(useDirty.isDirty()).toBe(false)
    })

    it('works without dirtyKey', () => {
      const { modal, closeModal } = useModalForm<TestForm>({ defaultForm })

      modal.visible = true
      closeModal()

      expect(modal.visible).toBe(false)
    })
  })

  describe('markDirty', () => {
    it('sets dirty flag when dirtyKey is provided', () => {
      const { markDirty } = useModalForm<TestForm>({ defaultForm, dirtyKey: 'my-form' })

      markDirty()

      expect(useDirty.getDirtyKeys()).toContain('my-form')
    })

    it('does nothing when dirtyKey is empty', () => {
      const { markDirty } = useModalForm<TestForm>({ defaultForm })

      markDirty()

      expect(useDirty.isDirty()).toBe(false)
    })
  })

  describe('handleSave', () => {
    it('calls saveFn, shows success, closes modal, and invokes onSuccess', async () => {
      const saveFn = vi.fn().mockResolvedValue(undefined)
      const onSuccess = vi.fn()
      const { modal, handleSave } = useModalForm<TestForm>({ defaultForm, dirtyKey: 'save-test' })

      modal.visible = true
      useDirty.setDirty('save-test')

      await handleSave(saveFn, '保存成功', '保存失败', onSuccess)

      expect(saveFn).toHaveBeenCalledOnce()
      expect(notifySpy).toHaveBeenCalledWith('保存成功', 'success')
      expect(modal.visible).toBe(false)
      expect(onSuccess).toHaveBeenCalledOnce()
      expect(useDirty.isDirty()).toBe(false)
      expect(modal.saving).toBe(false)
    })

    it('shows error and keeps modal open when save fails', async () => {
      const saveFn = vi.fn().mockRejectedValue(new Error('network error'))
      const { modal, handleSave } = useModalForm<TestForm>({ defaultForm, dirtyKey: 'save-test' })

      modal.visible = true

      await handleSave(saveFn, '保存成功', '保存失败')

      expect(errorWithDetailSpy).toHaveBeenCalledWith('保存失败', expect.any(Error))
      expect(modal.visible).toBe(true)
      expect(modal.saving).toBe(false)
    })

    it('keeps dirty state when save fails so the form can be retried', async () => {
      const saveFn = vi.fn().mockRejectedValue(new Error('network error'))
      const { modal, handleSave } = useModalForm<TestForm>({ defaultForm, dirtyKey: 'save-test' })

      modal.visible = true
      useDirty.setDirty('save-test')

      await handleSave(saveFn)

      expect(modal.visible).toBe(true)
      expect(useDirty.getDirtyKeys()).toContain('save-test')
    })

    it('prevents concurrent save calls', async () => {
      const saveFn = vi.fn().mockImplementation(() => new Promise(r => setTimeout(r, 50)))
      const { handleSave } = useModalForm<TestForm>({ defaultForm })

      const p1 = handleSave(saveFn)
      const p2 = handleSave(saveFn)

      await Promise.all([p1, p2])

      expect(saveFn).toHaveBeenCalledTimes(1)
    })

    it('uses default success message', async () => {
      const saveFn = vi.fn().mockResolvedValue(undefined)
      const { handleSave } = useModalForm<TestForm>({ defaultForm })

      await handleSave(saveFn)

      expect(notifySpy).toHaveBeenCalledWith('操作成功', 'success')
    })

    it('uses default error message', async () => {
      const saveFn = vi.fn().mockRejectedValue(new Error('fail'))
      const { handleSave } = useModalForm<TestForm>({ defaultForm })

      await handleSave(saveFn)

      expect(errorWithDetailSpy).toHaveBeenCalledWith('操作失败', expect.any(Error))
    })
  })
})
