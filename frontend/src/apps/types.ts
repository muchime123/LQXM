import type { RouteGroup } from '@/types/vue-router'

/**
 * 侧栏导航项：应用外壳与各应用共用的最小结构。
 *
 * 原先定义在 `constants/navigation.ts`，与「档案侧导航清单」混在一个文件里；
 * 拆应用后它属于**外壳层**，所以上提到 `apps/types.ts`，两侧导航各自 import。
 */
export interface NavItem {
  to: string
  label: string
  icon: string
  description?: string
  children?: NavItem[]
  activePaths?: string[]
  permission?: string | string[]
  group?: RouteGroup
}

/** 应用标识。一个应用 = 一套导航根 + 一张路由表。 */
export type AppKey = 'erp' | 'archive'

export interface AppDefinition {
  key: AppKey
  /** 应用显示名（应用切换器上用） */
  label: string
  /** 一句话说明这个应用负责什么 */
  description: string
  /** 应用内首页：从别的应用切过来时落地在这里 */
  landingPath: string
  /** 该应用的一级导航根（侧栏只渲染当前应用的这一份） */
  navItems: NavItem[]
}
