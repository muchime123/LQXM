import { reactive, readonly, ref } from 'vue'

/**
 * 应用级状态公告
 * 用于维护模式、后端不可用、版本更新等全局提示。
 * 普通页面内状态请使用 DataState，不要在业务页面重复造全局横幅。
 */
export type AppAnnouncement = {
  id: string
  kind: 'info' | 'warning' | 'danger'
  title: string
  message?: string
  dismissible?: boolean
}

const announcements = reactive<AppAnnouncement[]>([])

/** 发布一条全局公告，返回立即移除该公告的函数。 */
export function announce(item: AppAnnouncement): () => void {
  const existing = announcements.findIndex(a => a.id === item.id)
  if (existing !== -1) announcements.splice(existing, 1)
  announcements.push(item)
  return () => dismiss(item.id)
}

export function dismiss(id: string) {
  const index = announcements.findIndex(a => a.id === id)
  if (index !== -1) announcements.splice(index, 1)
}

/** 后端不可用状态：由 http.ts 拦截器调用 reportBackendStatus() 维护。 */
const backendUnavailable = ref(false)
let failingSince = 0

export function reportBackendStatus(ok: boolean) {
  if (ok) {
    failingSince = 0
    backendUnavailable.value = false
    return
  }
  if (backendUnavailable.value) return
  if (!failingSince) failingSince = Date.now()
  window.setTimeout(() => {
    if (failingSince && Date.now() - failingSince >= 800) backendUnavailable.value = true
  }, 800)
}

export function useAppStatus() {
  return {
    announcements: readonly(announcements),
    backendUnavailable,
    announce,
    dismiss,
    reportBackendStatus
  }
}
