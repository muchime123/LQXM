const base = 'https://liqiang-website.zjlqbz.workers.dev'
const urls = ['/', '/products.html', '/about.html', '/contact.html', '/products/product-67.html', '/sitemap.xml']
;(async () => {
  for (const p of urls) {
    try {
      const r = await fetch(base + p, { redirect: 'follow' })
      const t = await r.text()
      console.log(r.status, 'len=' + t.length, 'title=', (t.match(/<title>(.*?)<\/title>/s) || ['', ''])[1].slice(0, 40), ' ', p)
    } catch (e) {
      console.log('FETCH-ERR', e.message, ' ', p)
    }
  }
})()
