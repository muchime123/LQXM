import 'vue-router'

export type RouteGroup = 'h3' | 'sales' | 'production' | 'inventory' | 'settlement' | 'product' | 'marketing' | 'system'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题，用于 document.title 同步 */
    title?: string
    /** 页面布局类型，决定路由级骨架屏形状与工作台容器适用边界。
     *  - list（默认）：列表+筛选+分页
     *  - workspace：主从工作台（MasterLayout，左侧列表 + 右侧详情）
     *  - detail：单一详情页
     *  - operation：全屏操作终端（导入/发布/批量）
     *  - default：特殊页面（403/404），不渲染业务骨架 */
    layout?: 'list' | 'workspace' | 'detail' | 'operation' | 'default' | 'auth'
    /** 页面自带标题，ListPageLayout 已渲染页头时为 true */
    pageOwnsTitle?: boolean
    /** 业务场景分组，用于侧边栏分组展示和面包屑 */
    group?: RouteGroup
  }
}
