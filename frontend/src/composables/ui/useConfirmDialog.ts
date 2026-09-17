import { reactive } from 'vue'

interface ConfirmDialogState {
  visible: boolean
  title: string
  message: string
  subtitle: string
  confirmText: string
  cancelText: string
  secondaryText: string
  type: 'danger' | 'warning' | 'info'
  loading: boolean
  resolver: ((value: boolean | 'secondary') => void) | null
  active: boolean
}

interface ConfirmOptions {
  title?: string
  message?: string
  subtitle?: string
  confirmText?: string
  cancelText?: string
  secondaryText?: string
  danger?: boolean
  warning?: boolean
  loading?: boolean
}

const confirmDialog = reactive<ConfirmDialogState>({
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

export { confirmDialog }

export function askConfirm(options: ConfirmOptions = {}): Promise<boolean | 'secondary'> {
  return new Promise(resolve => {
    if (confirmDialog.active) {
      console.warn('[useConfirmDialog] 已有确认弹窗在进行中，新的请求已自动拒绝。请等待当前弹窗关闭后再试。')
      resolve(false)
      return
    }

    const type: 'danger' | 'warning' | 'info' = options.danger ? 'danger' : options.warning ? 'warning' : 'info'

    confirmDialog.active = true
    Object.assign(confirmDialog, {
      visible: true,
      title: options.title || '确认操作',
      message: options.message || '',
      subtitle: options.subtitle || '',
      confirmText: options.confirmText || '确认',
      cancelText: options.cancelText || '取消',
      secondaryText: options.secondaryText || '',
      type,
      loading: false,
      resolver: resolve
    })
  })
}

export function resolveConfirm(value: boolean | 'secondary'): void {
  const resolver = confirmDialog.resolver
  confirmDialog.visible = false
  confirmDialog.loading = false
  confirmDialog.resolver = null
  confirmDialog.active = false
  if (resolver) resolver(value)
}

export function setConfirmLoading(loading: boolean): void {
  confirmDialog.loading = loading
}
