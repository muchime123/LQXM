<script setup lang="ts">
/**
 * 生产跟单列表「Excel 式表头筛选」的通用封装。
 *
 * 【为什么单独一个组件】原先每列要写一遍 popover + 搜索框 + 复选框 + 确定/取消，重复且易漂移；
 * 抽出来后，任何一列要接表头筛选，只需在 `<el-table-column>` 的 `#header` 插槽放一个标签：
 *   <TableHeaderFilter v-model="xxxFilter" :options="..." @change="search" />
 * 状态仍收在 useTrackingFilters（加一个 ref + 在 trackingFilters() 输出即可），后端加对应条件。
 *
 * 【交互】点表头文字/箭头打开下拉，内部勾选（支持搜索、全选、清空），点「确定」才提交并触发 onChange
 * （默认就是 search 重查）——避免每次勾选都打一次后端。未确定前只改草稿，不影响已生效筛选。
 * 触发用 click：点表头开关，点弹窗外任意处自动关闭。
 *
 * 【options 兼容】既接受 `string[]`（值即显示），也接受 `{value,label}[]`；组件内部统一规整。
 */
import { computed, ref } from 'vue'

type Option = string | { value: string; label: string }

const props = withDefaults(defineProps<{
  modelValue: string[]
  options: Option[]
  /** 表头文字（如「业务员」）。留空则只显示筛选箭头。 */
  title?: string
  placeholder?: string
  /** 选项是否可搜索（选项多时打开）。 */
  searchable?: boolean
}>(), { title: '', placeholder: '搜索', searchable: true })

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
  /** 确定提交后触发，父组件通常接 search / 重查。 */
  change: []
}>()

const visible = ref(false)
const keyword = ref('')
/** 打开时的草稿：确定前只改它，不影响已生效的 modelValue。 */
const draft = ref<string[]>([...props.modelValue])

const normalized = computed(() =>
  props.options.map(option => (typeof option === 'string' ? { value: option, label: option } : option)))
const filteredOptions = computed(() => {
  if (!keyword.value.trim()) return normalized.value
  const kw = keyword.value.trim().toLowerCase()
  return normalized.value.filter(option =>
    option.label.toLowerCase().includes(kw) || option.value.toLowerCase().includes(kw))
})

/** popover 显示时同步草稿、清空搜索框。 */
function onShow() { draft.value = [...props.modelValue]; keyword.value = '' }
function selectAll() { draft.value = filteredOptions.value.map(option => option.value) }
function clearAll() { draft.value = [] }
function cancel() { visible.value = false }
function apply() {
  emit('update:modelValue', [...draft.value])
  visible.value = false
  emit('change')
}
</script>

<template>
  <el-popover v-model:visible="visible" placement="bottom-start" :width="240" trigger="click" @show="onShow">
    <template #reference>
      <span class="th-filter-trigger" :class="{ 'is-active': modelValue.length }">
        <span v-if="title" class="th-filter-label">{{ title }}</span>
        <span class="th-filter-caret" :class="{ open: visible }">▼</span>
        <span v-if="modelValue.length" class="th-filter-count">{{ modelValue.length }}</span>
      </span>
    </template>
    <div class="th-filter-panel">
      <el-input
        v-if="searchable" v-model="keyword" clearable :placeholder="placeholder" size="small" class="th-filter-search" />
      <div class="th-filter-options">
        <el-checkbox-group v-if="filteredOptions.length" v-model="draft" class="th-filter-checkbox-group">
          <el-checkbox
            v-for="option in filteredOptions" :key="option.value" :value="option.value" class="th-filter-option">
            {{ option.label }}
          </el-checkbox>
        </el-checkbox-group>
        <p v-else class="th-filter-empty">无匹配项</p>
      </div>
      <div class="th-filter-actions">
        <el-button link size="small" type="primary" @click="selectAll">全选</el-button>
        <el-button link size="small" type="primary" @click="clearAll">清空</el-button>
        <span class="th-filter-spacer" />
        <el-button size="small" @click="cancel">取消</el-button>
        <el-button type="primary" size="small" @click="apply">确定</el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.th-filter-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  user-select: none;
  line-height: 1;
}
.th-filter-trigger:hover .th-filter-label,
.th-filter-trigger.is-active .th-filter-label {
  color: var(--el-color-primary);
}
.th-filter-caret {
  font-size: 10px;
  color: var(--el-text-color-secondary);
  transition: transform 0.15s ease;
}
.th-filter-caret.open { transform: rotate(180deg); color: var(--el-color-primary); }
.th-filter-trigger.is-active .th-filter-caret { color: var(--el-color-primary); }
.th-filter-count {
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 8px;
  padding: 0 5px;
  line-height: 16px;
  min-width: 16px;
  text-align: center;
}
.th-filter-panel { display: flex; flex-direction: column; gap: 8px; }
.th-filter-options { max-height: 240px; overflow-y: auto; }
.th-filter-checkbox-group { display: flex; flex-direction: column; }
.th-filter-option { margin-right: 0 !important; height: 28px; }
.th-filter-empty { margin: 0; color: var(--el-text-color-secondary); font-size: 12px; text-align: center; padding: 8px 0; }
.th-filter-actions { display: flex; align-items: center; gap: 4px; border-top: 1px solid var(--el-border-color-lighter); padding-top: 8px; }
.th-filter-spacer { flex: 1; }
</style>
