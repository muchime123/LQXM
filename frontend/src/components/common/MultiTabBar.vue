<script setup lang="ts">
/**
 * 多标签页导航栏
 *
 * 状态来自 useMultiTabs composable（模块级 reactive，全应用共享）。
 * 无 props/emits，符合项目 composables 模式（如 useTheme/useConfirmDialog）。
 *
 * 编排原则："先导航后改列表"——关闭激活标签时先 router.push(neighbor)，
 * 由 router.beforeEach 守卫处理脏确认；用户取消则 router.push 返回失败，
 * 组件检测到失败即保留标签，避免重复弹窗。
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import LqDropdown from './LqDropdown.vue'
import type { DropdownItem } from './LqDropdown.vue'
import { useMultiTabs, type MultiTabItem } from '../../composables/common/useMultiTabs'

const router = useRouter()
const route = useRoute()
const { tabs, refreshActive, removeTab, removeOthers, removeRight, removeAll, pickNeighbor } =
  useMultiTabs()

const AFFIX_PATH = '/products'

// ===== 横向滚动 =====
const scrollRef = ref<HTMLElement | null>(null)
const canScrollLeft = ref(false)
const canScrollRight = ref(false)

function updateScroll(): void {
  const scroll = scrollRef.value
  if (!scroll) return
  canScrollLeft.value = scroll.scrollLeft > 0
  canScrollRight.value = scroll.scrollLeft + scroll.clientWidth < scroll.scrollWidth - 1
}

function scrollBy(delta: number): void {
  scrollRef.value?.scrollBy({ left: delta, behavior: 'smooth' })
}

function onWheel(e: WheelEvent): void {
  const scroll = scrollRef.value
  if (!scroll) return
  // 无横向溢出时放行，让页面正常垂直滚动
  if (scroll.scrollWidth <= scroll.clientWidth) return
  e.preventDefault()
  scroll.scrollLeft += e.deltaY
}

function scrollActiveIntoView(): void {
  const scroll = scrollRef.value
  if (!scroll) return
  const active = scroll.querySelector<HTMLElement>('.multi-tab__item.is-active')
  if (!active) return
  const left = active.offsetLeft
  const right = left + active.offsetWidth
  if (left < scroll.scrollLeft) {
    scroll.scrollLeft = left
  } else if (right > scroll.scrollLeft + scroll.clientWidth) {
    scroll.scrollLeft = right - scroll.clientWidth
  }
}

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  updateScroll()
  nextTick(scrollActiveIntoView)
  if (scrollRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(updateScroll)
    resizeObserver.observe(scrollRef.value)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})

watch(
  () => tabs.length,
  () => nextTick(updateScroll)
)

watch(
  () => route.fullPath,
  () => nextTick(scrollActiveIntoView)
)

// ===== 标签操作 =====
// 由于标签 key 现已改用 path（不含 query），所有匹配都基于 route.path。
async function onClose(tab: MultiTabItem): Promise<void> {
  if (!tab.closable) return
  // 关闭激活标签：先导航到邻居，由守卫处理脏确认；取消则保留标签
  if (tab.key === route.path) {
    const next = pickNeighbor(tab.key)
    const neighbor = tabs.find(item => item.key === next)
    const failure = await router.push({ path: next, query: neighbor?.query })
    if (failure) return
  }
  removeTab(tab.key)
}

function onTabClick(tab: MultiTabItem): void {
  // 点击激活标签时忽略（已是当前页）
  if (tab.key !== route.path) {
    router.push({ path: tab.path, query: tab.query }) // 守卫处理脏确认
  }
}

function onMiddleClick(e: MouseEvent, tab: MultiTabItem): void {
  if (e.button === 1 && tab.closable) {
    e.preventDefault()
    onClose(tab)
  }
}

async function onTabAction(action: string, key?: string): Promise<void> {
  const activePath = route.path
  switch (action) {
    case 'refresh':
      return refreshActive(activePath)
    case 'close':
      return onClose(tabs.find(t => t.key === (key ?? activePath))!)
    case 'closeOthers':
      return removeOthers(activePath)
    case 'closeRight':
      return removeRight(activePath)
    case 'closeAll': {
      // 含激活非 affix 标签时，先跳 affix（守卫处理脏确认）
      if (activePath !== AFFIX_PATH) {
        const f = await router.push(AFFIX_PATH)
        if (f) return
      }
      return removeAll()
    }
  }
}

// ===== 右键菜单 =====
interface CtxState {
  visible: boolean
  x: number
  y: number
  tab: MultiTabItem | null
}
const ctx = reactive<CtxState>({ visible: false, x: 0, y: 0, tab: null })

function onContextMenu(e: MouseEvent, tab: MultiTabItem): void {
  ctx.tab = tab
  ctx.visible = true
  // y 越界处理（菜单高度约 5 项 * 30px ≈ 150px）
  const menuHeight = 160
  ctx.x = e.clientX
  ctx.y = e.clientY + menuHeight > window.innerHeight ? window.innerHeight - menuHeight - 8 : e.clientY
}

function runCtx(action: string): void {
  const tab = ctx.tab
  ctx.visible = false
  if (action === 'close' && tab) {
    onTabAction('close', tab.key)
  } else {
    onTabAction(action)
  }
}

function closeCtx(): void {
  ctx.visible = false
}

watch(
  () => ctx.visible,
  visible => {
    if (visible) {
      window.addEventListener('mousedown', onWindowMouseDown)
      window.addEventListener('scroll', closeCtx, true)
      window.addEventListener('keydown', onEsc)
    } else {
      window.removeEventListener('mousedown', onWindowMouseDown)
      window.removeEventListener('scroll', closeCtx, true)
      window.removeEventListener('keydown', onEsc)
    }
  }
)

function onWindowMouseDown(e: MouseEvent): void {
  const target = e.target as HTMLElement
  if (!target.closest('.multi-tab__ctx-menu')) closeCtx()
}

function onEsc(e: KeyboardEvent): void {
  if (e.key === 'Escape') closeCtx()
}

onBeforeUnmount(() => {
  window.removeEventListener('mousedown', onWindowMouseDown)
  window.removeEventListener('scroll', closeCtx, true)
  window.removeEventListener('keydown', onEsc)
})

// ===== 右侧"更多"下拉 =====
const moreItems: DropdownItem[] = [
  { command: 'refresh', label: '刷新当前' },
  { command: 'closeOthers', label: '关闭其他' },
  { command: 'closeRight', label: '关闭右侧' },
  { command: 'closeAll', label: '关闭全部', divided: true, type: 'danger' }
]

function onMoreCommand(command: string | number): void {
  onTabAction(String(command))
}

const activeKey = computed(() => route.path)

// 标签 tooltip：基础标题 + 可选 query 摘要（如"产品管理 · 网格视图"）
function buildTooltip(tab: MultiTabItem): string {
  const queryEntries = Object.entries(tab.query ?? {})
  if (queryEntries.length === 0) return tab.title
  const suffix = queryEntries
    .map(([k, v]) => {
      const vals = Array.isArray(v) ? v.join(',') : v
      return `${k}=${vals}`
    })
    .join(' & ')
  return `${tab.title}?${suffix}`
}
</script>

<template>
  <div class="multi-tab-bar">
    <button v-if="canScrollLeft" type="button" class="multi-tab__arrow multi-tab__arrow--left" aria-label="向左滚动"
      @click="scrollBy(-200)">
      ‹
    </button>

    <div ref="scrollRef" class="multi-tab__scroll" @scroll="updateScroll" @wheel="onWheel">
      <div class="multi-tab__list">
        <div v-for="tab in tabs" :key="tab.key" class="multi-tab__item"
          :class="{ 'is-active': tab.key === activeKey, 'is-affix': tab.affix }" :title="buildTooltip(tab)"
          @click="onTabClick(tab)" @contextmenu.prevent="onContextMenu($event, tab)"
          @auxclick="onMiddleClick($event, tab)">
          <span class="multi-tab__icon" v-html="tab.icon" />
          <span class="multi-tab__label">{{ tab.title }}</span>
          <button v-if="tab.closable" type="button" class="multi-tab__close" aria-label="关闭标签"
            @click.stop="onClose(tab)">
            ×
          </button>
        </div>
      </div>
    </div>

    <button v-if="canScrollRight" type="button" class="multi-tab__arrow multi-tab__arrow--right" aria-label="向右滚动"
      @click="scrollBy(200)">
      ›
    </button>

    <LqDropdown class="multi-tab__more" :items="moreItems" trigger="click" placement="bottom-end"
      @command="onMoreCommand">
      <template #trigger>
        <button type="button" class="multi-tab__more-btn" aria-label="标签操作">⋯</button>
      </template>
    </LqDropdown>

    <Teleport to="body">
      <ul v-if="ctx.visible" class="multi-tab__ctx-menu" :style="{ left: ctx.x + 'px', top: ctx.y + 'px' }" @click.stop
        @contextmenu.prevent>
        <li @click="runCtx('refresh')">刷新当前</li>
        <li :class="{ 'is-disabled': !ctx.tab?.closable }" @click="runCtx('close')">关闭</li>
        <li @click="runCtx('closeOthers')">关闭其他</li>
        <li @click="runCtx('closeRight')">关闭右侧</li>
        <li class="is-divided is-danger" @click="runCtx('closeAll')">关闭全部</li>
      </ul>
    </Teleport>
  </div>
</template>
