<template>
  <main class="shell" :class="{ 'shell--collapsed': sidebarCollapsed }">
    <aside class="sidebar" :class="{ 'sidebar--collapsed': sidebarCollapsed }">
      <div class="brand">
        <div class="brand-main">
          <span class="brand-icon">
            <img src="/logo-only.png" alt="利强 LIQIANG" />
          </span>
          <span class="brand-text">
            <strong class="brand-title">利强</strong>
            <em class="brand-subtitle">LIQIANG</em>
          </span>
        </div>
        <p class="brand-desc">利强包装科技有限公司</p>
      </div>

      <button
        class="nav-toggle"
        type="button"
        :aria-expanded="mobileNavOpen"
        aria-controls="primary-navigation"
        :aria-label="mobileNavOpen ? '关闭导航' : '打开导航'"
        @click="mobileNavOpen = !mobileNavOpen"
      >
        <span aria-hidden="true">{{ mobileNavOpen ? '×' : '☰' }}</span>
      </button>

      <nav
        id="primary-navigation"
        class="nav"
        :class="{ 'is-open': mobileNavOpen }"
        aria-label="主导航"
      >
        <template v-for="(group, groupIndex) in groupedNavItems" :key="group.key">
          <div
            class="nav-group"
            :class="{ 'nav-group--first': groupIndex === 0 }"
          >
            <div class="nav-group__items">
              <template v-for="item in group.items" :key="item.to">
                <template v-if="item.children && item.children.length">
                  <div
                    class="nav-item-wrap nav-item-wrap--parent"
                    :class="{ active: isParentActive(item) }"
                  >
                    <RouterLink
                      :to="item.children[0]?.to || item.to"
                      class="nav-item nav-item--parent-main"
                      :title="item.description ?? (sidebarCollapsed ? item.label : undefined)"
                      active-class="active"
                      @click="handleParentClick(item.to)"
                    >
                      <span class="nav-icon" v-html="item.icon"></span>
                      <span class="nav-label">{{ item.label }}</span>
                    </RouterLink>
                    <button
                      type="button"
                      class="nav-item nav-item--parent-arrow"
                      :aria-label="openSubmenus.has(item.to) ? '收起子菜单' : '展开子菜单'"
                      @click.stop="toggleSubmenu(item.to)"
                    >
                      <span class="nav-arrow" :class="{ 'nav-arrow--open': openSubmenus.has(item.to) }">›</span>
                    </button>
                  </div>
                  <div v-show="openSubmenus.has(item.to) && !sidebarCollapsed" class="nav-submenu">
                    <RouterLink
                      v-for="child in item.children"
                      :key="child.to"
                      :to="child.to"
                      class="nav-item nav-item--child"
                      :class="{ active: isNavActive(child) }"
                      active-class="active"
                      @click="mobileNavOpen = false"
                    >
                      <span class="nav-icon nav-icon--child" v-html="child.icon"></span>
                      <span class="nav-label">{{ child.label }}</span>
                    </RouterLink>
                  </div>
                </template>
                <LqTooltip
                  v-else
                  :content="navTooltip(item)"
                  placement="right"
                  popper-class="nav-description-tooltip"
                  :disabled="!item.description && !sidebarCollapsed"
                  :show-after="300"
                >
                  <RouterLink
                    :to="item.to"
                    class="nav-item"
                    active-class="active"
                    :title="item.description ?? (sidebarCollapsed ? item.label : undefined)"
                    @click="mobileNavOpen = false"
                  >
                    <span class="nav-icon" v-html="item.icon"></span>
                    <span class="nav-label">{{ item.label }}</span>
                  </RouterLink>
                </LqTooltip>
              </template>
            </div>
          </div>
        </template>
      </nav>

      <div class="sidebar__user" :class="{ 'sidebar__user--collapsed': sidebarCollapsed }">
        <el-dropdown trigger="click" placement="top-start" @command="onUserCommand">
          <button class="user-chip" type="button" :title="user?.name || '未登录'">
            <span class="user-avatar">{{ avatarText }}</span>
            <span v-if="!sidebarCollapsed" class="user-meta">
              <span class="user-name">
                {{ user?.name || '未登录' }}
                <el-tag
                  v-if="user?.compatibilityMode"
                  size="small"
                  type="warning"
                  effect="plain"
                  class="user-dev-tag"
                >开发</el-tag>
              </span>
              <span class="user-dept">{{ user?.department || (user?.loginType === 'NONE' ? '请先登录' : '') }}</span>
            </span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>
                {{ user?.name || '未登录' }} · {{ user?.department || '未分部门' }}
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <div class="sidebar__footer">
        <button
          class="sidebar__collapse"
          type="button"
          :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'"
          :aria-label="sidebarCollapsed ? '展开侧栏' : '收起侧栏'"
          :aria-expanded="!sidebarCollapsed"
          @click="toggleSidebar"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            width="16"
            height="16"
          >
            <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
            <line x1="15" y1="3" x2="15" y2="21" />
            <polyline points="10 9 7 12 10 15" />
          </svg>
        </button>
        <button
          class="theme-toggle"
          type="button"
          :title="isDark ? '切换浅色模式' : '切换深色模式'"
          :aria-label="isDark ? '切换浅色模式' : '切换深色模式'"
          @click="toggleTheme"
        >
          <svg
            v-if="!isDark"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            width="16"
            height="16"
          >
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
          </svg>
          <svg
            v-else
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            width="16"
            height="16"
          >
            <circle cx="12" cy="12" r="5" />
            <line x1="12" y1="1" x2="12" y2="3" />
            <line x1="12" y1="21" x2="12" y2="23" />
            <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
            <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
            <line x1="1" y1="12" x2="3" y2="12" />
            <line x1="21" y1="12" x2="23" y2="12" />
            <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
            <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
          </svg>
        </button>
      </div>
    </aside>

    <section class="workspace">
      <header class="topbar">
        <MultiTabBar />
      </header>
      <GlobalStatusBanner />
      <div class="workspace__body">
        <slot />
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import GlobalStatusBanner from './GlobalStatusBanner.vue'
import MultiTabBar from './MultiTabBar.vue'
import LqTooltip from './LqTooltip.vue'
import { allNavItems } from '../../apps'
import type { NavItem } from '../../apps'
import { ROUTE_GROUPS, GROUP_ORDER } from '../../constants/routeGroups'
import type { RouteGroup } from '../../types/vue-router'
import { useTheme } from '../../composables/ui/useTheme'
import { useCurrentUser } from '../../composables/auth/useCurrentUser'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const { isDark, toggleTheme } = useTheme()
const { user, load: loadCurrentUser, can, logout } = useCurrentUser()
void loadCurrentUser()

