import type { NavItem } from './types'
import { archiveNavItems } from './archive/navigation'

export type { NavItem } from './types'

/**
 * 全站统一侧栏的导航源。
 *
 * 2026-10-06 按 LQ 决定取消「ERP / 档案与运营」分开进入：侧栏只此一份，
 * 按路由分组的既有顺序连续渲染。
 *
 * 2026-10-06 再按 LQ 决定下线 ERP 前端页面（销售订单/履约评审/生产任务/采购订单/
 * 销售出库/订单协作）：`apps/erp/` 整目录删除，后端与共享 API 层保留。
 * 留底于 commit 23a0eda，需要恢复可从该提交取回。
 */
export const allNavItems: NavItem[] = [...archiveNavItems]
