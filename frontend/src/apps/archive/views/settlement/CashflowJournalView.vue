<template>
  <ListPageLayout
    class="cashflow-page"
    title="资金日记账"
    description="按来源日记账查看流水、账户收支与核对结论。金额均为账户原币，外币不与人民币合计。"
    :busy="reportLoading || entriesLoading"
    content-label="资金流水"
  >
    <template #eyebrow>资金 · CASH JOURNAL</template>

    <template #actions>
      <el-button @click="reviewOpen = true">导入差异复核</el-button>
      <el-button @click="router.push('/h3-imports?tab=ledger&ledger=cashflowJournal')">查看来源台账</el-button>
    </template>

    <template #summary>
      <MetricGrid :columns="4" density="compact">
        <MetricCard
          title="期间收入"
          :value="amount(summary.income)"
          :loading="reportLoading && !accounts.length"
          :empty="!reportLoading && !accounts.length"
          :hint="rangeHint"
        />
        <MetricCard
          title="期间支出"
          :value="amount(summary.expense)"
          :loading="reportLoading && !accounts.length"
          :empty="!reportLoading && !accounts.length"
          :hint="rangeHint"
        />
        <MetricCard
          title="净流入"
          :value="amount(summary.netFlow)"
          :tone="summary.netFlow < 0 ? 'warning' : 'default'"
          :loading="reportLoading && !accounts.length"
          :empty="!reportLoading && !accounts.length"
          :hint="`${accounts.length} 个账户有流水`"
        />
        <MetricCard
          title="期间期末（分币种）"
          :value="currencyClosingText"
          :loading="reportLoading && !accounts.length"
          :empty="!reportLoading && !accounts.length"
          :hint="closingCardHint"
        />
      </MetricGrid>
    </template>

    <template #notice>
      <div class="cf-notice">
        <el-alert
          v-if="reportBlockedCount > 0"
          type="warning"
          :closable="false"
          show-icon
          :title="`${reportBlockedCount} 个账户缺少期初余额，其期间期末不可计算（仍照常显示收支）`"
          description="期间期末 = 期间期初 + 期间收入 − 期间支出。缺期初时不做任何兜底填充，也不以 0 代替。"
        />
        <p class="cf-legend">
          <strong>各维度互不代表：</strong>
          <em>期间余额核对</em>只说明期末能否计算；<em>逐笔结存核对</em>只说明明细能否衔接；
          <em>来源末行比对</em>是辅助项，仅在当日顺序被数据唯一锁定时才给结论；
          <em>人工对账</em>才是财务与银行确认的结果（P1 尚未开放，一律显示未对账）。
          系统算出的数与财务确认过的数是两件事。
        </p>
      </div>
    </template>

    <template #toolbar>
      <div class="cf-toolbar">
        <LqTabs v-model="activeTab" :items="tabItems" @tab-change="onTabChange" />
        <div class="cf-toolbar__right">
          <el-select
            v-model="accountFilter"
            clearable
            filterable
            placeholder="全部账户"
            class="cf-toolbar__account"
            @change="onAccountFilterChange"
          >
            <el-option
              v-for="account in accounts"
              :key="account.id"
              :value="account.id"
              :label="`${account.accountName} · ${account.currency}`"
            />
          </el-select>
          <el-date-picker
            v-model="period"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :disabled-date="disableOtherYears"
            :clearable="false"
            style="width: 250px"
            @change="onPeriodPicked"
          />
          <el-button-group>
            <el-button :type="preset === 'month' ? 'primary' : ''" @click="setPeriod('month')">本月</el-button>
            <el-button :type="preset === 'quarter' ? 'primary' : ''" @click="setPeriod('quarter')">本季</el-button>
            <el-button :type="preset === 'year' ? 'primary' : ''" @click="setPeriod('year')">本年</el-button>
          </el-button-group>
        </div>
      </div>
      <p class="cf-range">
        查询范围：<strong>{{ start }}</strong> 至 <strong>{{ end }}</strong>
        <span v-if="isPresetCappedToday" class="cf-range__hint">（{{ presetLabel }}默认截至今天）</span>
        <span v-else class="cf-range__hint">（自定义期间，可包含未来日期）</span>
      </p>
    </template>

    <ActiveFilterChips :filters="activeChips" @remove="removeChip" @clear="clearAllFilters" />

    <!-- ========== 视图一：流水明细（主视图） ========== -->
    <template v-if="activeTab === 'entries'">
      <SearchToolbar
        v-model:keyword="entryKeyword"
        search-placeholder="搜索摘要、往来对象或经办人"
        :fields="[]"
        :field-values="{}"
        :batch-enabled="false"
        aria-label="资金流水"
        @search="searchEntries"
        @clear="clearEntryFilters"
      >
        <template #actions>
          <el-button @click="router.push('/h3-imports?tab=import')">导入流水</el-button>
          <el-button :disabled="!entries.length" @click="exportEntries">导出本页</el-button>
        </template>
      </SearchToolbar>

      <DataState
        :state="entriesState"
        :skeleton="entriesState === 'loading'"
        skeleton-variant="table"
        :skeleton-rows="10"
        :refreshing="entriesLoading && entries.length > 0"
        error-title="流水明细加载失败"
        :error-message="entriesError"
        stale-message="刷新失败，当前仍显示上一次成功加载的流水。"
        empty-text="该期间没有流水"
        empty-hint="可在氚云导入中心导入流水日记账。"
        filtered-empty-text="没有符合条件的流水"
        filtered-empty-hint="请调整搜索内容或账户筛选。"
        @retry="loadEntries"
      >
        <template #emptyActions="{ state }">
          <el-button v-if="state === 'filtered-empty'" @click="clearEntryFilters">清除筛选</el-button>
        </template>

        <CashflowEntryTable :entries="entries" />
      </DataState>

      <p class="cf-note">
        共 {{ pagination.pageInfo.total }} 笔，按日期从新到旧显示。「来源结存」是 Excel 原值，系统不做任何改写；
        手工记账将在 P2 开放，届时手工流水以 <code>MANUAL-</code> 前缀区分来源。
      </p>

      <LqPagination :pagination="pagination" @change="loadEntries" />
    </template>

    <!-- ========== 视图二：账户汇总 ========== -->
    <template v-else-if="activeTab === 'accounts'">
      <div class="cf-account-tools">
        <el-select v-model="currencyFilter" clearable placeholder="全部币种" class="cf-account-tools__currency">
          <el-option v-for="currency in availableCurrencies" :key="currency" :label="currency" :value="currency" />
        </el-select>
        <el-select v-model="reconcileFilter" clearable placeholder="全部核对状态" class="cf-account-tools__status">
          <el-option label="期末可计算" value="CLOSING_OK" />
          <el-option label="缺期初，期末不可计算" value="CLOSING_BLOCKED" />
          <el-option label="明细全部衔接" value="DETAIL_OK" />
          <el-option label="明细有断点待核实" value="DETAIL_EXCEPTIONS" />
          <el-option label="来源末行可比（顺序已锁定）" value="EVIDENCE_COMPARABLE" />
          <el-option label="来源末行不可比" value="EVIDENCE_SKIPPED" />
          <el-option label="未人工对账" value="MANUAL_NOT_STARTED" />
        </el-select>
        <el-button :disabled="!visibleAccounts.length" @click="exportAccountReport">导出账户报表</el-button>
        <span class="cf-account-tools__hint">{{ visibleAccounts.length }} 个账户 · 按流水笔数降序</span>
      </div>

      <DataState
        :state="accountsState"
        :skeleton="accountsState === 'loading'"
        skeleton-variant="table"
        :skeleton-rows="8"
        :refreshing="reportLoading && accounts.length > 0"
        error-title="账户汇总加载失败"
        :error-message="reportError"
        stale-message="刷新失败，当前仍显示上一次成功加载的账户汇总。"
        empty-text="暂无账户数据"
        empty-hint="请先在氚云导入中心导入流水日记账。"
        filtered-empty-text="没有符合条件的账户"
        filtered-empty-hint="请调整账户、币种或核对状态筛选。"
        @retry="loadReport"
      >
        <template #emptyActions="{ state }">
          <el-button v-if="state === 'filtered-empty'" @click="clearAccountFilters">清除筛选</el-button>
        </template>

        <CashflowAccountTable :accounts="visibleAccounts" :year="PERIOD_YEAR" @open-entries="openAccount" />
      </DataState>
    </template>

    <!-- ========== 视图三：对账核对 ========== -->
    <template v-else>
      <div class="cf-reconcile-head">
        <h2>数据核对 <span v-if="issues.length">· {{ issues.length }} 项</span></h2>
        <p>
          本页只列「确定性的数据事实」，不自动改写任何来源结存。
          <strong>期间余额核对</strong>是账户级结论（期初 + 收支 = 期末）；
          <strong>逐笔结存核对</strong>是明细级结论（相邻两行能否接上）——后者有异常不等于前者不可信。
          卡片与清单均按当前账户筛选口径统计。
        </p>
      </div>

      <MetricGrid :columns="4" density="compact" class="cf-reconcile-metrics">
        <MetricCard
          title="期末可计算"
          :value="reconcileScope.closingComputable"
          unit="个账户"
          :loading="reportLoading"
          :hint="reconcileScope.closingBlocked ? `${reconcileScope.closingBlocked} 个缺期初不可计算` : '全部账户期末可计算'"
        />
        <MetricCard
          title="逐笔链接已衔接"
          :value="reconcileScope.checkedLinks"
          unit="条"
          :loading="reportLoading"
          hint="相邻两行来源结存是否接得上"
        />
        <MetricCard
          title="明细断点"
          :value="reconcileScope.exceptionCount"
          unit="处"
          :tone="reconcileScope.exceptionCount ? 'warning' : 'success'"
          :loading="reportLoading"
          :hint="reconcileScope.exceptionCount
            ? `涉及 ${reconcileScope.affectedAccounts} 个账户`
            : '全部账户逐笔衔接'"
        />
        <MetricCard
          title="已人工对账"
          :value="reportSummary.manualReconciledCount"
          unit="个账户"
          :loading="reportLoading"
          hint="P1 未开放人工对账；上三项均为系统计算，不代表财务已确认"
        />
      </MetricGrid>

      <!-- 辅助核对：来源末行能不能比，缺的是哪类证据。刻意不并入上面的「对账通过/失败」口径。 -->
      <div class="cf-evidence">
        <div class="cf-evidence__head">
          <strong>来源末行比对（辅助）</strong>
          <span>
            {{ reconcileScope.evidenceComparable }} 个账户顺序已被结存数据唯一锁定，可比对；
            {{ reconcileScope.evidenceSkipped }} 个不可比。
          </span>
        </div>
        <p class="cf-evidence__note">
          本库流水来自氚云导出，<strong>没有银行交易序号</strong>，同日多笔的层内顺序
          无法由数据唯一确定。只有在「使每行结存首尾相接的排列恰好唯一」时，来源末行的结存才算被数据锁定，
          才拿它与期间期末比；否则不予比对，并列出缺的是哪类证据。
        </p>
        <ul v-if="reconcileScope.evidenceReasons.length" class="cf-evidence__list">
          <li v-for="item in reconcileScope.evidenceReasons" :key="item.reason">
            <span class="cf-evidence__count">{{ item.count }}</span>
            <span>{{ item.reason }}</span>
          </li>
        </ul>
      </div>

      <DataState
        :state="issuesState"
        :skeleton="issuesState === 'loading'"
        skeleton-variant="table"
        :skeleton-rows="8"
        :refreshing="issuesLoading && issues.length > 0"
        error-title="核对清单加载失败"
        :error-message="issuesError"
        stale-message="刷新失败，当前仍显示上一次成功加载的核对清单。"
        empty-text="该期间没有发现数据异常"
        empty-hint="缺期初、缺日期、收支双填、外币缺汇率与逐笔结存断点均会列在这里。"
        @retry="loadIssues"
      >
        <CashflowIssueTable :issues="issues" @open-source="openIssue" />
      </DataState>
    </template>

    <CashflowReviewDrawer v-model="reviewOpen" />
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ActiveFilterChips, { type ActiveFilter } from '@/components/common/ActiveFilterChips.vue'
import DataState, { type DataStateStatus } from '@/components/common/DataState.vue'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import LqTabs, { type TabItem } from '@/components/common/LqTabs.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import MetricGrid from '@/components/common/MetricGrid.vue'
import SearchToolbar from '@/components/common/SearchToolbar.vue'
import { useListPagination } from '@/composables/list/useListPagination'
import { notify } from '@/composables/ui/useToast'
import {
  fetchCashflowEntries,
  fetchCashflowIssues,
  fetchCashflowReport,
  type CashflowAccount,
  type CashflowCurrencyTotal,
  type CashflowEntry,
  type CashflowIssue,
  type CashflowReportSummary
} from '@/api/cashflowJournal'
import CashflowAccountTable from './cashflow/CashflowAccountTable.vue'
import CashflowEntryTable from './cashflow/CashflowEntryTable.vue'
import CashflowIssueTable from './cashflow/CashflowIssueTable.vue'
import CashflowReviewDrawer from './cashflow/CashflowReviewDrawer.vue'
import { amount, downloadCsv } from './cashflow/cashflowFormat'
import { useCashflowPeriod } from './cashflow/useCashflowPeriod'

