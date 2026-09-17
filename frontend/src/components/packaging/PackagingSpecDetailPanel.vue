<template>
  <aside class="spec-detail-card">
    <template v-if="variant">
      <header>
        <div>
          <span>{{ isFilmModel ? '报价详情' : '规格详情' }}</span>
          <h3>{{ variant.variantName || variant.specLabel || '未命名规格' }}</h3>
        </div>
        <div>
          <button type="button" class="text-button" @click="emit('open-history', variant)">历史记录</button>
          <button type="button" class="text-button" @click="emit('open-editor', variant)">编辑</button>
          <button type="button" class="text-button danger" @click="emit('remove-variant', variant.id)">删除</button>
        </div>
      </header>
      <section
        class="spec-detail-image"
        :class="{ 'drag-active': detailImageDragging }"
        @dragenter.prevent="detailImageDragging = true"
        @dragover.prevent
        @dragleave.prevent="detailImageDragging = false"
        @drop.prevent="handleImageDrop($event)"
      >
        <template v-if="variantImageAssets.length > 0">
          <VariantCarousel
            :images="variantImageAssets"
            :index="carouselIndex"
            :thumbnail-url="asset => packagingAssetThumbnailUrl(asset)"
            :alt="variant.variantName || variant.specLabel || '规格图片'"
            @update:index="i => updateCarouselIndex(i)"
            @preview="i => emit('preview-image', variant!, i)"
            @manage="emit('manage-images')"
          />
        </template>
        <button
          v-else
          type="button"
          class="no-image-btn"
          @click="emit('upload-image', variant!)"
        >
          {{ detailImageDragging ? '📥 释放上传' : '暂无规格图' }}
        </button>
        <button type="button" class="btn-sm" @click="emit('upload-image', variant!)">
          {{ variantImage ? '上传更多图片' : '上传规格图' }}
        </button>
      </section>
      <section v-if="isFilmModel" class="film-detail-quote">
        <div class="film-detail-summary">
          <span>参考低价</span>
          <strong>{{ filmBestPrice(variant) }}</strong>
          <em>{{ priceUnitLabel(variant.priceUnit) || '元/kg' }}</em>
        </div>
        <div class="film-detail-tags">
          <span v-for="tag in filmVariantTags(variant)" :key="tag">{{ tag }}</span>
        </div>
        <div class="film-tier-table">
          <div
            v-for="field in filmTierFields(variant)"
            :key="field.label"
            :class="{ empty: !field.value, best: isFilmBestTier(variant, field.value) }"
          >
            <span>{{ field.label }}</span>
            <strong>{{ field.value || '-' }}</strong>
          </div>
        </div>
      </section>
      <section v-if="variantMissingItems.length" class="quality-alert">
        <div>
          <strong>待补资料</strong>
          <span v-for="item in variantMissingItems" :key="item">{{ item }}</span>
        </div>
        <div class="quality-alert-actions">
          <button
            v-for="action in quickFixActions"
            :key="action.field"
            type="button"
            @click="emit('quick-fix', action.field)"
          >
            {{ action.label }}
          </button>
        </div>
      </section>
      <div class="detail-section-list">
        <section
          v-for="group in variantDetailGroups(variant)"
          :key="group.title"
          class="detail-section"
          :class="{
            'detail-section-dimensions': group.kind === 'dimensions' || group.kind === 'filmParams',
            'detail-section-packing': group.kind === 'packing',
            'detail-section-pricing': group.kind === 'pricing',
            'detail-section-notes': group.kind === 'notes'
          }"
        >
          <h4>{{ group.title }}</h4>
          <!-- C17 LqDescriptions 推广：将 dl.detail-list + div.detail-field 替换为统一描述列表 -->
          <LqDescriptions :items="group.fields.map(f => ({ label: f.label, value: f.value || '-' }))" :column="2" border />
        </section>
      </div>
      <section class="detail-section adapted-molds-section">
        <SpecMoldLinksPanel
          :variant-id="variant.id"
          :model-id="modelId"
          :variant-shape-type="variant.shapeType"
          :variant-capacity="variant.capacityMl"
        />
      </section>
      <section v-if="!isFilmModel && tierFields(variant).length" class="tier-panel">
        <h4>阶梯价格</h4>
        <span v-for="field in tierFields(variant)" :key="field.label">{{ field.label }}：{{ field.value }}</span>
      </section>
    </template>
    <template v-else>
      <div class="empty-spec-detail">
        <strong>选择一个规格</strong>
        <span>规格详情会显示在这里，适合快速比对同系列型号。</span>
      </div>
    </template>
  </aside>
</template>

<script setup lang="ts">
import { useVariantDisplay } from '@/composables/packaging/useVariantDisplay'
import { usePackagingAssetLookup } from '@/composables/packaging/usePackagingAssetLookup'
import { priceUnitLabel, PACKAGING_ASSET_CATEGORY_KEYS } from '@/constants/packaging'
import type { PackagingModelDetail, PackagingVariantView } from '@/api/packaging'
import { computed, ref } from 'vue'
import LqDescriptions from '../common/LqDescriptions.vue'
import SpecMoldLinksPanel from './SpecMoldLinksPanel.vue'
import VariantCarousel from '@/components/common/VariantCarousel.vue'

interface Props {
  variant: PackagingVariantView | null
  detail: PackagingModelDetail | null
  modelId: number
  isFilmModel: boolean
  exchangeRate: number
  carouselIndex: number
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:carousel-index': [index: number]
  'manage-images': []
  'upload-image': [variant: PackagingVariantView]
  'preview-image': [variant: PackagingVariantView, index: number]
  'open-history': [variant: PackagingVariantView]
  'open-editor': [variant: PackagingVariantView]
  'remove-variant': [id: number]
  'quick-fix': [field: string]
  'image-drop': [event: DragEvent, variant: PackagingVariantView]
  'make-default': [id: number]
  'remove-asset': [id: number]
}>()

