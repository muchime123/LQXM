<script setup>
/**
 * 根组件。布局容器：Header + RouterView + Footer。
 * 全局布局组件在此挂载，各页面只关心自己的内容。
 * 启动时加载一次公司数据，供 Footer / Home / About / Contact 共享。
 */
import { onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from './components/AppHeader.vue'
import AppFooter from './components/AppFooter.vue'
import BackToTop from './components/BackToTop.vue'
import InquiryFormModal from './components/common/InquiryFormModal.vue'
import { useCompany } from './composables/useCompany'
import { useOfficialMedia } from './composables/useOfficialMedia'
import { useCopy } from './composables/useCopy'

const { load: loadCompany } = useCompany()
const { load: loadOfficialMedia } = useOfficialMedia()
const { toast } = useCopy()
const route = useRoute()
const pageClass = computed(() => `site-page site-page--${String(route.name || 'unknown')}`)
onMounted(() => {
  loadCompany()
  loadOfficialMedia()
})
</script>

<template>
  <div id="top" :class="pageClass">
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

</style>
