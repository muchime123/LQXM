import { ref, watch, type Ref } from 'vue'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { setDirty, clearDirty } from '@/composables/common/useDirty'

interface ModelForm {
  id?: number | string
  name: string
  modelCode: string
  // ====== V2 字段 ======
  category?: string
  descriptionCn?: string
  descriptionEn?: string
  status?: string
  /** 店铺链接；V121 重新加回 */
  shopUrl?: string
  defaultImage: string
  defaultImageAssetId?: number
  defaultImageOwnerType?: 'product_model' | 'product_variant'
  sortOrder?: number
  remark: string
  applicableScope?: string
  applicableScopeEn?: string
  introductionCn?: string
  introductionEn?: string
  searchKeywordsCn?: string
  searchKeywordsEn?: string
  websiteEnabled: boolean
  websiteFeatured: boolean
  websiteSlug: string
  websiteSummary: string
  applicationTags: string[]
  containerTags: string[]
  packagingProcessTags: string[]
  capacityLevel: string
}

interface VariantForm {
  id?: number | string
  variantName: string
  remark: string
  specKey?: string | null
  sortOrder?: number
  baseline?: boolean
  moldSizeType?: string | null
  moldLengthMm?: string | null
  moldWidthMm?: string | null
  moldDiameterMm?: string | null
  moldSizeLabel?: string | null
  machineTierId?: number | null
}

function normalizeModelEditorForm(model: Partial<ModelForm>): ModelForm {
  const form = { ...model } as ModelForm
  form.applicationTags = Array.isArray(form.applicationTags) ? form.applicationTags : []
  form.containerTags = Array.isArray(form.containerTags) ? form.containerTags : []
  form.packagingProcessTags = Array.isArray(form.packagingProcessTags) ? form.packagingProcessTags : []
  form.capacityLevel = form.capacityLevel || ''
  return form
}

export interface CatalogEditors {
  modelEditorVisible: Ref<boolean>
  modelEditorMode: Ref<'create' | 'edit'>
  modelEditorForm: Ref<ModelForm | Record<string, never>>
  modelEditorImageUploading: Ref<boolean>
  modelEditorManualUploading: Ref<boolean>
  modelEditorDirty: Ref<boolean>
  modelEditorValidationErrors: Ref<string[]>
  blankModel: () => ModelForm
  openNewModelEditor: () => void
  openModelEditor: (existingModel: Partial<ModelForm>) => void
  handleModelFieldChange: (key: string) => void
  handleChooseModelImage: (fileName: string) => void
  closeModelEditor: () => Promise<void>
  variantEditorVisible: Ref<boolean>
  variantEditorMode: Ref<'create' | 'edit'>
  variantEditorForm: Ref<VariantForm | Record<string, never>>
  variantEditorImageUploading: Ref<boolean>
  variantEditorDirty: Ref<boolean>
  variantEditorSkuImages: Ref<string[]>
  blankVariant: (modelName?: string, defaults?: Partial<VariantForm>) => VariantForm
  openNewVariantEditor: (modelName?: string, defaults?: Partial<VariantForm>) => void
  openVariantEditor: (variant: Partial<VariantForm>, skuImages?: string[]) => void
  closeVariantEditor: () => Promise<void>
  cleanupEditors: () => void
}

