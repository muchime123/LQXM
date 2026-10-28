<script setup>
/**
 * 产品卡片组件。用于产品中心网格和首页实时产品区。
 * 点击跳转到 /products/{slug}（slug 为空时用 id 兜底）。
 * 图片加载失败由全局保底机制（main.js）替换为品牌占位图。
 */
import { computed } from 'vue'
import { productImageUrl, productImageSrcSet } from '../api/website'

const props = defineProps({
  product: { type: Object, required: true },
})

const link = computed(() => `/products/${props.product.websiteSlug || props.product.slug || props.product.id}`)
const subtitle = computed(
  () => props.product.productLineName || props.product.equipmentCategory || props.product.category || '包装设备'
)
</script>

<template>
  <RouterLink :to="link" class="catalog-card catalog-card--cover">
    <div class="catalog-image">
      <img
        loading="lazy"
        decoding="async"
        :src="productImageUrl(product)"
        :srcset="productImageSrcSet(product)"
        sizes="(max-width: 980px) 50vw, (max-width: 1200px) 33vw, 25vw"
        :alt="product.name"
      />
      <div class="catalog-card__name-plate">
        <small class="catalog-card__eyebrow">{{ subtitle }}</small>
        <h3 class="catalog-card__name">{{ product.name }}</h3>
      </div>
    </div>
  </RouterLink>
</template>
