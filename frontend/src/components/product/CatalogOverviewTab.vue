<template>
  <section class="catalog-overview-tab">
    <!-- 顶部区：主图摘要 + (产品名称 + 完整度评分) -->
    <section class="overview-top">
      <ProductHeroImage
        :model="model"
        :assets="assets"
        :default-image="model?.defaultImage"
        @preview-asset="$emit('preview-asset', $event)"
        @reorder-images="$emit('reorder-images', $event)"
        @drop-images="$emit('drop-images', $event)"
      />
      <div class="overview-top-right">
        <!-- 产品名称和标签区 -->
        <section class="overview-product-title">
          <div class="product-title-row">
            <div class="product-title-main">
              <span v-if="modelTag" class="product-model-tag">{{ modelTag }}</span>
              <span class="product-title-text">{{ modelName }}</span>
            </div>
            <div v-if="subtitleParts?.length || manualState" class="product-title-tags">
              <span v-for="part in subtitleParts" :key="part" class="drawer-subtitle-tag">{{ part }}</span>
              <span v-if="manualState" :class="['manual-summary-status', manualState.level]">
                说明书：{{ manualState.label }}
              </span>
            </div>
          </div>
        </section>

        <!-- 档案完整度速览 -->
        <div class="overview-health">
          <div class="health-score" :class="`health-score--${healthScoreLevel}`">
            <span class="health-score-value">{{ healthScore }}</span>
            <span class="health-score-label">完整度</span>
          </div>
          <div class="health-checks">
            <span
              v-for="item in healthItems"
              :key="item.key"
              class="health-chip"
              :class="{ 'is-done': item.done }"
              :title="item.done ? item.doneText : item.todoText"
            >
              <i class="health-chip-dot" aria-hidden="true"></i>
              <span class="health-chip-label">{{ item.label }}</span>
              <span class="health-chip-text">{{ item.done ? item.doneText : item.todoText }}</span>
            </span>
          </div>
        </div>
      </div>
    </section>

    <!-- 产品身份速览 -->
    <section class="data-section">
      <header>
        <h3>产品身份</h3>
        <span class="data-section-count">{{ keyFields.length }} 项</span>
      </header>
      <div class="snapshot-grid">
        <article v-for="item in keyFields" :key="item.label">
          <span>{{ item.label }}</span>
          <strong>
            <a
              v-if="item.link"
              :href="item.link"
              target="_blank"
              rel="noopener noreferrer"
              class="snapshot-link"
              :title="item.link"
            >{{ item.value || '前往店铺' }}</a>
            <template v-else>{{ item.value || '—' }}</template>
          </strong>
        </article>
      </div>
    </section>

    <!-- 主产品默认参数 -->
    <section class="data-section">
      <header>
        <h3>
          主产品默认参数
          <span class="spec-source">直接取自主产品档案字段</span>
        </h3>
        <span class="data-section-count">{{ specFields.length }} 项</span>
      </header>
      <div class="snapshot-grid">
        <article v-for="item in specFields" :key="item.label">
          <span>{{ item.label }}</span>
          <strong>
            <template v-if="item.value">{{ item.value }}{{ item.suffix ? ' ' + item.suffix : '' }}</template>
            <template v-else>—</template>
          </strong>
        </article>
      </div>
    </section>

    <!-- 新建产品引导 -->
    <section v-if="showGuide" class="new-product-guide">
      <span class="guide-icon">🎯</span>
      <div class="guide-text"><strong>产品已创建！</strong>接下来建议：</div>
      <div class="guide-actions">
        <button type="button" class="primary" @click="$emit('guide-action', 'edit')">补充主产品信息</button>
        <button type="button" @click="$emit('guide-action', 'assets')">上传素材</button>
        <button type="button" @click="$emit('guide-action', 'manual')">上传说明书</button>
        <button type="button" class="text-btn" @click="$emit('guide-action', 'dismiss')">知道了</button>
      </div>
    </section>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import ProductHeroImage from './ProductHeroImage.vue'

const props = defineProps({
  model: { type: Object, default: null },
  assets: { type: Array, default: () => [] },
  health: { type: Object, default: () => ({ items: [] }) },
  manualState: { type: Object, default: () => ({ label: '待生成', level: 'missing' }) },
  showGuide: { type: Boolean, default: false },
  modelName: { type: String, default: '' },
  modelTag: { type: String, default: '' },
  subtitleParts: { type: Array, default: () => [] }
})

