import { type ComputedRef, type Ref, computed, ref } from 'vue'
import type { PackagingModelDetail, PackagingVariantView } from '@/api/packaging'

/**
 * 包材规格过滤/排序质量过滤
 *
 * 抽取自 PackagingDetailDrawer.vue，负责规格列表的搜索、排序、质量筛选，
 * 以及缺失项检测。质量过滤条与卡片上的"缺 XX"标签共用同一份 missingItems 计算。
 */
export type VariantQualityFilter =
  | 'all'
  | 'complete'
  | 'missingImage'
  | 'missingCapacity'
  | 'missingDimension'
  | 'missingCommerce'
  | 'missingTierPrice'
  | 'missingPlateFee'
  | 'missingRemark'

export type VariantSortBy = 'sortOrder' | 'name' | 'capacity' | 'bestPrice'

export interface VariantFilterHelpers {
  isBlank: (value: unknown) => boolean
  hasVariantDimension: (variant: PackagingVariantView) => boolean
  tierFields: (variant: PackagingVariantView) => Array<{ label: string; value: unknown }>
  filmPricedTierFields: (variant: PackagingVariantView) => Array<{ label: string; value: unknown }>
  filmFeatureMissingLabel: () => string
  filmUnifiedRemark: (variant: PackagingVariantView) => string
  filmBestPriceNumber: (variant: PackagingVariantView) => number
  variantImage: (variant: PackagingVariantView) => string
}