const avatarText = computed(() => (user.value?.name || '未').slice(0, 1))

async function onUserCommand(command: string) {
  if (command !== 'logout') return
  await logout()
  ElMessage.success('已退出登录')
  router.replace({ path: '/login' })
}

/**
 * 2026-10-06 按 LQ 决定取消「ERP / 档案与运营」分开进入：侧栏只此一份，
 * 连续渲染两侧导航根的并集（顺序仍按路由分组 erp → h3 → product → marketing → system）。
 * `apps/{erp,archive}` 目录拆分保留 —— 那是代码层的文件归属，用户不可见。
 */
const filteredNavItems = computed(() =>
  allNavItems
    .map(item => ({
      ...item,
      children: item.children?.filter(child => can(child.permission))
    }))
    .filter(item => can(item.permission) && (!item.children || item.children.length > 0))
)

interface GroupedNavItems {
  key: RouteGroup
  label: string
  items: NavItem[]
}

const groupedNavItems = computed<GroupedNavItems[]>(() => {
  const groupMap = new Map<RouteGroup, NavItem[]>()

  for (const item of filteredNavItems.value) {
    const groupKey = (item.group as RouteGroup) || item.children?.[0]?.group as RouteGroup
    if (groupKey) {
      if (!groupMap.has(groupKey)) {
        groupMap.set(groupKey, [])
      }
      groupMap.get(groupKey)!.push(item)
    }
  }

  return GROUP_ORDER
    .filter(key => groupMap.has(key))
    .map(key => ({
      key,
      label: ROUTE_GROUPS[key].label,
      items: groupMap.get(key)!
    }))
})

const mobileNavOpen = ref(false)

function navTooltip(item: NavItem) {
  return item.description ?? item.label
}

const openSubmenus = ref(new Set<string>())

function isNavActive(item: NavItem): boolean {
  return [item.to, ...(item.activePaths || [])].some(path => route.path === path || route.path.startsWith(`${path}/`))
}
function isParentActive(item: NavItem): boolean {
  if (route.path === item.to) return true
  if (item.children) {
    return item.children.some(isNavActive)
  }
  return false
}

function toggleSubmenu(to: string) {
  if (openSubmenus.value.has(to)) {
    openSubmenus.value.delete(to)
  } else {
    openSubmenus.value.add(to)
  }
  openSubmenus.value = new Set(openSubmenus.value)
}

