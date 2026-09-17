<script setup>
/**
 * 区块标题组件（全站统一协议）。
 * 所有内容页、列表页、首页 section 标题统一使用。禁止页面自行发明参数名。
 *
 * Props：
 *  - eyebrow   眉标（通常大写英文：SOLUTIONS / PACKAGING SUPPLIES …）
 *  - title     h2 主标题（支持换行 <br/>，可用 #title 插槽覆盖复杂内容）
 *  - description  右侧/下方说明文字（可用 #description 插槽覆盖）
 *  - theme     dark（默认，浅色底用深色标题）| light（深蓝底色用浅色标题）
 *  - align     left（默认，左右布局：左侧标题 + 右侧说明）| center（居中上下堆叠）
 *  - size      md（默认）| lg（标题字号加大，用于 content section 大标题）
 *
 *  插槽：
 *   - #title         覆盖 h2 标题
 *   - #description   覆盖 description 区
 */
defineProps({
  eyebrow: { type: String, default: '' },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  theme: { type: String, default: 'dark' }, // dark | light
  align: { type: String, default: 'left' }, // left | center
  size: { type: String, default: 'md' }, // md | lg
})
</script>

<template>
  <div
    class="section-heading reveal"
    v-reveal
    :class="[
      `section-heading--theme-${theme}`,
      `section-heading--align-${align}`,
      `section-heading--size-${size}`,
    ]"
  >
    <div class="section-heading__left">
      <p v-if="eyebrow" class="eyebrow" :class="theme === 'light' ? 'light' : 'dark'">
        <span></span>{{ eyebrow }}
      </p>
      <h2>
        <slot name="title">{{ title }}</slot>
      </h2>
    </div>
    <div v-if="description || $slots.description" class="section-heading__right">
      <slot name="description">
        <p>{{ description }}</p>
      </slot>
    </div>
  </div>
</template>
