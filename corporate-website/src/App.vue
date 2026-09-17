<script setup>
/**
 * 根组件。布局容器：Header + RouterView + Footer。
 * 全局布局组件在此挂载，各页面只关心自己的内容。
 * 启动时加载一次公司数据，供 Footer / Home / About / Contact 共享。
 */
import { onMounted, onUnmounted } from 'vue'
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from './components/AppHeader.vue'
import AppFooter from './components/AppFooter.vue'
import BackToTop from './components/BackToTop.vue'
import InquiryFormModal from './components/common/InquiryFormModal.vue'
import { useCompany } from './composables/useCompany'
import { useOfficialMedia } from './composables/useOfficialMedia'
import { useCopy } from './composables/useCopy'
import { applyCmsDraft, clearCmsDraft, applyBannerDraft, clearBannerDraft, selectPreviewBanner, clearAllCmsDrafts, clearDraftsForPage } from './composables/useCmsPreview'

const { load: loadCompany } = useCompany()
const { load: loadOfficialMedia } = useOfficialMedia()
const { toast } = useCopy()
const route = useRoute()
const router = useRouter()
const pageClass = computed(() => `site-page site-page--${String(route.name || 'unknown')}`)
const cmsEditMode = computed(() => route.query.cmsEdit === '1')
/* 编辑模式下当前页面标识：随路由变化，用于 READY / 页面归属。 */
const cmsPageKey = computed(() => {
  if (route.name === 'about') return 'ABOUT'
  if (route.name === 'products') return 'PRODUCTS'
  if (route.name === 'solutions') return 'SOLUTIONS'
  if (route.name === 'contact') return 'CONTACT'
  if (route.name === 'packaging') return 'PACKAGING'
  if (route.name === 'molds') return 'MOLDS'
  return 'HOME'
})
function postCmsMessage(message) {
  if (!cmsEditMode.value || window.parent === window) return
  window.parent.postMessage(message, '*')
}