const router = useRouter()
const route = useRoute()

// ==================== 期间 + 快捷期间（唯一状态源在 cashflow/useCashflowPeriod.ts） ====================
const {
  CASHFLOW_PERIOD_YEAR: PERIOD_YEAR,
  start,
  end,
  period,
  preset,
  presetLabel,
  isPresetCappedToday,
  setPeriod,
  onPeriodPicked,
  disableOtherYears
} = useCashflowPeriod({
  initialStart: route.query.start,
  initialEnd: route.query.end,
  onChange: () => {
    pagination.resetPage()
    syncQuery()
    void loadReport()
    void loadIssues()
    void loadEntries()
  }
})

const initialTab = String(route.query.tab || '')
const activeTab = ref<'entries' | 'accounts' | 'reconcile'>(
  initialTab === 'accounts' || initialTab === 'reconcile' ? initialTab : 'entries'
)
const tabItems = computed<TabItem[]>(() => [
  { label: '流水明细', name: 'entries' },
  { label: '账户汇总', name: 'accounts' },
  { label: `对账核对${issues.value.length ? ` · ${issues.value.length}` : ''}`, name: 'reconcile' }
])


// ==================== 账户汇总 ====================
const accounts = ref<CashflowAccount[]>([])
const currencyTotals = ref<CashflowCurrencyTotal[]>([])
const reportSummary = reactive<CashflowReportSummary>({
  closingComputableCount: 0,
  closingBlockedCount: 0,
  sourceBalanceExceptionCount: 0,
  sourceBalanceCheckedLinks: 0,
  sourceBalanceAffectedAccounts: 0,
  manualReconciledCount: 0,
  manualReconcilePendingCount: 0
})
const reportLoading = ref(false)
const reportError = ref('')
const hasLoadedReport = ref(false)

