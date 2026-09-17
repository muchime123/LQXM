import { computed, ref } from 'vue'
import { getCurrentUser, loginLocal, logout as apiLogout, type CurrentUser, type LoginResult } from '@/api/auth'

const TOKEN_KEY = 'lq.token'

const user = ref<CurrentUser | null>(null)
const loading = ref(false)
let pending: Promise<CurrentUser | null> | null = null

/**
 * P7 正式登录态管理：
 * - 登录成功后将服务端下发的 Token 存入 localStorage（lq.token），后续请求由 http.ts 自动带 Bearer 头；
 * - "我是谁"一律来自后端 /api/auth/me，前端不再自行声明身份；
 * - 开发环境保留 X-Employee-Id 兼容（selectEmployee），生产禁用。
 */
export function useCurrentUser() {
  function token(): string | null {
    return localStorage.getItem(TOKEN_KEY)
  }

  function hasToken(): boolean {
    return !!localStorage.getItem(TOKEN_KEY)
  }

  async function load(force = false): Promise<CurrentUser | null> {
    if (user.value && !force) return user.value
    if (pending) return pending
    // 没有任何身份来源（既无 Token 也无开发兼容身份）时直接返回未登录
    if (!hasToken() && !localStorage.getItem('lq.currentEmployeeId')) {
      user.value = null
      return null
    }
    loading.value = true
    pending = getCurrentUser()
      .then(x => {
        user.value = x
        return x
      })
      .catch(() => {
        user.value = null
        return null
      })
      .finally(() => {
        loading.value = false
        pending = null
      })
    return pending
  }

  function applyLogin(result: LoginResult) {
    localStorage.setItem(TOKEN_KEY, result.token)
    // 登录后清除旧的开发兼容身份，避免身份混用
    localStorage.removeItem('lq.currentEmployeeId')
    localStorage.removeItem('lq.currentUserName')
    user.value = result.user
  }

  async function loginByLocal(username: string, password: string) {
    applyLogin(await loginLocal(username, password))
    return user.value
  }

  async function logout() {
    const t = token()
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem('lq.currentEmployeeId')
    localStorage.removeItem('lq.currentUserName')
    user.value = null
    if (t) {
      try {
        await apiLogout()
      } catch {
        /* 服务端撤销失败不影响本地退出 */
      }
    }
  }

  function can(permission?: string | string[]) {
    if (!permission) return true
    const list = Array.isArray(permission) ? permission : [permission]
    return list.some(x => user.value?.permissions.includes(x))
  }

  /** 开发环境快捷登录（仅后端 allow-employee-header=true 时有效，生产禁用）。 */
  async function selectEmployee(id: number | null, name?: string) {
    if (id == null) {
      localStorage.removeItem('lq.currentEmployeeId')
      localStorage.removeItem('lq.currentUserName')
    } else {
      localStorage.setItem('lq.currentEmployeeId', String(id))
      if (name) localStorage.setItem('lq.currentUserName', name)
    }
    user.value = null
    return load(true)
  }

  return {
    user: computed(() => user.value),
    loading: computed(() => loading.value),
    token,
    hasToken,
    load,
    can,
    loginByLocal,
    logout,
    selectEmployee
  }
}
