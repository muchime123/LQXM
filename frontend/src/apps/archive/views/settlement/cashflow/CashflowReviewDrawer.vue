<template>
  <el-drawer :model-value="modelValue" title="流水日记账导入差异" size="86%" @update:model-value="emit('update:modelValue', $event)">
    <el-alert
      type="info"
      :closable="false"
      title="这里仅列出旧库与新文件的差异，不自动认定两行是同一笔。财务调整历史行后，须复核再确认。"
    />
    <div class="cf-review-toolbar">
      <el-select v-model="batchId" placeholder="选择待确认批次" filterable @change="changeBatch">
        <el-option
          v-for="batch in batches"
          :key="batch.id"
          :value="batch.id"
          :label="`#${batch.id} · ${batch.fileName} · ${batch.totalCount} 行`"
        />
      </el-select>
      <el-select v-model="account" clearable filterable placeholder="全部受影响账户" @change="changeAccount">
        <el-option v-for="name in diff?.accounts || []" :key="name" :label="name" :value="name" />
      </el-select>
    </div>
    <el-empty v-if="!batches.length && !loading" description="目前没有待确认的流水日记账批次" />
    <template v-else-if="diff">
      <el-alert v-if="!diff.stagingAvailable" type="warning" :closable="false" title="该批次暂存行不可用，请重新上传文件。" />
      <p class="cf-review-counts">
        旧库有而新文件没有：{{ diff.oldOnlyCount }} 行　·　新文件有而旧库没有：{{ diff.newOnlyCount }} 行
      </p>
      <el-table v-loading="loading" :data="diff.items" stripe max-height="550">
        <el-table-column label="差异" width="145">
          <template #default="{ row }">
            <el-tag :type="row.kind === 'OLD_ONLY' ? 'warning' : 'info'">
              {{ row.kind === 'OLD_ONLY' ? '旧库独有' : '新文件独有' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="accountName" label="账户" min-width="170" show-overflow-tooltip />
        <el-table-column prop="sourceRowNumber" label="本批 Excel 行号" width="135">
          <template #default="{ row }">{{ row.sourceRowNumber || '—' }}</template>
        </el-table-column>
        <el-table-column prop="entryDate" label="日期" width="135" show-overflow-tooltip />
        <el-table-column prop="summary" label="摘要" min-width="190" show-overflow-tooltip />
        <el-table-column label="收入" width="120" align="right">
          <template #default="{ row }">{{ amount(row.income) }}</template>
        </el-table-column>
        <el-table-column label="支出" width="120" align="right">
          <template #default="{ row }">{{ amount(row.expense) }}</template>
        </el-table-column>
        <el-table-column label="来源结存" width="130" align="right">
          <template #default="{ row }">{{ amount(row.balance) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="diff.total > 50"
        v-model:current-page="page"
        layout="prev, pager, next, total"
        :total="diff.total"
        :page-size="50"
        class="cf-review-pagination"
        @current-change="loadDiff"
      />
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  fetchCashflowReviewBatches,
  fetchCashflowReviewDiff,
  type CashflowReviewBatch,
  type CashflowReviewDiff
} from '@/api/cashflowJournal'
import { notify } from '@/composables/ui/useToast'
import { amount } from './cashflowFormat'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const loading = ref(false)
const batches = ref<CashflowReviewBatch[]>([])
const batchId = ref<number | null>(null)
const account = ref('')
const page = ref(1)
const diff = ref<CashflowReviewDiff | null>(null)

watch(
  () => props.modelValue,
  open => {
    if (open) void loadBatches()
  }
)

async function loadBatches() {
  loading.value = true
  try {
    batches.value = (await fetchCashflowReviewBatches()).items
    batchId.value = batches.value[0]?.id ?? null
    account.value = ''
    page.value = 1
    if (batchId.value) await loadDiff()
    else diff.value = null
  } catch {
    notify.error('导入差异加载失败')
  } finally {
    loading.value = false
  }
}

async function loadDiff() {
  if (!batchId.value) return
  loading.value = true
  try {
    diff.value = await fetchCashflowReviewDiff(batchId.value, account.value, page.value)
  } catch {
    notify.error('批次差异加载失败')
  } finally {
    loading.value = false
  }
}

function changeBatch() {
  account.value = ''
  page.value = 1
  void loadDiff()
}

function changeAccount() {
  page.value = 1
  void loadDiff()
}
</script>

<style scoped>
.cf-review-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap);
  margin: var(--space-section-gap) 0;
}

.cf-review-toolbar .el-select:first-child {
  width: 390px;
}

.cf-review-toolbar .el-select:last-child {
  width: 230px;
}

.cf-review-counts {
  margin: 0 0 var(--space-control-gap);
  color: var(--color-text-secondary);
}

.cf-review-pagination {
  margin-top: var(--space-control-gap);
  justify-content: flex-end;
}
</style>
