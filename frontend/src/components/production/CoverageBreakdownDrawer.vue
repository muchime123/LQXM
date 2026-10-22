<template>
  <el-drawer :model-value="modelValue" title="覆盖率明细 · 按车间与产品" size="min(900px, 94vw)"
    @update:model-value="$emit('update:modelValue', $event)">
    <el-skeleton v-if="loading" :rows="8" animated />
    <el-alert v-else-if="error" type="error" :closable="false" :title="error" />
    <template v-else>
      <div class="toolbar">
        <el-segmented v-model="filter" :options="filterOptions" size="small" />
        <el-input v-model="keyword" size="small" clearable placeholder="搜索产品名称" class="kw" />
        <span class="count">{{ filtered.length }} / {{ rows.length }} 个产品</span>
      </div>
      <p class="hint">
        口径与页头统计一致：<b>产品配置匹配＝同车间同产品名恰好一行</b>（重名不猜，会退回通用参考）。
        产品名为空的自成一组，属源数据缺口。点行可直接跳到该产品，继续看历史任务与产品参考工期。
      </p>
      <el-empty v-if="!filtered.length" :image-size="70" description="没有符合条件的产品" />
      <el-table v-else :data="filtered" size="small" stripe border max-height="calc(100vh - 300px)"
        :row-class-name="rowClass" @row-click="pick">
        <el-table-column label="车间" min-width="130">
          <template #default="{ row }"><span class="muted">{{ row.workshopName || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="产品名称" min-width="180">
          <template #default="{ row }">
            <span v-if="row.productName" class="product-name">{{ row.productName }}</span>
            <el-tag v-else size="small" type="danger" effect="plain">产品名为空</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="历史任务" width="96" align="right" sortable prop="orders">
          <template #default="{ row }"><span class="num">{{ row.orders }}</span></template>
        </el-table-column>
        <el-table-column label="跟单记录" width="96" align="right" sortable prop="tracked">
          <template #default="{ row }"><span class="num" :class="{ muted: !row.tracked }">{{ row.tracked || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="产品配置" width="132">
          <template #default="{ row }">
            <el-tag v-if="row.configCount === 1" size="small" effect="plain" type="success">唯一匹配</el-tag>
            <el-tooltip v-else-if="row.configCount > 1" content="该车间有多个同名产品配置，参考日期匹配不猜、沿用通用参考。请删除多余行。" placement="top">
              <el-tag size="small" effect="plain" type="warning">重名 ×{{ row.configCount }}</el-tag>
            </el-tooltip>
            <el-tooltip v-else content="该车间没有这个产品名。氚云字典里应有这一项：到中间产品表补录（名称须与氚云完全一致）。" placement="top">
              <el-tag size="small" effect="plain" type="danger">未建档</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="已发布节点" width="110" align="center" sortable prop="publishedStages">
          <template #default="{ row }">
            <span class="num" :class="{ muted: !row.publishedStages }">{{ row.publishedStages || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { http } from '@/api/http'

/**
 * 覆盖率下钻：页头的统计数字都是汇总值，点开必须能回答「是哪些产品」。
 * 一行＝一个「车间 + 产品名」，与 /duration-coverage 的判据同源（configCount===1 才算匹配）。
 */
interface CoverageBreakdownRow {
  workshopName: string | null
  productName: string | null
  orders: number
  tracked: number
  /** 同车间同产品名的产品配置行数：1＝唯一匹配；0＝未建档；>1＝重名歧义。 */
  configCount: number
  /** 唯一匹配时的配置行 id，用于点行进产品；其余情况为 null。 */
  configId: number | null
  /** 该产品已发布的节点数（含按需节点，仅作治理信号）。 */
  publishedStages: number
}

type FilterKey = 'all' | 'matched' | 'gap' | 'published' | 'tracked'

const props = defineProps<{
  modelValue: boolean
  /** 打开时默认落在哪个筛选（对应页头点的是哪个数字）。 */
  initialFilter?: FilterKey
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; pick: [row: CoverageBreakdownRow] }>()

const filterOptions: { label: string; value: FilterKey }[] = [
  { label: '全部', value: 'all' },
  { label: '有跟单记录', value: 'tracked' },
  { label: '唯一匹配', value: 'matched' },
  { label: '未匹配/重名', value: 'gap' },
  { label: '已有发布', value: 'published' }
]

const loading = ref(false)
const error = ref('')
const rows = ref<CoverageBreakdownRow[]>([])
const filter = ref<FilterKey>('all')
const keyword = ref('')

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return rows.value.filter(row => {
    if (k && !(row.productName ?? '').toLowerCase().includes(k)) return false
    switch (filter.value) {
      case 'tracked': return row.tracked > 0
      case 'matched': return row.configCount === 1
      case 'gap': return row.configCount !== 1
      case 'published': return row.publishedStages > 0
      default: return true
    }
  })
})

watch(() => props.modelValue, visible => {
  if (!visible) return
  filter.value = props.initialFilter ?? 'all'
  keyword.value = ''
  load()
})

async function load() {
  loading.value = true
  error.value = ''
  try { rows.value = (await http.get<CoverageBreakdownRow[]>('/manufacturing/master/duration-coverage/breakdown')).data }
  catch (e: any) { error.value = e?.message || '覆盖率明细加载失败' }
  finally { loading.value = false }
}

/** 只有唯一匹配的产品才有配置行可跳；其余仍跳车间，方便就地补录/删重名。 */
function rowClass({ row }: { row: CoverageBreakdownRow }) {
  return row.configCount === 1 ? 'is-pickable' : ''
}
function pick(row: CoverageBreakdownRow) { emit('pick', row) }
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
.kw { width: 200px; }
.count { font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
.hint { margin: 0 0 10px; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary, #5f6f82); }
.product-name { color: var(--color-text-heading, #0f172a); font-weight: 500; }
.num { font-variant-numeric: tabular-nums; }
.muted { color: var(--color-text-secondary, #94a3b8); }
:deep(tr.is-pickable) { cursor: pointer; }
</style>
