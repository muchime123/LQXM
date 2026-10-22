<template>
  <el-drawer :model-value="modelValue" :title="`历史生产任务 · ${productName}`" size="min(1080px, 94vw)"
    @update:model-value="$emit('update:modelValue', $event)">
    <el-skeleton v-if="loading" :rows="6" animated />
    <el-alert v-else-if="error" type="error" :closable="false" :title="error" />
    <template v-else-if="data">
      <div class="summary">
        <span class="stat"><b>{{ data.summary.total ?? 0 }}</b> 张历史任务</span>
        <span class="stat ok"><b>{{ data.summary.tracked ?? 0 }}</b> 张已建跟单</span>
        <span v-if="data.summary.draft" class="stat"><b>{{ data.summary.draft }}</b> 张草稿</span>
        <span v-if="data.summary.cancelled" class="stat warn"><b>{{ data.summary.cancelled }}</b> 张已取消</span>
        <span v-if="data.summary.firstAt" class="stat">跨度 {{ day(data.summary.firstAt) }} ~ {{ day(data.summary.lastAt) }}</span>
      </div>
      <p class="hint">
        口径与中间产品表的「历史任务单 / 跟单记录」两个计数完全一致：按「车间 + 产品名」精确匹配，
        <b>不排除</b>草稿与已取消（它们是真实历史，剔掉数字就对不上了，这里单独标注）。
        「跟单进度 x/{{ data.requiredStageCount }}」的分子分母都只数<b>必经节点</b>——按需工序（氧化、木箱）没做不算缺，
        所以会看到「7/7 但未完成」这类组合，那是木箱没做、不影响。
      </p>

      <div class="toolbar">
        <el-segmented v-model="status" :options="statusOptions" size="small" @change="load" />
        <span v-if="data.truncated" class="truncated">只显示最近 {{ data.orders.length }} 张；下方筛选可缩小范围</span>
      </div>

      <el-empty v-if="!data.orders.length" :image-size="70" description="没有符合条件的历史任务" />
      <el-table v-else :data="data.orders" size="small" stripe border max-height="calc(100vh - 290px)">
        <el-table-column label="任务单号" min-width="156" fixed>
          <template #default="{ row }">
            <div class="order-no">{{ row.orderNo || '—' }}</div>
            <small class="order-sub">{{ day(row.createdAt) }}</small>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="86">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="statusType(row.status)">{{ row.status || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="申请日" width="100">
          <template #default="{ row }">{{ row.applicationDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="排产日" width="100">
          <template #default="{ row }">
            <span :class="{ none: !row.scheduledDate }">{{ row.scheduledDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="要求交期" width="100">
          <template #default="{ row }">{{ row.requiredDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="预计完成" width="100">
          <template #default="{ row }">
            <span :class="{ none: !row.expectedDate }">{{ row.expectedDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="80" align="right">
          <template #default="{ row }">{{ qty(row.quantity) }}</template>
        </el-table-column>
        <el-table-column label="跟单" min-width="150">
          <template #default="{ row }">
            <template v-if="row.tracked">
              <el-tag size="small" effect="plain" type="success">已跟单</el-tag>
              <span class="stage-count">完成 {{ row.doneStages }} / {{ data.requiredStageCount }} 节点</span>
              <small v-if="row.lastDoneDate" class="order-sub">最近 {{ row.lastDoneDate }}</small>
            </template>
            <span v-else class="none">未建跟单记录</span>
          </template>
        </el-table-column>
        <!-- 下钻到底：在本页打开这张单的跟单详情抽屉（节点进度 / 报工 / 时间核实），不跳转页面。
             只对有读权限的人显示——没权限的人点开也只会拿到后端「无权查看」。 -->
        <el-table-column v-if="canOpenTracking" label="操作" width="96" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="emit('open-order', row.orderId)">跟单详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchProductOrderHistory, type ProductOrderHistory } from '@/api/productionTracking'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { TRACKING_READ_PERMISSIONS } from '@/utils/tracking/detailLink'

const props = defineProps<{
  modelValue: boolean
  taskConfigId: number | null
  productName: string
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; 'open-order': [number] }>()

/** 「跟单详情」需要生产跟单页的读权限（任一即可），否则不给入口。 */
const { can } = useCurrentUser()
const canOpenTracking = computed(() => can(TRACKING_READ_PERMISSIONS))

/** 空值＝全部；其余按氚云任务单状态原样筛。 */
const status = ref('')
const statusOptions = [
  { label: '全部', value: '' },
  { label: '已生效', value: '已生效' },
  { label: '进行中', value: '进行中' },
  { label: '草稿', value: '草稿' },
  { label: '已取消', value: '已取消' }
]

const loading = ref(false)
const error = ref('')
const data = ref<ProductOrderHistory | null>(null)

watch(() => [props.modelValue, props.taskConfigId], value => {
  if (!value[0] || props.taskConfigId == null) return
  // 每次重新打开都回到「全部」：上次留下的筛选会让用户以为这个产品只有几张单。
  status.value = ''
  load()
}, { immediate: true })

async function load() {
  if (props.taskConfigId == null) return
  loading.value = true
  error.value = ''
  try { data.value = await fetchProductOrderHistory(props.taskConfigId, status.value) }
  catch (e: any) { error.value = e?.message || '历史任务加载失败' }
  finally { loading.value = false }
}

function day(value: string | null) { return value ? String(value).slice(0, 10) : '—' }
function qty(value: number | null) {
  if (value == null) return '—'
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}
function statusType(value: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (value === '进行中') return 'warning'
  if (value === '已取消') return 'danger'
  if (value === '已生效') return 'success'
  return 'info'
}
</script>

<style scoped>
.summary { display: flex; gap: 20px; flex-wrap: wrap; margin-bottom: 10px; }
.stat { font-size: 13px; color: var(--color-text-secondary, #5f6f82); }
.stat b { font-size: 17px; color: var(--color-text-heading, #0f172a); margin-right: 2px; }
.stat.ok b { color: var(--color-success, #059669); }
.stat.warn b { color: var(--color-warning, #d97706); }
.hint { margin: 0 0 12px; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary, #5f6f82); }
.toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
.truncated { font-size: 12px; color: var(--color-warning, #d97706); }
.order-no { font-weight: 600; color: var(--color-text-heading, #0f172a); }
.order-sub { display: block; color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
.stage-count { margin-left: 6px; font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.none { color: var(--color-text-secondary, #94a3b8); font-size: 12px; }
</style>
