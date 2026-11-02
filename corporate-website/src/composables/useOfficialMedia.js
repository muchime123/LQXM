/**
 * 全局官方自媒体数据源（单例）。
 * - 用法与 useCompany 相同：在 App.vue 层调用 load() 加载一次，所有组件共享
 * - 数据来自 /api/website/official-media-accounts；公开来源由后端部署配置决定。
 *   Contact 页社交卡片与 Footer 社交图标复用这份结果。
 * - 失败时 accounts.value 保持 []，由各组件降级（例如只显示抖音图标但链接不跳转）
 */
import { ref } from 'vue'
import { fetchOfficialMediaAccounts } from '../api/website'

const accounts = ref([])
const loading = ref(false)
const loaded = ref(false)

async function load() {
  if (loaded.value || loading.value) return
  loading.value = true
  try {
    const list = await fetchOfficialMediaAccounts()
    accounts.value = Array.isArray(list) ? list : []
    loaded.value = true
  } catch (err) {
    console.warn('Official media accounts unavailable:', err)
    accounts.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 按平台取第一个官方账号（如 findByPlatform('抖音')）。
 * @param {string} platform 平台名称，与后台 MediaAccount.platform 一致
 */
function findByPlatform(platform) {
  return accounts.value.find(a => a && a.platform === platform) || null
}

export function useOfficialMedia() {
  return { accounts, loading, load, findByPlatform }
}
