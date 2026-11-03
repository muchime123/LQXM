<script setup>
/**
 * 顶部导航布局组件。
 * - 一级导航 4 项：首页 / 产品中心 / 解决方案 / 关于利强
 * - 右侧主 CTA：获取包装方案 → /contact?type=consult
 * - 通过 route.meta.navKey 判断当前页并增加高亮（底部蓝绿色指示线）
 * - 资质页已降为 about 下属分区，但保留高亮联系规则（仍回 about）
 * - 滚动 24px 后切换 .scrolled 样式（高斯模糊收缩）
 * - 移动端汉堡菜单切换 .desktop-nav.open
 */
import { computed, ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useInquiryModal } from '../composables/useInquiryModal'
import { trapTabKey } from '../composables/useFocusTrap'

const route = useRoute()
const { openInquiry } = useInquiryModal()
const scrolled = ref(false)
const mobileNavOpen = ref(false)
const navRef = ref(null)
const menuButtonRef = ref(null)

/**
 * 全站一级主导航。key 与 router.meta.navKey 对齐用于高亮。
 */
const navLinks = [
  { navKey: 'home',      label: '首页',      to: '/' },
  { navKey: 'products',  label: '产品中心',  to: '/products' },
  { navKey: 'solutions', label: '解决方案',  to: '/solutions' },
  {
    navKey: 'about',
    label: '关于利强',
    to: '/about',
  },
]

/* 主 CTA：打开询盘弹窗（header + mobile-nav） */
function openHeaderInquiry() {
  openInquiry({ type: 'consult', source: 'header' })
}

const currentNavKey = computed(() => route.meta?.navKey || '')
const isHome = computed(() => route.name === 'home')
const solidHeader = computed(() => scrolled.value || !isHome.value)

function handleScroll() {
  scrolled.value = scrollY > 24
}

function toggleMobileNav() {
  mobileNavOpen.value = !mobileNavOpen.value
}

function closeMobileNav() {
  mobileNavOpen.value = false
}

function handleKeydown(event) {
  if (!mobileNavOpen.value) return
  if (event.key === 'Escape') {
    closeMobileNav()
    /* 键盘关闭后把焦点还回汉堡按钮，否则焦点会落在 body 上丢失位置 */
    menuButtonRef.value?.focus()
    return
  }
  trapTabKey(event, navRef.value)
}

/* 点击菜单与汉堡按钮之外的区域时收起（小屏全屏菜单的 header 区域仍可点） */
function handleOutsidePointer(event) {
  if (!mobileNavOpen.value) return
  const target = event.target instanceof Element ? event.target : null
  if (target && target.closest('.desktop-nav, .menu-button')) return
  closeMobileNav()
}

/* 从窄屏拉宽到桌面时收起，避免 open 状态与 body 滚动锁残留 */
function handleResize() {
  if (window.innerWidth > 980 && mobileNavOpen.value) closeMobileNav()
}

// 路由切换后关闭移动菜单
watch(() => route.fullPath, closeMobileNav)
watch(mobileNavOpen, async (open) => {
  /* 与 home.css 的 body.mobile-nav-active { overflow:hidden } 配合锁定背景滚动 */
  document.body.classList.toggle('mobile-nav-active', open)
  if (!open) return
  await nextTick()
  /* 覆盖式菜单打开后把焦点移入第一项，键盘用户不需要再 Tab 穿过 header 才够到菜单；
     桌面宽度下菜单是行内导航，不抢焦点。 */
  if (window.innerWidth <= 980) navRef.value?.querySelector('.nav-item')?.focus()
})

onMounted(() => {
  window.addEventListener('scroll', handleScroll, { passive: true })
  window.addEventListener('keydown', handleKeydown)
  window.addEventListener('resize', handleResize)
  document.addEventListener('pointerdown', handleOutsidePointer)
  handleScroll()
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
  window.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('resize', handleResize)
  document.removeEventListener('pointerdown', handleOutsidePointer)
  document.body.classList.remove('mobile-nav-active')
})
</script>

<template>
  <header class="site-header" :class="{ scrolled, solid: solidHeader, 'on-home': isHome }">
    <RouterLink to="/" class="brand" aria-label="利强包装首页">
      <span class="brand-icon">
        <img src="/images/logo-only.png" alt="" decoding="async" />
      </span>
      <span class="brand-text">
        <strong class="brand-title">利强</strong>
        <em class="brand-subtitle">LIQIANG</em>
      </span>
    </RouterLink>
    <nav id="primary-nav" ref="navRef" class="desktop-nav" :class="{ open: mobileNavOpen }" aria-label="主导航">
      <div
        v-for="link in navLinks"
        :key="link.navKey"
        class="nav-group"
        :class="{ 'has-children': link.children }"
      >
        <span v-if="link.children && !link.to" class="mobile-nav-group-title">{{ link.label }}</span>
        <RouterLink
          v-if="link.to"
          :to="link.to"
          class="nav-item"
          :class="{ active: currentNavKey === link.navKey }"
          @click="closeMobileNav"
        >{{ link.label }} <span v-if="link.children" class="nav-parent-arrow" aria-hidden="true">⌄</span></RouterLink>
        <button
          v-else
          type="button"
          class="nav-item nav-trigger"
          :class="{ active: currentNavKey === link.navKey }"
          aria-haspopup="true"
        >{{ link.label }} <span aria-hidden="true">⌄</span></button>
        <div v-if="link.children" class="nav-dropdown">
          <RouterLink
            v-for="child in link.children"
            :key="child.to"
            :to="child.to"
            @click="closeMobileNav"
          >
            <strong>{{ child.label }}</strong>
            <small>{{ child.description }}</small>
          </RouterLink>
        </div>
      </div>
      <button type="button" class="mobile-nav-cta" @click="closeMobileNav(); openHeaderInquiry()">获取包装方案 <span>→</span></button>
    </nav>
    <button type="button" class="header-cta" @click="openHeaderInquiry()">获取包装方案 <span>→</span></button>
    <button
      ref="menuButtonRef"
      class="menu-button"
      :aria-expanded="String(mobileNavOpen)"
      aria-controls="primary-nav"
      :aria-label="mobileNavOpen ? '关闭导航' : '打开导航'"
      @click="toggleMobileNav"
    >{{ mobileNavOpen ? '×' : '☰' }}</button>
  </header>
</template>
