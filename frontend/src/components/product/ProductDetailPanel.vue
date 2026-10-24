<template>
  <!-- 工作台模式：内联 sticky 面板 -->
  <section
    v-if="displayMode === 'workbench'"
    v-show="!!detail && !detailSuspended"
    class="product-detail-drawer pinned"
    role="dialog"
    :aria-label="detail?.model?.name || '产品详情'"
  >
    <div class="drawer-body" :class="{ 'drawer-body--assets': activeTab === 'assets' }">
      <div v-if="detail" class="drawer-detail-content">
        <div v-if="selectingModel" class="catalog-detail-loading">加载产品详情...</div>
        <div v-else class="drawer-detail-body">
          <MasterDetailHeader :tabs="detailTabs" :active="activeTab" @update:active="emit('update:active-tab', $event)">
            <template #actions>
              <button type="button" class="btn-sm" @click="emit('open-model-editor')">编辑</button>
              <div class="action-menu">
                <button type="button" class="btn-sm ghost" @click.stop="emit('toggle-title-menu', $event)">更多</button>
              </div>
            </template>
          </MasterDetailHeader>

          <Transition name="tab-fade" mode="out-in">
            <div :key="activeTab" class="drawer-tab-transition">
              <slot />
            </div>
          </Transition>
        </div>
      </div>
    </div>
  </section>

  <!-- 弹窗模式：LqDialog mode=detail（浏览模式下从右侧滑入列表上方） -->
  <LqDialog
    v-else
    :model-value="drawerVisible && !!detail && !detailSuspended"
    size="xl"
    mode="detail"
    :scrollable="false"
    :show-footer="false"
    :close-on-click-modal="true"
    @close="emit('close-drawer')"
  >
    <template #title>
      <div class="lq-dialog__title">
        <div class="lq-dialog__title-main">
          <span class="lq-dialog__title-text">产品详情</span>
        </div>
      </div>
    </template>

    <div class="drawer-body" :class="{ 'drawer-body--assets': activeTab === 'assets' }">
      <div v-if="detail" class="drawer-detail-content">
        <div v-if="selectingModel" class="catalog-detail-loading">加载产品详情...</div>
        <div v-else class="drawer-detail-body">
          <MasterDetailHeader :tabs="detailTabs" :active="activeTab" @update:active="emit('update:active-tab', $event)">
            <template #actions>
              <button type="button" class="btn-sm" @click="emit('open-model-editor')">编辑</button>
              <div class="action-menu">
                <button type="button" class="btn-sm ghost" @click.stop="emit('toggle-title-menu', $event)">更多</button>
              </div>
            </template>
          </MasterDetailHeader>

          <Transition name="tab-fade" mode="out-in">
            <div :key="activeTab" class="drawer-tab-transition">
              <slot />
            </div>
          </Transition>
        </div>
      </div>
    </div>
  </LqDialog>
</template>

<script setup>
import LqDialog from '@/components/common/LqDialog.vue'
import MasterDetailHeader from '@/components/master/MasterDetailHeader.vue'

const props = defineProps({
  detail: { type: Object, default: null },
  activeTab: { type: String, default: 'variants' },
  displayMode: { type: String, default: 'browse' },
  drawerVisible: { type: Boolean, default: false },
  detailSuspended: { type: Boolean, default: false },
  selectingModel: { type: Boolean, default: false }
})

const emit = defineEmits(['update:active-tab', 'close-drawer', 'open-model-editor', 'toggle-title-menu'])

const detailTabs = [
  { key: 'variants', label: '机型参数' },
  { key: 'overview', label: '产品概览' },
  { key: 'assets', label: '素材管理' }
]
</script>

<style scoped>
/* 工作台模式：sticky 内联面板 */
.product-detail-drawer.pinned {
  position: sticky;
  top: 108px;
  right: auto;
  bottom: auto;
  left: auto;
  width: 100%;
  height: calc(100vh - 124px);
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

/* 抽屉头部（工作台模式专用，抽屉模式由 LqDialog 渲染） */
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap);
  padding: 6px 12px 6px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
  flex-shrink: 0;
}

.drawer-header-left {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  min-width: 0;
  flex: 1;
}

.drawer-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
  color: var(--color-text-heading);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin: 0;
}

.drawer-title > span:last-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.product-model-tag {
  flex: 0 0 auto;
  padding: 3px 7px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-sm);
  background: var(--color-primary-bg-light);
  color: var(--color-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-extrabold);
  line-height: 1.35;
}

.drawer-subtitle {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  flex-wrap: wrap;
  padding-top: 2px;
  margin: 0;
}

.drawer-subtitle-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  min-height: 24px;
  height: 24px;
  padding: 3px 8px;
  border-radius: var(--radius-pill);
  background: var(--color-success-bg);
  color: var(--color-success-text);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-bold);
}

.drawer-header-right {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  flex-shrink: 0;
}

/* 抽屉内容区 */
.drawer-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
  overflow-x: hidden;
  min-height: 0;
}

.drawer-body--assets {
  overflow: hidden;
}

/* 抽屉内部样式 */
.drawer-actions .btn-sm.ghost {
  border-color: transparent;
  background: transparent;
}

.drawer-actions .btn-sm.primary-action {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: var(--color-surface);
}
.drawer-actions .btn-sm.primary-action:hover {
  background: var(--color-primary-hover);
  border-color: var(--color-primary-hover);
}

.drawer-detail-content {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.drawer-detail-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.drawer-tab-transition {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}

/* ===== Tab 内容容器：承接 slot 内容，统一 flex 高度约束 =====
 * 模板中：<CatalogOverviewTab class="drawer-tab-panel overview-tab-host">
 *        <section class="drawer-tab-panel drawer-tab-panel--scroll"> (variants/assets)
 * 概览 tab (overview) 内部有自己的分段滚动，不需要 panel 级滚动；
 * 规格/素材 tab 需要 panel 级滚动条，加 --scroll 修饰。 */
.drawer-tab-panel {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
}

/* 滚动 tab：内容超出时显示内部滚动条，不破坏外层布局 */
.drawer-tab-panel--scroll {
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
  padding: 16px 20px;
}

/* 概览 tab 专用：去掉额外 padding，由内部 section 自己控制间距 */
.drawer-tab-panel.overview-tab-host {
  padding: 0;
}

.catalog-detail-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: var(--color-text-faint);
  font-size: var(--fs-base);
}

/* 标签页切换动画 */
.tab-fade-enter-active {
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
.tab-fade-leave-active {
  transition: all 0.15s cubic-bezier(0.4, 0, 1, 1);
}
.tab-fade-enter-from {
  opacity: 0;
  transform: translateY(8px);
}
.tab-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* 响应式：小屏下工作台面板全屏 */
@media (max-width: 980px) {
  .product-detail-drawer.pinned {
    position: fixed;
    inset: 0;
    border: 0;
    border-radius: 0;
  }
}
</style>
