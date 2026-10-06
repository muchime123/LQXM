/**
 * 多标签页导航 Composable
 *
 * 采用模块级 reactive 状态（仿 useDirty.ts 模式），全应用共享单一标签栏状态。
 * 不导入 router 实例（避免循环依赖）——路由联动由 router/index.ts afterEach
 * 调用 addTabFromRoute，导航动作由 MultiTabBar 组件执行。
 *
 * 设计要点：
 * - 保活：App.vue 的 KeepAlive 以 :include=cachedViewNames 缓存所有标签页，
 *   切换标签时页面状态保留、不重新加载；关闭标签即从 include 移除并销毁缓存实例
 * - 刷新当前：先从 include 移除该页组件名触发 pruneCache 销毁实例，再恢复 include
 *   并递增 refreshNonce[path]（App.vue 组件 key = `${path}:${refreshNonce}`）强制重挂
 * - 持久化：localStorage 'lq.multiTabs'，刷新后恢复标签列表
 * - affix：'/products' 固定标签，常驻不可关闭
 * - 脏确认：复用 router.beforeEach 守卫，关闭激活标签先 router.push(neighbor)，
 *   守卫提示脏确认，用户取消则不移除标签——避免重复弹窗
 */

import { reactive, ref, nextTick } from 'vue'
import type { RouteLocationNormalized } from 'vue-router'
import { allNavItems as navItems } from '../../apps'
import { askConfirm } from '../ui/useConfirmDialog'
import { isDirty } from './useDirty'

/** 多标签页项（命名 MultiTabItem 以避免与 LqTabs.vue 的 TabItem 冲突） */
export interface MultiTabItem {
  /** 唯一标识 = route.path（同 path 不同 query 视为同一标签，更新显示而非新增） */
  key: string
  /** 不含 query 的路径，用于匹配 navItems / affix 判定 / 点击导航目标 */
  path: string
  /** 路由 name（如 'products'） */
  name: string
  /** 显示标题，取自 route.meta.title */
  title: string
  /** SVG 字符串，取自 navItems 按 path 匹配 */
  icon: string
  /** 查询参数快照（用于 tooltip / 调试，不参与 key 判定） */
  query: Record<string, string | string[]>
  /** 固定标签：true 不可关闭、不可被 removeAll/removeOthers/removeRight 移除 */
  affix: boolean
  /** 是否可关闭：!affix（派生但存储，模板直接读） */
  closable: boolean
  /** LRU 时间戳：命中/激活时 touch */
  timestamp: number
}

const STORAGE_KEY = 'lq.multiTabs'
/** V2: key 策略由 fullPath 改为 path，同 path 不同 query 合并为同一标签 */
const STORAGE_VERSION = 2
const MAX_TABS = 20
const AFFIX_PATH = '/products'

/** navItems（含子菜单）.to 集合，用于 tab-able 判定 */
const tabablePaths = new Set(
  navItems.flatMap(n => [n.to, ...(n.activePaths || []), ...(n.children || []).flatMap(c => [c.to, ...(c.activePaths || [])])])
)

const tabs = reactive<MultiTabItem[]>([])

/**
 * 路由 path → 页面组件名。
 * SFC 组件名由文件名推断（compiler-sfc 写入 __name），KeepAlive 的 include/exclude
 * 按组件名匹配，故此处必须与 src/views 下的文件名一致。
 */
const COMPONENT_NAME_BY_PATH: Record<string, string> = {
  '/products': 'ProductCatalogView',
  '/packaging': 'PackagingView',
  '/molds': 'PackagingMoldView',
  '/media': 'MediaAccountView',
  '/certificates': 'CertificateView',
  '/company': 'CompanyInfoView',
  '/company/certifications': 'CompanyCertificationsView',
  '/office-assets': 'OfficeAssetView'
}

/** KeepAlive include 列表：当前所有标签对应页面组件名（含标签增删时同步） */
export const cachedViewNames = ref<string[]>([])

/** 每个标签的"刷新代数"：App.vue 组件 key = `${path}:${refreshNonce[path] ?? 0}`，递增即强制重挂 */
export const refreshNonce = reactive<Record<string, number>>({})

let persistTimer: ReturnType<typeof setTimeout> | null = null

/** 路径是否可建标签：必须在 navItems 中且非 '/' redirect */
function isTabable(path: string): boolean {
  return path !== '/' && (tabablePaths.has(path) || /^\/sales-orders\/\d+$/.test(path))
}