/** 跨三个视图共享的账户筛选（流水 / 账户汇总 / 对账核对同一份状态）。 */
const accountFilter = ref<number | null>(
  Number(route.query.accountId) > 0 ? Number(route.query.accountId) : null
)
const currencyFilter = ref('')
const reconcileFilter = ref('')
const availableCurrencies = computed(() => [...new Set(accounts.value.map(account => account.currency))].sort())

const visibleAccounts = computed(() =>
  accounts.value.filter(account => {
    if (accountFilter.value != null && account.id !== accountFilter.value) return false
    if (currencyFilter.value && account.currency !== currencyFilter.value) return false
    const status = reconcileFilter.value
    if (status === 'CLOSING_OK' && account.closingStatus !== 'OK') return false
    if (status === 'CLOSING_BLOCKED' && account.closingStatus === 'OK') return false
    if (status === 'DETAIL_OK' && account.sourceBalanceStatus !== 'DETAIL_OK') return false
    if (status === 'DETAIL_EXCEPTIONS' && account.sourceBalanceStatus !== 'DETAIL_EXCEPTIONS') return false
    if (status === 'EVIDENCE_COMPARABLE' && !account.sourceClosingComparable) return false
    if (status === 'EVIDENCE_SKIPPED' && account.sourceClosingComparable) return false
    if (status === 'MANUAL_NOT_STARTED' && account.manualReconcileStatus !== 'NOT_STARTED') return false
    return true
  })
)

