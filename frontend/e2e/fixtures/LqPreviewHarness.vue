<template>
  <LqPreview
    :model-value="true"
    title="统一文件预览"
    :url="assetId === null ? '' : '/api/file-assets/document/assets/41/preview'"
    :file-name="assetId === null ? '' : 'manual.pdf'"
    :category="assetId === null ? '' : 'document'"
    :owner-type="assetId === null ? '' : 'document'"
    :asset-id="assetId"
  />
</template>

<script setup lang="ts">
import LqPreview from '@/components/common/LqPreview.vue'

withDefaults(defineProps<{ assetId?: number | null }>(), { assetId: 41 })
</script>