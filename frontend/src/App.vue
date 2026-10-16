<template>
  <div
    class="route-progress-bar"
    :class="{ 'is-visible': routeProgressState.visible, 'is-failed': routeProgressState.failed }"
    :style="{ '--route-progress-width': `${routeProgressState.width}%` }"
    aria-hidden="true"
    data-testid="route-progress-bar"
  />

  <!-- P7：登录页等 auth 布局页面使用独立全屏布局 -->
  <DefaultLayout v-if="!isAuthLayout">
    <RouterView v-slot="{ Component }">
      <Transition name="route-fade" mode="out-in">
        <KeepAlive :max="20" :include="cachedViewNames">
          <Suspense>
            <div class="route-host" style="display: contents">
              <component :is="Component" :key="componentKey" />
            </div>
            <template #fallback>
              <RouteSkeleton :layout="currentRouteLayout" />
            </template>
          </Suspense>
        </KeepAlive>
      </Transition>
    </RouterView>
  </DefaultLayout>
  <Suspense v-else>
    <RouterView />
    <template #fallback>
      <div class="auth-route-fallback" aria-hidden="true" />
    </template>
  </Suspense>

  <LqPreview
    v-model="attachmentPreview.visible"
    :title="attachmentPreview.title"
    :file-name="attachmentPreview.fileName"
    :url="attachmentPreview.fileUrl"
    :category="attachmentPreview.category"
    :model-id="attachmentPreview.modelId"
    :owner-type="attachmentPreview.ownerType"
    :asset-id="attachmentPreview.assetId"
    :gallery-index="attachmentPreview.index"
    :gallery-count="attachmentPreview.assets.length"
    @close="closeAssetPreview"
    @prev="shiftAssetPreview(-1)"
    @next="shiftAssetPreview(1)"
  />

  <!-- 全局销售出库台账详情抽屉：任意页面通过 ?orderId=xxx 打开（orderId = sales_outbounds.id） -->
  <H3LedgerDetailDrawer
    v-if="!isAuthLayout"
    v-model="ledgerVisible"
    ledger-type="outbound"
    :loading="ledgerLoading"
    :data="ledgerDetail"
    :error="ledgerError"
    @update:model-value="onLedgerVisibleChange"
    @updated="reloadGlobalLedgerDetail"
  />

  <LqConfirmDialog
    v-model="confirmDialog.visible"
    :title="confirmDialog.title"
    :subtitle="confirmDialog.subtitle"
    :type="confirmDialog.type"
    :confirm-text="confirmDialog.confirmText"
    :cancel-text="confirmDialog.cancelText"
    :secondary-text="confirmDialog.secondaryText"
    :loading="confirmDialog.loading"
    @close="handleConfirmCancel"
    @cancel="handleConfirmCancel"
    @confirm="handleConfirmOk"
    @secondary="handleConfirmSecondary"
  >
    <div style="white-space: pre-line">{{ confirmDialog.message }}</div>
  </LqConfirmDialog>

  <ErrorBoundaryOverlay />
  <LqLoading />
</template>

