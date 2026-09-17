<script setup lang="ts">
import FormSection from '../common/FormSection.vue'

defineProps<{ form: Record<string, any> }>()

const emit = defineEmits<{ 'field-input': [key: string] }>()

const dimensions = [
  { key: 'applicationTags', label: '包装产品 / 应用场景', options: ['鲜食与预制菜', '生鲜肉类', '熟食与餐饮', '烘焙食品', '果蔬农产品', '其他产品'] },
  { key: 'containerTags', label: '适用容器', options: ['托盒/餐盒', '杯/碗', '桶/罐', '袋装产品', '异形容器'] },
  { key: 'packagingProcessTags', label: '包装工艺', options: ['普通封口', '气调保鲜', '真空包装', '热收缩', '贴体包装'] }
]
const capacityOptions = ['手动/小批量', '半自动/中等产能', '全自动/高产能', '产线级']
</script>

<template>
  <section class="product-selection-section">
    <FormSection
      title="适用与选型"
      description="维护产品本身的标准化适用属性，供内部检索、方案匹配、模具包材推荐和官网筛选共同使用。"
    >
      <div class="selection-field-grid">
        <label v-for="dimension in dimensions" :key="dimension.key" class="selection-field">
          <span>{{ dimension.label }}</span>
          <el-select
            v-model="form[dimension.key]"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="请选择，可多选"
            @change="emit('field-input', dimension.key)"
          >
            <el-option v-for="item in dimension.options" :key="item" :label="item" :value="item" />
          </el-select>
        </label>
        <label class="selection-field">
          <span>目标产能</span>
          <el-select v-model="form.capacityLevel" clearable placeholder="请选择产能级别" @change="emit('field-input', 'capacityLevel')">
            <el-option v-for="item in capacityOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </label>
      </div>
    </FormSection>
  </section>
</template>

<style scoped>
.product-selection-section { display: grid; gap: var(--space-card-gap); }
.selection-field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 16px; }
.selection-field { display: grid; gap: 6px; min-width: 0; }
.selection-field > span { color: var(--color-text-strong); font-size: var(--fs-xs); font-weight: 650; }
.selection-field :deep(.el-select) { width: 100%; }
@media (max-width: 720px) { .selection-field-grid { grid-template-columns: 1fr; } }
</style>