function focusCmsSection(sectionKey, scroll = false) {
  document.querySelectorAll('[data-cms-active="true"]').forEach((element) => {
    element.removeAttribute('data-cms-active')
  })
  if (!sectionKey) return
  const target = document.querySelector(`[data-cms-section="${CSS.escape(sectionKey)}"]`)
  if (!(target instanceof HTMLElement)) return
  target.dataset.cmsActive = 'true'
  if (scroll) target.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function onCmsClick(event) {
  if (!cmsEditMode.value) return
  const target = event.target instanceof Element ? event.target.closest('[data-cms-section]') : null
  if (!target) return
  event.preventDefault()
  event.stopPropagation()
  focusCmsSection(target.dataset.cmsSection)
  postCmsMessage({
    type: 'LIQIANG_CMS_SELECT',
    pageKey: target.dataset.cmsPage || cmsPageKey.value,
    sectionKey: target.dataset.cmsSection,
    source: target.dataset.cmsSource || 'page-section',
  })
}

/* 编辑模式下站内导航保持 cmsEdit：拦截 Header/Footer 内链，补回编辑参数并通知编辑器切换页面。 */
function onCmsNavClick(event) {
  if (!cmsEditMode.value) return
  const anchor = event.target instanceof Element ? event.target.closest('a[href]') : null
  if (!anchor || anchor.closest('[data-cms-section]')) return
  const href = anchor.getAttribute('href') || ''
  if (/^(https?:|tel:|mailto:|\/\/|#)/i.test(href)) return
  event.preventDefault()
  const url = new URL(anchor.href)
  url.searchParams.set('cmsEdit', '1')
  router.push(url.pathname + url.search + url.hash)
}

/* 编辑模式下页面切换后通知编辑器（如点击官网导航进入 /about），父窗口据此切换结构与表单面板。 */
watch(cmsPageKey, (key) => {
  if (cmsEditMode.value) {
    postCmsMessage({ type: 'LIQIANG_CMS_NAV', pageKey: key })
    postCmsMessage({ type: 'LIQIANG_CMS_READY', app: 'liqiang-corporate-website', pageKey: key })
  }
})

/* 记录最近一次 PING 时间，用于握手超时检测。 */
let lastPingTime = 0
const PING_TIMEOUT = 8000
let pingCheckTimer = null

function onCmsMessage(event) {
  if (!cmsEditMode.value || event.source !== window.parent) return
  if (event.data?.type === 'LIQIANG_CMS_PING') {
    lastPingTime = Date.now()
    postCmsMessage({ type: 'LIQIANG_CMS_READY', app: 'liqiang-corporate-website', pageKey: cmsPageKey.value })
    return
  }
  if (event.data?.type === 'LIQIANG_CMS_DRAFT') applyCmsDraft(event.data)
  if (event.data?.type === 'LIQIANG_CMS_CLEAR_DRAFT') {
    if (event.data.sectionKey === '') {
      clearDraftsForPage(event.data.pageKey)
    } else {
      clearCmsDraft(event.data)
    }
  }
  if (event.data?.type === 'LIQIANG_CMS_BANNER_DRAFT') applyBannerDraft(event.data)
  if (event.data?.type === 'LIQIANG_CMS_CLEAR_BANNER_DRAFT') clearBannerDraft(event.data)
  if (event.data?.type === 'LIQIANG_CMS_CLEAR_ALL_DRAFTS') clearAllCmsDrafts()
  if (event.data?.type === 'LIQIANG_CMS_SHOW_BANNER') selectPreviewBanner(event.data)
  if (event.data?.type === 'LIQIANG_CMS_FOCUS') {
    focusCmsSection(event.data.sectionKey, event.data.scroll !== false)
  }
}

function startPingCheck() {
  if (pingCheckTimer) return
  pingCheckTimer = setInterval(() => {
    if (!cmsEditMode.value) {
      clearInterval(pingCheckTimer)
      pingCheckTimer = null
      return
    }
    if (Date.now() - lastPingTime > PING_TIMEOUT) {
      postCmsMessage({ type: 'LIQIANG_CMS_READY', app: 'liqiang-corporate-website', pageKey: cmsPageKey.value })
    }
  }, 3000)
}

onMounted(() => {
  loadCompany()
  loadOfficialMedia()
  window.addEventListener('message', onCmsMessage)
  document.addEventListener('click', onCmsClick, true)
  document.addEventListener('click', onCmsNavClick, true)
  postCmsMessage({ type: 'LIQIANG_CMS_READY', app: 'liqiang-corporate-website', pageKey: cmsPageKey.value })
  lastPingTime = Date.now()
  startPingCheck()
})
onUnmounted(() => {
  window.removeEventListener('message', onCmsMessage)
  document.removeEventListener('click', onCmsClick, true)
  document.removeEventListener('click', onCmsNavClick, true)
  if (pingCheckTimer) { clearInterval(pingCheckTimer); pingCheckTimer = null }
})
</script>

<template>
  <div id="top" :class="pageClass">
    <div v-if="cmsEditMode" class="cms-edit-mode-bar">可视化编辑预览 · 点击页面区块进行编辑</div>
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <AppHeader />
    <div id="main-content" tabindex="-1"><RouterView /></div>
    <AppFooter />
    <BackToTop />
    <InquiryFormModal />
    <transition name="toast">
      <div v-if="toast" class="copy-toast" role="status">{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.copy-toast {
  position: fixed;
  left: 50%;
  bottom: 40px;
  transform: translateX(-50%);
  background: var(--footer-bg-deep);
  color: var(--white);
  font-size: 14px;
  padding: 10px 20px;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(6, 21, 40, .35);
  border: 1px solid rgba(67, 198, 213, .4);
  z-index: 2000;
  max-width: 90vw;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.toast-enter-active,
.toast-leave-active {
  transition: opacity .25s var(--ease, ease), transform .25s var(--ease, ease);
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(10px);
}

.cms-edit-mode-bar { position: fixed; z-index: 9999; top: 10px; left: 50%; padding: 8px 14px; transform: translateX(-50%); border: 1px solid rgba(87, 205, 226, .55); border-radius: 999px; background: rgba(5, 35, 44, .92); color: #fff; box-shadow: 0 8px 24px rgba(0, 0, 0, .22); font-size: 12px; pointer-events: none; }
:global([data-cms-section]) { position: relative; }
:global(.site-page:has(.cms-edit-mode-bar) [data-cms-section]) { outline: 1px dashed rgba(16, 169, 197, .45); outline-offset: -2px; cursor: pointer; }
:global(.site-page:has(.cms-edit-mode-bar) [data-cms-section]:hover) { outline: 3px solid #16a9c5; outline-offset: -3px; }
:global(.site-page:has(.cms-edit-mode-bar) [data-cms-active="true"]) { z-index: 1; outline: 4px solid #16a9c5; outline-offset: -4px; box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .8), 0 0 0 4px rgba(22, 169, 197, .18); }
</style>