defineEmits(['preview-asset', 'reorder-images', 'drop-images', 'run-action', 'guide-action'])

const keyFields = computed(() => {
  if (!props.model) return []
  const m = props.model
  return [
    { label: '产品名称', value: m.name },
    { label: '产品分类', value: m.category },
    { label: '上架状态', value: m.status },
    // 店铺链接（V121 重新加回）：仅在配置了 shopUrl 时展示，点击新窗口跳转
    ...(m.shopUrl ? [{ label: '店铺链接', value: '前往店铺', link: m.shopUrl }] : [])
  ].filter(item => String(item.value || '').trim())
})

const specFields = computed(() => {
  if (!props.model) return []
  return []
})

const healthItems = computed(() => {
  return Array.isArray(props.health?.items) ? props.health.items : []
})

const healthScore = computed(() => {
  const items = healthItems.value
  if (!items.length) return 100
  const done = items.filter(item => item.done).length
  return Math.round((done / items.length) * 100)
})

const healthScoreLevel = computed(() => {
  const score = healthScore.value
  if (score >= 80) return 'good'
  if (score >= 60) return 'warning'
  return 'danger'
})
</script>

<style scoped>
/* ===== 产品概览 Tab ===== */

.catalog-overview-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-xl-2xl);
  padding: 20px 22px 28px;
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
}

/* 与 .drawer-tab-panel 的 overflow:hidden 竞争，确保概览内容可内部滚动 */
.drawer-tab-panel.catalog-overview-tab {
  overflow-y: auto;
  overflow-x: hidden;
}

/* ---------- 顶部主卡：主图 + 名称/标签 + 完整度 ---------- */
.overview-top {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: var(--space-xl-2xl);
  padding: var(--space-xl-2xl);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-3xl);
  background: linear-gradient(
    150deg,
    var(--color-primary-bg-light) 0%,
    var(--color-surface-raised) 55%,
    var(--color-surface) 100%
  );
  box-shadow: var(--shadow-sm);
}

.overview-top-right {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: var(--space-lg);
  min-width: 0;
}

/* 产品名称与标签区 */
.overview-product-title {
  flex-shrink: 0;
  padding-bottom: var(--space-control-gap-wide);
  border-bottom: 1px solid var(--color-border-lighter);
}

.overview-product-title .product-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  flex-wrap: nowrap;
  min-width: 0;
}

.overview-product-title .product-title-main {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--fs-xl);
  font-weight: var(--fw-bold);
  color: var(--color-text-heading);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
  flex: 1 1 auto;
}

.overview-product-title .product-title-main .product-title-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.overview-product-title .product-title-tags {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  flex-wrap: wrap;
  flex-shrink: 0;
}

/* 说明书状态 */
.manual-summary-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 24px;
  height: 24px;
  padding: 3px 8px;
  border-radius: var(--radius-pill);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-bold);
}
.manual-summary-status.published {
  background: var(--color-success-bg);
  color: var(--color-success-text);
}
.manual-summary-status.draft {
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
}
.manual-summary-status.missing {
  background: var(--color-danger-bg);
  color: var(--color-danger-text);
}

/* ---------- 档案完整度 ---------- */
.overview-health {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--space-lg);
  align-items: center;
  min-width: 0;
}

.health-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 62px;
  height: 62px;
  border-radius: var(--radius-pill);
  border: 2px solid var(--color-border);
  background: var(--color-surface);
  flex-shrink: 0;
}
.health-score--good {
  border-color: var(--color-success-border);
  background: var(--color-success-bg-soft);
}
.health-score--warning {
  border-color: var(--color-warning-border);
  background: var(--color-warning-bg-soft);
}
.health-score--danger {
  border-color: var(--color-danger-border);
  background: var(--color-danger-bg-soft);
}

.health-score-value {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-black);
  line-height: 1;
  color: var(--color-text-heading);
}
.health-score--good .health-score-value {
  color: var(--color-success-text);
}
.health-score--warning .health-score-value {
  color: var(--color-warning-text);
}
.health-score--danger .health-score-value {
  color: var(--color-danger-text);
}

.health-score-label {
  margin-top: 3px;
  font-size: var(--fs-xxs);
  color: var(--color-text-muted);
}

