<template>
  <LqTable :data="issues" row-key="issueKey" density="compact" max-height="560">
    <el-table-column prop="accountName" label="账户" min-width="175" show-overflow-tooltip />
    <el-table-column label="日期" width="110">
      <template #default="{ row }">{{ row.entryDate || '—' }}</template>
    </el-table-column>
    <el-table-column label="类别" width="130">
      <template #default="{ row }">
        <StatusTag :type="issueTagType(row.issueCode)" :label="issueLabel(row.issueCode)" size="sm" />
      </template>
    </el-table-column>
    <el-table-column prop="detail" label="异常说明" min-width="250" show-overflow-tooltip />
    <el-table-column label="上一行结存" width="125" align="right">
      <template #default="{ row }">{{ amount(row.expectedBalance) }}</template>
    </el-table-column>
    <el-table-column label="本行结存" width="125" align="right">
      <template #default="{ row }">{{ amount(row.actualBalance) }}</template>
    </el-table-column>
    <el-table-column label="差额" width="120" align="right">
      <template #default="{ row }">
        <span :class="{ 'cf-gap': row.gap }">{{ amount(row.gap) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="100" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click.stop="emit('openSource', row)">来源台账</el-button>
      </template>
    </el-table-column>
  </LqTable>
</template>

<script setup lang="ts">
import LqTable from '@/components/common/LqTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { CashflowIssue } from '@/api/cashflowJournal'
import { amount, issueLabel, issueTagType } from './cashflowFormat'

defineProps<{ issues: CashflowIssue[] }>()
const emit = defineEmits<{ openSource: [issue: CashflowIssue] }>()
</script>

<style scoped>
.cf-gap {
  color: var(--color-danger-text);
  font-weight: var(--fw-medium);
}
</style>
