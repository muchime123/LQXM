<script setup lang="ts">
interface CarouselImage {
  id: number
  [key: string]: any
}

interface Props {
  images: readonly CarouselImage[]
  index: number
  thumbnailUrl: (image: CarouselImage) => string
  alt?: string
}

const props = withDefaults(defineProps<Props>(), {
  alt: '规格图片'
})

const emit = defineEmits<{
  'update:index': [index: number]
  preview: [index: number]
  manage: []
}>()

function prev() {
  const len = props.images.length
  if (len <= 1) return
  emit('update:index', (props.index - 1 + len) % len)
}

function next() {
  const len = props.images.length
  if (len <= 1) return
  emit('update:index', (props.index + 1) % len)
}
</script>

<template>
  <div class="variant-carousel">
    <div class="carousel-track">
      <img
        v-for="(image, i) in images"
        :key="image.id"
        :src="thumbnailUrl(image)"
        :alt="alt"
        :class="{ active: index === i }"
        loading="lazy"
        decoding="async"
        @click.stop="emit('preview', i)"
      />
    </div>
    <button v-if="images.length > 1" type="button" class="carousel-prev" @click.stop="prev">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16">
        <path d="m15 18-6-6 6-6" />
      </svg>
    </button>
    <button v-if="images.length > 1" type="button" class="carousel-next" @click.stop="next">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16">
        <path d="m9 18 6-6-6-6" />
      </svg>
    </button>
    <div v-if="images.length > 1" class="carousel-indicators">
      <button
        v-for="(_, i) in images"
        :key="i"
        type="button"
        :class="{ active: index === i }"
        @click.stop="emit('update:index', i)"
      ></button>
    </div>
    <button type="button" class="carousel-manage" title="管理图片" @click.stop="emit('manage')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16">
        <path
          d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"
        />
      </svg>
    </button>
  </div>
</template>

<style scoped>
.variant-carousel {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.carousel-track {
  position: relative;
  width: 100%;
  height: 100%;
}

.carousel-track img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  cursor: zoom-in;
  object-fit: cover;
  opacity: 0;
  transition: opacity 0.3s ease;
}

.carousel-track img.active {
  opacity: 1;
}

.carousel-prev,
.carousel-next {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 50%;
  background: var(--color-overlay-image, rgba(0, 0, 0, 0.45));
  color: var(--color-surface, #fff);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background var(--transition-base, 0.2s ease);
  z-index: 10;
}

.carousel-prev:hover,
.carousel-next:hover {
  background: var(--color-overlay-image-strong, rgba(0, 0, 0, 0.65));
}

.carousel-prev {
  left: 8px;
}

.carousel-next {
  right: 8px;
}

.carousel-indicators {
  position: absolute;
  bottom: 8px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: var(--space-sm, 4px);
  z-index: 10;
}

.carousel-indicators button {
  width: 8px;
  height: 8px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  transition: background var(--transition-base, 0.2s ease);
}

.carousel-indicators button.active {
  background: var(--color-surface, #fff);
}

.carousel-manage {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 50%;
  background: var(--color-overlay-image, rgba(0, 0, 0, 0.45));
  color: var(--color-surface, #fff);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background var(--transition-base, 0.2s ease);
  z-index: 10;
}

.carousel-manage:hover {
  background: var(--color-overlay-image-strong, rgba(0, 0, 0, 0.65));
}
</style>
