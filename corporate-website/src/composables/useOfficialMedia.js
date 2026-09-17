/**
 * 全局官方自媒体数据源（单例）。
 * - 用法与 useCompany 相同：在 App.vue 层调用 load() 加载一次，所有组件共享
 * - 数据来自 /api/website/official-media-accounts，由后台「自媒体账号管理」勾选
 *   「官网官方展示」的账号会出现在这里；Contact 页第四张卡片、Footer 社交图标均复用
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
