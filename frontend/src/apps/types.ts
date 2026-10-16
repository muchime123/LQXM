import type { RouteGroup } from '@/types/vue-router'

/**
 * 侧栏导航项：应用外壳与各应用共用的最小结构。
 *
 * 原先定义在 `constants/navigation.ts`，与「档案侧导航清单」混在一个文件里；
 * 拆应用后它属于**外壳层**，所以上提到 `apps/types.ts`。
 *
 * ⚠️ 2026-10-06 ERP 前端下线并取消「ERP / 档案分开进入」后，原先的
 * `AppKey` / `AppDefinition`（应用切换器模型）已无使用方，一并删除。
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
