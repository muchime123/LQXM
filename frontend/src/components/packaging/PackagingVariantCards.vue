<script setup lang="ts">
import type { PackagingModelDetail, PackagingVariantView } from '@/api/packaging'
import {
  fetchVariantMoldSummaryByModel,
  type VariantMoldSummary
} from '@/api/packagingMold'
import { usePackagingAssetLookup } from '@/composables/packaging/usePackagingAssetLookup'
import { useVariantDisplay } from '@/composables/packaging/useVariantDisplay'
import { PACKAGING_ASSET_CATEGORY_KEYS, filmFormLabel, kindLabel, priceUnitLabel, shapeLabel } from '@/constants/packaging'
import { computed, ref, watch } from 'vue'
import VariantCarousel from '@/components/common/VariantCarousel.vue'

interface Props {
  detail: PackagingModelDetail | null
  modelId: number
  isFilmModel: boolean
  activeFilmForm: string
  exchangeRate: number
  filteredVariants: PackagingVariantView[]
  batchMode: boolean
  selectedVariantIds: Set<number>
  selectedVariant: PackagingVariantView | null
  focusVariantId?: number | null
  draggedVariantId: number | null
  dragOverVariantId: number | null
  variantCarouselIndex: Record<number, number>
  variantMissingItems: (variant: PackagingVariantView) => string[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:carousel-index': [variantId: number, index: number]
  'card-click': [variant: PackagingVariantView, event: MouseEvent | KeyboardEvent]
  'toggle-selection': [variantId: number]
  'checkbox-click': [event: MouseEvent]
  'drag-start': [variant: PackagingVariantView, event: DragEvent]
  'drag-over': [variant: PackagingVariantView, event: DragEvent]
  'drag-leave': [variant: PackagingVariantView]
  'drop': [variant: PackagingVariantView, event: DragEvent]
  'drag-end': []
  'image-drop': [event: DragEvent, variant: PackagingVariantView]
  'upload-image': [variant: PackagingVariantView]
  'manage-images': [variant: PackagingVariantView]
  'preview-image': [variant: PackagingVariantView, index?: number]
  'make-default': [assetId: number]
  'remove-asset': [assetId: number]
  'copy-variant': [variant: PackagingVariantView]
}>()

const {
  variantShape,
  variantSubtitle,
  variantCardMetrics,
  filmVariantTags,
  filmTierFields,
  filmBestPrice,
  filmQuoteMetaItems,
  isFilmBestTier
} = useVariantDisplay(computed(() => props.detail), computed(() => props.isFilmModel), computed(() => props.activeFilmForm), computed(() => props.exchangeRate))

const { packagingAssetThumbnailUrl } = usePackagingAssetLookup({
  detail: computed(() => props.detail),
  modelId: props.modelId
})

// ====== 规格模具摘要：按 modelId 批量拉取，给每张规格卡显示默认模具 + 其他模具数量。
const loadingVariantMoldSummary = ref(false)
const variantMoldMap = ref<Map<number, VariantMoldSummary>>(new Map())

function moldSummaryOf(variantId: number) {
  return variantMoldMap.value.get(variantId)
}
function moldStatusLabel(status?: string) {
  switch (status) {
    case 'in_use': return { text: '在用', type: 'success' as const }
    case 'stopped': return { text: '停用', type: 'danger' as const }
    case 'repair': return { text: '维修中', type: 'warning' as const }
    case 'unverified': return { text: '待核实', type: 'info' as const }
    default: return { text: status || '未知', type: 'info' as const }
  }
}
async function loadVariantMoldSummary() {
  if (!props.modelId || !props.detail?.variants?.length) {
    variantMoldMap.value = new Map()
    return
  }
  try {
    loadingVariantMoldSummary.value = true
    const list = await fetchVariantMoldSummaryByModel(props.modelId)
    const map = new Map<number, VariantMoldSummary>()
    for (const s of list) map.set(s.variantId, s)
    variantMoldMap.value = map
  } catch {
    variantMoldMap.value = new Map()
  } finally {
    loadingVariantMoldSummary.value = false
  }
}
watch(
  () => [props.modelId, props.detail?.variants?.length, props.detail?.model?.id] as const,
  () => loadVariantMoldSummary(),
  { immediate: true }
)

function onCardClick(variant: PackagingVariantView, event: MouseEvent | KeyboardEvent) {
  emit('card-click', variant, event)
}
function onDragStart(variant: PackagingVariantView, event: DragEvent) {
  emit('drag-start', variant, event)
}
function onDragOver(variant: PackagingVariantView, event: DragEvent) {
  emit('drag-over', variant, event)
}
function onDrop(variant: PackagingVariantView, event: DragEvent) {
  emit('drop', variant, event)
}

function variantImageAssets(variant: PackagingVariantView) {
  return (
    props.detail?.assets.filter(
      asset => asset.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage && asset.variantId === variant.id
    ) || []
  )
}

function variantImageAsset(variant: PackagingVariantView) {
  const assets = variantImageAssets(variant)
  return assets.find(asset => asset.defaultAsset) || assets[0] || null
}

function variantImage(variant: PackagingVariantView) {
  return variantImageAsset(variant)?.fileName || ''
}

function carouselIndex(variantId: number) {
  return props.variantCarouselIndex[variantId] || 0
}

function setCarouselIndex(variantId: number, index: number) {
  emit('update:carousel-index', variantId, index)
}

const filmOverviewItems = computed(() => {
  const variants = props.detail?.variants || []
  const prices = variants
    .flatMap(variant => filmTierFields(variant).map(field => Number(field.value)))
    .filter(price => Number.isFinite(price) && price > 0)
  const thicknessValues = Array.from(
    new Set(variants.map(variant => String(variant.thicknessUm || '').trim()).filter(Boolean))
  )
  const stockCount = variants.filter(
    variant => variant.filmSupplyType === 'stock' || String(variant.variantName || '').includes('现货')
  ).length
  const customCount = variants.filter(
    variant => variant.filmSupplyType === 'custom' || String(variant.variantName || '').includes('定制')
  ).length
  const specialCount = variants.filter(variant =>
    [variant.filmFeatures, variant.variantName, variant.remark].some(value =>
      /防雾|冷冻|蒸煮|镀铝|纯铝|磨砂|高透|进口/.test(String(value || ''))
    )
  ).length
  return [
    { label: '报价条目', value: `${variants.length}` },
    { label: '供货类型', value: `现货 ${stockCount} / 定制 ${customCount}` },
    { label: '厚度覆盖', value: thicknessValues.length ? thicknessValues.join('、') : '-' },
    { label: '价格区间', value: prices.length ? `${Math.min(...prices)}-${Math.max(...prices)} 元/kg` : '-' },
    { label: '特殊工艺', value: `${specialCount}` }
  ]
})
</script>

<template>
  <div v-if="isFilmModel" class="film-overview-strip">
    <span v-for="item in filmOverviewItems" :key="item.label">
      <em>{{ item.label }}</em>
      <strong>{{ item.value }}</strong>
    </span>
  </div>
  <div v-if="isFilmModel" class="film-quote-board">
    <div class="film-quote-head">
      <span>膜材 / 工艺</span>
      <span>参考低价</span>
      <span>报价状态</span>
      <span>下单重量阶梯价（元/kg）</span>
      <span>操作</span>
    </div>
    <article
      v-for="variant in filteredVariants"
      :key="variant.id"
      class="film-quote-row"
      role="button"
      tabindex="0"
      :class="{
        active: selectedVariant?.id === variant.id,
        focused: focusVariantId === variant.id,
        dragging: draggedVariantId === variant.id,
        'drag-over': dragOverVariantId === variant.id,
        'batch-selected': selectedVariantIds.has(variant.id)
      }"
      draggable="true"
      @click="onCardClick(variant, $event)"
      @keydown.enter.prevent="onCardClick(variant, $event)"
      @keydown.space.prevent="onCardClick(variant, $event)"
      @dragstart="onDragStart(variant, $event)"
      @dragover="onDragOver(variant, $event)"
      @dragleave="emit('drag-leave', variant)"
      @drop="onDrop(variant, $event)"
      @dragend="emit('drag-end')"
    >
      <label v-if="batchMode" class="spec-card-checkbox film-row-checkbox">
        <input
          type="checkbox"
          :checked="selectedVariantIds.has(variant.id)"
          @change="emit('toggle-selection', variant.id)"
          @click.stop="emit('checkbox-click', $event)"
        />
        <svg
          v-if="selectedVariantIds.has(variant.id)"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="3"
          stroke-linecap="round"
          stroke-linejoin="round"
          width="14"
          height="14"
        >
          <polyline points="20 6 9 17 4 12" />
        </svg>
      </label>
      <div
        class="film-row-thumb"
        :class="{ 'drag-active': draggedVariantId === variant.id || dragOverVariantId === variant.id }"
        @dragenter.stop.prevent
        @dragover.stop.prevent
        @dragleave.stop.prevent
        @drop.stop.prevent="emit('image-drop', $event, variant)"
      >
        <template v-if="variantImageAssets(variant).length > 0">
          <img
            :src="
              packagingAssetThumbnailUrl(
                variantImageAssets(variant)[carouselIndex(variant.id)] || variantImageAssets(variant)[0]
              )
            "
            :alt="variant.variantName || variant.specLabel || '膜材图片'"
            @click.stop="emit('preview-image', variant)"
          />
        </template>
        <span v-else>
          <b>{{ variant.thicknessUm || '膜' }}</b>
          <em>{{ filmFormLabel(detail?.model.filmForm) || '膜材' }}</em>
        </span>
        <button type="button" @click.stop="emit('upload-image', variant)">
          {{ variantImage(variant) ? '换图' : '传图' }}
        </button>
      </div>
      <div class="film-row-main">
        <strong>{{ variant.variantName || variant.specLabel || '未命名膜材' }}</strong>
        <p>{{ variantSubtitle(variant) }}</p>
        <div class="film-row-tags">
          <span v-for="tag in filmVariantTags(variant)" :key="tag">{{ tag }}</span>
          <span
