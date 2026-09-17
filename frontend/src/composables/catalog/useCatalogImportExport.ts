import { ref, type Ref } from 'vue'
import { exportCatalogExcel, importCatalog, exportProductAssetsZip } from '../../api/catalog/exportImport'
import { downloadBlob } from '../../utils/ui/downloadBlob'
import { todayIso } from '../../utils/common/dateUtils'
import type { NotifyFn } from '../ui/useToast'
import { buildImportToast } from '../common/useImportFlow'
import type { ImportResultSummary } from '../../types/importFlow'

export function useCatalogImportExport({
  query,
  notify,
  loadModels,
  globalLoading,
  globalLoadingText
}: {
  query: Record<string, any>
  notify: NotifyFn
  loadModels: (page?: number) => Promise<void>
  globalLoading: Ref<boolean>
  globalLoadingText: Ref<string>
}) {
  const exporting = ref(false)
  const exportingZip = ref(false)

  async function handleExportExcel() {
    exporting.value = true
    try {
      const blob = await exportCatalogExcel({
        keyword: query.keyword || undefined,
        category: query.category || undefined,
        status: query.status || undefined
      })
      const url = window.URL.createObjectURL(new Blob([blob]))
      const link = document.createElement('a')
      link.href = url
      link.download = '产品档案导出_' + todayIso() + '.xlsx'
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      window.URL.revokeObjectURL(url)
      notify('导出成功')
    } catch (err) {
      notify.errorWithDetail('导出失败', err)
    } finally {
      exporting.value = false
    }
  }

  async function handleExportAssetsZip(selectedId: number | null, modelName?: string) {
    if (!selectedId) return
    exportingZip.value = true
    try {
      const blob = await exportProductAssetsZip(selectedId)
      downloadBlob(new Blob([blob]), '产品素材_' + (modelName || selectedId) + '.zip')
      notify('素材打包下载成功')
    } catch (err) {
      notify.errorWithDetail('打包下载失败', err)
    } finally {
      exportingZip.value = false
    }
  }

  async function handleCatalogImport(
    event: { target: { files?: FileList; value?: string } },
    askConfirm: (opts: any) => Promise<any>,
    onSuccess?: (result: any) => void
  ) {
    const file = event.target.files?.[0]
    if (!file) return
    const confirmed = await askConfirm({
      title: '导入 Excel？',
      message: '导入会重建新版主产品和素材数据，历史配置兼容数据会随导入同步保留。旧 products 表不会删除。确定继续吗？',
      confirmText: '开始导入'
    })
    if (!confirmed) {
      event.target.value = ''
      return
    }
    globalLoading.value = true
    globalLoadingText.value = '正在导入数据...'
    try {
      const result = await importCatalog(file, true)
      /** R1 配方：将业务 result 映射为 ImportResultSummary，统一推导语义与 level。 */
      const summary: ImportResultSummary = {
        custom: {
          '主产品': result.models ?? 0,
          '素材': result.assets ?? 0
        },
        created: (result.models ?? 0) + (result.assets ?? 0)
      }
      const toast = buildImportToast(summary)
      notify(toast.message || '导入完成', toast.level)
      if (onSuccess) onSuccess(result)
      await loadModels(0)
    } catch (err) {
      notify.errorWithDetail('导入失败', err)
    } finally {
      globalLoading.value = false
      event.target.value = ''
    }
  }

  return {
    exporting,
    exportingZip,
    handleExportExcel,
    handleExportAssetsZip,
    handleCatalogImport
  }
}
