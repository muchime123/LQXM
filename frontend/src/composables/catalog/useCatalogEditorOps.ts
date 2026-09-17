import { type Ref } from 'vue'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { createCatalogModel, deleteCatalogModel, fetchCatalogModelsSimple, updateCatalogModel } from '../../api/catalog'
import { MODEL_FIELD_GROUPS, ASSET_CATEGORY_KEYS } from '../../constants/fields'

export interface UseCatalogEditorOpsOptions {
  editors: {
    closeModelEditor: () => Promise<void>
  }
  modelEditorVisible: Ref<boolean>
  modelEditorDirty: Ref<boolean>
  modelEditorMode: Ref<'create' | 'edit'>
  modelEditorForm: Ref<any>
  modelEditorImageUploading: Ref<boolean>
  modelEditorManualUploading: Ref<boolean>
  modelEditorValidationErrors: Ref<string[]>
  selectedId: Ref<number | null>
  detail: Ref<any>
  models: Ref<any[]>
  activeTab: Ref<string>
  effectiveCatalogDisplayMode: Ref<string>
  detailDrawerVisible: Ref<boolean>
  resumeDetailAfterModelEdit: Ref<boolean>
  showNewProductGuide: Ref<boolean>
  loadModels: (page?: number, isPageChange?: boolean) => Promise<void>
  selectModel: (id: number, options?: any) => Promise<void>
  processUploadFiles: (
    files: File[],
    category: string,
    options?: { onRegistered?: (result: any) => void; successText?: string }
  ) => Promise<void>
  closeTitleMenu: () => void
  openModelEditorForm: (model: any) => void
}

