<template>
  <section class="cashflow-page">
    <header class="cashflow-head">
      <div>
        <h1>资金日记账</h1>
        <p>按账户查看期间收支与结存。金额均为账户原币，外币不与人民币合计。</p>
      </div>
      <div class="cashflow-head-actions">
        <el-button @click="openReview">导入差异复核</el-button>
        <el-button @click="router.push('/h3-imports?tab=ledger&ledger=cashflowJournal')">查看来源台账</el-button>
      </div>
    </header>

    <div class="cashflow-toolbar">
      <el-date-picker v-model="period" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
        start-placeholder="开始日期" end-placeholder="结束日期" :disabled-date="disableOtherYears" @change="applyPeriod" />
      <el-button @click="setPeriod('year')">本年</el-button>
      <el-button @click="setPeriod('month')">本月</el-button>
      <el-input v-model="accountKeyword" clearable placeholder="搜索账户" class="cashflow-account-search" />
      <el-select v-model="currencyFilter" clearable placeholder="全部币种" class="cashflow-currency-filter">
        <el-option v-for="currency in availableCurrencies" :key="currency" :label="currency" :value="currency" />
      </el-select>
      <el-button :disabled="!visibleAccounts.length" @click="exportAccountReport">导出账户报表</el-button>
      <span>2026 年 · {{ visibleAccounts.length }} 个账户</span>
    </div>

    <el-alert type="info" :closable="false" title="当前为已导入日记账的来源汇总。财务若插行或修改历史流水，旧导入可能出现重复；正式资金余额须完成跨批次复核。" />

    <el-alert v-if="!loading && accounts.length === 0" type="warning" :closable="false"
      title="暂无账户数据，请先在氚云导入中心导入流水日记账。" class="cashflow-empty" />
    <div v-if="currencyTotals.length" class="cashflow-totals">
      <div v-for="group in currencyTotals" :key="group.currency" class="cashflow-total-card">
        <strong>{{ group.currency }} · {{ group.count }} 个账户</strong>
        <span>收入 {{ amount(group.income) }}　支出 {{ amount(group.expense) }}</span>
        <span>期末 {{ group.unknownOpening ? '部分账户缺期初' : amount(group.closing) }}</span>
      </div>
    </div>
    <el-table v-loading="loading" :data="visibleAccounts" stripe @row-click="openAccount">
      <el-table-column prop="accountName" label="账户" min-width="190" show-overflow-tooltip />
      <el-table-column prop="currency" label="币种" width="85" />
      <el-table-column label="期间期初" width="145" align="right">
        <template #default="{ row }">{{ openingAmount(row.openingBalance) }}</template>
      </el-table-column>
      <el-table-column label="收入" width="145" align="right">
        <template #default="{ row }">{{ amount(row.income) }}</template>
      </el-table-column>
      <el-table-column label="支出" width="145" align="right">
        <template #default="{ row }">{{ amount(row.expense) }}</template>
      </el-table-column>
      <el-table-column label="净流量" width="145" align="right">
        <template #default="{ row }">{{ amount(row.netFlow) }}</template>
      </el-table-column>
      <el-table-column label="期间期末" width="145" align="right">
        <template #default="{ row }">{{ openingAmount(row.closingBalance) }}</template>
      </el-table-column>
      <el-table-column prop="entryCount" label="笔数" width="85" align="right" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }"><el-button link type="primary" @click.stop="openAccount(row)">明细</el-button></template>
      </el-table-column>
    </el-table>

    <section class="cashflow-issues">
      <div class="cashflow-issues-head">
        <h2>数据核对 <span>{{ issues.length }} 项</span></h2>
        <p>检查缺期初、缺日期、收支双填和外币缺汇率。逐笔结存核对需先确认跨次导入的流水身份。</p>
      </div>
      <el-table v-loading="issuesLoading" :data="issues" stripe max-height="360">
        <el-table-column prop="accountName" label="账户" min-width="190" show-overflow-tooltip />
        <el-table-column prop="entryDate" label="日期" width="120">
          <template #default="{ row }">{{ row.entryDate || '—' }}</template>
        </el-table-column>
        <el-table-column prop="detail" label="异常" min-width="240" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }"><el-button link type="primary" @click="openIssue(row)">来源台账</el-button></template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="drawerOpen" :title="selected?.accountName || '账户明细'" size="85%">
      <p v-if="selected" class="cashflow-detail-summary">
        {{ start }} 至 {{ end }} · 期初 {{ openingAmount(selected.openingBalance) }} · 收入 {{ amount(selected.income) }} ·
        支出 {{ amount(selected.expense) }} · 期末 {{ openingAmount(selected.closingBalance) }} {{ selected.currency }}
      </p>
      <el-tabs v-model="detailTab">
        <el-tab-pane label="期间流水" name="entries">
      <el-table v-loading="entriesLoading" :data="entries" stripe>
        <el-table-column prop="entry_date" label="日期" width="120" />
        <el-table-column prop="summary" label="摘要" min-width="230" show-overflow-tooltip />
        <el-table-column prop="supplier_raw" label="供应商" min-width="140" show-overflow-tooltip />
        <el-table-column prop="counterparty_raw" label="客户 / 供应商" min-width="150" show-overflow-tooltip />
        <el-table-column prop="reimbursed_by" label="报销人" width="110" />
        <el-table-column prop="invoiced" label="开票" width="80" />
        <el-table-column label="收入" width="130" align="right"><template #default="{ row }">{{ amount(row.income) }}</template></el-table-column>
        <el-table-column label="支出" width="130" align="right"><template #default="{ row }">{{ amount(row.expense) }}</template></el-table-column>
        <el-table-column label="来源结存" width="140" align="right"><template #default="{ row }">{{ amount(row.balance) }}</template></el-table-column>
      </el-table>
      <el-pagination v-if="entryTotal > 30" v-model:current-page="entryPage" layout="prev, pager, next, total"
        :total="entryTotal" :page-size="30" class="cashflow-pagination" @current-change="loadEntries" />
        </el-tab-pane>
        <el-tab-pane label="2026 月度汇总" name="monthly">
          <div class="cashflow-monthly-actions">
            <span>全年 12 个月，金额为账户原币；缺期初时余额不计算。</span>
            <el-button :disabled="!monthlyRows.length" @click="exportMonthly">导出 CSV</el-button>
          </div>
          <el-table v-loading="monthlyLoading" :data="monthlyRows" stripe max-height="580">
            <el-table-column label="月份" width="90"><template #default="{ row }">{{ row.month }} 月</template></el-table-column>
            <el-table-column label="月初" width="145" align="right"><template #default="{ row }">{{ openingAmount(row.openingBalance) }}</template></el-table-column>
            <el-table-column label="收入" width="145" align="right"><template #default="{ row }">{{ amount(row.income) }}</template></el-table-column>
            <el-table-column label="支出" width="145" align="right"><template #default="{ row }">{{ amount(row.expense) }}</template></el-table-column>
            <el-table-column label="净流量" width="145" align="right"><template #default="{ row }">{{ amount(row.netFlow) }}</template></el-table-column>
            <el-table-column label="月末" width="145" align="right"><template #default="{ row }">{{ openingAmount(row.closingBalance) }}</template></el-table-column>
            <el-table-column prop="entryCount" label="笔数" width="90" align="right" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <el-drawer v-model="reviewOpen" title="流水日记账导入差异" size="86%">
      <el-alert type="info" :closable="false" title="这里仅列出旧库与新文件的差异，不自动认定两行是同一笔。财务调整历史行后，须复核再确认。" />
      <div class="cashflow-review-toolbar">
        <el-select v-model="reviewBatchId" placeholder="选择待确认批次" filterable @change="changeReviewBatch">
          <el-option v-for="batch in reviewBatches" :key="batch.id" :value="batch.id"
            :label="`#${batch.id} · ${batch.fileName} · ${batch.totalCount} 行`" />
        </el-select>
        <el-select v-model="reviewAccount" clearable filterable placeholder="全部受影响账户" @change="changeReviewAccount">
          <el-option v-for="name in reviewDiff?.accounts || []" :key="name" :label="name" :value="name" />
        </el-select>
      </div>
      <el-empty v-if="!reviewBatches.length && !reviewLoading" description="目前没有待确认的流水日记账批次" />
      <template v-else-if="reviewDiff">
        <el-alert v-if="!reviewDiff.stagingAvailable" type="warning" :closable="false" title="该批次暂存行不可用，请重新上传文件。" />
        <p class="cashflow-review-counts">旧库有而新文件没有：{{ reviewDiff.oldOnlyCount }} 行　·　新文件有而旧库没有：{{ reviewDiff.newOnlyCount }} 行</p>
        <el-table v-loading="reviewLoading" :data="reviewDiff.items" stripe max-height="550">
          <el-table-column label="差异" width="145">
            <template #default="{ row }"><el-tag :type="row.kind === 'OLD_ONLY' ? 'warning' : 'info'">{{ row.kind === 'OLD_ONLY' ? '旧库独有' : '新文件独有' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="accountName" label="账户" min-width="170" show-overflow-tooltip />
          <el-table-column prop="sourceRowNumber" label="本批 Excel 行号" width="135">
            <template #default="{ row }">{{ row.sourceRowNumber || '—' }}</template>
          </el-table-column>
          <el-table-column prop="entryDate" label="日期" width="135" show-overflow-tooltip />
          <el-table-column prop="summary" label="摘要" min-width="190" show-overflow-tooltip />
          <el-table-column label="收入" width="120" align="right"><template #default="{ row }">{{ amount(row.income) }}</template></el-table-column>
          <el-table-column label="支出" width="120" align="right"><template #default="{ row }">{{ amount(row.expense) }}</template></el-table-column>
          <el-table-column label="来源结存" width="130" align="right"><template #default="{ row }">{{ amount(row.balance) }}</template></el-table-column>
        </el-table>
        <el-pagination v-if="reviewDiff.total > 50" v-model:current-page="reviewPage" layout="prev, pager, next, total"
          :total="reviewDiff.total" :page-size="50" class="cashflow-pagination" @current-change="loadReviewDiff" />
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchCashflowEntries, fetchCashflowIssues, fetchCashflowMonthly, fetchCashflowReport, fetchCashflowReviewBatches, fetchCashflowReviewDiff, type CashflowAccount, type CashflowCurrencyTotal, type CashflowEntry, type CashflowIssue, type CashflowMonthlyRow, type CashflowReviewBatch, type CashflowReviewDiff } from '@/api/cashflowJournal'
import { downloadBlob } from '@/utils/ui/downloadBlob'

