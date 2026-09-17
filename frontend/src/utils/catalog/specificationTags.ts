export interface ClassifiedCatalogTags {
  contentTags: string[]
  outputCount: string
  moldShape: string
}

export function parseCatalogTags(value: unknown): string[] {
  return [...new Set(
    String(value || '')
      .split(/[,，、]/)
      .map(item => item.trim())
      .filter(Boolean)
  )]
}

export function isOutputSpecificationTag(tag: string): boolean {
  return /^一出(?:[一二三四五六七八九十百]+|\d+)$/.test(tag.trim())
}

export function isMoldSpecificationTag(tag: string): boolean {
  const value = tag.trim()
  return value.endsWith('模具') || value.endsWith('模') || /^(?:方|圆|异形|不规则)形?$/.test(value)
}

export function classifyCatalogTags(value: unknown): ClassifiedCatalogTags {
  const result: ClassifiedCatalogTags = { contentTags: [], outputCount: '', moldShape: '' }
  for (const tag of parseCatalogTags(value)) {
    if (isOutputSpecificationTag(tag)) result.outputCount = tag
    else if (isMoldSpecificationTag(tag)) result.moldShape = tag
    else result.contentTags.push(tag)
  }
  return result
}

export function catalogSpecificationKey(outputCount: unknown, moldShape: unknown): string {
  return `${String(outputCount || '').trim().toLocaleLowerCase()}|${String(moldShape || '').trim().toLocaleLowerCase()}`
}
