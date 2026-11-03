import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, readFile, writeFile, readdir, unlink, rmdir } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { generateStaticProducts } from '../scripts/prerender-products.mjs'

/* 显式逐层删除，避免部分环境拦截 fs.rm({recursive:true}) 后遗留临时目录 */
async function removeDirectory(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) await removeDirectory(path)
    else await unlink(path)
  }
  await rmdir(dir)
}

test('build generates crawlable HTML and sitemap for every public product', async () => {
  const directory = await mkdtemp(join(process.cwd(), 'lqxm-website-pages-'))
  try {
    const template = await readFile(fileURLToPath(new URL('../index.html', import.meta.url)), 'utf8')
    const products = JSON.parse(await readFile(fileURLToPath(new URL('../public/content/products.json', import.meta.url)), 'utf8'))
    await writeFile(join(directory, 'index.html'), template)
    const count = await generateStaticProducts(directory)
    assert.equal(count, products.length)
    const sitemap = await readFile(join(directory, 'sitemap.xml'), 'utf8')
    const urls = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((match) => match[1])
    assert.equal(urls.length, count + 4)
    for (const url of urls.filter((value) => value.includes('/products/product-'))) {
      const slug = url.split('/').pop()
      const html = await readFile(join(directory, 'products', `${slug}.html`), 'utf8')
      assert.match(html, /<h1>[^<]+<\/h1>/)
      assert.match(html, new RegExp(`<link rel="canonical" href="${url}"`))
      assert.match(html, new RegExp(`<meta property="og:url" content="${url}"`))
      assert.doesNotMatch(html, /专业[^<"]*品质保障|\/api\/file-assets/)
    }
  } finally {
    await removeDirectory(directory)
  }
})
