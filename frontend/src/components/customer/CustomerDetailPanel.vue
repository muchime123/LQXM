<template>
  <section class="customer-detail-panel pinned" :class="{ 'is-loading': loading, 'is-empty': !detail }">
    <template v-if="detail">
      <header class="cust-head">
        <div class="cust-head__avatar" :style="avatarStyle">{{ nameInitials }}</div>
        <div class="cust-head__main">
          <h2 class="cust-head__name" :title="detail.customerName">{{ detail.customerName }}</h2>
          <span class="cust-head__code">{{ detail.customerCode }}</span>
        </div>
        <div class="cust-head__pills">
          <StatusTag class="cust-head__status" :type="relationTagClass(detail.relationStatus)" :label="detail.relationStatus || '-'" size="sm" />
          <StatusTag class="cust-head__status" :type="detail.status === '启用' ? 'tag-ok' : 'tag-neutral'" :label="detail.status || '-'" size="sm" />
          <span v-if="detail.industry" class="cust-head__pill cust-head__pill--meta">{{ detail.industry }}</span>
          <span v-if="regionText" class="cust-head__pill cust-head__pill--meta cust-head__pill--region">{{ regionText }}</span>
          <span v-if="primarySalesperson" class="cust-head__pill cust-head__pill--primary cust-head__pill--manager">
            负责人：{{ primarySalesperson }}
          </span>
          <span v-if="detail.customerType" class="cust-head__pill cust-head__pill--meta">{{ detail.customerType }}</span>
        </div>
      </header>

      <MasterDetailHeader
        :tabs="detailTabs"
        :active="activeTab"
        @update:active="activeTab = $event"
      >
        <template #actions>
          <el-button v-if="canManage" size="small" @click="emit('edit', detail)">编辑档案</el-button>
        </template>
      </MasterDetailHeader>
    </template>

    <div class="customer-detail-body">
      <div v-if="!detail" class="customer-detail-empty">
        <el-skeleton v-if="loading" :rows="8" animated />
        <template v-else>
          <strong>未选择客户</strong>
          <span>从左侧列表选择客户后，在此查看客户档案与销售出库业务记录。</span>
        </template>
      </div>

      <div v-else class="customer-detail-content">
        <!-- 概览 -->
        <section v-if="activeTab === 'overview'" class="customer-tab-panel">
          <div class="ov-stats">
            <MetricCard title="销售出库" :value="overview?.outboundCount ?? 0" unit="单" tone="primary">
              <template #icon>
                <span class="ov-stat-icon ov-stat-icon--primary">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><line x1="10" y1="9" x2="8" y2="9"/></svg>
                </span>
              </template>
            </MetricCard>
            <MetricCard title="累计出库金额" :value="amountText" tone="success">
              <template #icon>
                <span class="ov-stat-icon ov-stat-icon--success">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
                </span>
              </template>
            </MetricCard>
            <MetricCard title="首次出库" :value="overview?.firstOutboundDate ? fmtDate(overview.firstOutboundDate) : '—'">
              <template #icon>
                <span class="ov-stat-icon ov-stat-icon--warning">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
                </span>
              </template>
            </MetricCard>
            <MetricCard title="最近出库" :value="overview?.lastOutboundDate ? fmtDate(overview.lastOutboundDate) : '—'">
              <template #icon>
                <span class="ov-stat-icon ov-stat-icon--info">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                </span>
              </template>
            </MetricCard>
          </div>

          <section class="ov-section ov-section--descriptions">
            <LqDescriptions :groups="descGroups" :column="3" border />
          </section>

          <section v-if="purchasedProducts.length" class="ov-section ov-section--products">
            <div class="ov-section__head">
              <span class="ov-section__icon ov-section__icon--package">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="16.5" y1="9.4" x2="7.5" y2="4.21"/><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>
              </span>
              <h4 class="ov-section__title">已购产品（{{ purchasedProducts.length }}）</h4>
            </div>
            <div class="ov-section__body">
              <div class="ov-products">
                <span v-for="p in purchasedProducts" :key="p" class="ov-products__item">{{ p }}</span>
              </div>
            </div>
          </section>
        </section>

        <section v-else-if="activeTab === 'mergeHistory'" class="customer-tab-panel">
          <div class="sub-head"><span class="sub-head__desc">曾并入当前主客户的历史档案，仅用于来源追溯。</span></div>
          <el-table v-if="mergeHistory.length" :data="mergeHistory"><el-table-column prop="mergedCustomerCode" label="原编号" width="130"/><el-table-column prop="mergedCustomerName" label="原客户名" min-width="170"/><el-table-column prop="mergedAddress" label="原地址" min-width="260" show-overflow-tooltip/><el-table-column label="合并时间" width="120"><template #default="{row}">{{ fmtDate(row.mergedAt) }}</template></el-table-column></el-table>
          <div v-else class="ov-empty">暂无历史合并档案</div>
        </section>

        <section v-else-if="activeTab === 'sourceIdentities'" class="customer-tab-panel">
          <div class="sub-head">
            <span class="sub-head__desc">说明销售出库中的原始客户身份为什么归入当前档案。电话是主要识别依据，地址用于辅助核对。</span>
          </div>
          <div v-if="sourceIdentities.length" class="identity-summary">
            <div><span>来源身份</span><strong>{{ sourceIdentities.length }}</strong></div>
            <div><span>覆盖销售出库</span><strong>{{ identityOutboundCount }}</strong></div>
            <div><span>人工确认</span><strong>{{ confirmedIdentityCount }}</strong></div>
            <div><span>自动关联</span><strong>{{ sourceIdentities.length - confirmedIdentityCount }}</strong></div>
          </div>
          <el-table v-if="sourceIdentities.length" :data="sourceIdentities" max-height="520">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="identity-evidence">
                  <div class="identity-evidence__group">
                    <span class="identity-evidence__label">销售出库电话</span>
                    <div class="identity-evidence__values">
                      <span v-for="phone in row.phones" :key="phone" class="identity-token identity-token--phone">{{ phone }}</span>
                      <span v-if="!row.phones.length" class="identity-empty">未填写有效电话</span>
                    </div>
                  </div>
                  <div class="identity-evidence__group">
                    <span class="identity-evidence__label">销售出库地址</span>
                    <div class="identity-evidence__values identity-evidence__values--stack">
                      <span v-for="address in row.addresses" :key="address" class="identity-address">{{ address }}</span>
                      <span v-if="!row.addresses.length" class="identity-empty">未填写地址</span>
                    </div>
                  </div>
                  <div class="identity-evidence__group">
                    <span class="identity-evidence__label">系统关联依据</span>
                    <span>{{ row.resolutionNote || '销售出库已关联到当前客户档案' }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="sourceName" label="氚云原始名称" min-width="200" show-overflow-tooltip />
            <el-table-column prop="outboundCount" label="销售出库" width="92" align="right" />
            <el-table-column label="业务时间" width="185">
              <template #default="{ row }">{{ identityDateRange(row) }}</template>
            </el-table-column>
            <el-table-column label="关联方式" width="105"><template #default="{ row }"><StatusTag :type="row.resolutionStatus === 'CONFIRMED' ? 'tag-ok' : 'tag-neutral'" :label="row.resolutionStatus === 'CONFIRMED' ? '人工确认' : '自动关联'" size="sm" /></template></el-table-column>
            <el-table-column prop="resolutionNote" label="关联依据" min-width="220" show-overflow-tooltip />
            <el-table-column v-if="canManage" label="操作" width="110" fixed="right">
              <template #default="{ row }"><el-button link type="warning" @click="reopenIdentity(row)">退回待复核</el-button></template>
            </el-table-column>
          </el-table>
          <div v-else class="ov-empty">该客户暂无可追溯的氚云来源身份，可能是手工建立的档案。</div>
        </section>

        <!-- 业务（销售出库） -->
        <section v-else-if="activeTab === 'contracts'" class="customer-tab-panel">
          <div class="sub-head sub-head--stack">
            <div class="sub-head__stack">
              <span class="sub-head__desc">已确认销售出库（共 {{ businessOrders.length }} 张，受当前账号数据权限限制）{{ bizFilterActive ? `，当前筛出 ${filteredOrders.length} 张` : '' }}</span>
              <div class="biz-filters">
                <el-input v-model="bizKeyword" class="biz-filters__kw" placeholder="搜索单号 / 业务员 / 电话 / 收货地址" clearable />
                <el-select v-model="bizCustomer" class="biz-filters__select" filterable clearable placeholder="全部客户" :disabled="!bizCustomerOptions.length">
                  <el-option v-for="name in bizCustomerOptions" :key="name" :label="name" :value="name" />
                </el-select>
                <el-select v-model="bizPhone" class="biz-filters__select" filterable clearable placeholder="全部电话" :disabled="!bizPhoneOptions.length">
                  <el-option v-for="phone in bizPhoneOptions" :key="phone" :label="phone" :value="phone" />
                </el-select>
                <el-select v-model="bizRegion" class="biz-filters__select" filterable clearable placeholder="全部地区" :disabled="!bizRegionOptions.length">
                  <el-option v-for="region in bizRegionOptions" :key="region" :label="region" :value="region" />
                </el-select>
                <el-select v-model="bizAddress" class="biz-filters__select biz-filters__select--wide" filterable clearable placeholder="全部收货地址" :disabled="!bizAddressOptions.length">
                  <el-option v-for="address in bizAddressOptions" :key="address" :label="address" :value="address" :title="address" />
                </el-select>
                <el-select v-model="bizSalesperson" class="biz-filters__select" clearable placeholder="全部业务员" :disabled="!bizSalespersonOptions.length">
                  <el-option v-for="name in bizSalespersonOptions" :key="name" :label="name" :value="name" />
                </el-select>
                <el-select v-model="bizOwner" class="biz-filters__select" clearable placeholder="全部归属" :disabled="!bizOwnerOptions.length">
                  <el-option v-for="name in bizOwnerOptions" :key="name" :label="name" :value="name" />
                </el-select>
                <el-select v-model="bizStatus" class="biz-filters__select" clearable placeholder="全部核算状态">
                  <el-option v-for="opt in BIZ_STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
                <el-select v-model="bizNature" class="biz-filters__select" clearable placeholder="全部订单性质">
                  <el-option v-for="opt in BIZ_NATURE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
                <el-date-picker
                  v-model="bizDateRange"
                  class="biz-filters__date"
                  type="daterange"
                  value-format="YYYY-MM-DD"
                  range-separator="至"
                  start-placeholder="发货起"
                  end-placeholder="发货止"
                  unlink-panels
                  clearable
                />
                <el-select v-model="bizSort" class="biz-filters__sort">
                  <el-option v-for="opt in BIZ_SORT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
                <el-button v-if="bizFilterActive" link type="primary" @click="resetBizFilters">重置筛选</el-button>
              </div>
            </div>
          </div>
          <div v-if="businessOrders.length" class="contract-list">
            <template v-if="filteredOrders.length">
              <div
                v-for="bo in filteredOrders"
                :key="bo.id"
                class="contract-card contract-card--clickable"
                @click.stop="openLedgerDetail(bo)">
                <div class="contract-card__head">
                  <span class="contract-card__no">{{ bo.outboundNo }}</span>
                  <el-tag size="small">{{ bo.calculationStatus === 'CALCULATED' ? '已核算' : '待核算' }}</el-tag>
                  <div class="contract-card__actions">
                    <el-button link size="small" type="primary" @click.stop="openLedgerDetail(bo)">查看台账</el-button>
                  </div>
                </div>
                <div class="contract-card__address">
                  <span>客户 {{ bo.customerName || '—' }}</span>
                  <span>客户地址 {{ bo.customerAddress || '—' }}</span>
                </div>
                <div class="contract-card__meta">
                  <span v-if="bo.salespersonName">业务员 {{ bo.salespersonName }}</span>
                  <span v-if="bo.shipDate">发货 {{ fmtDate(bo.shipDate) }}</span>
                  <span class="contract-card__amount">销售额 {{ fmtMoney(bo.salesAmount) }}</span>
                  <span class="contract-card__amount contract-card__amount--profit">
                    订单利润 {{ bo.calculationStatus === 'CALCULATED' ? fmtMoney(bo.accountingProfit) : '待核算' }}
                  </span>
                </div>
                <div v-if="bo.contactPhone || (bo.businessOwner && bo.businessOwner !== bo.salespersonName)" class="contract-card__contact">
                  <span v-if="bo.contactPhone">联系电话 {{ bo.contactPhone }}</span>
                  <span v-if="bo.businessOwner && bo.businessOwner !== bo.salespersonName">归属 {{ bo.businessOwner }}</span>
                </div>
              </div>
            </template>
            <div v-else class="ov-empty">没有符合当前筛选的销售出库单。</div>
          </div>
          <div v-else class="ov-empty">该客户暂无已确认的销售出库。请核对出库的客户绑定和当前账号数据权限。</div>
        </section>

        <!-- 案例素材（P3 占位） -->
        <section v-else-if="activeTab === 'cases'" class="customer-tab-panel">
          <div class="ov-empty">客户案例与素材将在下一阶段上线（P3）。</div>
        </section>
      </div>
    </div>

    <H3LedgerDetailDrawer
      v-model="ledgerVisible"
      ledger-type="outbound"
      :loading="ledgerLoading"
      :data="ledgerDetail"
      :error="ledgerError"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import {
  fetchCustomer,
  fetchCustomerMergeHistory,
  fetchCustomerOverview,
  fetchCustomerSourceIdentities
} from '../../api/customer'
import { fetchOrderProfits, type OrderProfitItem } from '@/api/salesOutbound/orderProfit'
import { notify } from '../../composables/ui/useToast'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
import type { CustomerDetail, CustomerMergeHistory, CustomerOverview, CustomerSourceIdentity, SalespersonSummary } from '../../types/customer'
import { fmtDate } from '../../utils/common/dateUtils'
import LqDescriptions, { type DescGroup } from '../common/LqDescriptions.vue'
import MasterDetailHeader, { type MasterDetailTab } from '../master/MasterDetailHeader.vue'
import StatusTag from '../common/StatusTag.vue'
import MetricCard from '../common/MetricCard.vue'
import H3LedgerDetailDrawer from '../h3/H3LedgerDetailDrawer.vue'
import { fetchLedgerDetail, reopenCustomerIdentity } from '@/api/business/orders'
import type { LedgerDetailResult } from '@/types/h3Import'

const props = withDefaults(
  defineProps<{
    customerId: number | null
    /** 是否可维护客户档案（customer.manage 权限），默认放行以兼容旧用法 */
    canManage?: boolean
  }>(),
  { canManage: true }
)

const emit = defineEmits<{
  edit: [detail: CustomerDetail]
  changed: []
}>()

const detail = ref<CustomerDetail | null>(null)
const overview = ref<CustomerOverview | null>(null)
const activeTab = ref('overview')
const loading = ref(false)
const businessOrders = ref<OrderProfitItem[]>([])
const mergeHistory = ref<CustomerMergeHistory[]>([])
const sourceIdentities = ref<CustomerSourceIdentity[]>([])
// 销售出库台账详情（打开氚云台账原始镜像，与氚云导入中心台账详情同源）
const ledgerVisible = ref(false)
const ledgerLoading = ref(false)
const ledgerDetail = ref<LedgerDetailResult | null>(null)
const ledgerError = ref('')

watch(() => props.customerId, () => {
  ledgerVisible.value = false
})

// ===== 业务 tab 二次筛选：全部基于出库单台账自带字段，在前端内存中过滤/排序 =====
const BIZ_STATUS_OPTIONS = [
  { value: 'CALCULATED', label: '已核算' },
  { value: 'PENDING', label: '待核算' }
]

/** 订单性质取值与后端 SalesOutboundProfitService 的 ORDER_NATURE_CASE 一致。 */
const BIZ_NATURE_OPTIONS = [
  { value: 'SALE', label: '正单' },
  { value: 'NEGATIVE', label: '负单（冲销）' },
  { value: 'ZERO_VALUE', label: '零金额单' },
  { value: 'MIXED', label: '混合（正负明细）' },
  { value: 'RETURN_LINES', label: '含退货明细' }
]

const DEFAULT_BIZ_SORT = 'shipDate-desc'

const BIZ_SORT_OPTIONS = [
  { value: 'shipDate-desc', label: '发货日期 新→旧' },
  { value: 'shipDate-asc', label: '发货日期 旧→新' },
  { value: 'salesAmount-desc', label: '销售额 高→低' },
  { value: 'accountingProfit-desc', label: '订单利润 高→低' }
]

const bizKeyword = ref('')
const bizCustomer = ref('')
const bizPhone = ref('')
const bizRegion = ref('')
const bizAddress = ref('')
const bizSalesperson = ref('')
const bizOwner = ref('')
const bizStatus = ref('')
const bizNature = ref('')
const bizDateRange = ref<[string, string] | null>(null)
const bizSort = ref(DEFAULT_BIZ_SORT)

/** 从已加载出库单中提取某字段的去重取值（升序），用于下拉选项。 */
function distinctOrderValues(pick: (order: OrderProfitItem) => string | null | undefined): string[] {
  const set = new Set<string>()
  for (const order of businessOrders.value) {
    const value = (pick(order) || '').trim()
    if (value) set.add(value)
  }
  return [...set].sort((a, b) => a.localeCompare(b, 'zh-CN'))
}

const bizCustomerOptions = computed<string[]>(() => distinctOrderValues(order => order.customerName))
const bizPhoneOptions = computed<string[]>(() => distinctOrderValues(order => order.contactPhone))
const bizRegionOptions = computed<string[]>(() => distinctOrderValues(order => order.region))
const bizAddressOptions = computed<string[]>(() => distinctOrderValues(order => order.customerAddress))
const bizSalespersonOptions = computed<string[]>(() => distinctOrderValues(order => order.salespersonName))
const bizOwnerOptions = computed<string[]>(() => distinctOrderValues(order => order.businessOwner))

const bizFilterActive = computed(
  () =>
    Boolean(bizKeyword.value.trim()) ||
    Boolean(bizCustomer.value) ||
    Boolean(bizPhone.value) ||
    Boolean(bizRegion.value) ||
    Boolean(bizAddress.value) ||
    Boolean(bizSalesperson.value) ||
    Boolean(bizOwner.value) ||
    Boolean(bizStatus.value) ||
    Boolean(bizNature.value) ||
    Boolean(bizDateRange.value?.[0])
)

const filteredOrders = computed<OrderProfitItem[]>(() => {
  const kw = bizKeyword.value.trim().toLowerCase()
  const from = bizDateRange.value?.[0] || ''
  const to = bizDateRange.value?.[1] || ''
  const matched = businessOrders.value.filter(order => {
    if (bizCustomer.value && order.customerName !== bizCustomer.value) return false
    if (bizPhone.value && order.contactPhone !== bizPhone.value) return false
    if (bizRegion.value && order.region !== bizRegion.value) return false
    if (bizAddress.value && order.customerAddress !== bizAddress.value) return false
    if (bizSalesperson.value && order.salespersonName !== bizSalesperson.value) return false
    if (bizOwner.value && order.businessOwner !== bizOwner.value) return false
    if (bizStatus.value === 'CALCULATED' && order.calculationStatus !== 'CALCULATED') return false
    if (bizStatus.value === 'PENDING' && order.calculationStatus === 'CALCULATED') return false
    if (bizNature.value && order.orderNature !== bizNature.value) return false
    if (from || to) {
      const shipDay = (order.shipDate || '').slice(0, 10)
      if (!shipDay) return false
      if (from && shipDay < from) return false
      if (to && shipDay > to) return false
    }
    if (!kw) return true
    return [order.outboundNo, order.salespersonName, order.dealerName, order.businessOwner, order.region, order.customerAddress, order.contactPhone]
      .some(v => v != null && String(v).toLowerCase().includes(kw))
  })
  return sortBizOrders(matched, bizSort.value)
})

/** 发货日期比较；缺失发货日期的单据恒排末尾，不随升降序翻转。 */
function compareShipDate(a: OrderProfitItem, b: OrderProfitItem, factor: number): number {
  const av = (a.shipDate || '').slice(0, 10)
  const bv = (b.shipDate || '').slice(0, 10)
  if (!av && !bv) return 0
  if (!av) return 1
  if (!bv) return -1
  return av.localeCompare(bv) * factor
}

function sortBizOrders(list: OrderProfitItem[], sort: string): OrderProfitItem[] {
  const rows = [...list]
  switch (sort) {
    case 'shipDate-asc':
      return rows.sort((a, b) => compareShipDate(a, b, 1))
    case 'salesAmount-desc':
      return rows.sort((a, b) => Number(b.salesAmount ?? 0) - Number(a.salesAmount ?? 0))
    case 'accountingProfit-desc':
      return rows.sort((a, b) => Number(b.accountingProfit ?? 0) - Number(a.accountingProfit ?? 0))
    default:
      return rows.sort((a, b) => compareShipDate(a, b, -1))
  }
}

function resetBizFilters() {
  bizKeyword.value = ''
  bizCustomer.value = ''
  bizPhone.value = ''
  bizRegion.value = ''
  bizAddress.value = ''
  bizSalesperson.value = ''
  bizOwner.value = ''
  bizStatus.value = ''
  bizNature.value = ''
  bizDateRange.value = null
  bizSort.value = DEFAULT_BIZ_SORT
}

const salespersons = computed<SalespersonSummary[]>(() => overview.value?.salespersons || [])
const purchasedProducts = computed<string[]>(() => overview.value?.purchasedProducts || [])

const primarySalesperson = computed(() => {
  const s = salespersons.value.find(x => x.role === 'PRIMARY')
  return s?.salespersonName || salespersons.value[0]?.salespersonName || ''
})

const regionText = computed(() =>
  [detail.value?.province, detail.value?.city, detail.value?.district].filter(Boolean).join(' · '))

const nameInitials = computed(() => {
  const name = (detail.value?.customerName || '').trim()
  if (!name) return '?'
  return name.slice(0, 1)
})

const avatarStyle = computed(() => {
  const name = (detail.value?.customerName || '').trim()
  if (!name) return {}
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = (hash << 5) - hash + name.charCodeAt(i)
  const hue = Math.abs(hash) % 360
  return {
    backgroundColor: `hsl(${hue} 42% 92%)`,
    color: `hsl(${hue} 48% 38%)`
  }
})

const amountText = computed(() => {
  const amount = overview.value?.totalSalesAmount
  if (amount == null) return '—'
  if (amount === 0) return '¥0'
  return `¥${Number(amount).toLocaleString('zh-CN')}`
})

/** 使用客户业务 Tab 的去重出库集合，避免同一名称存在名称级与证据级映射时重复累计。 */
const identityOutboundCount = computed(() => businessOrders.value.length)
const confirmedIdentityCount = computed(() =>
  sourceIdentities.value.filter(item => item.resolutionStatus === 'CONFIRMED').length
)

const detailTabs = computed<MasterDetailTab[]>(() => [
  { key: 'overview', label: '概览' },
  { key: 'contracts', label: '业务', badge: businessOrders.value.length },
  { key: 'mergeHistory', label: '合并历史', badge: mergeHistory.value.length },
  { key: 'sourceIdentities', label: '来源身份', badge: sourceIdentities.value.length },
  { key: 'cases', label: '案例素材' }
])

const descGroups = computed<DescGroup[]>(() => {
  const d = detail.value
  if (!d) return []
  return [
    {
      title: '企业信息',
      items: [
        { label: '客户编号', value: d.customerCode, mono: true },
        { label: '客户名称', value: d.customerName },
        { label: '客户简称', value: d.shortName },
        { label: '客户类型', value: d.customerType },
        { label: '所属行业', value: d.industry },
        { label: '统一社会信用代码', value: d.creditCode, mono: true, wide: true },
        { label: '省', value: d.province },
        { label: '市', value: d.city },
        { label: '区县', value: d.district },
        { label: '公司地址', value: d.address, wide: true },
        { label: '官网', value: d.website, wide: true }
      ]
    },
    {
      title: '客户关系',
      items: [
        { label: '客户来源', value: d.source === 'H3YUN_SALES_OUTBOUND' ? '氚云销售出库' : d.source },
        { label: '客户等级', value: d.level === 'KEY' ? '重点' : d.level === 'COMMON' ? '普通' : d.level },
        { label: '关系状态', value: d.relationStatus, slot: 'relationStatus' },
        { label: '启用状态', value: d.status, slot: 'status' },
        { label: '建档时间', value: fmtDate(d.createdAt), mono: true },
        { label: '最近修改', value: fmtDate(d.updatedAt), mono: true },
        { label: '备注', value: d.remark, wide: true }
      ]
    }
  ]
})

// ===== 加载 =====

watch(
  () => props.customerId,
  async (id) => {
    activeTab.value = 'overview'
    detail.value = null
    overview.value = null
    businessOrders.value = []
    resetBizFilters()
    if (!id) return
    loading.value = true
    try {
      await Promise.all([loadDetail(id), loadOverview(id), loadBusinessOrders(id), loadMergeHistory(id), loadSourceIdentities(id)])
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)

async function loadDetail(id: number) {
  try {
    detail.value = await fetchCustomer(id)
  } catch (err) {
    notify.errorWithDetail('加载客户详情失败', err)
  }
}

async function loadOverview(id: number) {
  try {
    overview.value = await fetchCustomerOverview(id)
  } catch (err) {
    notify.errorWithDetail('加载客户概览失败', err)
  }
}

async function loadBusinessOrders(id: number) {
  try {
    const pageSize = 100
    const first = await fetchOrderProfits({ customerId: id, page: 0, size: pageSize })
    const allOrders = [...first.items]
    const pageCount = Math.ceil(first.total / pageSize)
    for (let page = 1; page < pageCount; page += 1) {
      const result = await fetchOrderProfits({ customerId: id, page, size: pageSize })
      allOrders.push(...result.items)
    }
    if (props.customerId === id) businessOrders.value = allOrders
  } catch (err) {
    notify.errorWithDetail('加载客户订单失败', err)
  }
}

async function loadMergeHistory(id: number) { mergeHistory.value = await fetchCustomerMergeHistory(id) }
async function loadSourceIdentities(id: number) { sourceIdentities.value = await fetchCustomerSourceIdentities(id) }

async function reopenIdentity(row: CustomerSourceIdentity) {
  if (!props.customerId) return
  const confirmed = await askConfirm({
    title: '退回身份待复核',
    message: `确认将氚云名称“${row.sourceName || row.sourceKey}”从当前客户移出？对应销售出库、生产和收款会暂时解除客户关联，重新匹配后即可恢复。`,
    danger: true
  })
  if (!confirmed) return
  const result = await reopenCustomerIdentity(row.id)
  await Promise.all([loadSourceIdentities(props.customerId), loadBusinessOrders(props.customerId)])
  emit('changed')
  notify.success(`已退回待复核，解除 ${result.unlinkedFacts} 条业务关联`)
}

/** 打开销售出库台账详情：与氚云导入中心台账同源（sales_outbounds 主记录 + 出库产品明细）。 */
async function openLedgerDetail(bo: OrderProfitItem) {
  const id = Number(bo?.id)
  if (!id || Number.isNaN(id)) {
    notify.error('该出库单缺少有效 ID，无法查看台账详情')
    return
  }
  ledgerDetail.value = null
  ledgerError.value = ''
  ledgerVisible.value = true
  ledgerLoading.value = true
  try {
    ledgerDetail.value = await fetchLedgerDetail('outbound', id)
  } catch (e) {
    ledgerError.value = (e as Error).message || '台账详情加载失败'
    notify.error(ledgerError.value)
  } finally {
    ledgerLoading.value = false
  }
}

function fmtMoney(value: number | null | undefined) {
  if (value == null) return '-'
  return '¥' + Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function identityDateRange(row: CustomerSourceIdentity) {
  if (!row.firstOutboundDate && !row.lastOutboundDate) return '暂无已确认出库'
  const first = row.firstOutboundDate ? fmtDate(row.firstOutboundDate) : '—'
  const last = row.lastOutboundDate ? fmtDate(row.lastOutboundDate) : '—'
  return first === last ? first : `${first} 至 ${last}`
}

// ===== 标签样式 =====

function relationTagClass(status: string | null | undefined): string {
  if (status === '重点关注') return 'tag-warn'
  if (status === '停止合作') return 'tag-expired'
  if (status === '禁止联系') return 'tag-danger'
  return 'tag-ok'
}

</script>

<style scoped>
/* 工作台模式：sticky 内联面板（与产品/包材详情面板一致） */
.customer-detail-panel.pinned {
  position: sticky;
  top: 108px;
  right: auto;
  bottom: auto;
  left: auto;
  width: 100%;
  height: calc(100vh - 124px);
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

/* ===== 身份区（名称 + 编号 + 状态） ===== */
.cust-head {
  display: flex;
  align-items: center;
  gap: var(--space-md-lg);
  padding: var(--space-lg) var(--space-control-gap-wide);
  border-bottom: 1px solid var(--color-primary-border-light);
  background: linear-gradient(135deg, var(--color-primary-bg-light) 0%, var(--color-surface) 55%);
  flex-shrink: 0;
}

.cust-head__avatar {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  font-size: var(--fs-2xl);
  font-weight: var(--fw-bold);
  line-height: 1;
  user-select: none;
  box-shadow: 0 0 0 3px var(--color-surface), 0 0 0 5px var(--color-primary-border-light);
}

.cust-head__main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.cust-head__name {
  margin: 0;
  font-size: var(--fs-xl);
  font-weight: var(--fw-bold);
  color: var(--color-text-heading);
  line-height: var(--lh-tight);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cust-head__code {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
  letter-spacing: 0.02em;
}

.cust-head__pills {
  flex: 0 1 auto;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-control-gap-tight);
}

.cust-head__status {
  box-shadow: var(--shadow-xs);
}

.cust-head__pill {
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  border: 1px solid var(--color-border-light);
  white-space: nowrap;
  box-shadow: var(--shadow-xs);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.cust-head__pill--meta:hover {
  border-color: var(--color-border-input);
  box-shadow: var(--shadow-sm);
}

.cust-head__pill--primary {
  background: var(--color-primary-bg);
  color: var(--color-primary);
  border-color: var(--color-primary-border-light);
}

.customer-detail-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  overflow-y: auto;
  overflow-x: hidden;
  min-height: 0;
}

.customer-detail-content {
  display: grid;
  gap: var(--space-section-gap-wide);
  padding: var(--space-section-gap-wide);
}

.customer-detail-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  padding: 60px 20px;
  color: var(--color-text-faint);
  text-align: center;
}

.customer-detail-empty strong {
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  color: var(--color-text-muted);
}

.customer-detail-empty span {
  font-size: var(--fs-sm);
}

.customer-tab-panel {
  display: grid;
  gap: var(--space-section-gap-wide);
}

/* ===== 概览统计卡片 ===== */
.ov-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--space-card-gap);
  margin-bottom: var(--space-section-gap-wide);
}

.ov-stats :deep(.metric-card__value strong) {
  font-size: var(--fs-2xl);
}

.ov-stat-icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-md);
}

