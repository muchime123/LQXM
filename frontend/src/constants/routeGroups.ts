import type { RouteGroup } from '../types/vue-router'

export interface RouteGroupInfo {
  key: RouteGroup
  label: string
  order: number
}

/**
 * 分组元数据。
 *
 * ⚠️ 2026-09-29 拆应用时删掉了 sales / production / inventory / settlement 四个分组：
 * 它们只存在于这张表里，全仓 `group: 'sales'` 之类**零匹配**（导航项早已归到 erp 与 h3）。
 * 留下它们会让人以为还存在「销售管理」「生产管理」这类独立分组 —— 定义与实现漂移正是
 * 「同一概念两套口径」的温床。
 *
 * ⚠️ 2026-10-06 ERP 前端下线（`apps/erp/` 整目录删除），`erp` 分组同样被删：全仓
 * `group: 'erp'` 零匹配。
 *
 * 注意：`label` 现只用于应用内二级分组的标题；应用本身的名称在 `apps/index.ts`。
 */
export const ROUTE_GROUPS: Record<RouteGroup, RouteGroupInfo> = {
  h3: { key: 'h3', label: '氚云数据管理', order: 0 },
  product: { key: 'product', label: '产品中心', order: 1 },
  marketing: { key: 'marketing', label: '公司与市场', order: 2 },
  system: { key: 'system', label: '系统管理', order: 3 }
}

export const GROUP_ORDER: RouteGroup[] = ['h3', 'product', 'marketing', 'system']

export function getGroupLabel(group: RouteGroup | undefined): string {
  if (!group) return ''
  return ROUTE_GROUPS[group]?.label ?? ''
}