const router = useRouter()
const today = new Date()
const currentMonth = today.getFullYear() === 2026 ? today.getMonth() : 11
const dateText = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
const start = ref(dateText(new Date(2026, currentMonth, 1)))
const end = ref(dateText(new Date(2026, currentMonth + 1, 0)))
const period = ref<[string, string]>([start.value, end.value])
const accounts = ref<CashflowAccount[]>([])
const accountKeyword = ref('')
const currencyFilter = ref('')
const availableCurrencies = computed(() => [...new Set(accounts.value.map(account => account.currency))].sort())
const visibleAccounts = computed(() => accounts.value.filter(account =>
  account.accountName.includes(accountKeyword.value.trim()) &&
  (!currencyFilter.value || account.currency === currencyFilter.value)
))
const currencyTotals = ref<CashflowCurrencyTotal[]>([])
const loading = ref(false)
const drawerOpen = ref(false)
const selected = ref<CashflowAccount | null>(null)
const entries = ref<CashflowEntry[]>([])
const entriesLoading = ref(false)
const issues = ref<CashflowIssue[]>([])
const issuesLoading = ref(false)
const entryPage = ref(1)
const entryTotal = ref(0)
const detailTab = ref<'entries' | 'monthly'>('entries')
const monthlyRows = ref<CashflowMonthlyRow[]>([])
const monthlyLoading = ref(false)
const reviewOpen = ref(false)
const reviewLoading = ref(false)
const reviewBatches = ref<CashflowReviewBatch[]>([])
const reviewBatchId = ref<number | null>(null)
const reviewAccount = ref('')
const reviewPage = ref(1)
const reviewDiff = ref<CashflowReviewDiff | null>(null)