export function useCatalogEditorOps(options: UseCatalogEditorOpsOptions) {
  const {
    editors,
    modelEditorVisible,
    modelEditorDirty,
    modelEditorMode,
    modelEditorForm,
    modelEditorImageUploading,
    modelEditorManualUploading,
    modelEditorValidationErrors,
    selectedId,
    detail,
    models,
    activeTab,
    effectiveCatalogDisplayMode,
    detailDrawerVisible,
    resumeDetailAfterModelEdit,
    showNewProductGuide,
    loadModels,
    selectModel,
    processUploadFiles,
    closeTitleMenu,
    openModelEditorForm
  } = options

  function normalizedArchiveName(value: any): string {
    return String(value || '')
      .replace(/\s+/g, '')
      .toLocaleLowerCase()
  }

  async function confirmDuplicateModelName(name: string): Promise<boolean> {
    const normalizedName = normalizedArchiveName(name)
    if (!normalizedName) return true
    try {
      const modelOptions = await fetchCatalogModelsSimple()
      const duplicate = modelOptions.find((item: any) => normalizedArchiveName(item.name) === normalizedName)
      if (!duplicate) return true
      return Boolean(
        await askConfirm({
          title: '发现同名主产品',
          message: `“${name.trim()}”已经存在于产品档案中。如果这是同一产品，建议返回检查后再维护；如果确实需要创建独立记录，仍可继续。`,
          cancelText: '返回检查',
          confirmText: '仍然创建'
        })
      )
    } catch {
      return true
    }
  }

  async function closeModelEditor(): Promise<void> {
    await editors.closeModelEditor()
    if (!modelEditorVisible.value && resumeDetailAfterModelEdit.value) {
      resumeDetailAfterModelEdit.value = false
      if (effectiveCatalogDisplayMode.value === 'browse' && detail.value) detailDrawerVisible.value = true
    }
  }

  function openModelEditor(existingModel = detail.value?.model): void {
    if (!existingModel) return
    resumeDetailAfterModelEdit.value = effectiveCatalogDisplayMode.value === 'browse' && detailDrawerVisible.value
    detailDrawerVisible.value = false
    openModelEditorForm(existingModel)
  }

  function handleOpenModelEditor(): void {
    if (!detail.value) return
    const editableModel = { ...detail.value.model }
    openModelEditor(editableModel)
  }

  async function saveModel(): Promise<void> {
    if (modelEditorImageUploading.value || modelEditorManualUploading.value) {
      notify('文件仍在上传中，请稍后再保存', 'warning')
      return
    }
    const requiredFields = MODEL_FIELD_GROUPS.flatMap(g => g.fields).filter(f => f.required)
    const missing = requiredFields.filter(f => !modelEditorForm.value[f.key]?.trim())
    if (missing.length > 0) {
      modelEditorValidationErrors.value = missing.map(f => f.key)
      notify(`请填写必填项：${missing.map(f => f.label).join('、')}`, 'warning')
      return
    }
    const nextWebsiteEnabled = Boolean(modelEditorForm.value.websiteEnabled)
    const previousWebsiteEnabled =
      modelEditorMode.value === 'edit' ? Boolean(detail.value?.model?.websiteEnabled) : false
    if (nextWebsiteEnabled) {
      const websiteMissing: string[] = []
      if (!String(modelEditorForm.value.websiteSlug || '').trim()) websiteMissing.push('官网 URL 标识')
      if (!String(modelEditorForm.value.websiteSummary || '').trim()) websiteMissing.push('官网摘要')
      if (websiteMissing.length) {
        notify(`发布前请补齐：${websiteMissing.join('、')}`, 'warning')
        return
      }
    }
    if (nextWebsiteEnabled !== previousWebsiteEnabled) {
      const confirmed = await askConfirm({
        title: nextWebsiteEnabled ? '确认发布产品' : '确认取消官网展示',
        message: nextWebsiteEnabled
          ? `“${modelEditorForm.value.name}”保存后将进入官网产品发布范围。官网图片、规格和兼容包材仍读取当前产品档案，请确认公开内容已经检查完成。`
          : `“${modelEditorForm.value.name}”保存后将从官网隐藏，但产品档案、规格、图片及包材关联不会删除。`,
        confirmText: nextWebsiteEnabled ? '保存并发布' : '保存并取消展示',
        cancelText: '返回检查',
        danger: !nextWebsiteEnabled
      })
      if (!confirmed) return
      if (!nextWebsiteEnabled) modelEditorForm.value.websiteFeatured = false
    }
    if (modelEditorMode.value === 'create' && !(await confirmDuplicateModelName(modelEditorForm.value.name))) return
    try {
      const saved =
        modelEditorMode.value === 'create'
          ? await createCatalogModel(modelEditorForm.value)
          : await updateCatalogModel(modelEditorForm.value.id, modelEditorForm.value)
      const isCreate = modelEditorMode.value === 'create'
      const tabAfterSave = isCreate ? 'overview' : activeTab.value
      modelEditorVisible.value = false
      modelEditorDirty.value = false
      selectedId.value = saved.id

      if (isCreate) {
        await loadModels()
        await selectModel(saved.id, { nextTab: 'overview' })
      } else {
        if (models.value.length > 0) {
          const idx = models.value.findIndex(m => m.id === saved.id)
          if (idx >= 0) {
            models.value[idx] = { ...models.value[idx], ...saved }
          }
        }
        await selectModel(saved.id, { preserveTab: true, nextTab: tabAfterSave })
      }
      activeTab.value = tabAfterSave
      if (effectiveCatalogDisplayMode.value === 'browse' && (isCreate || resumeDetailAfterModelEdit.value)) {
        detailDrawerVisible.value = true
      }
      resumeDetailAfterModelEdit.value = false
      notify(isCreate ? '主产品已创建' : '主产品已保存')
      if (isCreate) showNewProductGuide.value = true
    } catch (err) {
      notify.errorWithDetail('保存主产品失败', err)
    }
  }

  async function removeModel(): Promise<void> {
    closeTitleMenu()
    if (!detail.value) return
    const model = detail.value.model
    if (
      !(await askConfirm({
        title: '删除主产品？',
        message:
          '确定删除主产品“' +
          model.name +
          '”？\n\n删除后产品档案、素材及历史兼容数据将移入回收站，可在回收站中恢复或永久删除。',
        confirmText: '删除产品',
        danger: true
      }))
    )
      return
    try {
      await deleteCatalogModel(model.id)
      notify('主产品记录已删除')
      selectedId.value = null
      detail.value = null
      detailDrawerVisible.value = false
      await loadModels(0)
    } catch (err) {
      notify.errorWithDetail('删除主产品失败', err)
    }
  }

  async function uploadModelImage(event: any): Promise<void> {
    const files = extractFiles(event)
    if (!files.length) return
    if (modelEditorMode.value === 'create' || !selectedId.value) {
      notify('请先保存主产品后再上传主图', 'warning')
      resetEventTarget(event)
      return
    }
    modelEditorImageUploading.value = true
    try {
      await processUploadFiles(files, ASSET_CATEGORY_KEYS.mainImage, {
        successText: '主图已上传',
        onRegistered: result => {
          if (result?.upload?.fileName) {
            modelEditorForm.value.defaultImage = result.upload.fileName
            modelEditorDirty.value = true
          }
        }
      })
    } finally {
      modelEditorImageUploading.value = false
      resetEventTarget(event)
    }
  }

  async function uploadModelManual(event: any): Promise<void> {
    const files = extractFiles(event)
    if (!files.length) return
    if (modelEditorMode.value === 'create' || !selectedId.value) {
      notify('请先保存主产品后再上传说明书', 'warning')
      resetEventTarget(event)
      return
    }
    modelEditorManualUploading.value = true
    try {
      await processUploadFiles(files, 'manual', {
        successText: '说明书已上传',
        onRegistered: result => {
          if (result?.upload?.fileName) {
            modelEditorDirty.value = true
          }
        }
      })
    } finally {
      modelEditorManualUploading.value = false
      resetEventTarget(event)
    }
  }

  function extractFiles(event: any): File[] {
    const files = event?.target?.files
    if (!files) return []
    if (Array.isArray(files)) return files
    if (files instanceof FileList) return Array.from(files)
    return []
  }

  function resetEventTarget(event: any): void {
    if (event?.target?.value !== undefined) event.target.value = ''
  }

  return {
    closeModelEditor,
    openModelEditor,
    handleOpenModelEditor,
    saveModel,
    removeModel,
    uploadModelImage,
    uploadModelManual,
    confirmDuplicateModelName,
    normalizedArchiveName
  }
}
