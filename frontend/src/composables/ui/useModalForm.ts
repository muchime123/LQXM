import { reactive, computed } from 'vue'
import { setDirty, clearDirty, getDirtyState } from '@/composables/common/useDirty'
import { notify } from '@/composables/ui/useToast'

interface ModalFormOptions<T> {
  defaultForm: () => T
  dirtyKey?: string
}

interface ModalState {
  visible: boolean
  editId: number | null
  saving: boolean
}

export function useModalForm<T extends object>(options: ModalFormOptions<T>) {
  const { defaultForm, dirtyKey = '' } = options

  const modal = reactive<ModalState>({
    visible: false,
    editId: null,
    saving: false
  })
  const isNew = computed(() => modal.editId === null)

  const form = reactive(defaultForm()) as T

  function clearFormDirty(): void {
    if (dirtyKey) clearDirty(dirtyKey)
  }

  function openAdd(defaults: Partial<T> = {}): void {
    modal.editId = null
    Object.assign(form, defaultForm(), defaults)
    clearFormDirty()
    modal.visible = true
  }

  function openEdit(item: T & { id: number }, mapToForm?: (item: T & { id: number }) => Partial<T>): void {
    modal.editId = item.id
    if (mapToForm) {
      Object.assign(form, defaultForm(), mapToForm(item))
    } else {
      Object.assign(form, defaultForm(), item)
    }
    clearFormDirty()
    modal.visible = true
  }

  function closeModal(): void {
    modal.visible = false
    clearFormDirty()
  }

  function markDirty(): void {
    if (dirtyKey) setDirty(dirtyKey)
  }

  /** 供 LqDialog before-close 调用；返回 false 阻止关闭 */
  function handleBeforeClose(done: () => void): void | boolean {
    if (dirtyKey && getDirtyState(dirtyKey)) {
      return false
    }
    done()
    return true
  }

  async function handleSave(
    saveFn: () => Promise<void>,
    successMsg = '操作成功',
    errorMsg = '操作失败',
    onSuccess?: () => void
  ): Promise<void> {
    if (modal.saving) return
    modal.saving = true
    try {
      await saveFn()
      clearFormDirty()
      if (successMsg) notify(successMsg, 'success')
      closeModal()
      if (onSuccess) onSuccess()
    } catch (err) {
      notify.errorWithDetail(errorMsg, err)
    } finally {
      modal.saving = false
    }
  }

  return {
    modal,
    form,
    isNew,
    openAdd,
    openEdit,
    closeModal,
    markDirty,
    handleBeforeClose,
    handleSave
  }
}
