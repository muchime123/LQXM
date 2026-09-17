/**
 * Composables 统一导出
 *
 * 使用方式：
 * import { useCatalogFilters, useListPagination, useBatchSelection } from '@/composables'
 */

// Catalog 专用
export { useCatalogEditors } from './catalog/useCatalogEditors'
export { useCatalogFilters } from './catalog/useCatalogFilters'
export { useCatalogModels } from './catalog/useCatalogModels'
export { useCatalogMoveOperations } from './catalog/useCatalogMoveOperations'

// Archive Health 相关
export {
  ARCHIVE_CHECKS,
  buildArchiveChecks,
  computeListHealthSummary,
  useArchiveHealthCache
} from './catalog/useArchiveHealth'

// 批量操作
export { useBatchActions } from './list/useBatchActions'
export { useBatchSelection } from './list/useBatchSelection'

// 通用列表/弹窗 composables（所有视图共用）
export { buildSortPayload, useDragSort } from './list/useDragSort'
export { useListFilters } from './list/useListFilters'
export { useListPagination } from './list/useListPagination'

// 文件上传
export { useCatalogFileUpload } from './upload/useCatalogFileUpload'
export { useFileAssets } from './upload/useFileAssets'
export { useFileUpload } from './upload/useFileUpload'
export { useFileUploadWorkflow } from './upload/useFileUploadWorkflow'
export { useLocalDropUpload } from './upload/useLocalDropUpload'
export { useRegisteredFileUpload } from './upload/useRegisteredFileUpload'

// 预览
export { useAssetPreview } from './ui/useAssetPreview'
export { askConfirm } from './ui/useConfirmDialog'
export { useModalForm } from './ui/useModalForm'
export { useTheme } from './ui/useTheme'
export { notify } from './ui/useToast'
export { showLoading, hideLoading, withLoading } from './ui/useLoading'

// 状态管理
export { clearDirty, isDirty, setDirty } from './common/useDirty'
export {
  CERT_ASSET_STATUS,
  DOC_STATUS,
  DOC_STATUS_LABELS,
  certFeeTagClass,
  certStatusTagClass
} from './common/useStatus'

// 多标签页导航
export { useMultiTabs, cachedViewNames, refreshNonce } from './common/useMultiTabs'
export type { MultiTabItem } from './common/useMultiTabs'

// R1 导入流程公共配方
export { IMPORT_STATE_ORDER } from './common/useImportFlow'