.health-checks {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-control-gap);
  min-width: 0;
}

.health-chip {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  font-size: var(--fs-xs);
  white-space: nowrap;
  overflow: hidden;
  transition:
    border-color var(--transition-fast),
    background var(--transition-fast);
}
.health-chip.is-done {
  border-color: var(--color-success-border);
  background: var(--color-success-bg-soft);
}
.health-chip:hover {
  border-color: var(--color-text-faint);
}

.health-chip-dot {
  width: 7px;
  height: 7px;
  border-radius: var(--radius-pill);
  background: var(--color-danger);
  flex-shrink: 0;
}
.health-chip.is-done .health-chip-dot {
  background: var(--color-success);
}

.health-chip-label {
  font-weight: var(--fw-bold);
  color: var(--color-text-strong);
  flex-shrink: 0;
}
.health-chip-text {
  min-width: 0;
  color: var(--color-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
}
.health-chip.is-done .health-chip-text {
  color: var(--color-success-text);
}

/* ---------- 数据区块 ---------- */
.data-section {
  padding: var(--space-lg-xl) var(--space-xl);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-xs);
}

.data-section header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-control-gap);
  margin-bottom: var(--space-lg-xl);
  padding-bottom: var(--space-control-gap-wide);
  border-bottom: 1px solid var(--color-border);
}

.data-section h3 {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  margin: 0;
  font-size: var(--fs-base);
  font-weight: var(--fw-bold);
  color: var(--color-text-heading);
}
.data-section h3::before {
  content: '';
  width: 3px;
  height: 14px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  flex-shrink: 0;
}

.spec-source {
  font-size: var(--fs-xxs);
  font-weight: var(--fw-normal);
  color: var(--color-text-faint);
  margin-left: var(--space-control-gap-tight);
}

.data-section-count {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-hover);
  color: var(--color-text-muted);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-bold);
  white-space: nowrap;
}

.snapshot-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-md);
}

.snapshot-grid article {
  display: grid;
  gap: var(--space-control-gap-tight);
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface-raised);
  transition:
    border-color var(--transition-fast),
    box-shadow var(--transition-fast),
    transform var(--transition-fast);
}
.snapshot-grid article:hover {
  border-color: var(--color-primary-border);
  box-shadow: 0 4px 14px var(--shadow-color-soft);
  transform: translateY(-1px);
}

.snapshot-grid article span {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}
.snapshot-grid article span::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  opacity: 0.4;
  flex-shrink: 0;
}

.snapshot-grid article strong {
  overflow: hidden;
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-extrabold);
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 店铺链接跳转项 */
.snapshot-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  color: var(--color-primary);
  font-weight: var(--fw-bold);
  text-decoration: none;
  cursor: pointer;
  transition: color var(--transition-fast);
}

.snapshot-link::before {
  content: '↗';
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
}

.snapshot-link:hover {
  color: var(--color-primary-hover, var(--color-primary));
  text-decoration: underline;
}

/* ---------- 新建产品引导 ---------- */
.new-product-guide {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-wide);
  padding: 14px 18px;
  background: var(--color-warning-bg-soft);
  border: 1px solid var(--color-warning-border);
  border-radius: var(--radius-xl);
  flex-wrap: wrap;
}
.guide-icon {
  font-size: var(--fs-2xl);
}
.guide-text strong {
  font-size: var(--fs-base);
  color: var(--color-warning-text);
}
.guide-actions {
  display: flex;
  gap: var(--space-control-gap);
}
.guide-actions button {
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  font-size: var(--fs-xs);
  cursor: pointer;
}
.guide-actions button.primary {
  background: var(--color-primary);
  color: var(--color-surface);
  border-color: var(--color-primary);
}
.guide-actions button.text-btn {
  background: transparent;
  border: none;
  color: var(--color-text-muted);
}

/* ---------- 响应式 ---------- */
@media (max-width: 1020px) {
  .snapshot-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .overview-top {
    grid-template-columns: 160px minmax(0, 1fr);
    gap: var(--space-lg);
  }
  .snapshot-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .health-checks {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .overview-top {
    grid-template-columns: 1fr;
  }
  .snapshot-grid {
    grid-template-columns: 1fr;
  }
  .health-score {
    width: 56px;
    height: 56px;
  }
}
</style>
