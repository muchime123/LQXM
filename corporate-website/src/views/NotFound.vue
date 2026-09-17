<script setup>
/**
 * 404 页面。
 * 友好提示 + 回到首页 / 产品中心 / 解决方案的入口。
 */
import { RouterLink } from 'vue-router'
import { onMounted } from 'vue'
import { setSeo } from '../composables/useSeo'

onMounted(() => {
  setSeo('页面未找到', '您访问的页面不存在或已下架，请返回利强包装官网首页继续浏览。')
})
</script>

<template>
  <main class="not-found-page">
    <div class="not-found-inner">
      <div class="not-found-code">404</div>
      <h1>页面走丢了</h1>
      <p>您访问的页面不存在或已下架，可能链接已失效。</p>
      <p class="not-found-hint">不用担心，以下入口可以帮您回到正轨：</p>
      <div class="not-found-actions">
        <RouterLink to="/" class="button primary">返回首页 <span>→</span></RouterLink>
        <RouterLink to="/products" class="button secondary">浏览产品</RouterLink>
        <RouterLink to="/about#contact" class="text-link">联系获取帮助 <span>→</span></RouterLink>
      </div>
    </div>
  </main>
</template>

<style scoped>
.not-found-page {
  min-height: 72vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 150px 20px 80px;
  background: linear-gradient(180deg, var(--paper), #fff);
  text-align: center;
}
.not-found-inner {
  max-width: 560px;
}
.not-found-code {
  font-size: clamp(72px, 14vw, 128px);
  font-weight: 800;
  line-height: 1;
  background: linear-gradient(135deg, var(--accent, #43c6d5), var(--accent-2, #2a8a9e));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  margin-bottom: 16px;
  letter-spacing: -0.04em;
}
.not-found-page h1 {
  font-size: 1.6rem;
  margin-bottom: 12px;
}
.not-found-page p {
  color: var(--text-muted, #8896a6);
  margin-bottom: 8px;
  line-height: 1.6;
}
.not-found-hint {
  margin-top: 24px !important;
  font-size: 0.9rem;
}
.not-found-actions {
  display: flex;
  gap: 16px;
  justify-content: center;
  flex-wrap: wrap;
  margin-top: 32px;
}
.not-found-actions .button {
  padding: 12px 28px;
  border-radius: 8px;
  font-weight: 600;
  text-decoration: none;
  transition: all 0.2s;
}
.not-found-actions .button.primary {
  background: var(--accent, #43c6d5);
  color: #fff;
}
.not-found-actions .button.primary:hover {
  filter: brightness(1.1);
}
.not-found-actions .button.secondary {
  border: 1px solid var(--line);
  color: var(--ink);
  background: #fff;
}
.not-found-actions .button.secondary:hover {
  border-color: var(--blue);
  color: var(--blue);
}
.not-found-actions .text-link { margin: 0; align-self: center; }
</style>
