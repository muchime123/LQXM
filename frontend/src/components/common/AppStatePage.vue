<template>
  <section class="app-state" :class="`app-state--${kind}`" :data-testid="`app-state-${kind}`">
    <div class="app-state__icon" aria-hidden="true">
      <svg v-if="kind === 'not-found'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
        stroke-linecap="round" stroke-linejoin="round">
        <circle cx="11" cy="11" r="7" />
        <path d="m21 21-4.35-4.35M11 8v3M11 14.5h.01" />
      </svg>
      <svg v-else-if="kind === 'forbidden'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
        stroke-linecap="round" stroke-linejoin="round">
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
        <path d="M7 11V7a5 5 0 0 1 9.9-1" />
      </svg>
      <svg v-else-if="kind === 'unavailable'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
        stroke-linecap="round" stroke-linejoin="round">
        <rect x="2" y="2" width="20" height="8" rx="2" ry="2" />
        <rect x="2" y="14" width="20" height="8" rx="2" ry="2" />
        <line x1="6" y1="6" x2="6.01" y2="6" />
        <line x1="6" y1="18" x2="6.01" y2="18" />
        <path d="m22 2-20 20" />
      </svg>
      <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"
        stroke-linejoin="round">
        <path
          d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
      </svg>
    </div>
    <p class="app-state__code">{{ copy.code }}</p>
    <h1 class="app-state__title">{{ copy.title }}</h1>
    <p class="app-state__description">{{ copy.description }}</p>
    <div class="app-state__actions">
      <slot>
        <el-button v-if="kind === 'not-found'" @click="goHome">返回首页</el-button>
        <el-button v-if="kind === 'forbidden'" @click="goBack">返回上一页</el-button>
        <el-button v-if="kind === 'unavailable'" type="primary" @click="$emit('retry')">重新加载</el-button>
      </slot>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps<{
  kind: 'not-found' | 'forbidden' | 'unavailable' | 'maintenance'
}>()

defineEmits<{ retry: [] }>()

const router = useRouter()

const COPY: Record<string, { code: string; title: string; description: string }> = {
  'not-found': {
    code: '404',
    title: '页面不存在',
    description: '你访问的地址不存在或已被移除，请检查地址后重试。'
  },
  forbidden: {
    code: '403',
    title: '没有访问权限',
    description: '当前账号没有查看此页面的权限，如需访问请联系系统管理员。'
  },
  unavailable: {
    code: '503',
    title: '服务暂时不可用',
    description: '后端服务当前无法响应，请稍后重试；问题持续时请联系管理员。'
  },
  maintenance: {
    code: 'MAINT',
    title: '系统维护中',
    description: '系统正在进行计划内维护，预计很快恢复，请稍后再访问。'
  }
}

const copy = computed(() => COPY[props.kind])

function goHome() {
  router.push('/products')
}

function goBack() {
  if (window.history.length > 1) window.history.back()
  else goHome()
}
</script>

<style scoped>
.app-state {
  display: flex;
  align-items: center;
  flex-direction: column;
  justify-content: center;
  gap: var(--space-control-gap-tight);
  min-height: 60vh;
  padding: var(--space-3xl) var(--space-section-gap);
  text-align: center;
}

.app-state__icon {
  display: grid;
  place-items: center;
  width: 88px;
  height: 88px;
  margin-bottom: var(--space-control-gap);
  border-radius: var(--radius-2xl);
  background: var(--color-surface-alt);
  color: var(--color-primary);
}

.app-state__icon svg {
  width: 44px;
  height: 44px;
}

.app-state--forbidden .app-state__icon {
  color: var(--color-warning-text);
  background: var(--color-warning-bg);
}

.app-state--unavailable .app-state__icon,
.app-state--maintenance .app-state__icon {
  color: var(--color-danger-text);
  background: var(--color-danger-bg);
}

.app-state__code {
  margin: 0;
  color: var(--color-text-faint);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  letter-spacing: 0.12em;
}

.app-state__title {
  margin: 0;
  color: var(--color-text-heading);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
  letter-spacing: -0.02em;
}

.app-state__description {
  max-width: 46ch;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-base);
  line-height: var(--lh-loose);
}

.app-state__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-sm);
  margin-top: var(--space-section-gap);
}
</style>