/** 对账视图的卡片按「账户筛选」口径统计，与下方清单（/issues 只接受 accountId）严格同源。 */
const reconcileScope = computed(() => {
  const scope = accounts.value.filter(
    account => accountFilter.value == null || account.id === accountFilter.value
  )
  return {
    closingComputable: scope.filter(account => account.closingStatus === 'OK').length,
    closingBlocked: scope.filter(account => account.closingStatus !== 'OK').length,
    checkedLinks: scope.reduce((sum, account) => sum + Number(account.sourceBalanceCheckedLinks || 0), 0),
    exceptionCount: scope.reduce((sum, account) => sum + Number(account.sourceBalanceExceptionCount || 0), 0),
    affectedAccounts: scope.filter(account => account.sourceBalanceStatus === 'DETAIL_EXCEPTIONS').length,
    // 证据区必须与上面四张卡片同口径（只受 accountId 影响），否则选完账户会出现
    // 「卡片 1 个账户、说明区 13 个可比」这种自相矛盾的数字。
    evidenceComparable: scope.filter(account => account.sourceClosingComparable).length,
    evidenceSkipped: scope.filter(account => !account.sourceClosingComparable).length,
    evidenceReasons: (() => {
      const map = new Map<string, number>()
      scope.forEach(account => {
        if (account.sourceClosingComparable) return
        const label = account.sourceClosingSkipLabel || '未给出原因'
        map.set(label, (map.get(label) || 0) + 1)
      })
      return [...map.entries()].map(([reason, count]) => ({ reason, count }))
    })()
  }
})

