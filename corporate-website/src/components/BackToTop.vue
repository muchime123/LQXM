<script setup>
/**
 * 回到顶部悬浮按钮。
 * - 滚动超过阈值后淡入，固定于视口右下角（浮层，永远在内容之上）
 * - 圆环实时显示页面滚动进度
 * - 点击平滑回到顶部
 */
import { ref, onMounted, onUnmounted } from 'vue'

const visible = ref(false)
const progress = ref(0)
const THRESHOLD = 320

const RADIUS = 21
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

function handleScroll() {
  const doc = document.documentElement
  const max = doc.scrollHeight - window.innerHeight
  progress.value = max > 0 ? Math.min(window.scrollY / max, 1) : 0
  visible.value = window.scrollY > THRESHOLD
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => {
  window.addEventListener('scroll', handleScroll, { passive: true })
  window.addEventListener('resize', handleScroll, { passive: true })
  handleScroll()
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
  window.removeEventListener('resize', handleScroll)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="btt">
      <button
        v-if="visible"
        class="back-to-top"
        @click="scrollToTop"
        aria-label="回到顶部"
      >
        <svg class="back-to-top__ring" viewBox="0 0 52 52" aria-hidden="true">
          <circle class="back-to-top__track" cx="26" cy="26" :r="RADIUS" />
          <circle
            class="back-to-top__bar"
            cx="26"
            cy="26"
            :r="RADIUS"
            :stroke-dasharray="CIRCUMFERENCE"
            :stroke-dashoffset="CIRCUMFERENCE * (1 - progress)"
          />
        </svg>
        <svg class="back-to-top__icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <polyline points="18 15 12 9 6 15" />
        </svg>
      </button>
    </Transition>
  </Teleport>
</template>

<style scoped>
.back-to-top {
  position: fixed;
  right: 28px;
  bottom: 32px;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  background: linear-gradient(145deg, var(--navy), var(--navy-tone));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 24px rgba(7, 27, 52, .35);
  z-index: 8000;
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease), background var(--dur) var(--ease);
}

.back-to-top__ring {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.back-to-top__track {
  fill: none;
  stroke: rgba(255, 255, 255, .12);
  stroke-width: 2.5;
}

.back-to-top__bar {
  fill: none;
  stroke: var(--cyan);
  stroke-width: 2.5;
  stroke-linecap: round;
  transform: rotate(-90deg);
  transform-origin: center;
  transition: stroke-dashoffset .1s linear;
}

.back-to-top__icon {
  position: relative;
  z-index: 1;
  transition: transform var(--dur) var(--ease);
}

.back-to-top:hover {
  box-shadow: 0 10px 28px rgba(14, 114, 232, .45);
  transform: translateY(-2px);
}

.back-to-top:active { transform: translateY(0) scale(.96); }

.back-to-top:hover .back-to-top__icon {
  transform: translateY(-2px);
}

/* 淡入 + 上浮 */
.btt-enter-active,
.btt-leave-active {
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
}

.btt-enter-from,
.btt-leave-to {
  opacity: 0;
  transform: translateY(12px);
}

.btt-enter-to,
.btt-leave-from {
  opacity: 1;
  transform: translateY(0);
}

@media (max-width: 640px) {
  .back-to-top {
    right: 16px;
    bottom: 22px;
    width: 46px;
    height: 46px;
  }
}

@media (pointer: coarse) {
  .back-to-top { width: 50px; height: 50px; }
}
</style>
