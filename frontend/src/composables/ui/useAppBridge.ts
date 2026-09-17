import { inject, type InjectionKey } from 'vue'
import type { AssetPreviewSource } from './useAssetPreview'

export type PreviewAssetFn = (asset: AssetPreviewSource) => void
export type ImportedFn = () => void

export const PREVIEW_ASSET_KEY: InjectionKey<PreviewAssetFn> = Symbol('previewAsset')
export const IMPORTED_KEY: InjectionKey<ImportedFn> = Symbol('imported')

export function usePreviewAsset() {
  return inject(PREVIEW_ASSET_KEY, () => {})
}

export function useImported() {
  return inject(IMPORTED_KEY, () => {})
}
