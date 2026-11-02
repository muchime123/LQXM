/**
 * 官网页面区块内容（usePageSections）。
 * - 拉取 /api/website/page-sections/{pageKey} 获取某页面下启用的区块
 * - 按 sectionKey 提供查找能力，页面组件用后台文案替换硬编码默认值
 * - 失败时返回空映射，页面回退到前端默认文案，不阻塞渲染
 * - 统一提供 sectionEnabled / sectionRows / parseSectionRows 供所有页面使用，
 *   避免各页面重复实现相同的JSON 解析逻辑。
 */
import { ref, computed } from 'vue'
import { fetchPageSections } from '../api/website'

export function usePageSections(pageKey) {
  const sections = ref([])
  const loaded = ref(false)
  const loadFailed = ref(false)

  const sectionMap = computed(() => {
    const map = {}
    for (const s of sections.value) {
      if (s && s.sectionKey) map[s.sectionKey] = s
    }
    return map
  })

  /** 按 sectionKey 取区块，不存在返回 null（页面自行回退默认文案）。 */
  function section(key) {
    return sectionMap.value[key] || null
  }

  /**
   * 后台关闭的区块不会出现在公开接口中。加载成功后缺少该 key 即视为隐藏；
   * 接口失败或尚未完成加载时保持显示，确保官网可以使用静态回退内容。
   */
  function isEnabled(key) {
    if (!loaded.value || loadFailed.value) return true
    return Boolean(sectionMap.value[key])
  }

  /** 解析结构化 content JSON 字符串为行数组。 */
  function parseSectionRows(raw) {
    if (!raw) return []
    try {
      const rows = JSON.parse(raw)
      return Array.isArray(rows) ? rows.filter((row) => row && typeof row === 'object') : []
    } catch {
      return []
    }
  }

  /**
   * 读取后台已保存的结构化内容。
   */
  function sectionRows(key) {
    return parseSectionRows(section(key)?.content)
  }

  async function load({ force = false } = {}) {
    if (!force && loaded.value) return
    try {
      sections.value = await fetchPageSections(pageKey)
      loadFailed.value = false
    } catch (err) {
      console.warn(`Page sections unavailable (${pageKey}), using fallback:`, err)
      sections.value = []
      loadFailed.value = true
    } finally {
      loaded.value = true
    }
  }

  return { sections, loaded, loadFailed, section, isEnabled, parseSectionRows, sectionRows, load }
}
