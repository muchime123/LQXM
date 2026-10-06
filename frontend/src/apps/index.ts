import type { NavItem } from './types'
import { erpNavItems } from './erp/navigation'
import { archiveNavItems } from './archive/navigation'

export type { NavItem } from './types'

/**
 * 全站统一侧栏的导航源。
 *
 * 2026-10-06 按 LQ 决定取消「ERP / 档案与运营」分开进入：侧栏只此一份，
 * 按路由分组的既有顺序（erp → h3 → product → marketing → system）连续渲染。
 * `apps/{erp,archive}` 目录拆分保留 —— 那是代码层的文件归属，用户不可见。
 */
export const allNavItems: NavItem[] = [...erpNavItems, ...archiveNavItems]