export function useVariantFilters(
  detail: Ref<PackagingModelDetail | null>,
  isFilmModel: ComputedRef<boolean>,
  activeFilmForm: ComputedRef<string>,
  helpers: VariantFilterHelpers
) {
  const {
    isBlank,
    hasVariantDimension,
    tierFields,
    filmPricedTierFields,
    filmFeatureMissingLabel,
    filmUnifiedRemark,
    filmBestPriceNumber,
    variantImage
  } = helpers

  const variantSearchKeyword = ref('')
  const variantSortBy = ref<VariantSortBy>('sortOrder')
  const variantSortAsc = ref(true)
  const variantQualityFilter = ref<VariantQualityFilter>('all')

  /** 膜材/盒子规格缺失项检测 — 同时驱动质量过滤条与卡片"缺 XX"标签 */
  function variantMissingItems(variant: PackagingVariantView) {
    const items: string[] = []
    if (!variantImage(variant)) items.push('图片')
    if (isFilmModel.value) {
      if (isBlank(variant.filmMaterial) && isBlank(variant.materialDetail)) items.push('材质')
      if (isBlank(variant.peelability)) items.push(activeFilmForm.value === 'sheet' ? '揭膜手感' : '易揭性')
      if (isBlank(variant.thicknessUm)) items.push('厚度')
      if (isBlank(variant.filmFeatures)) items.push(filmFeatureMissingLabel())
      if (!tierFields(variant).length && isBlank(variant.dealerPrice) && isBlank(variant.retailPrice))
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

  function variantMatchesQualityFilter(variant: PackagingVariantView) {
    const missingItems = variantMissingItems(variant)
    switch (variantQualityFilter.value) {
      case 'complete':
        return missingItems.length === 0
      case 'missingImage':
        return missingItems.includes('图片')
      case 'missingCapacity':
        return missingItems.includes(isFilmModel.value ? '材质' : '容量')
      case 'missingDimension':
        return missingItems.includes(isFilmModel.value ? '厚度' : '尺寸')
      case 'missingCommerce':
        return missingItems.some(item =>
          isFilmModel.value
            ? ['易揭性', '揭膜手感', '功能/工艺', '功能/适配', '印刷/工艺', '阶梯价', '计价单位'].includes(item)
            : ['克重', '起订量', '箱规'].includes(item)
        )
      case 'missingTierPrice':
        return (
          isFilmModel.value &&
          filmPricedTierFields(variant).length === 0 &&
          !variant.dealerPrice &&
          !variant.retailPrice
        )
      case 'missingPlateFee':
        return isFilmModel.value && isBlank(variant.plateFee)
      case 'missingRemark':
        return isFilmModel.value && !filmUnifiedRemark(variant)
      case 'all':
      default:
        return true
    }
  }

  const qualityFilterOptions = computed(() => {
    const variants = detail.value?.variants || []
    const options: Array<{ key: VariantQualityFilter; label: string; count: number }> = [
      { key: 'all', label: '全部', count: variants.length },
      {
        key: 'complete',
        label: '已完整',
        count: variants.filter(variant => variantMissingItems(variant).length === 0).length
      },
      {
        key: 'missingImage',
        label: '缺图片',
        count: variants.filter(variant => variantMissingItems(variant).includes('图片')).length
      },
      {
        key: 'missingCapacity',
        label: isFilmModel.value ? '缺材质' : '缺容量',
        count: variants.filter(variant => variantMissingItems(variant).includes(isFilmModel.value ? '材质' : '容量'))
          .length
      },
      {
        key: 'missingDimension',
        label: isFilmModel.value ? '缺厚度' : '缺尺寸',
        count: variants.filter(variant => variantMissingItems(variant).includes(isFilmModel.value ? '厚度' : '尺寸'))
          .length
      },
      {
        key: 'missingCommerce',
        label: isFilmModel.value ? '缺报价/工艺' : '缺采购信息',
        count: variants.filter(variant =>
          variantMissingItems(variant).some(item =>
            isFilmModel.value
              ? ['易揭性', '揭膜手感', '功能/工艺', '功能/适配', '印刷/工艺', '阶梯价', '计价单位'].includes(item)
              : ['克重', '起订量', '箱规'].includes(item)
          )
        ).length
      }
    ]
    if (isFilmModel.value) {
      options.push(
        {
          key: 'missingTierPrice',
          label: '缺阶梯价',
          count: variants.filter(
            variant => filmPricedTierFields(variant).length === 0 && !variant.dealerPrice && !variant.retailPrice
          ).length
        },
        {
          key: 'missingPlateFee',
          label: '缺版费',
          count: variants.filter(variant => isBlank(variant.plateFee)).length
        },
        {
          key: 'missingRemark',
          label: '缺说明',
          count: variants.filter(variant => !filmUnifiedRemark(variant)).length
        }
      )
    }
    return options
  })

  const filteredVariants = computed(() => {
    let variants = detail.value?.variants || []
    if (variantQualityFilter.value !== 'all') {
      variants = variants.filter(variantMatchesQualityFilter)
    }
    if (variantSearchKeyword.value.trim()) {
      const keyword = variantSearchKeyword.value.toLowerCase()
      variants = variants.filter(v => {
        const name = (v.variantName || v.specLabel || '').toLowerCase()
        const subtitle = [
          v.capacityMl ? `${v.capacityMl}ml` : '',
          v.variantMoq ? `${v.variantMoq}个/箱` : '',
          v.materialDetail,
          v.filmMaterial,
          v.filmFeatures,
          v.peelability,
          v.thicknessUm
        ]
          .filter(Boolean)
          .join(' ')
        return name.includes(keyword) || subtitle.toLowerCase().includes(keyword)
      })
    }
    const sorted = [...variants]
    sorted.sort((a, b) => {
      let comparison = 0
      switch (variantSortBy.value) {
        case 'name': {
          const nameA = (a.variantName || a.specLabel || '').toLowerCase()
          const nameB = (b.variantName || b.specLabel || '').toLowerCase()
          comparison = nameA.localeCompare(nameB, 'zh-CN')
          break
        }
        case 'capacity': {
          const capA = Number(String(a.capacityMl || '').replace(/[^\d.]/g, '')) || 0
          const capB = Number(String(b.capacityMl || '').replace(/[^\d.]/g, '')) || 0
          comparison = capA - capB
          break
        }
        case 'bestPrice':
          comparison = filmBestPriceNumber(a) - filmBestPriceNumber(b)
          break
        case 'sortOrder':
        default:
          comparison = (a.sortOrder || 0) - (b.sortOrder || 0)
          break
      }
      return variantSortAsc.value ? comparison : -comparison
    })
    return sorted
  })

  /** 重置过滤条件（不重置排序方式）— 切换系列/聚焦规格时调用 */
  function resetFilters() {
    variantQualityFilter.value = 'all'
    variantSearchKeyword.value = ''
  }

  return {
    variantSearchKeyword,
    variantSortBy,
    variantSortAsc,
    variantQualityFilter,
    variantMissingItems,
    variantMatchesQualityFilter,
    qualityFilterOptions,
    filteredVariants,
    resetFilters
  }
}
