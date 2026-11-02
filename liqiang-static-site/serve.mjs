import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'dist')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml', '.webp': 'image/webp', '.xml': 'application/xml', '.txt': 'text/plain' }
http.createServer((req, res) => {
  const urlPath = decodeURIComponent(new URL(req.url, 'http://x').pathname)
  let fp = path.join(root, urlPath === '/' ? 'index.html' : urlPath)
  if (!fp.startsWith(root)) { res.writeHead(403); res.end(); return }
  if (!fs.existsSync(fp) || fs.statSync(fp).isDirectory()) { res.writeHead(404); res.end('404'); return }
  res.writeHead(200, { 'Content-Type': types[path.extname(fp)] || 'application/octet-stream' })
  fs.createReadStream(fp).pipe(res)
}).listen(8090, () => console.log('SERVE 8090'))