/** 从 navItems（含子菜单）查找 icon */
function findIcon(path: string): string {
  for (const n of navItems) {
    if (n.to === path) return n.icon
    if (n.children) {
      const c = n.children.find(child => child.to === path || child.activePaths?.includes(path))
      if (c) return c.icon
    }
  }
  return ''
}

/** 标准化 query：过滤 null 值（vue-router LocationQuery 允许 null） */
function normalizeQuery(
  query: RouteLocationNormalized['query']
): Record<string, string | string[]> {
  const result: Record<string, string | string[]> = {}
  for (const [k, v] of Object.entries(query)) {
    if (v == null) continue
    if (Array.isArray(v)) {
      const filtered = v.filter((x): x is string => x != null)
      if (filtered.length) result[k] = filtered
    } else {
      result[k] = v
    }
  }
  return result
}

/** 按路由构造标签（非 tabable 返回 null）
 * key 使用 path（不含 query），使同 path 不同 query 共享同一标签。
 * 这符合主流管理后台惯例：显示模式/过滤条件的切换是页面内部状态，
 * 不应产生新标签。 */
function buildTab(to: RouteLocationNormalized): MultiTabItem | null {
  if (!isTabable(to.path)) return null
  const isAffix = to.path === AFFIX_PATH
  return {
    key: to.path,
    path: to.path,
    name: String(to.name ?? to.path),
    title: (to.meta?.title as string | undefined) ?? to.path,
    icon: findIcon(to.path),
    query: normalizeQuery(to.query),
    affix: isAffix,
    closable: !isAffix,
    timestamp: Date.now()
  }
}

/** LRU 上限淘汰：淘汰最旧的、非 affix、非当前激活的标签 */
function enforceLimit(keepKey: string): void {
  while (tabs.length > MAX_TABS) {
    const victim = [...tabs]
      .filter(t => !t.affix && t.key !== keepKey)
      .sort((a, b) => a.timestamp - b.timestamp)[0]
    if (!victim) break // 只剩 affix + active，无法再淘汰
    const idx = tabs.findIndex(t => t.key === victim.key)
    if (idx !== -1) tabs.splice(idx, 1)
  }
}

/** 持久化（150ms debounce，避免连续 touch 高频写） */
function schedulePersist(): void {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    persistTimer = null
    const payload = { version: STORAGE_VERSION, tabs: tabs.map(t => ({ ...t })) }
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
    } catch {
      // 配额不足或禁用 localStorage 时静默失败
    }
  }, 150)
}

/** 标签列表 → KeepAlive include 组件名列表（标签增删/恢复时调用） */
function syncCachedViewNames(): void {
  cachedViewNames.value = tabs
    .map(t => COMPONENT_NAME_BY_PATH[t.path])
    .filter((n): n is string => Boolean(n))
}

/** 构造 affix 标签（用于 init/恢复兜底） */
function buildAffixTab(): MultiTabItem {
  const nav = navItems.find(n => n.to === AFFIX_PATH)
  return {
    key: AFFIX_PATH,
    path: AFFIX_PATH,
    name: 'products',
    title: nav?.label ?? '产品管理',
    icon: nav?.icon ?? '',
    query: {},
    affix: true,
    closable: false,
    timestamp: 0
  }
}

/** 模块加载时从 localStorage 恢复标签列表（一次性）
 * STORAGE_VERSION 不匹配时直接忽略（V1 使用 fullPath 作 key，与当前 V2 path key 不兼容）。 */
function restoreTabs(): void {
  let restored: MultiTabItem[] = []
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (parsed?.version === STORAGE_VERSION && Array.isArray(parsed.tabs)) {
        // V2 数据：已按 path 作 key，直接恢复
        restored = (parsed.tabs as MultiTabItem[])
          .filter(t => isTabable(t.path))
          .map(t => ({ ...t, timestamp: t.timestamp ?? 0 }))
      }
    }
  } catch {
    // JSON 解析失败或版本不匹配时静默回退
  }

  // 始终确保 affix 标签存在且排第一
  if (!restored.some(t => t.affix)) {
    restored.unshift(buildAffixTab())
  } else {
    // 把 affix 提到首位
    const affixIdx = restored.findIndex(t => t.affix)
    if (affixIdx > 0) {
      const [affix] = restored.splice(affixIdx, 1)
      restored.unshift(affix)
    }
  }

  tabs.splice(0, tabs.length, ...restored)
  syncCachedViewNames()
}

