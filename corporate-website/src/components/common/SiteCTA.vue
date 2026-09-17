<script setup>
/**
 * 全站统一底部转化 CTA 组件。
 * 所有内容页、首页、列表页底部统一使用。禁止各页面自行发明 CTA 区块样式。
 *
 * Props：
 *  - eyebrow       眉标（默认：START YOUR PROJECT），传空字符串隐藏
 *  - title         主标题（默认：告诉我们您的包装需求）
 *  - description   说明文字（默认：通用询盘引导）
 *  - actionText    主按钮文字（默认：获取包装方案）
 *  - actionTo      主按钮跳转（默认：空，触发 openInquiry() 弹窗）
 *                    传 '/contact?type=consult' 等内部路由或 http/tel/mailto 链接则走跳转
 *  - inquiryType   弹窗模式下的询盘类型：'consult'（默认） | 'sample'
 *  - inquirySource 弹窗模式下的来源页标记（如 'home' / 'solutions'），利于后台归因
 *  - variant       dark（深蓝大色块）| light（浅色条带型，适合页面前中部）
 *  - id            可选，区块 ID，用于锚点跳转
 *
 * 内部判断：
 *  - actionTo 非空：内部路由用 RouterLink，外部/ tel:/ mailto: 用原生 <a>
 *  - actionTo 为空：点击触发 openInquiry({ type, source })
 */
import { RouterLink } from 'vue-router'
import { useInquiryModal } from '../../composables/useInquiryModal'

const props = defineProps({
  eyebrow: { type: String, default: 'START YOUR PROJECT' },
  title: { type: String, default: '告诉我们您的包装需求' },
  description: {
    type: String,
    default: '提供产品、容器、目标产能或样品信息，我们的工程师将协助您梳理适合的设备与工艺方案。',
  },
  actionText: { type: String, default: '获取包装方案' },
  actionTo: { type: String, default: '' },
  inquiryType: { type: String, default: 'consult' }, // 'consult' | 'sample'
  inquirySource: { type: String, default: '' },
  variant: { type: String, default: 'dark' }, // dark | light
  id: { type: String, default: '' },
})

const { openInquiry } = useInquiryModal()

function isInternal(url) {
  if (!url) return false
  if (url.startsWith('http://') || url.startsWith('https://')) return false
  if (url.startsWith('tel:') || url.startsWith('mailto:')) return false
  return true
}

function handleActionClick() {
  if (props.actionTo) return // 走 RouterLink / a 的原生跳转
  openInquiry({
    type: props.inquiryType === 'sample' ? 'sample' : 'consult',
    source: props.inquirySource,
  })
}
</script>

<template>
  <section
    class="site-cta"
    :class="[`site-cta--${variant}`]"
    :id="id || (variant === 'dark' ? 'cta' : '')"
  >
    <div class="site-cta__inner reveal" v-reveal>
      <p v-if="eyebrow" class="eyebrow light"><span></span> {{ eyebrow }}</p>
      <h2>{{ title }}</h2>
      <p v-if="description" class="site-cta__desc">{{ description }}</p>
      <div class="site-cta__actions">
        <!-- 弹窗模式（默认，actionTo 为空） -->
        <button
          v-if="!actionTo"
          type="button"
          class="button"
          :class="variant === 'dark' ? 'white lg' : 'primary lg'"
          @click="handleActionClick"
        >{{ actionText }} <span>→</span></button>

        <!-- 内部路由跳转 -->
        <RouterLink
          v-else-if="isInternal(actionTo)"
          :to="actionTo"
          class="button"
          :class="variant === 'dark' ? 'white lg' : 'primary lg'"
        >{{ actionText }} <span>→</span></RouterLink>

        <!-- 外部链接 / tel / mailto -->
        <a
          v-else
          :href="actionTo"
          class="button"
          :class="variant === 'dark' ? 'white lg' : 'primary lg'"
          :target="actionTo.startsWith('http') ? '_blank' : undefined"
          :rel="actionTo.startsWith('http') ? 'noopener' : undefined"
        >{{ actionText }} <span>→</span></a>
      </div>
    </div>
    <div v-if="variant === 'dark'" class="site-cta__rings"></div>
  </section>
</template>