const summary = computed(() => {
  const totals = currencyTotals.value.filter(group => group.currency === 'CNY')
  const income = totals.reduce((sum, group) => sum + Number(group.income || 0), 0)
  const expense = totals.reduce((sum, group) => sum + Number(group.expense || 0), 0)
  return { income, expense, netFlow: income - expense }
})
const rangeHint = computed(() => `${start.value} 至 ${end.value}（人民币账户）`)
const currencyClosingText = computed(() =>
  currencyTotals.value.length > 1
    ? `${currencyTotals.value.length} 个币种`
    : currencyTotals.value.length === 1
      ? amount(currencyTotals.value[0].closing)
      : '—'
)
const closingCardHint = computed(() => {
  if (!currencyTotals.value.length) return '暂无可计算账户'
  if (currencyTotals.value.length === 1) return currencyTotals.value[0].currency
  return currencyTotals.value
    .map(group => `${group.currency} ${group.unknownOpening ? '部分缺期初' : amount(group.closing)}`)
    .join(' · ')
})
const reportBlockedCount = computed(() => reportSummary.closingBlockedCount)

const accountsState = computed<DataStateStatus>(() => {
  if (reportLoading.value && !hasLoadedReport.value) return 'loading'
  if (reportError.value && accounts.value.length) return 'stale'
  if (reportError.value) return 'error'
  if (!accounts.value.length) return 'empty'
  if (!visibleAccounts.value.length) return 'filtered-empty'
  return 'ready'
})