function handleParentClick(to: string) {
  if (!openSubmenus.value.has(to)) {
    openSubmenus.value.add(to)
    openSubmenus.value = new Set(openSubmenus.value)
  }
  mobileNavOpen.value = false
}

watch(
  () => route.path,
  () => {
    for (const item of filteredNavItems.value) {
      if (item.children && item.children.some(isNavActive)) {
        openSubmenus.value.add(item.to)
      }
    }
    openSubmenus.value = new Set(openSubmenus.value)
  },
  { immediate: true }
)

const sidebarCollapsed = ref(localStorage.getItem('lq.sidebar.collapsed') === '1')
watch(sidebarCollapsed, value => {
  localStorage.setItem('lq.sidebar.collapsed', value ? '1' : '0')
})

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

watch(
  () => route.fullPath,
  () => {
    mobileNavOpen.value = false
  }
)
</script>

<style scoped>
.shell {
  height: 100vh;
  display: grid;
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
  transition: grid-template-columns var(--transition-slow);
}

.shell--collapsed {
  grid-template-columns: var(--sidebar-width-collapsed) minmax(0, 1fr);
}

.sidebar {
  height: 100vh;
  min-height: 0;
  background: var(--color-sidebar-bg);
  color: var(--color-sidebar-text-strong);
  padding: 20px 18px;
  display: flex;
  flex-direction: column;
  gap: var(--space-xl-2xl);
  transition:
    padding var(--transition-slow),
    width var(--transition-slow);
  overflow: hidden;
}

.sidebar--collapsed {
  padding: 16px 10px;
}

.brand {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: var(--space-sm);
  padding: 0 4px;
}

.brand-main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-icon {
  display: block;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
}

.brand-icon img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: contain;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.05;
  gap: 3px;
}

.brand-title {
  display: block;
  font-size: 18px;
  font-weight: 800;
  color: var(--color-sidebar-text-strong);
  letter-spacing: 2.5px;
  font-style: normal;
}

.brand-subtitle {
  display: block;
  font-size: 10px;
  font-weight: 600;
  color: var(--color-brand-cyan);
  letter-spacing: 4px;
  font-style: normal;
}

.brand-desc {
  margin: 4px 0 0;
  padding: 0;
  color: var(--color-sidebar-muted);
  font-size: var(--fs-xs);
  letter-spacing: 0.08em;
  line-height: 1;
}

.sidebar--collapsed .brand {
  margin-bottom: var(--space-md);
  padding: 0;
}

.sidebar--collapsed .brand-main {
  flex-direction: column;
  gap: var(--space-xs);
  text-align: center;
}

.sidebar--collapsed .brand-icon {
  width: 40px;
  height: 40px;
}

.sidebar--collapsed .brand-text,
.sidebar--collapsed .brand-desc {
  display: none;
}

.nav {
  display: grid;
  flex: 1;
  min-height: 0;
  align-content: start;
  gap: 0;
  margin: 0 -8px;
  padding: 0 8px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--color-sidebar-active) transparent;
}

.nav::-webkit-scrollbar {
  width: 6px;
}

.nav::-webkit-scrollbar-thumb {
  border-radius: var(--radius-pill);
  background: var(--color-sidebar-active);
}

.nav-group {
  display: flex;
  flex-direction: column;
  padding: 0 10px;
}

.nav-group + .nav-group {
  /* 仅当紧跟上一组时施加小节距，模拟分组节奏而非大留白 */
  margin-top: 6px;
}

