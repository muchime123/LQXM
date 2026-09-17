<script setup>
/**
 * 内页统一 Hero 组件（全站统一协议）。
 *
 * Props（禁止页面自行发明参数名）：
 *  - primaryAction     { text, to } 或 { text, inquiry: {type, source, productSlug, productName} }
 *  - secondaryAction   同上结构；不传 to 传 inquiry 对象即触发弹窗而非跳转
 */
import { h } from 'vue'
import { RouterLink } from 'vue-router'
import { useInquiryModal } from '../../composables/useInquiryModal'

const props = defineProps({
  eyebrow: { type: String, default: '' },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  image: { type: String, default: '' },
  primaryAction: { type: Object, default: null },
  secondaryAction: { type: Object, default: null },
  decor: { type: String, default: 'grid' },
  theme: { type: String, default: 'dark' },
  size: { type: String, default: 'md' },
  twoCol: { type: Boolean, default: false },
})

const { openInquiry } = useInquiryModal()

function isInquiryAction(action) {
  return action?.inquiry && typeof action.inquiry === 'object'
}

function renderAction(action, variant, key) {
  if (!action || !action.text) return null
  const cls = `button ${variant}`

  /* 1. 弹窗模式：action.inquiry = { type, source, productSlug, productName } */
  if (isInquiryAction(action)) {
    const cfg = action.inquiry
    return h(
      'button',
      {
        key,
        type: 'button',
        class: cls,
        onClick: () =>
          openInquiry({
            type: cfg.type === 'sample' ? 'sample' : 'consult',
            source: cfg.source || '',
            productSlug: cfg.productSlug || '',
            productName: cfg.productName || '',
          }),
      },
      [action.text, h('span', { class: 'arrow' }, ' →')]
    )
  }

  /* 2. 外链 / tel / mailto / #anchor 用 <a> */
  const to = action.to
  const isExternal =
    typeof to === 'string' &&
    (to.startsWith('http') || to.startsWith('tel:') || to.startsWith('mailto:') || to.startsWith('#'))
  if (isExternal) {
    const attrs = { class: cls, href: to, key }
    if (to.startsWith('http')) {
      attrs.target = '_blank'
      attrs.rel = 'noopener noreferrer'
    }
    return h('a', attrs, [action.text, h('span', { class: 'arrow' }, ' →')])
  }

  /* 3. 内部路由走 RouterLink */
  if (to) {
    return h(
      RouterLink,
      { key, to, class: cls },
      () => [action.text, h('span', { class: 'arrow' }, ' →')]
    )
  }
  return null
}
</script>

<template>
  <section
    class="page-hero"
    :class="[
      `page-hero--${theme}`,
      `page-hero--${size}`,
      { 'page-hero--two-col': twoCol }
    ]"
    :style="image ? { '--page-hero-image': `url(${image})` } : undefined"
  >
    <div class="page-hero__media" aria-hidden="true"></div>
    <div v-if="decor === 'grid'" class="hero-grid"></div>
    <div v-else-if="decor === 'rings'" class="hero-rings"></div>

    <div class="hero-copy reveal" v-reveal>
      <p v-if="eyebrow" class="eyebrow" :class="theme === 'light' ? 'dark' : 'light'">
        <span></span>{{ eyebrow }}
      </p>
      <h1 v-if="title || $slots.title">
        <slot name="title">
          <template v-if="title">{{ title }}</template>
        </slot>
      </h1>

      <div v-if="description || $slots.default" class="hero-body">
        <slot>
          <p v-if="description" v-html="description"></p>
        </slot>
      </div>

      <div v-if="primaryAction || secondaryAction || $slots.actions" class="hero-actions">
        <slot name="actions">
          <component :is="() => renderAction(primaryAction, 'primary', 'primary')" />
          <component :is="() => renderAction(secondaryAction, 'ghost', 'secondary')" />
        </slot>
      </div>
    </div>

    <div
      v-if="twoCol && ($slots.side || image || $slots.image)"
      class="hero-side reveal"
      v-reveal
    >
      <slot name="side">
        <div v-if="image || $slots.image" class="hero-image-wrap">
          <slot name="image">
            <img :src="image" alt="" loading="lazy" decoding="async" />
          </slot>
        </div>
      </slot>
    </div>
  </section>
</template>
