/**
 * 素材相关工具函数
 */

interface AssetLike {
  tags?: string
}

/**
 * 解析素材 tags 字符串为数组
 * @param asset
 * @returns string[]
 */
export function assetTags(asset: AssetLike | null | undefined): string[] {
  return String(asset?.tags || '')
    .split(/[,，]/)
    .map(tag => tag.trim())
    .filter(Boolean)
    .slice(0, 4)
}
