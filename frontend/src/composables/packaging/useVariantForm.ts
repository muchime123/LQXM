import { computed, nextTick, reactive, ref, type Ref } from 'vue'
import {
  createPackagingVariant,
  deletePackagingVariant,
  duplicatePackagingVariant,
  updatePackagingVariant,
  type PackagingModelDetail,
  type PackagingVariantView
} from '@/api/packaging'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { notify } from '@/composables/ui/useToast'
import {
  BOX_MATERIALS,
  FILM_MATERIALS,
  FILM_SUPPLY_TYPES,
  PEELABILITY_OPTIONS,
  PRICE_UNITS,
  SHAPE_TYPES,
  normalizeKindKey,
  normalizeShapeKey
} from '@/constants/packaging'

function mergeRemarkFields(remark?: string, pricingNotes?: string) {
  const parts = [remark, pricingNotes].map(value => String(value || '').trim()).filter(Boolean)
  return Array.from(new Set(parts)).join('\n')
}

/**
 * 包材规格表单管理
 *
 * 抽取自 PackagingDetailDrawer.vue，负责规格编辑器的状态、字段定义、
 * 价格计算、表单校验和保存逻辑。
 */
export function useVariantForm(
  detail: Ref<PackagingModelDetail | null>,
  modelId: () => number,
  exchangeRate: Ref<number>,
  loadDetail: () => Promise<void>
) {
  const variantEditorVisible = ref(false)
  const editingVariantId = ref<number | null>(null)
  const savingVariant = ref(false)

  const variantForm = reactive<Record<string, string>>({
    variantName: '',
    capacityMl: '',
    shapeType: '',
    lengthMm: '',
    widthMm: '',
    heightMm: '',
    topDiameterMm: '',
    bottomDiameterMm: '',
    weightG: '',
    cartonSize: '',
    cartonWeight: '',
    cartonVolume: '',
    variantMoq: '',
    materialDetail: '',
    filmMaterial: '',
    filmSupplyType: '',
    filmFeatures: '',
    priceUnit: '',
    pricingNotes: '',
    dealerPrice: '',
    retailPrice: '',
    dealerPriceUsd: '',
    retailPriceUsd: '',
    peelability: '',
    thicknessUm: '',
    tier5099: '',
    tier100299: '',
    tier300499: '',
    tier500999: '',
    tier10001999: '',
    tier2000Up: '',
    plateFee: '',
    remark: ''
  })

  const variantFormFields = [
    { key: 'variantName', label: '规格名称', placeholder: '例：140x140x35mm / PP高透冷冻膜' },
    { key: 'capacityMl', label: '容量', placeholder: '500', kinds: ['box'] },
    { key: 'shapeType', label: '盒型形状', type: 'select', options: SHAPE_TYPES, kinds: ['box'] },
    { key: 'lengthMm', label: '长(mm)', placeholder: '140', kinds: ['box'], shapes: ['square', 'rectangle', 'oval'] },
    { key: 'widthMm', label: '宽(mm)', placeholder: '140', kinds: ['box'], shapes: ['square', 'rectangle', 'oval'] },
    {
      key: 'heightMm',
      label: '高(mm)',
      placeholder: '35',
      kinds: ['box'],
      shapes: ['square', 'rectangle', 'round', 'oval']
    },
    { key: 'topDiameterMm', label: '上直径(mm)', placeholder: '140', kinds: ['box'], shapes: ['round'] },
    { key: 'bottomDiameterMm', label: '下直径(mm)', placeholder: '120，可不填', kinds: ['box'], shapes: ['round'] },
    { key: 'weightG', label: '克重(g)', placeholder: '9', kinds: ['box'] },
    { key: 'variantMoq', label: '每箱数量', placeholder: '2400', kinds: ['box'] },
    { key: 'cartonSize', label: '纸箱尺寸', placeholder: '58×36×44', kinds: ['box'] },
    { key: 'cartonWeight', label: '整箱重量(kg)', placeholder: '20.4', kinds: ['box'] },
    { key: 'cartonVolume', label: '整箱体积(m³)', placeholder: '0.091', kinds: ['box'] },
    { key: 'materialDetail', label: '材质明细', type: 'select', options: BOX_MATERIALS, kinds: ['box'] },
    { key: 'filmMaterial', label: '膜材质', type: 'select', options: FILM_MATERIALS, kinds: ['film'] },
    { key: 'filmSupplyType', label: '供货类型', type: 'select', options: FILM_SUPPLY_TYPES, kinds: ['film'] },
    { key: 'filmFeatures', label: '功能/工艺', placeholder: '冷冻, 防雾, 高透, 镀铝', kinds: ['film'] },
    { key: 'peelability', label: '易揭性', type: 'select', options: PEELABILITY_OPTIONS, kinds: ['film'] },
    { key: 'thicknessUm', label: '厚度', placeholder: '8丝 / 10丝', kinds: ['film'] },
    { key: 'priceUnit', label: '计价单位', type: 'select', options: PRICE_UNITS, kinds: ['film'] },
    { key: 'tier5099', label: '50-99kg', placeholder: '阶梯价', kinds: ['film'] },
    { key: 'tier100299', label: '100-299kg', placeholder: '阶梯价', kinds: ['film'] },
    { key: 'tier300499', label: '300-499kg', placeholder: '阶梯价', kinds: ['film'] },
    { key: 'tier500999', label: '500-999kg', placeholder: '阶梯价', kinds: ['film'] },
    { key: 'tier10001999', label: '1000-1999kg', placeholder: '阶梯价', kinds: ['film'] },
    { key: 'tier2000Up', label: '2000kg+', placeholder: '阶梯价', kinds: ['film'] },
    { key: 'plateFee', label: '版费', placeholder: '200-260', kinds: ['film'] },
    { key: 'remark', label: '备注', type: 'textarea', wide: true }
  ]

  const effectiveVariantShape = computed(() =>
    normalizeShapeKey(variantForm.shapeType || detail.value?.model.shapeType || '')
  )
  const effectivePackagingKind = computed(() => normalizeKindKey(detail.value?.model.packagingKind || 'box') || 'box')
  const isFilmModel = computed(() => effectivePackagingKind.value === 'film')
  const activeFilmForm = computed(() => detail.value?.model.filmForm || '')

  const unitDealerPrice = computed(() => {
    const price = parseFloat(variantForm.dealerPrice) || 0
    const qty = parseFloat(variantForm.variantMoq) || 0
    if (price > 0 && qty > 0) {
      return (price / qty).toFixed(4)
    }
    return ''
  })

  const unitRetailPrice = computed(() => {
    const price = parseFloat(variantForm.retailPrice) || 0
    const qty = parseFloat(variantForm.variantMoq) || 0
    if (price > 0 && qty > 0) {
      return (price / qty).toFixed(4)
    }
    return ''
  })

  const dealerPriceUsd = computed(() => {
    const price = parseFloat(variantForm.dealerPrice) || 0
    if (price > 0) {
      return (price / exchangeRate.value).toFixed(2)
    }
    return ''
  })

  const retailPriceUsd = computed(() => {
    const price = parseFloat(variantForm.retailPrice) || 0
    if (price > 0) {
      return (price / exchangeRate.value).toFixed(2)
    }
    return ''
  })

  const unitDealerPriceUsd = computed(() => {
    const price = parseFloat(variantForm.dealerPrice) || 0
    const qty = parseFloat(variantForm.variantMoq) || 0
    if (price > 0 && qty > 0) {
      return (price / qty / exchangeRate.value).toFixed(4)
    }
    return ''
  })

  const unitRetailPriceUsd = computed(() => {
    const price = parseFloat(variantForm.retailPrice) || 0
    const qty = parseFloat(variantForm.variantMoq) || 0
    if (price > 0 && qty > 0) {
      return (price / qty / exchangeRate.value).toFixed(4)
    }
    return ''
  })

  function variantFormFieldDisplay(field: (typeof variantFormFields)[number]) {
    if (!isFilmModel.value) return field
    const form = activeFilmForm.value
    const overrides: Record<string, { label?: string; placeholder?: string }> = {
      variantName:
        form === 'sheet'
          ? { placeholder: '例：PP易揭片膜 95口径 / 透明片膜' }
          : { placeholder: '例：通用现货膜（彩膜）8丝 / PET复合卷膜' },
      filmMaterial:
        form === 'sheet'
          ? { label: '片膜材质', placeholder: 'PP / PET / 纯铝 / 淋膜纸' }
          : { label: '膜结构/材质', placeholder: 'PP / PET / PET+PE / 铝箔复合' },
      filmFeatures:
        form === 'sheet'
          ? { label: '功能/适配', placeholder: '冷冻, 防雾, 适配圆碗/方盒' }
          : { label: '印刷/复合工艺', placeholder: '彩膜, 透明, 镀铝, 防雾, 易撕' },
      peelability:
        form === 'sheet'
          ? { label: '揭膜手感', placeholder: '易揭 / 不易揭 / 半易揭' }
          : { label: '易揭性', placeholder: '易揭 / 不易揭 / 半易揭' },
      thicknessUm:
        form === 'sheet'
          ? { label: '片膜厚度', placeholder: '8丝 / 10丝' }
          : { label: '卷膜厚度', placeholder: '8丝 / 10丝 / 12丝' },
      plateFee:
        form === 'sheet'
          ? { label: '模切/版费', placeholder: '200-260 / 按图稿报价' }
          : { label: '版费', placeholder: '200-260 / 按图稿报价' },
      remark:
        form === 'sheet'
          ? { placeholder: '记录适配盒型、口径、特殊材质、是否含税运等' }
          : { placeholder: '记录纸芯扣重、纸箱费、印刷要求、是否含税运等' }
    }
    return { ...field, ...(overrides[field.key] || {}) }
  }

  function filmGroupTitle(baseTitle: string) {
    if (activeFilmForm.value === 'sheet') {
      if (baseTitle === '报价基础') return '片膜报价基础'
      if (baseTitle === '膜材工艺') return '片膜材质与适配'
      if (baseTitle === '补充详情') return '片膜补充说明'
    }
    if (activeFilmForm.value === 'roll') {
      if (baseTitle === '报价基础') return '卷膜报价基础'
      if (baseTitle === '膜材工艺') return '卷膜结构与工艺'
      if (baseTitle === '补充详情') return '卷膜报价说明'
    }
    return baseTitle
  }

  const visibleVariantFormFields = computed(() => {
    const shape = effectiveVariantShape.value
    const kind = effectivePackagingKind.value
    return variantFormFields
      .filter(field => {
        const fieldKinds = 'kinds' in field ? field.kinds : undefined
        const fieldShapes = 'shapes' in field ? field.shapes : undefined
        return (!fieldKinds || fieldKinds.includes(kind)) && (!fieldShapes || !shape || fieldShapes.includes(shape))
      })
      .map(field => variantFormFieldDisplay(field))
  })

  const visibleVariantFormGroups = computed(() => {
    const fields = visibleVariantFormFields.value
    const groupDefs = isFilmModel.value
      ? [
          { title: filmGroupTitle('报价基础'), keys: ['variantName', 'filmSupplyType', 'priceUnit'] },
          { title: filmGroupTitle('膜材工艺'), keys: ['filmMaterial', 'peelability', 'thicknessUm', 'filmFeatures'] },
          {
            title: '下单重量阶梯价',
            keys: ['tier5099', 'tier100299', 'tier300499', 'tier500999', 'tier10001999', 'tier2000Up']
          },
          { title: filmGroupTitle('补充详情'), keys: ['plateFee', 'remark'] }
        ]
      : [
          { title: '基础信息', keys: ['variantName', 'capacityMl', 'shapeType', 'materialDetail'] },
          {
            title: '尺寸参数',
            keys: ['lengthMm', 'widthMm', 'heightMm', 'topDiameterMm', 'bottomDiameterMm', 'weightG']
          },
          { title: '装箱信息', keys: ['variantMoq', 'cartonSize', 'cartonWeight', 'cartonVolume'] },
          { title: '补充说明', keys: ['remark'] }
        ]
    return groupDefs
      .map(group => ({
        title: group.title,
        fields: fields.filter(field => group.keys.includes(field.key))
      }))
      .filter(group => group.fields.length)
  })

  function resetVariantForm() {
    Object.keys(variantForm).forEach(key => {
      variantForm[key] = ''
    })
  }

  function focusVariantField(field: string) {
    const el = document.querySelector<HTMLElement>(`[data-variant-field="${field}"]`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el?.focus()
  }

  function openVariantEditor(variant?: PackagingVariantView | null, focusField?: string) {
    resetVariantForm()
    editingVariantId.value = variant?.id ?? null
    variantForm.shapeType = normalizeShapeKey(detail.value?.model.shapeType || '')
    if (isFilmModel.value) {
      variantForm.priceUnit = 'kg'
    }
    if (variant) {
      Object.keys(variantForm).forEach(key => {
        const value = variant[key as keyof PackagingVariantView]
        variantForm[key] = value == null ? '' : String(value)
      })
      if (isFilmModel.value) {
        variantForm.remark = mergeRemarkFields(variant.remark, variant.pricingNotes)
        variantForm.pricingNotes = ''
      }
      variantForm.shapeType = normalizeShapeKey(variantForm.shapeType)
    }
    variantEditorVisible.value = true
    if (focusField) {
      void nextTick(() => focusVariantField(focusField))
    }
  }

  function closeVariantEditor() {
    variantEditorVisible.value = false
    editingVariantId.value = null
  }

  function cleanVariantPayload() {
    const payload = { ...variantForm }
    const shape = normalizeShapeKey(payload.shapeType || detail.value?.model.shapeType || '')
    payload.shapeType = shape
    payload.dealerPriceUsd = dealerPriceUsd.value
    payload.retailPriceUsd = retailPriceUsd.value
    if (isFilmModel.value) {
      payload.capacityMl = ''
      payload.shapeType = ''
      payload.lengthMm = ''
      payload.widthMm = ''
      payload.heightMm = ''
      payload.topDiameterMm = ''
      payload.bottomDiameterMm = ''
      payload.weightG = ''
      payload.cartonSize = ''
      payload.cartonWeight = ''
      payload.cartonVolume = ''
      payload.priceUnit = payload.priceUnit || 'kg'
      payload.pricingNotes = ''
      return Object.fromEntries(
        Object.entries(payload).filter(([key, value]) => key === 'pricingNotes' || value !== '')
      )
    }
    payload.filmMaterial = ''
    payload.filmSupplyType = ''
    payload.filmFeatures = ''
    payload.priceUnit = ''
    payload.pricingNotes = ''
    payload.peelability = ''
    payload.thicknessUm = ''
    payload.tier5099 = ''
    payload.tier100299 = ''
    payload.tier300499 = ''
    payload.tier500999 = ''
    payload.tier10001999 = ''
    payload.tier2000Up = ''
    payload.plateFee = ''
    if (shape === 'round') {
      payload.lengthMm = ''
      payload.widthMm = ''
    } else {
      payload.topDiameterMm = ''
      payload.bottomDiameterMm = ''
    }
    return Object.fromEntries(Object.entries(payload).filter(([, value]) => value !== ''))
  }

  async function saveVariant() {
    savingVariant.value = true
    try {
      if (editingVariantId.value) {
        await updatePackagingVariant(editingVariantId.value, cleanVariantPayload())
        notify('规格已更新', 'success')
      } else {
        await createPackagingVariant(modelId(), cleanVariantPayload())
        notify('规格已创建', 'success')
      }
      closeVariantEditor()
      await loadDetail()
    } catch (error) {
      notify.errorWithDetail('保存失败', error)
    } finally {
      savingVariant.value = false
    }
  }

  async function removeVariant(id: number) {
    const ok = await askConfirm({ title: '删除规格', message: '确定删除这个规格？', danger: true })
    if (!ok) return
    await deletePackagingVariant(id)
    await loadDetail()
    notify('规格已删除', 'success')
  }

  async function copyVariant(variant: PackagingVariantView) {
    try {
      await duplicatePackagingVariant(variant.id)
      await loadDetail()
      notify('规格已复制', 'success')
    } catch (error) {
      notify.errorWithDetail('复制失败', error)
    }
  }

  return {
    variantForm,
    variantFormFields,
    variantEditorVisible,
    editingVariantId,
    savingVariant,
    effectiveVariantShape,
    effectivePackagingKind,
    isFilmModel,
    activeFilmForm,
    unitDealerPrice,
    unitRetailPrice,
    dealerPriceUsd,
    retailPriceUsd,
    unitDealerPriceUsd,
    unitRetailPriceUsd,
    visibleVariantFormFields,
    visibleVariantFormGroups,
    resetVariantForm,
    openVariantEditor,
    closeVariantEditor,
    saveVariant,
    removeVariant,
    copyVariant,
    focusVariantField
  }
}
