interface ProductLike {
  modelCode?: string
  name?: string
}

function text(value: unknown): string {
  return String(value || '').trim()
}

export function displayModelTag(product: ProductLike | null | undefined): string {
  const code = text(product?.modelCode)
  return code
}

export function hasDisplayModelTag(product: ProductLike | null | undefined): boolean {
  const model = displayModelTag(product)
  return Boolean(model)
}

export function productDisplayName(
  product: ProductLike | null | undefined,
  fallback = '\u672a\u547d\u540d\u4ea7\u54c1'
): string {
  const name = text(product?.name) || fallback
  return hasDisplayModelTag(product) ? `${displayModelTag(product)} ${name}` : name
}
