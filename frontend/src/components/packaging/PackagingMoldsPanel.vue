<template>
  <section class="packaging-molds-panel">
    <SearchToolbar
      v-model:keyword="moldKeyword"
      class="molds-panel-toolbar"
      search-placeholder="搜索模号 / 盒型 / 规格组"
      search-mode="debounce"
      :debounce="200"
    >
      <template #actions>
        <el-button size="small" @click="masterDetailRef?.openImport()">导入 Excel</el-button>
        <el-button size="small" type="primary" @click="masterDetailRef?.openCreate()">新建模具</el-button>
      </template>
    </SearchToolbar>

    <PackagingMoldsMasterDetail
      ref="masterDetailRef"
      mode="single"
      :model-id="modelId"
      :variants="variants"
      :assets="assets"
      v-model:mold-keyword="moldKeyword"
      @open-variant="emit('open-variant', $event)"
      @count-change="emit('count-change', $event)"
    />
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { PackagingVariantView, PackagingAssetView } from '@/api'
import SearchToolbar from '@/components/common/SearchToolbar.vue'
import PackagingMoldsMasterDetail from './PackagingMoldsMasterDetail.vue'

defineProps<{
  modelId: number
  variants?: PackagingVariantView[]
  assets?: PackagingAssetView[]
}>()

const emit = defineEmits<{
  'count-change': [count: number]
  'open-variant': [variant: PackagingVariantView]
}>()

// 顶部工具栏状态：本地搜索关键字，通过 v-model 与主从组件共享。
const moldKeyword = ref('')

const masterDetailRef = ref<InstanceType<typeof PackagingMoldsMasterDetail> | null>(null)
</script>

<style scoped>
.packaging-molds-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-md-lg);
}

.molds-panel-toolbar {
  flex-shrink: 0;
}
</style>
