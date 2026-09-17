import { ref, type Ref } from 'vue'
import { askConfirm } from '../ui/useConfirmDialog'
import { notify } from '../ui/useToast'
import { batchDeleteModels, batchUpdateModels, batchUpdateWebsiteFields } from '../../api/catalog'
import { buildBatchToast } from '../common/useBatchFlow'
import type { BatchResultSummary } from '../../types/batchFlow'

/** 官网发布字段走专用端点（含联动规则与唯一性校验），与 `batchUpdateModels` 的一般批量逻辑分离。 */
const WEBSITE_BATCH_FIELD_KEYS = new Set(['websiteEnabled', 'websiteFeatured', 'websiteSlug', 'websiteSummary'])

export interface UseCatalogBatchOpsOptions {
  selectedModelIds: Set<number>
  batchMode: Ref<boolean>
  selectedId: Ref<number | null>
  detail: Ref<unknown>
  detailDrawerVisible: Ref<boolean>
  pageInfo: { page: number }
  loadModels: (page?: number) => Promise<void>
}

export function useCatalogBatchOps(options: UseCatalogBatchOpsOptions) {
  const { selectedModelIds, batchMode, selectedId, detail, detailDrawerVisible, pageInfo, loadModels } = options

  const batchEditVisible = ref(false)

  function openBatchEditModal() {
    batchEditVisible.value = true
  }

  async function batchRemoveModels() {
    const ids = Array.from(selectedModelIds)
    const confirmed = await askConfirm({
      title: '批量删除产品？',
      message:
        '确定删除已选的 ' +
        ids.length +
        ' 个产品？\n\n删除后产品档案、素材及历史兼容数据将移入回收站，可在回收站中恢复或永久删除。',
      confirmText: '删除',
      danger: true
    })
    if (!confirmed) return
    try {
      const result = await batchDeleteModels(ids)
      /** R2 配方：将业务 result 映射为 BatchResultSummary，统一推导语义与 level。 */
      const summary: BatchResultSummary = {
        affected: result.affected,
        total: ids.length
      }
      const toast = buildBatchToast(summary, '批量删除')
      notify(toast.message || `已删除 ${result.affected} 个产品`, toast.level)
      selectedModelIds.clear()
      selectedId.value = null
      detail.value = null
      detailDrawerVisible.value = false
      batchMode.value = false
      await loadModels(0)
    } catch (err) {
      notify.errorWithDetail('批量删除失败', err)
    }
  }

  async function doBatchEditModels(payload: Record<string, unknown>) {
    if (Object.keys(payload).length === 0) {
      notify('请至少勾选一个字段并输入新值', 'warning')
      return
    }
    try {
      const ids = Array.from(selectedModelIds)
      // 官网发布字段拆出走专用端点，其余走一般批量端点
      const websiteFields: Record<string, string> = {}
      const otherFields: Record<string, unknown> = {}
      for (const [key, value] of Object.entries(payload)) {
        if (WEBSITE_BATCH_FIELD_KEYS.has(key)) {
          websiteFields[key] = typeof value === 'boolean' ? String(value) : String(value ?? '')
        } else {
          otherFields[key] = value
        }
      }
      let affected = 0
      if (Object.keys(websiteFields).length > 0) {
        affected += (await batchUpdateWebsiteFields(ids, websiteFields)).affected
      }
      if (Object.keys(otherFields).length > 0) {
        affected += (await batchUpdateModels(ids, otherFields)).affected
      }
      /** R2 配方：将业务 result 映射为 BatchResultSummary，统一推导语义与 level。 */
      const summary: BatchResultSummary = {
        affected,
        total: ids.length
      }
      const toast = buildBatchToast(summary, '批量编辑')
      notify(toast.message || `已更新 ${affected} 个产品`, toast.level)
      batchEditVisible.value = false
      selectedModelIds.clear()
      await loadModels(pageInfo.page)
    } catch (err) {
      notify.errorWithDetail('批量更新失败', err)
    }
  }

  return {
    batchEditVisible,
    openBatchEditModal,
    batchRemoveModels,
    doBatchEditModels
  }
}
