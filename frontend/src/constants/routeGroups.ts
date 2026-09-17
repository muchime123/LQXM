import type { RouteGroup } from '../types/vue-router'

export interface RouteGroupInfo {
  key: RouteGroup
  label: string
  order: number
}

export const ROUTE_GROUPS: Record<RouteGroup, RouteGroupInfo> = {
  h3: { key: 'h3', label: '氚云数据管理', order: 1 },
  sales: { key: 'sales', label: '销售管理', order: 2 },
  production: { key: 'production', label: '生产管理', order: 3 },
  inventory: { key: 'inventory', label: '库存管理', order: 4 },
  settlement: { key: 'settlement', label: '经营核算', order: 5 },
  product: { key: 'product', label: '产品中心', order: 6 },
  marketing: { key: 'marketing', label: '公司与市场', order: 7 },
  system: { key: 'system', label: '系统管理', order: 8 }
}

export const GROUP_ORDER: RouteGroup[] = ['h3', 'sales', 'production', 'inventory', 'settlement', 'product', 'marketing', 'system']

export function getGroupLabel(group: RouteGroup | undefined): string {
  if (!group) return ''
  return ROUTE_GROUPS[group]?.label ?? ''
}