// ==================== 流水明细 ====================
const entries = ref<CashflowEntry[]>([])
const entriesLoading = ref(false)
const entriesError = ref('')
const hasLoadedEntries = ref(false)
const entryKeyword = ref('')
const pagination = useListPagination({ size: 30 })
const entriesState = computed<DataStateStatus>(() => {
  if (entriesLoading.value && !hasLoadedEntries.value) return 'loading'
  if (entriesError.value && entries.value.length) return 'stale'
  if (entriesError.value) return 'error'
  if (!entries.value.length) {
    return entryKeyword.value.trim() || accountFilter.value != null ? 'filtered-empty' : 'empty'
  }
  return 'ready'
})
let entryRequestId = 0

// ==================== 对账核对 ====================
const issues = ref<CashflowIssue[]>([])
const issuesLoading = ref(false)
const issuesError = ref('')
const hasLoadedIssues = ref(false)
const issuesState = computed<DataStateStatus>(() => {
  if (issuesLoading.value && !hasLoadedIssues.value) return 'loading'
  if (issuesError.value && issues.value.length) return 'stale'
  if (issuesError.value) return 'error'
  return issues.value.length ? 'ready' : 'empty'
})

// ==================== 导入差异复核 ====================
const reviewOpen = ref(false)

// ==================== 已应用条件 ====================
/** 核对状态的中文名，与下拉选项同源。用原始 code 显示给财务等于没翻译。 */
const RECONCILE_FILTER_LABELS: Record<string, string> = {
  CLOSING_OK: '期末可计算',
  CLOSING_BLOCKED: '缺期初，期末不可计算',
  DETAIL_OK: '明细全部衔接',
  DETAIL_EXCEPTIONS: '明细有断点待核实',
  EVIDENCE_COMPARABLE: '来源末行可比（顺序已锁定）',
  EVIDENCE_SKIPPED: '来源末行不可比',
  MANUAL_NOT_STARTED: '未人工对账'
}

function reconcileFilterLabel(code: string): string {
  return RECONCILE_FILTER_LABELS[code] || code
}

const activeChips = computed<ActiveFilter[]>(() => {
  const chips: ActiveFilter[] = [{ key: 'period', label: '期间', valueLabel: `${start.value} 至 ${end.value}` }]
  if (activeTab.value === 'entries' && entryKeyword.value.trim()) {
    chips.push({ key: 'keyword', label: '关键词', valueLabel: entryKeyword.value.trim() })
  }
  if (accountFilter.value != null) {
    const account = accounts.value.find(item => item.id === accountFilter.value)
    chips.push({ key: 'accountId', label: '账户', valueLabel: account?.accountName ?? `#${accountFilter.value}` })
  }
  if (activeTab.value === 'accounts') {
    if (currencyFilter.value) chips.push({ key: 'currency', label: '币种', valueLabel: currencyFilter.value })
    if (reconcileFilter.value) {
      chips.push({
        key: 'reconcile',
        label: '核对状态',
        valueLabel: reconcileFilterLabel(reconcileFilter.value)
      })
    }
  }
  return chips
})

function removeChip(key: string) {
  if (key === 'period') return
  if (key === 'keyword') entryKeyword.value = ''
  else if (key === 'accountId') accountFilter.value = null
  else if (key === 'currency') currencyFilter.value = ''
  else if (key === 'reconcile') reconcileFilter.value = ''
  syncQuery()
  if (key === 'keyword' || key === 'accountId') {
    pagination.resetPage()
    void loadEntries()
  }
  if (key === 'accountId') void loadIssues()
}

function clearAllFilters() {
  entryKeyword.value = ''
  accountFilter.value = null
  clearAccountFilters()
  pagination.resetPage()
  syncQuery()
  void loadEntries()
  void loadIssues()
}

function clearAccountFilters() {
  currencyFilter.value = ''
  reconcileFilter.value = ''
}