v-if="variantMissingItems(variant).length"
class="warning"
            >缺 {{ variantMissingItems(variant).slice(0, 2).join('、') }}</span
          >
        </div>
      </div>
      <div class="film-row-price">
        <span>最低阶梯价</span>
        <strong>{{ filmBestPrice(variant) }}</strong>
        <em>{{ priceUnitLabel(variant.priceUnit) || '元/kg' }}</em>
      </div>
      <div class="film-row-quote-meta">
        <span v-for="item in filmQuoteMetaItems(variant)" :key="item.label" :class="item.tone">
          <em>{{ item.label }}</em>
          <b>{{ item.value }}</b>
        </span>
      </div>
      <div class="film-tier-strip">
        <span
          v-for="field in filmTierFields(variant)"
          :key="field.label"
          :class="{ empty: !field.value, best: isFilmBestTier(variant, field.value) }"
        >
          <em>{{ field.label }}</em>
          <b>{{ field.value || '-' }}</b>
        </span>
      </div>
      <div class="film-row-actions">
        <button type="button" class="spec-action-btn" @click.stop="emit('copy-variant', variant)">复制</button>
      </div>
    </article>
  </div>
  <div v-else class="spec-card-grid">
    <article
      v-for="variant in filteredVariants"
      :key="variant.id"
      class="spec-card"
      role="button"
      tabindex="0"
      :class="{
        active: selectedVariant?.id === variant.id,
        focused: focusVariantId === variant.id,
        dragging: draggedVariantId === variant.id,
        'drag-over': dragOverVariantId === variant.id,
        'batch-selected': selectedVariantIds.has(variant.id)
      }"
      draggable="true"
      @click="onCardClick(variant, $event)"
      @keydown.enter.prevent="onCardClick(variant, $event)"
      @keydown.space.prevent="onCardClick(variant, $event)"
      @dragstart="onDragStart(variant, $event)"
      @dragover="onDragOver(variant, $event)"
      @dragleave="emit('drag-leave', variant)"
      @drop="onDrop(variant, $event)"
      @dragend="emit('drag-end')"
    >
      <label v-if="batchMode" class="spec-card-checkbox">
        <input
          type="checkbox"
          :checked="selectedVariantIds.has(variant.id)"
          @change="emit('toggle-selection', variant.id)"
          @click.stop="emit('checkbox-click', $event)"
        />
        <svg
          v-if="selectedVariantIds.has(variant.id)"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="3"
          stroke-linecap="round"
          stroke-linejoin="round"
          width="14"
          height="14"
        >
          <polyline points="20 6 9 17 4 12" />
        </svg>
      </label>
      <span
        class="spec-card-image"
        :class="{ 'drag-active': draggedVariantId === variant.id || dragOverVariantId === variant.id }"
        @dragenter.stop.prevent
        @dragover.stop.prevent
        @dragleave.stop.prevent
        @drop.stop.prevent="emit('image-drop', $event, variant)"
      >
        <template v-if="variantImageAssets(variant).length > 0">
          <VariantCarousel
            :images="variantImageAssets(variant)"
            :index="carouselIndex(variant.id)"
            :thumbnail-url="asset => packagingAssetThumbnailUrl(asset)"
            :alt="variant.variantName || variant.specLabel || '规格图片'"
            @update:index="i => setCarouselIndex(variant.id, i)"
            @preview="i => emit('preview-image', variant, i)"
            @manage="emit('manage-images', variant)"
          />
        </template>
        <span v-else>
          <b>{{ variant.capacityMl ? `${variant.capacityMl}ml` : '规格' }}</b>
          <em>{{ kindLabel(detail?.model.packagingKind) || '包材' }}</em>
        </span>
        <span v-if="draggedVariantId === variant.id" class="drop-hint">📥 释放上传规格图</span>
        <span class="image-actions">
          <button type="button" @click.stop="emit('upload-image', variant)">
            {{ variantImage(variant) ? '上传更多图片' : '上传图片' }}
          </button>
          <button v-if="variantImage(variant)" type="button" @click.stop="emit('preview-image', variant)">
            预览
          </button>
        </span>
      </span>
      <span class="spec-card-body">
        <span class="spec-card-title-row">
          <strong>{{ variant.variantName || variant.specLabel || '未命名规格' }}</strong>
          <i>{{ shapeLabel(variantShape(variant)) || kindLabel(detail?.model.packagingKind) || '规格' }}</i>
        </span>
        <dl>
          <div v-for="metric in variantCardMetrics(variant)" :key="metric.label">
            <dt>{{ metric.label }}</dt>
            <dd>{{ metric.value }}</dd>
          </div>
        </dl>
        <div class="spec-mold-chip-row" v-if="moldSummaryOf(variant.id)">
          <template v-if="moldSummaryOf(variant.id)!.defaultMoldCode">
            <el-tag size="small" type="success" effect="light" class="spec-mold-default">
              默认 <strong>{{ moldSummaryOf(variant.id)!.defaultMoldCode }}</strong>
              <el-tag
                size="small"
                :type="moldStatusLabel(moldSummaryOf(variant.id)!.defaultMoldStatus).type"
                effect="plain"
                style="margin-left: 4px;"
              >
                {{ moldStatusLabel(moldSummaryOf(variant.id)!.defaultMoldStatus).text }}
              </el-tag>
            </el-tag>
          </template>
          <el-tag size="small" type="info" effect="plain">
            适配模具 {{ moldSummaryOf(variant.id)!.totalMoldCount }}
          </el-tag>
          <span
            v-if="
              moldSummaryOf(variant.id)!.defaultMoldStatus === 'stopped' ||
              moldSummaryOf(variant.id)!.defaultMoldStatus === 'repair'
            "
            class="spec-mold-warn"
          >
            默认模具{{ moldStatusLabel(moldSummaryOf(variant.id)!.defaultMoldStatus).text }}
          </span>
        </div>
        <div v-else class="spec-mold-chip-row spec-mold-empty">
          <el-tag size="small" type="warning" effect="plain">暂无关联模具</el-tag>
        </div>
        <div class="spec-card-actions">
          <div v-if="variantMissingItems(variant).length" class="spec-quality-tags">
            <span v-for="item in variantMissingItems(variant).slice(0, 3)" :key="item">缺{{ item }}</span>
            <span v-if="variantMissingItems(variant).length > 3"
              >+{{ variantMissingItems(variant).length - 3 }}</span
            >
          </div>
          <button type="button" class="spec-action-btn" @click.stop="emit('copy-variant', variant)">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              width="14"
              height="14"
            >
              <rect
x="9"
y="9"
width="13"
height="13"
rx="2"
ry="2" />
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
            </svg>
            复制
          </button>
        </div>
      </span>
    </article>
  </div>
</template>

<style scoped>
@import '../../styles/packaging-detail-drawer.css';

.spec-mold-chip-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  padding: 8px 10px;
  margin: 4px -10px 0;
  background: var(--color-bg-subtle, #fafafa);
  border-top: 1px dashed var(--color-border-subtle, #e5e7eb);
}

.spec-mold-chip-row.spec-mold-empty {
  padding: 4px 10px;
  color: var(--color-text-tertiary, #9ca3af);
}

.spec-mold-default :deep(strong) {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  margin-left: 2px;
}

.spec-mold-warn {
  font-size: 12px;
  color: var(--color-danger-text, #dc2626);
  background: var(--color-danger-bg, #fef2f2);
  padding: 2px 6px;
  border-radius: 4px;
}
</style>