.ov-stat-icon svg {
  width: 16px;
  height: 16px;
}

.ov-stat-icon--primary {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.ov-stat-icon--success {
  background: var(--color-success-bg);
  color: var(--color-success);
}

.ov-stat-icon--warning {
  background: var(--color-warning-bg);
  color: var(--color-warning);
}

.ov-stat-icon--info {
  background: var(--color-info-bg);
  color: var(--color-info);
}

/* ===== 概览卡片区块 ===== */
.ov-section {
  padding: var(--space-control-gap-wide);
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.ov-section--descriptions {
  padding: var(--space-control-gap-wide);
}

.ov-section--descriptions :deep(.lq-desc__group + .lq-desc__group) {
  margin-top: var(--space-section-gap-wide);
  padding-top: var(--space-section-gap-wide);
  border-top: 1px solid var(--color-border-lighter);
}

.ov-section--descriptions :deep(.lq-desc__group-title) {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin: 0 0 var(--space-md);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  letter-spacing: var(--tracking-wide);
}

.ov-section--descriptions :deep(.lq-desc__group-title::before) {
  content: '';
  width: 5px;
  height: 18px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
}

.ov-section--descriptions :deep(.el-descriptions__table) {
  display: table !important;
  table-layout: auto !important;
  width: 100% !important;
  border-collapse: collapse !important;
  background: transparent !important;
}

.ov-section--descriptions :deep(.el-descriptions__colgroup) {
  display: table-column-group !important;
}

.ov-section--descriptions :deep(.el-descriptions__body),
.ov-section--descriptions :deep(.el-descriptions__table) > tbody,
.ov-section--descriptions :deep(.el-descriptions__table) > thead {
  display: table-row-group !important;
}

.ov-section--descriptions :deep(.el-descriptions__row),
.ov-section--descriptions :deep(tr) {
  display: table-row !important;
  width: auto !important;
  margin-bottom: 0 !important;
  border: none !important;
  background: transparent !important;
}

.ov-section--descriptions :deep(.el-descriptions__cell),
.ov-section--descriptions :deep(td) {
  display: table-cell !important;
  width: auto !important;
  max-width: none !important;
  padding: 10px 12px !important;
  border: 1px solid var(--color-border-lighter) !important;
  background: transparent !important;
  vertical-align: middle !important;
}

.ov-section--descriptions :deep(.el-descriptions__label) {
  display: table-cell !important;
  width: auto !important;
  flex: none !important;
  margin-right: 0 !important;
  padding: 10px 12px !important;
  background: var(--color-surface-alt) !important;
  color: var(--color-text-muted);
  font-weight: var(--fw-medium);
  border: 1px solid var(--color-border-lighter) !important;
  white-space: nowrap;
}

.ov-section--descriptions :deep(.el-descriptions__content) {
  display: table-cell !important;
  width: auto !important;
  flex: none !important;
  padding: 10px 12px !important;
  background: transparent !important;
  color: var(--color-text-primary);
  border: 1px solid var(--color-border-lighter) !important;
  word-break: break-word;
}

.ov-section__head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-control-gap-wide);
  padding-bottom: var(--space-control-gap);
  border-bottom: 1px solid var(--color-border-lighter);
}