const amount = (value: number | string | null | undefined) => value == null || value === '' ? '—' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
const openingAmount = (value: number | null | undefined) => value == null ? '期初缺失' : amount(value)
const disableOtherYears = (date: Date) => date.getFullYear() !== 2026

async function loadReport() {
  loading.value = true
  try {
    const report = await fetchCashflowReport(start.value, end.value)
    accounts.value = report.items
    currencyTotals.value = report.totals
  } catch {
    ElMessage.error('资金日记账加载失败')
  } finally {
    loading.value = false
  }
}

async function loadIssues() {
  issuesLoading.value = true
  try {
    issues.value = (await fetchCashflowIssues(start.value, end.value)).items
  } catch {
    ElMessage.error('数据核对加载失败')
  } finally {
    issuesLoading.value = false
  }
}

async function loadEntries() {
  if (!selected.value) return
  entriesLoading.value = true
  try {
    const result = await fetchCashflowEntries(selected.value.id, start.value, end.value, entryPage.value)
    entries.value = result.items
    entryTotal.value = result.total
  } catch {
    ElMessage.error('流水明细加载失败')
  } finally {
    entriesLoading.value = false
  }
}


function openAccount(account: CashflowAccount) {
  selected.value = account
  entryPage.value = 1
  detailTab.value = 'entries'
  drawerOpen.value = true
  void loadEntries()
  void loadMonthly()
}

async function loadMonthly() {
  if (!selected.value) return
  monthlyLoading.value = true
  try {
    monthlyRows.value = (await fetchCashflowMonthly(selected.value.id)).items
  } catch {
    monthlyRows.value = []
    ElMessage.error('月度汇总加载失败')
  } finally {
    monthlyLoading.value = false
  }
}