<script setup lang="ts">
import { reactive, computed, provide, ref, onMounted, onErrorCaptured, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import DefaultLayout from './components/common/DefaultLayout.vue'
import LqPreview from './components/common/LqPreview.vue'
import LqConfirmDialog from './components/common/LqConfirmDialog.vue'
import ErrorBoundaryOverlay from './components/common/ErrorBoundaryOverlay.vue'
import LqLoading from './components/common/LqLoading.vue'
import RouteSkeleton from './components/common/RouteSkeleton.vue'
import H3LedgerDetailDrawer from './components/h3/H3LedgerDetailDrawer.vue'
import { fetchLedgerDetail } from './api/business/orders'
import type { LedgerDetailResult } from './api/business/orders'
import { fetchCatalogStats } from './api/catalog'
import { useCurrentUser } from './composables/auth/useCurrentUser'
import { useAssetPreview } from './composables/ui/useAssetPreview'
import { confirmDialog, resolveConfirm } from './composables/ui/useConfirmDialog'
import { captureError } from './composables/ui/useErrorBoundary'
import { routeProgressState } from './composables/ui/useRouteProgress'
import { cachedViewNames, refreshNonce } from './composables/common/useMultiTabs'
import { PREVIEW_ASSET_KEY, IMPORTED_KEY } from './composables/ui/useAppBridge'

const route = useRoute()
const router = useRouter()
const currentRouteLayout = computed(() => route.meta?.layout ?? 'list')
const isAuthLayout = computed(() => route.meta?.layout === 'auth')

// 全局销售出库台账详情抽屉：通过 ?orderId=xxx 打开（orderId = sales_outbounds.id，与氚云台账同源）
const ledgerVisible = ref(false)
const ledgerLoading = ref(false)
const ledgerDetail = ref<LedgerDetailResult | null>(null)
const ledgerError = ref('')
const ledgerOutboundId = computed(() => {
  const id = Number(route.query.orderId)
  return Number.isSafeInteger(id) && id > 0 ? id : null
})

let ledgerRequestSeq = 0
watch(ledgerOutboundId, async (id) => {
  const seq = ++ledgerRequestSeq
  if (id == null) {
    ledgerVisible.value = false
    return
  }
  ledgerVisible.value = true
  ledgerDetail.value = null
  ledgerError.value = ''
  ledgerLoading.value = true
  try {
    const r = await fetchLedgerDetail('outbound', id)
    if (seq === ledgerRequestSeq) ledgerDetail.value = r
  } catch (e) {
    if (seq === ledgerRequestSeq) ledgerError.value = (e as Error).message || '台账详情加载失败'
  } finally {
    if (seq === ledgerRequestSeq) ledgerLoading.value = false
  }
})

async function reloadGlobalLedgerDetail() {
  const id = ledgerOutboundId.value
  if (id == null) return
  ledgerDetail.value = await fetchLedgerDetail('outbound', id)
}

function onLedgerVisibleChange(open: boolean) {
  if (open) {
    ledgerVisible.value = true
  } else {
    ledgerVisible.value = false
    // 关闭时清除 orderId 参数（保留其他 query）
    if (route.query.orderId) {
      const { orderId: _o, ...rest } = route.query
      router.replace({ path: route.path, query: rest })
    }
  }
}

// 组件 key：path 稳定 + 刷新代数。切换标签命中 KeepAlive 缓存不重载，
// "刷新当前"通过递增 refreshNonce[path] 使 key 变化，强制重挂。
const componentKey = computed(() => `${route.path}:${refreshNonce[route.path] ?? 0}`)

const { attachmentPreview, openAssetPreview, closeAssetPreview, shiftAssetPreview } = useAssetPreview()

function handleConfirmCancel() {
  resolveConfirm(false)
}

function handleConfirmOk() {
  resolveConfirm(true)
}

function handleConfirmSecondary() {
  resolveConfirm('secondary')
}

const catalogStats = reactive({ modelCount: 0, variantCount: 0, assetCount: 0 })
const { user } = useCurrentUser()

async function loadStats() {
  // 未登录时不请求统计（避免未认证 401 告警；登录后由 watch 自动触发）
  if (!user.value) return
  try {
    Object.assign(catalogStats, await fetchCatalogStats())
  } catch (err) {
    console.warn('加载统计失败', err)
  }
}

watch(user, u => {
  if (u) {
    loadStats()
  } else {
    Object.assign(catalogStats, { modelCount: 0, variantCount: 0, assetCount: 0 })
  }
})
onMounted(loadStats)

provide(PREVIEW_ASSET_KEY, openAssetPreview)
provide(IMPORTED_KEY, loadStats)

onErrorCaptured((err, instance, info) => {
  captureError(err, 'render', instance, info)
  return false
})
</script>

<style>
.route-fade-enter-active,
.route-fade-leave-active {
  transition: opacity var(--transition-fast), transform var(--transition-fast);
}

.route-fade-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.route-fade-leave-to {
  opacity: 0;
}

.auth-route-fallback {
  position: fixed;
  inset: 0;
  background: var(--color-bg, #f6f7f9);
}
</style>
