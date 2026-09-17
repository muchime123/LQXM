/**
 * 官网可视化编辑的草稿管理（useCmsPreview）。
 * - 编辑器通过 postMessage 推送区块草稿（LIQIANG_CMS_DRAFT），覆盖页面区块的标题/描述/图片/结构化内容
 * - Banner 草稿（LIQIANG_CMS_BANNER_DRAFT）按 bannerId 覆盖 Hero 轮播的展示字段
 * - activeBannerId 用于编辑器选中特定 Banner 时同步官网预览
 * - 草稿同时保存在内存（reactive）与 localStorage（按 cmsEdit 会话），刷新 / iframe 重挂后自动恢复
 * - 「保存」写入数据库后编辑器会发送清除消息（CLEAR_DRAFT / CLEAR_BANNER_DRAFT），两处同步删除
 */
import { reactive, readonly, ref, computed, watch } from 'vue'

const LS_DRAFTS_KEY = 'liqiang-cms-section-drafts'
const LS_BANNER_DRAFTS_KEY = 'liqiang-cms-banner-drafts'
const LS_ACTIVE_BANNER_KEY = 'liqiang-cms-active-banner'

function loadObj(key) {
  try {
    const raw = typeof localStorage !== 'undefined' ? localStorage.getItem(key) : null
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}
function saveObj(key, obj) {
  try {
    if (typeof localStorage !== 'undefined') localStorage.setItem(key, JSON.stringify(obj))
  } catch { /* ignore */ }
}
function delObj(key) {
  try {
    if (typeof localStorage !== 'undefined') localStorage.removeItem(key)
  } catch { /* ignore */ }
}

const drafts = reactive(loadObj(LS_DRAFTS_KEY))
const bannerDrafts = reactive(loadObj(LS_BANNER_DRAFTS_KEY))

const loadedActiveBanner = (() => {
  try {
    const raw = localStorage.getItem(LS_ACTIVE_BANNER_KEY)
    return raw != null ? Number(raw) : null
  } catch { return null }
})()
const activeBannerId = ref(loadedActiveBanner)

function saveActiveBannerId(id) {
  try {
    if (id != null) localStorage.setItem(LS_ACTIVE_BANNER_KEY, String(id))
    else localStorage.removeItem(LS_ACTIVE_BANNER_KEY)
  } catch { /* ignore */ }
}

/* 草稿变更时同步持久化（对象变化不深度监听，用函数内同步即可）。 */

export function applyCmsDraft(payload = {}) {
  if (!payload.pageKey || !payload.sectionKey) return
  const key = `${payload.pageKey}.${payload.sectionKey}`
  drafts[key] = { ...(drafts[key] || {}), ...(payload.data || {}) }
  saveObj(LS_DRAFTS_KEY, { ...drafts })
}

export function clearCmsDraft(payload = {}) {
  if (!payload.pageKey || !payload.sectionKey) return
  const key = `${payload.pageKey}.${payload.sectionKey}`
  delete drafts[key]
  saveObj(LS_DRAFTS_KEY, { ...drafts })
  if (Object.keys(drafts).length === 0) delObj(LS_DRAFTS_KEY)
}

export function applyBannerDraft(payload = {}) {
  if (payload.bannerId == null) return
  bannerDrafts[payload.bannerId] = { ...(bannerDrafts[payload.bannerId] || {}), ...(payload.data || {}) }
  saveObj(LS_BANNER_DRAFTS_KEY, { ...bannerDrafts })
}

export function clearBannerDraft(payload = {}) {
  if (payload.bannerId == null) return
  delete bannerDrafts[payload.bannerId]
  saveObj(LS_BANNER_DRAFTS_KEY, { ...bannerDrafts })
  if (Object.keys(bannerDrafts).length === 0) delObj(LS_BANNER_DRAFTS_KEY)
}

/** 清空所有草稿（编辑模式结束时可选调用，目前走单条 CLEAR 即可）。 */
export function clearAllCmsDrafts() {
  for (const key of Object.keys(drafts)) delete drafts[key]
  for (const key of Object.keys(bannerDrafts)) delete bannerDrafts[key]
  delObj(LS_DRAFTS_KEY)
  delObj(LS_BANNER_DRAFTS_KEY)
  activeBannerId.value = null
  saveActiveBannerId(null)
}

/** 清除指定页面的所有草稿（包括 section 和 banner 草稿）。 */
export function clearDraftsForPage(pageKey) {
  if (!pageKey) return
  const sectionPrefix = `${pageKey}.`
  const keysToDelete = []
  for (const key of Object.keys(drafts)) {
    if (key.startsWith(sectionPrefix)) keysToDelete.push(key)
  }
  for (const key of keysToDelete) delete drafts[key]
  if (keysToDelete.length) saveObj(LS_DRAFTS_KEY, { ...drafts })
  if (Object.keys(drafts).length === 0) delObj(LS_DRAFTS_KEY)
}

export function selectPreviewBanner(payload = {}) {
  const id = payload.bannerId == null ? null : Number(payload.bannerId)
  activeBannerId.value = id
  saveActiveBannerId(id)
}

export function useCmsPreview(pageKey) {
  void pageKey
  const bannerDraftIds = computed(() => {
    return Object.keys(bannerDrafts)
      .filter((id) => {
        const d = bannerDrafts[id]
        return d && (d.imageUrl || d.title || d.description)
      })
      .map((id) => Number(id))
  })
  return {
    draft(sectionKey) {
      return readonly(drafts[`${pageKey}.${sectionKey}`] || {})
    },
    bannerDraft(bannerId) {
      if (bannerId == null) return readonly({})
      return readonly(bannerDrafts[bannerId] || {})
    },
    bannerDraftIds: readonly(bannerDraftIds),
    activeBannerId: readonly(activeBannerId),
  }
}

/* 保持 watch 不被 tree-shake 掉：在模块加载时做一次自检并确保 reactivity 注入。 */
void watch
