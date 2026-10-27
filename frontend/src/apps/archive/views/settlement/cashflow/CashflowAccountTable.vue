<template>
  <LqTable :data="accounts" row-key="id" density="compact" @row-click="onRowClick" @expand-change="onExpandChange">
    <el-table-column type="expand">
      <template #default="{ row }">
        <div class="cf-monthly">
          <div class="cf-monthly__head">
            <strong>{{ row.accountName }} · {{ year }} 年逐月</strong>
            <span class="cf-monthly__meta">
              金额为账户原币<template v-if="row.closingStatus !== 'OK'">；缺期初，月初/月末不计算</template>
            </span>
            <el-button size="small" :disabled="!monthlyOf(row.id).length" @click.stop="exportMonthly(row)">
              导出 CSV
            </el-button>
          </div>
          <el-table v-loading="monthlyLoadingId === row.id" :data="monthlyOf(row.id)" size="small" max-height="360">
            <el-table-column label="月份" width="80">
              <template #default="{ row: month }">{{ month.month }} 月</template>
            </el-table-column>
            <el-table-column label="月初" width="140" align="right">
              <template #default="{ row: month }">{{ openingAmount(month.openingBalance) }}</template>
            </el-table-column>
            <el-table-column label="收入" width="140" align="right">
              <template #default="{ row: month }">{{ amount(month.income) }}</template>
            </el-table-column>
            <el-table-column label="支出" width="140" align="right">
              <template #default="{ row: month }">{{ amount(month.expense) }}</template>
            </el-table-column>
            <el-table-column label="净流量" width="140" align="right">
              <template #default="{ row: month }">{{ amount(month.netFlow) }}</template>
            </el-table-column>
            <el-table-column label="月末" width="140" align="right">
              <template #default="{ row: month }">{{ openingAmount(month.closingBalance) }}</template>
            </el-table-column>
            <el-table-column prop="entryCount" label="笔数" width="80" align="right" />
          </el-table>
        </div>
      </template>
    </el-table-column>
    <el-table-column prop="accountName" label="账户" min-width="185" show-overflow-tooltip />
    <el-table-column prop="currency" label="币种" width="80" />
    <el-table-column label="期间期初" width="140" align="right">
      <template #default="{ row }">{{ openingAmount(row.openingBalance) }}</template>
    </el-table-column>
    <el-table-column label="收入" width="135" align="right">
      <template #default="{ row }">{{ amount(row.income) }}</template>
    </el-table-column>
    <el-table-column label="支出" width="135" align="right">
      <template #default="{ row }">{{ amount(row.expense) }}</template>
    </el-table-column>
    <el-table-column label="净流量" width="135" align="right">
      <template #default="{ row }">{{ amount(row.netFlow) }}</template>
    </el-table-column>
    <el-table-column label="期间期末" width="140" align="right">
      <template #default="{ row }">{{ openingAmount(row.closingBalance) }}</template>
    </el-table-column>
    <el-table-column prop="entryCount" label="笔数" width="75" align="right" />
    <!-- 三个维度刻意分列，任何一列都不得代表另一列 -->
    <el-table-column label="期间余额核对" width="150">
      <template #default="{ row }">
        <el-tooltip placement="top" :content="`期初 + 期间收入 − 期间支出 = 期间期末。${row.closingStatus === 'OK'
          ? '只说明能算出这个数，不代表已与银行核对。' : '来源缺期初，不做兜底填充。'}`">
          <StatusTag :type="closingTagType(row.closingStatus)" :label="row.closingStatusLabel" size="sm" />
        </el-tooltip>
      </template>
    </el-table-column>
    <el-table-column label="逐笔结存核对" width="175">
      <template #default="{ row }">
        <el-tooltip placement="top" :content="detailTooltip(row)">
          <StatusTag
            :type="row.sourceBalanceStatus === 'DETAIL_EXCEPTIONS' ? 'warn' : 'ok'"
            :label="row.sourceBalanceStatus === 'DETAIL_EXCEPTIONS'
              ? `${row.sourceBalanceExceptionCount} 处待核实` : `${row.sourceBalanceCheckedLinks} 条链接衔接`"
            size="sm"
          />
        </el-tooltip>
      </template>
    </el-table-column>
    <!-- 辅助维度：只有在「同日各笔顺序被结存数据唯一锁定」时才给结论，否则说明缺哪类证据 -->
    <el-table-column label="来源末行（辅助）" width="170">
      <template #default="{ row }">
        <el-tooltip placement="top" :content="evidenceTooltip(row)">
          <StatusTag
            :type="row.sourceClosingComparable ? 'ok' : 'neutral'"
            :label="row.sourceClosingComparable ? '顺序已锁定，末行一致' : '不可比'"
            size="sm"
          />
        </el-tooltip>
      </template>
    </el-table-column>
    <el-table-column label="人工对账" width="125">
      <template #default="{ row }">
        <el-tooltip placement="top" content="财务与银行流水/实际账户余额的确认。P1 尚未开放人工对账操作，一律未对账。">
          <StatusTag :type="row.manualReconcileStatus === 'NOT_STARTED' ? 'neutral' : 'ok'"
            :label="row.manualReconcileStatusLabel" size="sm" />
        </el-tooltip>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="165" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click.stop="emit('openEntries', row)">看流水</el-button>
        <el-button link type="primary" @click.stop="emit('openOpening', row)">年度期初</el-button>
      </template>
    </el-table-column>
  </LqTable>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import LqTable from '@/components/common/LqTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { fetchCashflowMonthly, type CashflowAccount, type CashflowMonthlyRow } from '@/api/cashflowJournal'
import { notify } from '@/composables/ui/useToast'
import { amount, closingTagType, downloadCsv, openingAmount } from './cashflowFormat'

const props = defineProps<{ accounts: CashflowAccount[]; year: number }>()
const emit = defineEmits<{ openEntries: [account: CashflowAccount]; openOpening: [account: CashflowAccount] }>()

// ==================== 逐月汇总（展开行，P1 从抽屉迁入） ====================
const monthlyCache = ref<Record<number, CashflowMonthlyRow[]>>({})
const monthlyLoadingId = ref<number | null>(null)
const monthlyOf = (accountId: number) => monthlyCache.value[accountId] ?? []
watch(() => props.year, () => {
  monthlyCache.value = {}
  monthlyLoadingId.value = null
})

async function ensureMonthly(accountId: number) {
  if (monthlyCache.value[accountId] || monthlyLoadingId.value === accountId) return
  const requestedYear = props.year
  monthlyLoadingId.value = accountId
  try {
    const report = await fetchCashflowMonthly(accountId, requestedYear)
    if (requestedYear !== props.year) return
    monthlyCache.value = { ...monthlyCache.value, [accountId]: report.items }
  } catch {
    if (requestedYear !== props.year) return
    monthlyCache.value = { ...monthlyCache.value, [accountId]: [] }
    notify.error('月度汇总加载失败')
  } finally {
    if (requestedYear === props.year && monthlyLoadingId.value === accountId) monthlyLoadingId.value = null
  }
}

async function onExpandChange(row: CashflowAccount, expandedRows: CashflowAccount[]) {
  const expanded = Array.isArray(expandedRows) ? expandedRows : [row]
  if (expanded.some(item => item && item.id === row.id)) await ensureMonthly(row.id)
}

function onRowClick(row: CashflowAccount) {
  emit('openEntries', row)
}

function detailTooltip(row: CashflowAccount): string {
  if (row.sourceBalanceStatus !== 'DETAIL_EXCEPTIONS') {
    return `期间内 ${row.sourceBalanceCheckedLinks} 条相邻链接全部衔接（按导入时的行序）。系统结论：${row.reconcileSummary}`
  }
  const range = `${row.sourceBalanceReviewStart || '—'} 至 ${row.sourceBalanceReviewEnd || '—'}`
  return `断点 ${row.sourceBalanceExceptionCount} 处，未核实区间 ${range}。仅提示历史明细，不影响期间期末。系统结论：${row.reconcileSummary}`
}

/**
 * 来源末行比对是辅助核对：本库没有银行交易序号，同日多笔的层内顺序大多不可证实。
 * 只有「使每行结存首尾相接的排列恰好唯一」时，来源末行的结存才算被数据锁定，才拿它与期间期末比。
 */
function evidenceTooltip(row: CashflowAccount): string {
  if (row.sourceClosingComparable) {
    return `同日各笔顺序已被结存数据唯一锁定；${row.sourceClosingDate || '期间末日'} 来源末行结存 `
      + `${openingAmount(row.sourceClosingBalance)}，与期间期末一致。`
  }
  return `不做来源末行比对：${row.sourceClosingSkipLabel || '缺少可靠来源顺序'}。`
    + '本库无银行交易序号，同日多笔的顺序无法由数据唯一确定，此时与来源末行比对会得出假结论。'
}

function exportMonthly(account: CashflowAccount) {
  const rows = monthlyCache.value[account.id]
  if (!rows?.length) return
  const data: unknown[][] = [
    ['账户', '币种', '月份', '月初', '收入', '支出', '净流量', '月末', '笔数'],
    ...rows.map(row => [
      account.accountName,
      account.currency,
      `${props.year}-${String(row.month).padStart(2, '0')}`,
      row.openingBalance,
      row.income,
      row.expense,
      row.netFlow,
      row.closingBalance,
      row.entryCount
    ])
  ]
  downloadCsv(data, `${account.accountName}_${props.year}月度汇总.csv`)
}
</script>

<style scoped>
.cf-monthly {
  display: grid;
  gap: var(--space-control-gap);
  padding: var(--space-control-gap) var(--space-control-gap-wide);
  background: var(--color-surface-alt);
}

.cf-monthly__head {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
}

.cf-monthly__meta {
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
}

.cf-monthly__head > .el-button {
  margin-left: auto;
}
</style>
