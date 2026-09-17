import { type ComputedRef, type Ref } from 'vue'
import {
  filmFormLabel,
  filmSupplyTypeLabel,
  normalizeShapeKey,
  priceUnitLabel,
  shapeLabel
} from '@/constants/packaging'
import { truncate } from '@/utils/common/textUtils'
import type { PackagingModelDetail, PackagingVariantView } from '@/api/packaging'

/**
 * 包材规格展示函数集合
 *
 * 抽取自 PackagingDetailDrawer.vue 的纯展示逻辑，
 * 负责规格卡片、详情面板、膜材报价板等区域的字段格式化和分组。
 */
export function useVariantDisplay(
  detail: Ref<PackagingModelDetail | null>,
  isFilmModel: ComputedRef<boolean>,
  activeFilmForm: ComputedRef<string>,
  exchangeRate: Ref<number>
) {
  const truncateText = truncate

  function variantShape(variant: PackagingVariantView) {
    return normalizeShapeKey(variant.shapeType || detail.value?.model.shapeType || '')
  }

  function isBlank(value: unknown) {
    return value == null || String(value).trim() === ''
  }

  function hasVariantDimension(variant: PackagingVariantView) {
    const shape = variantShape(variant)
    if (shape === 'round') {
      return !isBlank(variant.topDiameterMm) && !isBlank(variant.heightMm)
    }
    if (['square', 'rectangle', 'oval'].includes(shape)) {
      return !isBlank(variant.lengthMm) && !isBlank(variant.widthMm) && !isBlank(variant.heightMm)
    }
    return true
  }

  function variantDimensionText(variant: PackagingVariantView) {
    const shape = variantShape(variant)
    if (shape === 'round') {
      return [
        variant.topDiameterMm ? `上直径${variant.topDiameterMm}mm` : '',
        variant.bottomDiameterMm ? `下直径${variant.bottomDiameterMm}mm` : '',
        variant.heightMm ? `高${variant.heightMm}mm` : ''
      ]
        .filter(Boolean)
        .join(' / ')
    }
    return [variant.lengthMm, variant.widthMm, variant.heightMm].filter(Boolean).join(' x ')
  }

  function variantSubtitle(variant: PackagingVariantView) {
    if (isFilmModel.value) {
      return (
        [variant.filmMaterial || variant.materialDetail, variant.peelability, variant.thicknessUm, variant.filmFeatures]
          .filter(Boolean)
          .join(' / ') || '膜参数待补充'
      )
    }
    return (
      [variantDimensionText(variant), variant.materialDetail, truncateText(variant.remark || '', 30)]
        .filter(Boolean)
        .join(' / ') || '规格待补充'
    )
  }

  function variantCardMetrics(variant: PackagingVariantView) {
    return [
      variant.capacityMl ? { label: '容量', value: `${variant.capacityMl}ml` } : null,
      variant.weightG ? { label: '克重', value: `${variant.weightG}g` } : null,
      variant.variantMoq ? { label: '装箱', value: String(variant.variantMoq) } : null,
      variant.dealerPrice || variant.retailPrice
        ? { label: '价格', value: String(variant.dealerPrice || variant.retailPrice) }
        : null
    ].filter(Boolean) as Array<{ label: string; value: string }>
  }

  function filmVariantTags(variant: PackagingVariantView) {
    return [
      filmSupplyTypeLabel(variant.filmSupplyType),
      variant.filmMaterial || variant.materialDetail,
      variant.peelability,
      variant.thicknessUm,
      variant.filmFeatures
    ].filter(Boolean)
  }

  function mergeRemarkFields(remark?: string, pricingNotes?: string) {
    const parts = [remark, pricingNotes].map(value => String(value || '').trim()).filter(Boolean)
    return Array.from(new Set(parts)).join('\n')
  }

  function filmUnifiedRemark(variant: PackagingVariantView) {
    return mergeRemarkFields(variant.remark, variant.pricingNotes)
  }

  function filmFeatureMissingLabel() {
    if (activeFilmForm.value === 'sheet') return '功能/适配'
    if (activeFilmForm.value === 'roll') return '印刷/工艺'
    return '功能/工艺'
  }

  function priceWithUsd(price: string | number | undefined, unit = '') {
    if (!price) return ''
    const numericPrice = parseFloat(String(price))
    if (!Number.isFinite(numericPrice)) return String(price)
    const unitText = unit ? unit : ''
    return `${price}${unitText}\n(≈${(numericPrice / exchangeRate.value).toFixed(2)} USD)`
  }

  function variantDetailFields(variant: PackagingVariantView) {
    if (isFilmModel.value) {
      const form = activeFilmForm.value
      return [
        { key: 'variantName', label: '规格型号', value: variant.variantName || variant.specLabel },
        { key: 'filmForm', label: '膜形态', value: filmFormLabel(detail.value?.model.filmForm) },
        { key: 'filmSupplyType', label: '供货类型', value: filmSupplyTypeLabel(variant.filmSupplyType) },
        {
          key: 'filmMaterial',
          label: form === 'sheet' ? '片膜材质' : form === 'roll' ? '膜结构/材质' : '膜材质',
          value: variant.filmMaterial || variant.materialDetail
        },
        {
          key: 'filmFeatures',
          label: form === 'sheet' ? '功能/适配' : form === 'roll' ? '印刷/复合工艺' : '功能/工艺',
          value: variant.filmFeatures
        },
        { key: 'peelability', label: form === 'sheet' ? '揭膜手感' : '易揭性', value: variant.peelability },
        {
          key: 'thicknessUm',
          label: form === 'sheet' ? '片膜厚度' : form === 'roll' ? '卷膜厚度' : '厚度',
          value: variant.thicknessUm
        },
        { key: 'priceUnit', label: '计价单位', value: priceUnitLabel(variant.priceUnit) },
        { key: 'dealerPrice', label: '经销价', value: priceWithUsd(variant.dealerPrice) },
        { key: 'retailPrice', label: '零售价', value: priceWithUsd(variant.retailPrice) },
        { key: 'plateFee', label: form === 'sheet' ? '模切/版费' : '版费', value: variant.plateFee },
        { key: 'remark', label: '备注', value: filmUnifiedRemark(variant) }
      ]
    }
    const shape = variantShape(variant)
    const dimensionFields =
      shape === 'round'
        ? [
            { key: 'topDiameterMm', label: '上直径', value: variant.topDiameterMm ? `${variant.topDiameterMm}mm` : '' },
            {
              key: 'bottomDiameterMm',
              label: '下直径',
              value: variant.bottomDiameterMm ? `${variant.bottomDiameterMm}mm` : ''
            },
            { key: 'heightMm', label: '高', value: variant.heightMm ? `${variant.heightMm}mm` : '' }
          ]
        : [
            { key: 'lengthMm', label: '长', value: variant.lengthMm ? `${variant.lengthMm}mm` : '' },
            { key: 'widthMm', label: '宽', value: variant.widthMm ? `${variant.widthMm}mm` : '' },
            { key: 'heightMm', label: '高', value: variant.heightMm ? `${variant.heightMm}mm` : '' }
          ]
    return [
      { key: 'variantName', label: '规格型号', value: variant.variantName || variant.specLabel },
      { key: 'shape', label: '盒型形状', value: shapeLabel(shape) },
      { key: 'capacityMl', label: '容量', value: variant.capacityMl ? `${variant.capacityMl}ml` : '' },
      ...dimensionFields,
      { key: 'weightG', label: '克重', value: variant.weightG ? `${variant.weightG}g` : '' },
      { key: 'variantMoq', label: '每箱数量', value: variant.variantMoq },
      { key: 'cartonSize', label: '纸箱尺寸', value: variant.cartonSize },
      { key: 'cartonWeight', label: '整箱重量', value: variant.cartonWeight ? `${variant.cartonWeight}kg` : '' },
      { key: 'cartonVolume', label: '整箱体积', value: variant.cartonVolume ? `${variant.cartonVolume}m³` : '' },
      { key: 'materialDetail', label: '材质', value: variant.materialDetail },
      { key: 'dealerPrice', label: '经销价', value: priceWithUsd(variant.dealerPrice, '元/箱') },
      { key: 'retailPrice', label: '零售价', value: priceWithUsd(variant.retailPrice, '元/箱') },
      { key: 'remark', label: '备注', value: variant.remark }
    ]
  }

  function variantDetailGroups(variant: PackagingVariantView) {
    const fields = variantDetailFields(variant)
    const groupDefs = isFilmModel.value
      ? [
          {
            kind: 'basic',
            title:
              activeFilmForm.value === 'sheet' ? '片膜信息' : activeFilmForm.value === 'roll' ? '卷膜信息' : '基础信息',
            keys: ['filmForm', 'filmSupplyType', 'filmMaterial', 'filmFeatures']
          },
          {
            kind: 'filmParams',
            title:
              activeFilmForm.value === 'sheet' ? '片膜参数' : activeFilmForm.value === 'roll' ? '卷膜参数' : '膜参数',
            keys: ['peelability', 'thicknessUm', 'priceUnit']
          },
          { kind: 'pricing', title: '价格信息', keys: ['dealerPrice', 'retailPrice', 'plateFee'] },
          { kind: 'notes', title: '补充说明', keys: ['remark'] }
        ]
      : [
          { kind: 'basic', title: '基础信息', keys: ['shape', 'capacityMl', 'materialDetail'] },
          {
            kind: 'dimensions',
            title: '尺寸参数',
            keys: ['lengthMm', 'widthMm', 'heightMm', 'topDiameterMm', 'bottomDiameterMm', 'weightG']
          },
          { kind: 'packing', title: '装箱信息', keys: ['cartonSize', 'cartonWeight', 'cartonVolume', 'variantMoq'] },
          { kind: 'pricing', title: '价格信息', keys: ['dealerPrice', 'retailPrice', 'plateFee'] },
          { kind: 'notes', title: '补充说明', keys: ['thicknessUm', 'peelability', 'remark'] }
        ]
    return groupDefs
      .map(group => ({
        kind: group.kind,
        title: group.title,
        fields: fields.filter(field => group.keys.includes(field.key) && field.value)
      }))
      .filter(group => group.fields.length)
  }

  function tierFields(variant: PackagingVariantView) {
    return [
      { label: '50-99kg', value: variant.tier5099 },
      { label: '100-299kg', value: variant.tier100299 },
      { label: '300-499kg', value: variant.tier300499 },
      { label: '500-999kg', value: variant.tier500999 },
      { label: '1000-1999kg', value: variant.tier10001999 },
      { label: '2000kg+', value: variant.tier2000Up }
    ].filter(item => item.value)
  }

  function filmTierFields(variant: PackagingVariantView) {
    return [
      { label: '50-99kg', value: variant.tier5099 },
      { label: '100-299kg', value: variant.tier100299 },
      { label: '300-499kg', value: variant.tier300499 },
      { label: '500-999kg', value: variant.tier500999 },
      { label: '1000-1999kg', value: variant.tier10001999 },
      { label: '2000kg+', value: variant.tier2000Up }
    ]
  }

  function filmPricedTierFields(variant: PackagingVariantView) {
    return filmTierFields(variant).filter(field => {
      const price = Number(field.value)
      return Number.isFinite(price) && price > 0
    })
  }

  function filmBestPrice(variant: PackagingVariantView) {
    const prices = filmTierFields(variant)
      .map(field => Number(field.value))
      .filter(price => Number.isFinite(price) && price > 0)
    if (prices.length) return `${Math.min(...prices)}`
    const fallback = Number(variant.dealerPrice || variant.retailPrice)
    return Number.isFinite(fallback) && fallback > 0 ? `${fallback}` : '-'
  }

  function filmBestPriceNumber(variant: PackagingVariantView) {
    const prices = filmPricedTierFields(variant).map(field => Number(field.value))
    if (prices.length) return Math.min(...prices)
    const fallback = Number(variant.dealerPrice || variant.retailPrice)
    return Number.isFinite(fallback) && fallback > 0 ? fallback : Number.POSITIVE_INFINITY
  }

  function filmBestTierLabel(variant: PackagingVariantView) {
    const priced = filmPricedTierFields(variant)
    if (!priced.length) return '-'
    const best = priced.reduce(
      (current, field) => (Number(field.value) < Number(current.value) ? field : current),
      priced[0]
    )
    return best.label
  }

  function filmQuoteMetaItems(variant: PackagingVariantView) {
    const pricedCount = filmPricedTierFields(variant).length
    const totalCount = filmTierFields(variant).length
    const hasFallbackPrice = Boolean(variant.dealerPrice || variant.retailPrice)
    return [
      {
        label: '阶梯',
        value: pricedCount ? `${pricedCount}/${totalCount}` : hasFallbackPrice ? '参考价' : '待补',
        tone: pricedCount >= 3 ? 'ok' : pricedCount > 0 || hasFallbackPrice ? 'warn' : 'danger'
      },
      {
        label: '最低区间',
        value: filmBestTierLabel(variant),
        tone: pricedCount ? 'ok' : 'muted'
      },
      {
        label: '版费',
        value: variant.plateFee ? `${variant.plateFee}` : '待确认',
        tone: variant.plateFee ? 'ok' : 'muted'
      },
      {
        label: '说明',
        value: filmUnifiedRemark(variant) ? '已记录' : '待补',
        tone: filmUnifiedRemark(variant) ? 'ok' : 'muted'
      }
    ]
  }

  function isFilmBestTier(variant: PackagingVariantView, value: unknown) {
    const current = Number(value)
    if (!Number.isFinite(current) || current <= 0) return false
    const prices = filmPricedTierFields(variant).map(field => Number(field.value))
    return prices.length > 0 && current === Math.min(...prices)
  }

  return {
    variantShape,
    isBlank,
    hasVariantDimension,
    variantDimensionText,
    variantSubtitle,
    variantCardMetrics,
    filmVariantTags,
    mergeRemarkFields,
    filmUnifiedRemark,
    filmFeatureMissingLabel,
    priceWithUsd,
    variantDetailFields,
    variantDetailGroups,
    tierFields,
    filmTierFields,
    filmPricedTierFields,
    filmBestPrice,
    filmBestPriceNumber,
    filmBestTierLabel,
    filmQuoteMetaItems,
    isFilmBestTier
  }
}