// 模块顶层执行恢复（首次 import 时触发，时机早于应用挂载）
restoreTabs()

/**
 * 路由后置守卫调用：按当前路由 addOrUpdate 标签
 * - 同 path 命中（含不同 query）：原地更新 query/timestamp，不新增标签
 * - 新 path 未命中：push 新标签并执行 LRU 上限淘汰
 * 非 tab-able 路由（/、/403、/404、:pathMatch）直接 return
 */
export function addTabFromRoute(to: RouteLocationNormalized): void {
  const tab = buildTab(to)
  if (!tab) return
  const existing = tabs.find(t => t.key === tab.key)
  if (existing) {
    existing.query = tab.query
    existing.timestamp = Date.now()
    schedulePersist()
    return
  }
  tabs.push(tab)
  enforceLimit(tab.key)
  syncCachedViewNames()
  schedulePersist()
}

/** 纯列表移除（不导航）。affix 不可移除 */
export function removeTab(key: string): void {
  const idx = tabs.findIndex(t => t.key === key)
  if (idx === -1) return
  if (tabs[idx].affix) return
  tabs.splice(idx, 1)
  syncCachedViewNames()
  schedulePersist()
}

/** 关闭其他：保留 active + affix */
export function removeOthers(keepKey: string): void {
  for (let i = tabs.length - 1; i >= 0; i--) {
    if (!tabs[i].affix && tabs[i].key !== keepKey) tabs.splice(i, 1)
  }
  syncCachedViewNames()
  schedulePersist()
}

/** 关闭右侧：移除 active 之后的非 affix 标签 */
export function removeRight(activeKey: string): void {
  const idx = tabs.findIndex(t => t.key === activeKey)
  if (idx === -1) return
  for (let i = tabs.length - 1; i > idx; i--) {
    if (!tabs[i].affix) tabs.splice(i, 1)
  }
  syncCachedViewNames()
  schedulePersist()
}

/** 关闭全部：仅保留 affix */
export function removeAll(): void {
  for (let i = tabs.length - 1; i >= 0; i--) {
    if (!tabs[i].affix) tabs.splice(i, 1)
  }
  syncCachedViewNames()
  schedulePersist()
}

/**
 * 刷新当前标签：
 * 1) 从 KeepAlive include 中移除该页组件名 → include 的 pruneCache（post-flush）
 *    销毁其缓存实例（含当前激活实例的 shapeFlag 重置）
 * 2) 恢复 include 并递增 refreshNonce[path] → App.vue 组件 key 变化，强制重挂
 * 含未保存编辑时直接调 askConfirm 确认（不经路由守卫）。
 */
export async function refreshActive(path: string): Promise<void> {
  const name = COMPONENT_NAME_BY_PATH[path]
  if (!name) return
  if (isDirty()) {
    const ok = await askConfirm({
      title: '刷新确认',
      message: '当前页面有未保存的修改，刷新将丢失，确定继续吗？',
      confirmText: '刷新',
      cancelText: '取消',
      danger: true
    })
    if (ok !== true) return
  }
  // 先从 include 移除（watch flush:post，nextTick 后 pruneCache 已执行）
  cachedViewNames.value = cachedViewNames.value.filter(n => n !== name)
  await nextTick()
  await nextTick()
  // 再恢复 include，并递增刷新代数强制重挂
  syncCachedViewNames()
  refreshNonce[path] = (refreshNonce[path] ?? 0) + 1
}

/**
 * 计算关闭某标签后的跳转目标：优先右邻→左邻→affix
 * 返回 path（而非 fullPath），因为每个 path 唯一对应一个标签。
 */
export function pickNeighbor(key: string): string {
  const idx = tabs.findIndex(t => t.key === key)
  if (idx === -1) return AFFIX_PATH
  const right = tabs[idx + 1]
  const left = tabs[idx - 1]
  if (right) return right.key
  if (left) return left.key
  return AFFIX_PATH
}

export function useMultiTabs() {
  return {
    tabs,
    cachedViewNames,
    refreshNonce,
    addTabFromRoute,
    removeTab,
    removeOthers,
    removeRight,
    removeAll,
    refreshActive,
    pickNeighbor
  }
}
