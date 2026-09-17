/* 全局图片保底：内联 SVG 品牌占位图（data URI），不依赖任何静态文件，永不加载失败。
   viewBox 800×600 适配 4:3，cover 场景下被裁剪依然可用。 */
const svg = `
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 600">
  <rect width="800" height="600" fill="#eef4f6"/>
  <g fill="none" stroke="#b9c9cf" stroke-width="16" stroke-linecap="round" stroke-linejoin="round">
    <rect x="292" y="212" width="216" height="162" rx="14"/>
    <circle cx="352" cy="270" r="26"/>
    <path d="M298 362 L415 296 L468 342 L520 300 L576 362"/>
  </g>
  <text x="400" y="482" font-family="Arial, sans-serif" font-size="30" letter-spacing="8" fill="#8aa4ad" text-anchor="middle">LIQIANG</text>
</svg>`

export const FALLBACK_IMAGE_URL = `data:image/svg+xml,${encodeURIComponent(svg.trim())}`