// ==================== 加载 ====================
async function loadReport() {
  reportLoading.value = true
  reportError.value = ''
  try {
    const report = await fetchCashflowReport(start.value, end.value)
    accounts.value = report.items
    currencyTotals.value = report.totals
    Object.assign(reportSummary, report.summary)
    hasLoadedReport.value = true
    if (accountFilter.value != null && !report.items.some(item => item.id === accountFilter.value)) {
      accountFilter.value = null
      syncQuery()
    }
  } catch (e: any) {
    reportError.value = e?.message || '资金日记账账户汇总加载失败'
    notify.error(reportError.value)
  } finally {
    reportLoading.value = false
  }
}

async function loadEntries() {
  const requestId = ++entryRequestId
  entriesLoading.value = true
  entriesError.value = ''
  try {
    const { page, size } = pagination.getPageParams()
    const result = await fetchCashflowEntries(
      accountFilter.value,
      start.value,
      end.value,
      page + 1,
      size,
      entryKeyword.value.trim()
    )
    if (requestId !== entryRequestId) return
    entries.value = result.items
    pagination.updateFromResponse({ total: result.total, number: page, size })
    hasLoadedEntries.value = true
  } catch (e: any) {
    if (requestId !== entryRequestId) return
    entriesError.value = e?.message || '流水明细加载失败'
    notify.error(entriesError.value)
  } finally {
    if (requestId === entryRequestId) entriesLoading.value = false
  }
}

async function loadIssues() {
  issuesLoading.value = true
  issuesError.value = ''
  try {
    const result = await fetchCashflowIssues(start.value, end.value, accountFilter.value)
    issues.value = result.items.map(
      (item, index) => ({ ...item, issueKey: `${item.issueCode}-${item.accountId}-${item.entryId ?? 'x'}-${index}` })
    )
    hasLoadedIssues.value = true
  } catch (e: any) {
    issuesError.value = e?.message || '数据核对加载失败'
    notify.error(issuesError.value)
  } finally {
    issuesLoading.value = false
  }
}

// ==================== 交互 ====================
function syncQuery() {
  void router.replace({
    query: {
      ...route.query,
      tab: activeTab.value,
      start: start.value,
      end: end.value,
      accountId: accountFilter.value == null ? undefined : String(accountFilter.value)
    }
  })
}

function onTabChange(name: string) {
  activeTab.value = name as typeof activeTab.value
  syncQuery()
}

function searchEntries(keyword: string) {
  entryKeyword.value = keyword
  pagination.resetPage()
  void loadEntries()
}

function clearEntryFilters() {
  entryKeyword.value = ''
  accountFilter.value = null
  pagination.resetPage()
  syncQuery()
  void loadEntries()
  void loadIssues()
}

function onAccountFilterChange(value: unknown) {
  // el-select 清空时会回传 ''，这里统一归一成 null，否则 '' 会被当成一个有效账户 id 过滤掉全部行
  accountFilter.value = typeof value === 'number' && value > 0 ? value : null
  pagination.resetPage()
  syncQuery()
  void loadEntries()
  void loadIssues()
}

function openAccount(account: CashflowAccount) {
  accountFilter.value = account.id
  entryKeyword.value = ''
  pagination.resetPage()
  activeTab.value = 'entries'
  syncQuery()
  void loadEntries()
  void loadIssues()
}

function openIssue(issue: CashflowIssue) {
  void router.push({
    path: '/h3-imports',
    query: { tab: 'ledger', ledger: 'cashflowJournal', keyword: issue.accountName }
  })
}

// ==================== 导出 ====================
function exportEntries() {
  if (!entries.value.length) return
  const rows: unknown[][] = [
    ['日期', '账户', '摘要', '往来对象', '经办/报销', '收入', '支出', '来源结存'],
    ...entries.value.map(entry => [
      entry.entry_date,
      entry.account_name,
      entry.summary,
      entry.counterparty_raw,
      entry.reimbursed_by,
      entry.income,
      entry.expense,
      entry.balance
    ])
  ]
  downloadCsv(rows, `资金流水_${start.value}_${end.value}.csv`)
}