const detailRef = computed(() => props.detail)
const isFilmModelRef = computed(() => props.isFilmModel)
const activeFilmForm = computed(() => props.detail?.model.filmForm || '')
const exchangeRateRef = computed(() => props.exchangeRate)

const {
  variantDetailGroups,
  tierFields,
  filmTierFields,
  filmBestPrice,
  filmVariantTags,
  isFilmBestTier,
  isBlank,
  variantShape,
  hasVariantDimension,
  filmFeatureMissingLabel,
  filmPricedTierFields
} = useVariantDisplay(detailRef, isFilmModelRef, activeFilmForm, exchangeRateRef)

const detailImageDragging = ref(false)

const { packagingAssetThumbnailUrl } = usePackagingAssetLookup({
  detail: detailRef,
  modelId: props.modelId
})

const variantImageAssets = computed(() => {
  if (!props.variant) return []
  return (
    props.detail?.assets.filter(
      asset => asset.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage && asset.variantId === props.variant!.id
    ) || []
  )
})

const variantImage = computed(() => {
  const assets = variantImageAssets.value
  const defaultAsset = assets.find(asset => asset.defaultAsset)
  return defaultAsset?.fileName || assets[0]?.fileName || ''
})

function updateCarouselIndex(index: number) {
  emit('update:carousel-index', index)
}

function handleImageDrop(event: DragEvent) {
  detailImageDragging.value = false
  if (props.variant) {
    emit('image-drop', event, props.variant)
  }
}

function quickFixActionForMissingItem(item: string) {
  const map: Record<string, { label: string; field: string }> = props.isFilmModel
    ? {
        图片: { label: '上传图片', field: 'image' },
        材质: { label: '补材质', field: 'filmMaterial' },
        揭膜手感: { label: '补手感', field: 'peelability' },
        易揭性: { label: '补易揭性', field: 'peelability' },
        厚度: { label: '补厚度', field: 'thicknessUm' },
        '功能/工艺': { label: '补工艺', field: 'filmFeatures' },
        '功能/适配': { label: '补适配', field: 'filmFeatures' },
        '印刷/工艺': { label: '补工艺', field: 'filmFeatures' },
        阶梯价: { label: '补阶梯价', field: 'tier5099' },
        计价单位: { label: '补单位', field: 'priceUnit' }
      }
    : {
        图片: { label: '上传图片', field: 'image' },
        容量: { label: '补容量', field: 'capacityMl' },
        尺寸: {
          label: '补尺寸',
          field: variantShape(props.variant as PackagingVariantView) === 'round' ? 'topDiameterMm' : 'lengthMm'
        },
        克重: { label: '补克重', field: 'weightG' },
        起订量: { label: '补起订量', field: 'variantMoq' },
        箱规: { label: '补箱规', field: 'cartonSize' }
      }
  return map[item] || null
}

const variantMissingItems = computed(() => {
  if (!props.variant) return []
  return variantMissingItemsRaw(props.variant)
})

const quickFixActions = computed(() => {
  if (!props.variant) return []
  const fields = new Set<string>()
  return variantMissingItems.value
    .map(item => {
      const action = quickFixActionForMissingItem(item)
      if (!action || fields.has(action.field)) return null
      fields.add(action.field)
      return action
    })
    .filter(Boolean) as Array<{ label: string; field: string }>
})

// Note: variantMissingItemsRaw 与 computed variantMissingItems 区分，避免递归
function variantMissingItemsRaw(variant: PackagingVariantView) {
  const items: string[] = []
  if (!variantImage.value) items.push('图片')
  if (props.isFilmModel) {
    if (isBlank(variant.filmMaterial) && isBlank(variant.materialDetail)) items.push('材质')
    if (isBlank(variant.peelability)) items.push(activeFilmForm.value === 'sheet' ? '揭膜手感' : '易揭性')
    if (isBlank(variant.thicknessUm)) items.push('厚度')
    if (isBlank(variant.filmFeatures)) items.push(filmFeatureMissingLabel())
    if (!filmPricedTierFields(variant).length && isBlank(variant.dealerPrice) && isBlank(variant.retailPrice))
      items.push('阶梯价')
    if (isBlank(variant.priceUnit)) items.push('计价单位')
    return items
  }
  if (isBlank(variant.capacityMl)) items.push('容量')
  if (!hasVariantDimension(variant)) items.push('尺寸')
  if (isBlank(variant.weightG)) items.push('克重')
  if (isBlank(variant.variantMoq)) items.push('起订量')
  if (isBlank(variant.cartonSize)) items.push('箱规')
  return items
}
</script>

<style scoped>
@import '../../styles/packaging-detail-drawer.css';

.adapted-molds-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs, 4px);
}

.adapted-mold-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs, 4px);
  padding: 4px 8px;
  background: var(--color-bg-elevated, #f3f4f6);
  border-radius: var(--radius-sm, 6px);
  font-size: 12px;
  border: 1px solid transparent;
}

.adapted-mold-item.default {
  background: var(--color-success-bg, #ecfdf5);
  border-color: var(--color-success-border, #a7f3d0);
}

.adapted-mold-code {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-weight: 600;
  color: var(--color-text-primary, #111827);
}

.adapted-mold-cavity {
  color: var(--color-text-tertiary, #9ca3af);
}
</style>
