/**
 * 全局公司数据源（单例）。
 * - 在 App.vue 层调用 load() 加载一次，所有组件共享同一份 ref
 * - Footer / Home / About / Contact 复用同一份数据，避免重复请求
 * - 数据来自 /api/website/company-detail
 * - 失败时 company.value 保持 null，由各组件自行决定降级文案
 */
import { ref } from 'vue'
import { fetchCompanyDetail } from '../api/website'

const company = ref(null)
const loading = ref(false)
const loaded = ref(false)

async function load() {
  if (loaded.value || loading.value) return
  loading.value = true
  try {
    company.value = await fetchCompanyDetail()
    loaded.value = true
  } catch (err) {
    console.warn('Company data unavailable:', err)
  } finally {
    loading.value = false
  }
}

export function useCompany() {
  return { company, loading, load }
}
