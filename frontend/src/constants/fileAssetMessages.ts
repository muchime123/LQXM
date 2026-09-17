import type { FileCapability } from '@/types/fileAsset'

export const FILE_ASSET_MESSAGES = {
  assetNotLoaded: '文件素材尚未加载，请刷新后重试',
  selectCategory: '请选择目标分类',
  selectOwner: '请选择目标归属',
  tagsUpdated: '标签已更新',
  tagsBatchUpdated: '标签已批量更新',
  defaultUpdated: '默认文件已更新',
  reorderSaved: '排序已保存',
  categoryUpdated: '文件分类已更新',
  ownerUpdated: '文件归属已更新',
  restored: '文件已恢复',
  batchRestored: '文件已批量恢复',
  folderOpened: '文件所在文件夹已打开',
  folderOpenUnavailable: '无法打开文件所在文件夹'
} as const

export const FILE_ASSET_FAILURE_PREFIX = {
  loadList: '加载文件列表失败：',
  loadCapabilities: '加载文件能力失败：',
  updateTags: '更新标签失败：',
  batchUpdateTags: '批量更新标签失败：',
  setDefault: '设置默认失败：',
  reorder: '保存排序失败：',
  moveCategory: '移动文件分类失败：',
  moveOwner: '移动文件归属失败：',
  download: '下载文件失败：',
  delete: '删除文件失败：',
  batchDelete: '批量删除文件失败：',
  restore: '恢复文件失败：',
  batchRestore: '批量恢复文件失败：',
  localPath: '获取文件位置失败：',
  openFolder: '打开文件夹失败：',
  register: '素材登记失败：'
} as const

export function fileAssetDeleteSuccess(permanent: boolean, batch = false) {
  if (batch) return permanent ? '文件已批量永久删除' : '文件已批量移入回收站'
  return permanent ? '文件已永久删除' : '文件已移入回收站'
}

export const fileCapabilityUnsupportedMessages: Record<FileCapability, string> = {
  PREVIEW: '当前文件类型不支持预览',
  DOWNLOAD: '当前文件类型不支持下载',
  DELETE: '当前文件类型不支持删除操作',
  RESTORE: '当前文件类型不支持恢复操作',
  PERMANENT_DELETE: '当前文件类型不支持永久删除',
  MOVE_OWNER: '当前文件类型不支持归属移动',
  MOVE_CATEGORY: '当前文件类型不支持分类移动',
  UPDATE_TAGS: '当前文件类型不支持标签操作',
  SET_DEFAULT: '当前文件类型不支持设为默认文件',
  REORDER: '当前文件类型不支持排序',
  OPEN_FOLDER: '当前文件类型不支持打开文件夹'
}