function exportMonthly() {
  if (!selected.value || !monthlyRows.value.length) return
  const account = selected.value
  const rows = [['账户', '币种', '月份', '月初', '收入', '支出', '净流量', '月末', '笔数'],
    ...monthlyRows.value.map(row => [account.accountName, account.currency, `2026-${String(row.month).padStart(2, '0')}`,
      row.openingBalance, row.income, row.expense, row.netFlow, row.closingBalance, row.entryCount])]
  downloadCsv(rows, `${account.accountName}_2026月度汇总.csv`)
}

function exportAccountReport() {
  if (!visibleAccounts.value.length) return
  const rows = [['期间起', '期间止', '账户', '币种', '期间期初', '收入', '支出', '净流量', '期间期末', '笔数'],
    ...visibleAccounts.value.map(account => [start.value, end.value, account.accountName, account.currency,
      account.openingBalance, account.income, account.expense, account.netFlow, account.closingBalance, account.entryCount])]
  downloadCsv(rows, `资金日记账账户报表_${start.value}_${end.value}.csv`)
}

function downloadCsv(rows: unknown[][], filename: string) {
  const escape = (value: unknown) => {
    const text = String(value ?? '')
    const safe = typeof value === 'string' && /^[=+\-@]/.test(text) ? `'${text}` : text
    return `"${safe.replace(/"/g, '""')}"`
  }
  const csv = rows.map(row => row.map(escape).join(',')).join('\r\n')
  downloadBlob(new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }), filename)
}

function openIssue(issue: CashflowIssue) {
  void router.push({ path: '/h3-imports', query: { tab: 'ledger', ledger: 'cashflowJournal', keyword: issue.accountName } })
}

async function openReview() {
  reviewOpen.value = true
  reviewLoading.value = true
  try {
    reviewBatches.value = (await fetchCashflowReviewBatches()).items
    reviewBatchId.value = reviewBatches.value[0]?.id ?? null
    reviewAccount.value = ''
    reviewPage.value = 1
    if (reviewBatchId.value) await loadReviewDiff()
    else reviewDiff.value = null
  } catch {
    ElMessage.error('导入差异加载失败')
  } finally {
    reviewLoading.value = false
  }
}

async function loadReviewDiff() {
  if (!reviewBatchId.value) return
  reviewLoading.value = true
  try {
    reviewDiff.value = await fetchCashflowReviewDiff(reviewBatchId.value, reviewAccount.value, reviewPage.value)
  } catch {
    ElMessage.error('批次差异加载失败')
  } finally {
    reviewLoading.value = false
  }
}

function changeReviewBatch() {
  reviewAccount.value = ''
  reviewPage.value = 1
  void loadReviewDiff()
}

function changeReviewAccount() {
  reviewPage.value = 1
  void loadReviewDiff()
}

function applyPeriod() {
  if (!period.value || period.value.length !== 2) return
  ;[start.value, end.value] = period.value
  drawerOpen.value = false
  void loadReport()
  void loadIssues()
}

function setPeriod(kind: 'year' | 'month') {
  period.value = kind === 'year'
    ? ['2026-01-01', '2026-12-31']
    : [dateText(new Date(2026, currentMonth, 1)), dateText(new Date(2026, currentMonth + 1, 0))]
  applyPeriod()
}

await loadReport()
await loadIssues()
</script>

<style scoped>
.cashflow-page { padding: 22px; display: grid; gap: 18px; }
.cashflow-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; }
.cashflow-head-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.cashflow-head h1 { margin: 0 0 8px; font-size: 23px; }
.cashflow-head p, .cashflow-detail-summary { margin: 0; color: var(--el-text-color-secondary); }
.cashflow-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.cashflow-toolbar span { margin-left: auto; color: var(--el-text-color-secondary); }
.cashflow-account-search { width: 180px; }
.cashflow-currency-filter { width: 125px; }
.cashflow-monthly-actions { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin: 8px 0 12px; color: var(--el-text-color-secondary); }
.cashflow-pagination { margin-top: 16px; justify-content: flex-end; }
.cashflow-review-toolbar { display: flex; gap: 12px; margin: 18px 0; }
.cashflow-review-toolbar .el-select:first-child { width: 390px; }
.cashflow-review-toolbar .el-select:last-child { width: 230px; }
.cashflow-review-counts { margin: 0 0 12px; color: var(--el-text-color-secondary); }
.cashflow-totals { display: flex; flex-wrap: wrap; gap: 12px; }
.cashflow-total-card { display: grid; gap: 5px; min-width: 230px; padding: 12px 16px; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-light); }
.cashflow-total-card span { color: var(--el-text-color-secondary); }
.cashflow-issues { display: grid; gap: 10px; }
.cashflow-issues-head { display: flex; justify-content: space-between; align-items: baseline; gap: 16px; }
.cashflow-issues-head h2 { margin: 0; font-size: 18px; }
.cashflow-issues-head h2 span { font-size: 13px; color: var(--el-color-warning); }
.cashflow-issues-head p { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
</style>
