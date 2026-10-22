<template>
  <LqTable :data="entries" row-key="id" density="compact">
    <el-table-column prop="entry_date" label="日期" width="110" />
    <el-table-column prop="account_name" label="账户" min-width="175" show-overflow-tooltip />
    <el-table-column prop="summary" label="摘要" min-width="210" show-overflow-tooltip />
    <el-table-column prop="counterparty_raw" label="往来对象" min-width="170" show-overflow-tooltip />
    <el-table-column prop="reimbursed_by" label="经办 / 报销" width="115" show-overflow-tooltip />
    <el-table-column label="收入" width="125" align="right">
      <template #default="{ row }">{{ amount(row.income) }}</template>
    </el-table-column>
    <el-table-column label="支出" width="125" align="right">
      <template #default="{ row }">{{ amount(row.expense) }}</template>
    </el-table-column>
    <el-table-column label="来源结存" width="135" align="right">
      <template #default="{ row }">{{ amount(row.balance) }}</template>
    </el-table-column>
  </LqTable>
</template>

<script setup lang="ts">
import LqTable from '@/components/common/LqTable.vue'
import type { CashflowEntry } from '@/api/cashflowJournal'
import { amount } from './cashflowFormat'

defineProps<{ entries: CashflowEntry[] }>()
</script>