export function useCatalogEditors(): CatalogEditors {
  const modelEditorVisible = ref(false)
  const modelEditorMode = ref<'create' | 'edit'>('edit')
  const modelEditorForm = ref<ModelForm | Record<string, never>>({})
  const modelEditorImageUploading = ref(false)
  const modelEditorManualUploading = ref(false)
  const modelEditorDirty = ref(false)
  const modelEditorValidationErrors = ref<string[]>([])

  watch(modelEditorDirty, v => {
    if (v) setDirty('产品编辑')
    else clearDirty('产品编辑')
  })

  const variantEditorVisible = ref(false)
  const variantEditorMode = ref<'create' | 'edit'>('edit')
  const variantEditorForm = ref<VariantForm | Record<string, never>>({})
  const variantEditorImageUploading = ref(false)
  const variantEditorDirty = ref(false)
  const variantEditorSkuImages = ref<string[]>([])

  watch(variantEditorDirty, v => {
    if (v) setDirty('配置编辑')
    else clearDirty('配置编辑')
  })

  function blankModel(): ModelForm {
    return {
      name: '',
      modelCode: '',
      category: '',
      descriptionCn: '',
      descriptionEn: '',
      status: '',
      shopUrl: '',
      defaultImage: '',
      remark: '',
      applicableScope: '',
      applicableScopeEn: '',
      introductionCn: '',
      introductionEn: '',
      searchKeywordsCn: '',
      searchKeywordsEn: '',
      websiteEnabled: false,
      websiteFeatured: false,
      websiteSlug: '',
      websiteSummary: '',
      applicationTags: [],
      containerTags: [],
      packagingProcessTags: [],
      capacityLevel: ''
    }
  }

  function openNewModelEditor(): void {
    modelEditorMode.value = 'create'
    modelEditorForm.value = blankModel()
    modelEditorDirty.value = false
    modelEditorValidationErrors.value = []
    modelEditorVisible.value = true
  }

  function openModelEditor(existingModel: Partial<ModelForm>): void {
    modelEditorMode.value = 'edit'
    modelEditorForm.value = normalizeModelEditorForm(existingModel)
    modelEditorDirty.value = false
    modelEditorValidationErrors.value = []
    modelEditorVisible.value = true
  }

  function handleModelFieldChange(key: string): void {
    modelEditorDirty.value = true
    if (modelEditorValidationErrors.value.includes(key)) {
      modelEditorValidationErrors.value = modelEditorValidationErrors.value.filter(item => item !== key)
    }
  }

  function handleChooseModelImage(fileName: string): void {
    if ('defaultImage' in modelEditorForm.value) {
      ;(modelEditorForm.value as ModelForm).defaultImage = fileName
      modelEditorDirty.value = true
    }
  }

  async function closeModelEditor(): Promise<void> {
    if (modelEditorImageUploading.value || modelEditorManualUploading.value) {
      notify('文件仍在上传中，请稍后再关闭', 'warning')
      return
    }
    if (
      modelEditorDirty.value &&
      !(await askConfirm({
        title: '放弃主产品修改？',
        message: '主产品信息尚未保存，关闭后本次修改会丢失。',
        confirmText: '放弃修改',
        danger: true
      }))
    )
      return
    modelEditorVisible.value = false
    modelEditorDirty.value = false
  }

  function blankVariant(modelName = '', defaults: Partial<VariantForm> = {}): VariantForm {
    return {
      variantName: modelName ? `${modelName} / 新配置` : '新配置',
      remark: '',
      ...defaults
    }
  }

  function openNewVariantEditor(modelName = '', defaults: Partial<VariantForm> = {}): void {
    variantEditorMode.value = 'create'
    variantEditorForm.value = blankVariant(modelName, defaults)
    variantEditorSkuImages.value = []
    variantEditorDirty.value = false
    variantEditorVisible.value = true
  }

  function openVariantEditor(variant: Partial<VariantForm>, skuImages: string[] = []): void {
    variantEditorMode.value = 'edit'
    variantEditorForm.value = { ...variant } as VariantForm
    variantEditorSkuImages.value = Array.isArray(skuImages) ? [...skuImages] : []
    variantEditorDirty.value = false
    variantEditorVisible.value = true
  }

  async function closeVariantEditor(): Promise<void> {
    if (variantEditorImageUploading.value) {
      notify('历史规格图片仍在上传中，请稍后再关闭', 'warning')
      return
    }
    if (
      variantEditorDirty.value &&
      !(await askConfirm({
        title: '放弃历史规格修改？',
        message: '历史规格内容尚未保存，关闭后本次修改会丢失。',
        confirmText: '放弃修改',
        danger: true
      }))
    )
      return
    variantEditorVisible.value = false
    variantEditorDirty.value = false
    variantEditorSkuImages.value = []
  }

  function cleanupEditors(): void {
    clearDirty('产品编辑')
    clearDirty('配置编辑')
  }

  return {
    modelEditorVisible,
    modelEditorMode,
    modelEditorForm,
    modelEditorImageUploading,
    modelEditorManualUploading,
    modelEditorDirty,
    modelEditorValidationErrors,
    blankModel,
    openNewModelEditor,
    openModelEditor,
    handleModelFieldChange,
    handleChooseModelImage,
    closeModelEditor,
    variantEditorVisible,
    variantEditorMode,
    variantEditorForm,
    variantEditorImageUploading,
    variantEditorDirty,
    variantEditorSkuImages,
    blankVariant,
    openNewVariantEditor,
    openVariantEditor,
    closeVariantEditor,
    cleanupEditors
  }
}