.nav-group__items {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 9px 16px;
  text-align: left;
  text-decoration: none;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  color: var(--color-sidebar-text);
  font-size: var(--fs-md);
  font-weight: var(--fw-medium);
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.sidebar--collapsed .nav-item {
  justify-content: center;
  gap: 0;
  padding: 9px;
  border-radius: var(--radius-md);
}

.nav-label {
  transition: opacity var(--transition-slow);
  white-space: nowrap;
  line-height: 1.3;
}

.sidebar--collapsed .nav-label {
  display: none;
}

.sidebar--collapsed .nav-icon {
  width: 20px;
  height: 20px;
}

.nav-item:hover {
  background: color-mix(in srgb, var(--color-sidebar-active, #0ea5e9) 7%, transparent);
  color: var(--color-sidebar-text-strong);
}

.sidebar--collapsed .nav-item:hover {
  background: color-mix(in srgb, var(--color-sidebar-active, #0ea5e9) 10%, transparent);
}

.nav-item.active {
  background: color-mix(in srgb, var(--color-sidebar-active, #0ea5e9) 10%, transparent);
  color: var(--color-sidebar-text-strong);
  font-weight: var(--fw-semibold);
}

.nav-item.active::before {
  content: "";
  position: absolute;
  left: -1px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  border-radius: 0 3px 3px 0;
  background: var(--color-sidebar-active, #0ea5e9);
  opacity: 0.9;
}

.sidebar--collapsed .nav-item.active::before {
  left: 50%;
  top: auto;
  bottom: 4px;
  transform: translateX(-50%);
  width: 18px;
  height: 2px;
  border-radius: 2px;
}

.sidebar--collapsed .nav-item.active {
  background: color-mix(in srgb, var(--color-sidebar-active, #0ea5e9) 14%, transparent);
}

.nav-icon {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  opacity: 0.75;
  transition: opacity var(--transition-fast), transform var(--transition-fast);
}

.nav-item:hover .nav-icon,
.nav-item.active .nav-icon {
  opacity: 1;
}

.nav-item-wrap {
  display: flex;
  align-items: stretch;
  gap: 0;
  width: 100%;
  border-radius: var(--radius-md);
}

.nav-item-wrap--parent.active {
  background: color-mix(in srgb, var(--color-sidebar-active, #0ea5e9) 5%, transparent);
}

.nav-item--parent-main {
  flex: 1 1 auto;
  border-radius: var(--radius-md) 0 0 var(--radius-md);
}

.nav-item--parent-arrow {
  flex: 0 0 32px;
  width: 32px;
  min-width: 0;
  padding: 0;
  border-radius: 0 var(--radius-md) var(--radius-md) 0;
  background: transparent;
  border: none;
  color: var(--color-sidebar-text);
  opacity: 0.55;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.15s ease, background-color 0.15s ease;
}

.nav-item--parent-arrow:hover {
  opacity: 1;
  background: color-mix(in srgb, var(--color-sidebar-active, #0ea5e9) 6%, transparent);
}

.nav-item-wrap--parent.active .nav-item--parent-arrow {
  background: color-mix(in srgb, var(--color-sidebar-active, #0ea5e9) 8%, transparent);
  color: var(--color-sidebar-text-strong);
  opacity: 0.95;
}

.nav-arrow {
  margin-left: 0;
  font-size: 16px;
  line-height: 1;
  transition: transform 0.18s ease, opacity 0.18s ease;
}

.nav-arrow--open {
  transform: rotate(90deg);
}

.nav-submenu {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0 2px 28px;
}

.sidebar--collapsed .nav-item-wrap--parent {
  position: relative;
}

.sidebar--collapsed .nav-item--parent-main {
  border-radius: var(--radius-md);
}

.sidebar--collapsed .nav-item--parent-arrow {
  display: none;
}

.sidebar--collapsed .nav-submenu {
  display: none;
}

.nav-item--child {
  padding: 6px 14px;
  font-size: var(--fs-sm);
  font-weight: var(--fw-normal);
  color: var(--color-sidebar-text);
  opacity: 0.85;
}

.nav-item--child:hover {
  opacity: 1;
}

.nav-item--child.active {
  opacity: 1;
}

.nav-icon--child {
  width: 16px;
  height: 16px;
}

.workspace {
  height: 100vh;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.workspace__body {
  display: flex;
  flex-direction: column;
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: var(--page-block-padding) var(--page-inline-padding);
}

.workspace__body > * {
  flex: 1 0 auto;
  width: 100%;
  min-height: 100%;
  box-sizing: border-box;
}

.nav-toggle {
  display: none;
}

.sidebar__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap);
  margin-top: auto;
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-sidebar-overlay-border);
  transition: all var(--transition-base);
}

.sidebar--collapsed .sidebar__footer {
  flex-direction: column;
  gap: var(--space-xs);
  padding-top: var(--space-sm);
}

.sidebar__collapse,
.theme-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  flex: 1 1 0;
  min-width: 0;
  padding: 10px 14px;
  border: 1px solid var(--color-sidebar-overlay-border);
  border-radius: var(--radius-lg);
  background: var(--color-sidebar-overlay-bg);
  color: var(--color-sidebar-muted);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  cursor: pointer;
  transition: all var(--transition-base);
}

.sidebar--collapsed .sidebar__collapse,
.sidebar--collapsed .theme-toggle {
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  padding: 0;
  border-radius: var(--radius-md);
}

.sidebar__collapse:hover,
.theme-toggle:hover {
  background: var(--color-sidebar-overlay-hover-bg);
  color: var(--color-sidebar-text-strong);
  border-color: var(--color-sidebar-overlay-hover-border);
}

.sidebar__collapse svg,
.theme-toggle svg {
  flex-shrink: 0;
}

.sidebar__user {
  margin-top: auto;
  padding-bottom: var(--space-md);
}

.sidebar__user--collapsed {
  display: flex;
  justify-content: center;
  padding-bottom: var(--space-sm);
}

.user-chip {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--color-sidebar-overlay-border);
  border-radius: var(--radius-lg);
  background: var(--color-sidebar-overlay-bg);
  color: var(--color-sidebar-text-strong);
  cursor: pointer;
  transition: all var(--transition-base);
  text-align: left;
}

.user-chip:hover {
  background: var(--color-sidebar-overlay-hover-bg);
  border-color: var(--color-sidebar-overlay-hover-border);
}

.sidebar__user--collapsed .user-chip {
  width: 40px;
  padding: 0;
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  justify-content: center;
}

.user-avatar {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--color-sidebar-active);
  color: var(--color-sidebar-text-strong);
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
}

.sidebar__user--collapsed .user-avatar {
  width: 36px;
  height: 36px;
}

.user-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.sidebar__user--collapsed .user-meta {
  display: none;
}

.user-name {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-dev-tag {
  transform: scale(0.85);
  transform-origin: left center;
}

.user-dept {
  font-size: var(--fs-xs);
  color: var(--color-sidebar-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 小屏侧栏密度：全局 responsive.css 收窄 --sidebar-width 后，同步收紧侧栏
   内边距与导航字号，否则长菜单名（如「销售出库产品分析」需约 196px）会截断。 */
@media (max-width: 1440px) {
  .sidebar {
    padding: 18px 14px;
  }

  .nav-item {
    gap: 10px;
    padding: 9px 12px;
    font-size: var(--fs-base);
  }
}

@media (max-width: 1024px) {
  .sidebar {
    padding: 16px 11px;
  }

  .nav-item {
    gap: 8px;
    padding: 8px 10px;
    font-size: var(--fs-sm);
  }

  .nav-item--child {
    padding: 6px 10px;
  }
}

@media (max-width: 767px) {
  .shell {
    height: auto;
    min-height: 100vh;
    grid-template-columns: minmax(0, 1fr);
    overflow-x: clip;
  }

  .sidebar {
    position: relative;
    z-index: var(--z-sticky);
    display: grid;
    height: auto;
    grid-template-columns: minmax(0, 1fr) auto auto;
    gap: var(--space-control-gap);
    padding: var(--space-control-gap-wide);
  }

  .brand {
    min-width: 0;
    margin: 0;
  }

  .brand-logo {
    width: 128px;
    height: 48px;
  }

  .brand p {
    display: none;
  }

  .nav-toggle {
    display: grid;
    grid-column: 2;
    grid-row: 1;
    place-items: center;
    min-width: var(--control-height-lg);
    min-height: var(--control-height-lg);
    padding: var(--space-control-gap);
    border: 1px solid var(--color-sidebar-active);
    border-radius: var(--radius-lg);
    background: var(--color-sidebar-hover);
    color: var(--color-sidebar-text);
    font-size: var(--fs-xl);
    cursor: pointer;
  }

  .nav-toggle:hover,
  .nav-toggle:focus-visible {
    background: var(--color-sidebar-active);
    color: var(--color-sidebar-text);
  }

  .nav {
    display: none;
    grid-column: 1 / -1;
    grid-row: 2;
    gap: var(--space-sm);
    margin: 0;
    padding: var(--space-control-gap-tight) 0 0;
    overflow: visible;
  }

  .nav.is-open {
    display: grid;
  }

  .nav-item {
    width: 100%;
    padding: var(--space-control-gap) var(--space-control-gap-wide);
    font-size: var(--fs-sm);
  }

  .nav-icon {
    width: 18px;
    height: 18px;
  }

  .sidebar__footer {
    grid-column: 3;
    grid-row: 1;
    margin-top: 0;
    padding-top: 0;
    border-top: 0;
  }

  .sidebar__collapse {
    display: none;
  }

  .workspace {
    height: auto;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  .workspace__body {
    display: block;
    overflow: visible;
    padding: var(--space-section-gap) var(--space-control-gap-wide);
  }

  .workspace__body > * {
    min-height: 0;
  }
}
</style>
