/**
 * 脚本侧站点配置（只读）。
 *
 * Vite 只把 `VITE_*` 注入前端产物，独立的 node 脚本（预渲染、内容校验）不会自动读取 `.env`，
 * 若不补这一步，sitemap / canonical 与页面会各自用一套域名。
 * shell / CI 显式设置的环境变量优先于 `.env`（`process.loadEnvFile` 不覆盖已有值）。
 */
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))

try {
  process.loadEnvFile?.(join(projectRoot, '.env'))
} catch {
  /* 没有 .env（CI 中通常直接给环境变量）时按默认域名继续 */
}

/** 站点主域名，与 src/composables/useSeo.js 取值口径一致。 */
export const SITE_URL = (process.env.VITE_SITE_URL || 'https://www.liqiangbz.com').replace(/\/+$/, '')