.ov-section__icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.ov-section__icon svg {
  width: 16px;
  height: 16px;
}

.ov-section__icon--package {
  background: var(--color-warning-bg-soft);
  color: var(--color-warning);
}

.ov-section__title {
  margin: 0;
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  letter-spacing: var(--tracking-wide);
}

.ov-section__body {
  display: grid;
  gap: var(--space-control-gap);
}

.ov-products {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.ov-products__item {
  padding: 4px 12px;
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
  border: 1px solid var(--color-border-light);
  box-shadow: var(--shadow-xs);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast), color var(--transition-fast), transform var(--transition-fast);
}

.ov-products__item:hover {
  border-color: var(--color-primary-border-light);
  color: var(--color-primary);
  box-shadow: var(--shadow-sm);
  transform: translateY(-1px);
}

.contract-list {
  display: grid;
  gap: var(--space-control-gap);
}

.contract-card {
  padding: var(--space-control-gap-wide);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  transition:
    border-color var(--transition-fast),
    box-shadow var(--transition-fast),
    transform var(--transition-fast);
}

.contract-card:hover {
  border-color: var(--color-primary-border-light);
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.contract-card__head {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.contract-card__no {
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
}

.contract-card__actions {
  margin-left: auto;
}

.contract-card__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm) var(--space-lg);
  margin-top: var(--space-control-gap);
  padding-top: var(--space-control-gap);
  border-top: 1px solid var(--color-border-light);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.contract-card__meta > span {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.contract-card__amount {
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  font-variant-numeric: tabular-nums;
}

.contract-card__amount--profit {
  color: var(--color-primary);
}

.contract-card__address {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm) var(--space-lg);
  margin-top: var(--space-control-gap);
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
}

.contract-card__address > span {
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ov-empty {
  padding: var(--space-lg);
  text-align: center;
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  background: var(--color-surface-alt);
  border-radius: var(--radius-md);
}

/* ===== 子页签标题栏 ===== */
.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding: var(--space-control-gap-tight) 0 var(--space-control-gap);
  border-bottom: 1px solid var(--color-border-light);
  margin-bottom: var(--space-control-gap-wide);
}

.sub-head__desc {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.identity-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-sm);
  margin-bottom: var(--space-control-gap-wide);
}