function exportAccountReport() {
  if (!visibleAccounts.value.length) return
  const rows: unknown[][] = [
    ['期间起', '期间止', '账户', '币种', '期间期初', '收入', '支出', '净流量', '期间期末', '笔数',
      '期间余额核对', '逐笔结存核对', '断点处数', '已衔接链接',
      '来源末行比对（辅助）', '末行日期', '末行结存', '不可比原因',
      '人工对账', '系统结论'],
    ...visibleAccounts.value.map(account => [
      start.value,
      end.value,
      account.accountName,
      account.currency,
      account.openingBalance,
      account.income,
      account.expense,
      account.netFlow,
      account.closingBalance,
      account.entryCount,
      account.closingStatusLabel,
      account.sourceBalanceStatus === 'DETAIL_OK' ? '全部衔接' : '待核实',
      account.sourceBalanceExceptionCount,
      account.sourceBalanceCheckedLinks,
      account.sourceClosingComparable ? '顺序已锁定，末行一致' : '不可比',
      account.sourceClosingComparable ? account.sourceClosingDate : '',
      account.sourceClosingComparable ? account.sourceClosingBalance : '',
      account.sourceClosingComparable ? '' : (account.sourceClosingSkipLabel || ''),
      account.manualReconcileStatusLabel,
      account.reconcileSummary
    ])
  ]
  downloadCsv(rows, `资金日记账账户报表_${start.value}_${end.value}.csv`)
}

// ==================== 初始化 ====================
await Promise.all([loadReport(), loadIssues()])
await loadEntries()
</script>

<style scoped>
/* 页面只保留自己的排列；金额/徽标等展示细节在 cashflow/ 子组件内 */
.cf-notice {
  display: grid;
  gap: var(--space-control-gap);
}

.cf-legend {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
}

.cf-legend strong {
  color: var(--color-text-primary);
}

.cf-legend em {
  font-style: normal;
  color: var(--color-text-primary);
  font-weight: var(--fw-medium);
}

.cf-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-control-gap);
}

.cf-toolbar__right {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-control-gap-tight);
  margin-left: auto;
}

.cf-toolbar__account {
  width: 210px;
}

.cf-range {
  margin: var(--space-control-gap-tight) 0 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
}

.cf-range__hint {
  margin-left: 4px;
}

.cf-note {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  line-height: var(--lh-relaxed);
}

.cf-note code {
  padding: 1px 4px;
  border-radius: var(--radius-sm);
  background: var(--color-surface-alt);
  font-size: var(--fs-xs);
}

.cf-account-tools {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-control-gap);
}

.cf-account-tools__currency {
  width: 125px;
}

.cf-account-tools__status {
  width: 190px;
}

.cf-account-tools__hint {
  margin-left: auto;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
}

.cf-reconcile-head h2 {
  margin: 0 0 4px;
  color: var(--color-text-heading);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
}

.cf-reconcile-head p {
  max-width: 96ch;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
}

.cf-reconcile-metrics {
  margin-top: var(--space-control-gap);
}

.cf-evidence {
  display: grid;
  gap: 6px;
  margin-top: var(--space-control-gap);
  padding: var(--space-control-gap) var(--space-control-gap-wide);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
}

.cf-evidence__head {
  display: flex;
  align-items: baseline;
  gap: var(--space-control-gap);
  color: var(--color-text-heading);
  font-size: var(--fs-md);
}

.cf-evidence__head span {
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
}

.cf-evidence__note {
  max-width: 96ch;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
}

.cf-evidence__list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px var(--space-control-gap-wide);
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: var(--fs-sm);
}

.cf-evidence__list li {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  color: var(--color-text-secondary);
}

.cf-evidence__count {
  color: var(--color-text-heading);
  font-variant-numeric: tabular-nums;
  font-weight: var(--fw-semibold);
}

@media (max-width: 767px) {
  .cf-toolbar__right {
    margin-left: 0;
  }

  .cf-account-tools__hint {
    margin-left: 0;
  }
}
</style>
