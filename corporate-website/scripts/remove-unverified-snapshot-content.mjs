// One-time, repeatable cleanup of the checked-in static snapshot. Does not edit CMS data.
import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../public/content/', import.meta.url))
const placeholder = /^.+，专业[^，。]*，品质保障。$/
const missingMedia = /\/api\/website\/media\/(?:140|141|142|143|144|145|146)\/content/

function clean(node) {
  if (Array.isArray(node)) return node.map(clean)
  if (!node || typeof node !== 'object') return node
  for (const [key, value] of Object.entries(node)) {
    if ((key === 'summary' || key === 'seoDescription') && typeof value === 'string' && placeholder.test(value.trim())) {
      node[key] = null
    } else if ((key === 'image' || key === 'imageUrl') && typeof value === 'string' && missingMedia.test(value)) {
      node[key] = null
    } else if (key === 'content' && typeof value === 'string' && missingMedia.test(value)) {
      try {
        const items = JSON.parse(value)
        if (Array.isArray(items)) node[key] = JSON.stringify(items.filter((item) => !missingMedia.test(item.image || item.imageUrl || '')))
      } catch { /* Keep unknown content formats unchanged for manual review. */ }
    } else clean(value)
  }
  return node
}

for (const name of ['products', 'featured', 'home', 'product-details', 'page-sections']) {
  const path = join(root, `${name}.json`)
  const data = JSON.parse(await readFile(path, 'utf8'))
  await writeFile(path, `${JSON.stringify(clean(data))}\n`, 'utf8')
}
