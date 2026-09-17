<script setup>
/**
 * 统一 SVG 图标组件。
 * 线性风格（stroke-based），与官网工业风设计一致。
 * 用法：<AppIcon name="phone" :size="20" />
 *
 * 新增图标：在 ICON_PATHS 中添加 name → svg path 字符串。
 */
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 24 },
})

const ICON_PATHS = {
  /* 联系方式 */
  phone: '<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/>',
  mail: '<path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"/><polyline points="22,6 12,13 2,6"/>',
  location: '<path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/>',

  /* 解决方案 */
  fresh: '<path d="M12 2a4 4 0 0 0-4 4c0 2 2 4 4 8 2-4 4-6 4-8a4 4 0 0 0-4-4z"/><path d="M12 14v8"/><path d="M9 18l3 2 3-2"/>',
  sealing: '<rect x="3" y="8" width="18" height="4" rx="1"/><path d="M5 12v8h14v-8"/><path d="M9 16h6"/>',
  automation: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
  consumables: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5"/><path d="M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3"/>',
  film: '<path d="M4 4h16v16H4z"/><path d="M8 4v16"/><path d="M16 4v16"/><path d="M4 9h16"/><path d="M4 15h16"/>',
  container: '<path d="M4 7h16l-2 13H6z"/><path d="M3 4h18v3H3z"/><path d="M9 11h6"/>',
  machine: '<rect x="3" y="3" width="18" height="14" rx="2"/><path d="M3 14h18"/><circle cx="8" cy="9" r="2"/><path d="M14 8h4"/><path d="M14 11h4"/><path d="M7 21h10"/><path d="M9 17v4"/><path d="M15 17v4"/>',

  /* 行业场景 */
  restaurant: '<path d="M3 2v7a3 3 0 0 0 3 3h0V2"/><path d="M6 12v10"/><path d="M18 2c-1.5 0-3 1-3 4v4c0 1 .5 2 2 2h0V2"/><path d="M17 12v10"/>',
  meal: '<path d="M4 11h16"/><path d="M4 11a8 8 0 0 1 16 0"/><path d="M2 15h20"/><path d="M3 15a9 9 0 0 0 18 0"/><path d="M9 19h6"/>',
  bakery: '<path d="M4 8a4 4 0 0 1 8 0 4 4 0 0 1 8 0"/><path d="M4 8v4h16V8"/><path d="M4 12v8h16v-8"/><path d="M9 4l1.5 2"/><path d="M14.5 4l1.5 2"/>',
  airplane: '<path d="M21 14l-9-5-9 5 3-7 6 3 6-3 3 7z"/><path d="M12 9v10"/><path d="M9 13l3 3 3-3"/>',
  cosmetic: '<rect x="9" y="2" width="6" height="5" rx="1"/><path d="M10 7h4l2 13a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2l2-13z"/><path d="M9 13h6"/>',

  /* 认证 / 资质 */
  shield: '<path d="M12 2L4 5v6c0 5 3.4 9.4 8 11 4.6-1.6 8-6 8-11V5z"/><path d="M9 11.5l2 2 4-4.5"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',

  /* 沟通 / 消息 */
  message: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',

  /* 社交媒体 */
  douyin: '<path d="M9 3v10.5a3.5 3.5 0 1 1-3.5-3.5"/><path d="M9 3c0 2 1.5 3.5 3.5 3.5H16"/><path d="M14 6.5V14a3.5 3.5 0 1 0 3.5-3.5"/>',
  'wechat-channel': '<circle cx="12" cy="12" r="9"/><polygon points="10,8 16,12 10,16" fill="currentColor" stroke="none"/>',
  xiaohongshu: '<rect x="3" y="3" width="18" height="18" rx="3"/><path d="M7 8h4"/><path d="M7 12h10"/><path d="M7 16h7"/>',
  wechat: '<path d="M8.5 10.5a1 1 0 1 1 0-2 1 1 0 0 1 0 2z" fill="currentColor" stroke="none"/><path d="M15.5 10.5a1 1 0 1 1 0-2 1 1 0 0 1 0 2z" fill="currentColor" stroke="none"/><path d="M9 3C4.6 3 1 6.2 1 10a6.5 6.5 0 0 0 2.5 5.1L3 18l3.4-1.9c.8.2 1.7.4 2.6.4"/><path d="M15 9c-3.9 0-7 2.7-7 6s3.1 6 7 6c.8 0 1.6-.1 2.3-.3l2.7 1.5-.7-2.3c1.7-1.1 2.7-2.8 2.7-4.7 0-3.3-3.1-6-7-6z"/>',
  weibo: '<ellipse cx="12" cy="14" rx="8" ry="6"/><path d="M7 10c0-2 1.5-4 5-4"/><circle cx="10" cy="14" r="1.5" fill="currentColor" stroke="none"/>',

  /* 通用 */
  check: '<path d="M20 6L9 17l-5-5"/>',
  bullet: '<circle cx="12" cy="12" r="6"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.35-4.35"/>',
  ruler: '<path d="M4 20L20 4"/><path d="M7 17l-2-2"/><path d="M11 13l-2-2"/><path d="M15 9l-2-2"/><rect x="2" y="9" width="20" height="6" rx="1" transform="rotate(-45 12 12)"/>',
  edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4z"/>',
  factory: '<path d="M3 21V10l6 3V9l6 3V4h6v17z"/><path d="M7 21v-4h4v4"/>',
  tool: '<path d="M14.7 6.3a4 4 0 0 0-5-5l2.1 2.1-2.4 2.4-2.1-2.1a4 4 0 0 0 5 5l7.4 7.4a2 2 0 0 1-2.8 2.8l-7.4-7.4"/>',
  fire: '<path d="M12 22c4 0 7-3 7-7 0-5-4-7-3-12-4 2-7 6-7 10-1-2-2-3-2-5-2 2-3 4-3 7 0 4 4 7 8 7z"/><path d="M10 18c-1-3 1-5 3-7 0 3 2 4 1 7"/>',
  layers: '<path d="M12 2L2 7l10 5 10-5z"/><path d="M2 12l10 5 10-5"/><path d="M2 17l10 5 10-5"/>',
}

const svgContent = computed(() => ICON_PATHS[props.name] || '')
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    class="app-icon"
    v-html="svgContent"
  />
</template>

<style scoped>
.app-icon {
  display: inline-block;
  vertical-align: middle;
  flex-shrink: 0;
}
</style>
