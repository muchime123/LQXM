export type FileOwnerType =
  | 'product_model'
  | 'product_variant'
  | 'packaging_model'
  | 'packaging_variant'
  | 'company'
  | 'certificate'
  | 'employee'
  | 'document'

/**
 * 文件归属类型 key 常量
 *
 * 用于避免在组件/composable 中硬编码 'document' 等字符串字面量。
 */
export const FILE_OWNER_TYPE_KEYS = {
  productModel: 'product_model',
  productVariant: 'product_variant',
  packagingModel: 'packaging_model',
  packagingVariant: 'packaging_variant',
  company: 'company',
  certificate: 'certificate',
  employee: 'employee',
  document: 'document'
} as const

export const FILE_OWNER_TYPES: readonly FileOwnerType[] = [
  FILE_OWNER_TYPE_KEYS.productModel,
  FILE_OWNER_TYPE_KEYS.productVariant,
  FILE_OWNER_TYPE_KEYS.packagingModel,
  FILE_OWNER_TYPE_KEYS.packagingVariant,
  FILE_OWNER_TYPE_KEYS.company,
  FILE_OWNER_TYPE_KEYS.certificate,
  FILE_OWNER_TYPE_KEYS.employee,
  FILE_OWNER_TYPE_KEYS.document
]

export function isFileOwnerType(value: string | null | undefined): value is FileOwnerType {
  return FILE_OWNER_TYPES.includes(value as FileOwnerType)
}

export type FileCapability =
  | 'PREVIEW'
  | 'DOWNLOAD'
  | 'DELETE'
  | 'RESTORE'
  | 'PERMANENT_DELETE'
  | 'MOVE_OWNER'
  | 'MOVE_CATEGORY'
  | 'UPDATE_TAGS'
  | 'SET_DEFAULT'
  | 'REORDER'
  | 'OPEN_FOLDER'

export const FILE_CAPABILITIES: readonly FileCapability[] = [
  'PREVIEW',
  'DOWNLOAD',
  'DELETE',
  'RESTORE',
  'PERMANENT_DELETE',
  'MOVE_OWNER',
  'MOVE_CATEGORY',
  'UPDATE_TAGS',
  'SET_DEFAULT',
  'REORDER',
  'OPEN_FOLDER'
]

export function isFileCapability(value: string | null | undefined): value is FileCapability {
  return FILE_CAPABILITIES.includes(value as FileCapability)
}

export type FileAssetErrorCode =
  | 'OPEN_FOLDER_DISABLED'
  | 'INVALID_FILE'
  | 'INVALID_CATEGORY'
  | 'FILE_NOT_FOUND'
  | 'OPEN_FOLDER_FAILED'
  | 'FILE_BEHAVIOR_UNSUPPORTED'

export const FILE_ASSET_ERROR_CODES: readonly FileAssetErrorCode[] = [
  'OPEN_FOLDER_DISABLED',
  'INVALID_FILE',
  'INVALID_CATEGORY',
  'FILE_NOT_FOUND',
  'OPEN_FOLDER_FAILED',
  'FILE_BEHAVIOR_UNSUPPORTED'
]

export function isFileAssetErrorCode(value: string | null | undefined): value is FileAssetErrorCode {
  return FILE_ASSET_ERROR_CODES.includes(value as FileAssetErrorCode)
}

export interface FileHandle {
  assetId: number
  ownerType: FileOwnerType
  ownerId: number | null
  category: string
  storageCategory: string
  storageOwnerId: number | null
  fileName: string
  originalFileName: string | null
  mimeType: string | null
  fileSize: number | null
  tags: string | null
  defaultFile: boolean
  sortOrder: number | null
  deleted: boolean
  coverFileName: string | null
  createdAt: string | null
  updatedAt: string | null
  capabilities: FileCapability[]
}

export type FileCapabilityMap = Partial<Record<FileOwnerType, FileCapability[]>>

export interface FileAssetLocalPathResult {
  found: boolean
  fileRoot?: string
  checkedPath?: string
  localPath?: string
  fileName?: string
  category?: string
  ownerType?: FileOwnerType
  ownerId?: number | null
  error?: string
  errorCode?: FileAssetErrorCode | string
}

export interface FileAssetOpenFolderResult {
  success: boolean
  localPath?: string
  error?: string
  errorCode?: FileAssetErrorCode | string
}
