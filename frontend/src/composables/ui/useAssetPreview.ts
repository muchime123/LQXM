import { reactive } from 'vue'
import type { FileOwnerType } from '@/types/fileAsset'
import { fileAssetPreviewUrlByOptions } from '../../utils/ui/fileAssetUrls'

export interface AssetPreview {
  visible: boolean
  fileName: string
  fileUrl: string
  title: string
  category: string
  modelId: number | null
  ownerType: FileOwnerType | ''
  assetId: number | null
  assets: AssetPreviewItem[]
  index: number
}

export interface AssetPreviewSource {
  fileName: string
  fileUrl?: string
  title?: string
  category?: string
  modelId?: number
  model?: { id: number }
  ownerType?: FileOwnerType
  assetId?: number
  id?: number
  gallery?: AssetPreviewSource[]
  galleryIndex?: number
}

export interface AssetPreviewItem {
  fileName: string
  fileUrl: string
  title: string
  category: string
  modelId: number | null
  ownerType: FileOwnerType | ''
  assetId: number | null
}

type AssetPreviewPayload = string | AssetPreviewSource

const defaultTitle = '文件预览'

export function useAssetPreview() {
  const attachmentPreview = reactive<AssetPreview>({
    visible: false,
    fileName: '',
    fileUrl: '',
    title: defaultTitle,
    category: '',
    modelId: null,
    ownerType: '',
    assetId: null,
    assets: [],
    index: 0
  })

  function assetCategory(fileName: string): string {
    const lower = String(fileName || '').toLowerCase()
    if (lower.endsWith('.doc') || lower.endsWith('.docx') || lower.endsWith('.pdf')) {
      return 'manual'
    }
    return 'sku'
  }

  function resolvePreviewItem(asset: string | AssetPreviewSource): AssetPreviewItem {
    const fileName = typeof asset === 'string' ? asset : asset.fileName
    const category = typeof asset === 'string' ? assetCategory(fileName) : asset.category || assetCategory(fileName)
    const modelId = typeof asset === 'object' ? (asset.modelId ?? asset.model?.id ?? null) : null
    const ownerType = typeof asset === 'object' ? (asset.ownerType ?? '') : ''
    const previewOwnerType = ownerType || null
    const assetId = typeof asset === 'object' && ownerType ? (asset.assetId ?? asset.id ?? null) : null
    const url =
      typeof asset === 'object' && asset?.fileUrl
        ? asset.fileUrl
        : fileAssetPreviewUrlByOptions({
            ownerType: previewOwnerType,
            assetId,
            category,
            fileName,
            modelId
          })
    return {
      fileName,
      fileUrl: url,
      title: typeof asset === 'object' ? asset.title || fileName : fileName,
      category,
      modelId,
      ownerType,
      assetId
    }
  }

  function applyPreviewItem(item: AssetPreviewItem) {
    attachmentPreview.fileName = item.fileName
    attachmentPreview.fileUrl = item.fileUrl
    attachmentPreview.title = item.title
    attachmentPreview.category = item.category
    attachmentPreview.modelId = item.modelId
    attachmentPreview.ownerType = item.ownerType
    attachmentPreview.assetId = item.assetId
  }

  function openAssetPreview(asset: AssetPreviewPayload) {
    const sources = typeof asset === 'object' && asset.gallery?.length ? asset.gallery : [asset]
    const assets = sources.map(resolvePreviewItem)
    const requestedIndex = typeof asset === 'object' ? asset.galleryIndex || 0 : 0
    const index = Math.min(Math.max(requestedIndex, 0), assets.length - 1)
    attachmentPreview.assets = assets
    attachmentPreview.index = index
    applyPreviewItem(assets[index])
    attachmentPreview.visible = true
  }

  function shiftAssetPreview(step: number) {
    if (attachmentPreview.assets.length < 2) return
    attachmentPreview.index =
      (attachmentPreview.index + step + attachmentPreview.assets.length) % attachmentPreview.assets.length
    applyPreviewItem(attachmentPreview.assets[attachmentPreview.index])
  }

  function closeAssetPreview() {
    attachmentPreview.visible = false
    attachmentPreview.fileName = ''
    attachmentPreview.fileUrl = ''
    attachmentPreview.title = defaultTitle
    attachmentPreview.category = ''
    attachmentPreview.modelId = null
    attachmentPreview.ownerType = ''
    attachmentPreview.assetId = null
    attachmentPreview.assets = []
    attachmentPreview.index = 0
  }

  return {
    attachmentPreview,
    openAssetPreview,
    closeAssetPreview,
    shiftAssetPreview
  }
}