.identity-summary > div {
  display: grid;
  gap: 3px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
}

.identity-summary span,
.identity-evidence__label,
.identity-empty {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.identity-summary strong {
  color: var(--color-text-heading);
  font-size: var(--fs-lg);
  font-variant-numeric: tabular-nums;
}

.identity-evidence {
  display: grid;
  gap: var(--space-control-gap);
  padding: var(--space-control-gap) var(--space-lg);
  background: var(--color-surface-alt);
}

.identity-evidence__group {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: var(--space-md);
  align-items: start;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
}

.identity-evidence__values {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.identity-evidence__values--stack {
  display: grid;
}

.identity-token {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-primary-border-light);
  background: var(--color-surface);
  color: var(--color-primary);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.identity-address {
  line-height: 1.6;
}

/* ===== 子页签内的业务出库筛选与卡片增强 ===== */
.sub-head--stack {
  align-items: flex-start;
}

.sub-head__stack {
  flex: 1 1 auto;
  min-width: 0;
  display: grid;
  gap: var(--space-control-gap-tight);
}

.biz-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-control-gap);
}

.biz-filters__kw {
  width: 280px;
  max-width: 100%;
  flex: 1 1 240px;
}

.biz-filters__select {
  width: 140px;
  flex: 0 0 auto;
}

/* 收货地址等长文本维度加宽，避免下拉选项被截断 */
.biz-filters__select--wide {
  width: 200px;
}

.biz-filters__date {
  width: 240px;
  flex: 0 0 auto;
}

.biz-filters__sort {
  width: 160px;
  flex: 0 0 auto;
}

.contract-card--clickable {
  cursor: pointer;
}

.contract-card__contact {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm) var(--space-lg);
  margin-top: var(--space-control-gap);
  padding-top: var(--space-control-gap);
  border-top: 1px dashed var(--color-border-light);
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
}

.contract-card__contact > span {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  white-space: nowrap;
}

@media (max-width: 980px) {
  .customer-detail-panel.pinned {
    position: fixed;
    inset: 0;
    border: 0;
    border-radius: 0;
    z-index: 50;
  }

  /* 窄屏未选中客户时，不占用固定全屏遮罩，让列表独占可视区域 */
  .customer-detail-panel.pinned.is-empty {
    display: none;
  }

  /* 窄屏：筛选控件逐行铺满，避免下拉被压成窄条 */
  .biz-filters__kw,
  .biz-filters__select,
  .biz-filters__date,
  .biz-filters__sort {
    width: 100%;
    flex: 1 1 100%;
  }
}
</style>
