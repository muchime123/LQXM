<template>
  <div class="analysis-page">
    <header class="page-head">
      <div><h2>销售出库产品分析</h2><p>依据已确认的氚云销售出库批次实时汇总；按产品名称归并出库编号（氚云按名称关联，编号系人工填写易错），用于梳理主营产品，尚非 ERPNext Item 导入清单。</p></div>
      <el-button :loading="loading" @click="load">刷新数据</el-button>
    </header>

    <div v-if="data?.batches.length" class="source-note">
      数据源：氚云已确认出库 · 最近批次 #{{ data.batches[0].id }} · 历史出库日期 {{ day(data.scope.firstShip) }}—{{ day(data.scope.lastShip) }}
    </div>
    <el-empty v-if="!loading && data && !data.scope.orders" description="暂无已确认的氚云销售出库数据" />

    <template v-if="data?.scope.orders">
      <section class="overview" aria-label="当前筛选统计" v-loading="loading">
        <div class="overview-title"><strong>当前筛选结果</strong><span>{{ summaryNote }}</span></div>
        <div v-if="data.summary" class="metric-row">
          <div class="metric"><span>编号类目组合</span><strong>{{ num(data.summary.combinations) }}</strong></div>
          <div class="metric"><span>不同产品编号</span><strong>{{ num(data.summary.productCount) }}</strong></div>
          <div class="metric"><span>出库明细行</span><strong>{{ num(data.summary.lineCount) }}</strong></div>
          <div class="metric"><span>销售金额</span><strong>¥{{ money(data.summary.salesAmount) }}</strong></div>
        </div>
        <el-alert v-else class="summary-unavailable" type="warning" :closable="false"
          title="当前后端尚未返回筛选统计。产品明细仍可查看；请重启后端后刷新页面。" />
        <div class="group-strip" aria-label="产品大类分布">
          <button v-for="g in data.groups" :key="g.productGroup" type="button" class="group-item"
            :class="{ 'group-item--active': productGroup === g.productGroup }" @click="selectGroup(g.productGroup)">
            <span>{{ productGroupLabels[g.productGroup] }}</span>
            <strong>{{ num(g.productCount) }} <small>个编号</small></strong>
            <small>{{ num(g.lineCount) }} 行 · ¥{{ money(g.salesAmount) }}</small>
          </button>
        </div>
      </section>

      <el-card shadow="never" class="list-card">
        <div class="list-head"><strong>产品明细</strong><span>共 {{ num(data.total) }} 条编号类目组合；点「出库名称示例」表头排序可忽略规格数字、把相近名称聚到一起</span></div>
        <section class="filter-panel" aria-label="产品明细筛选">
          <div class="filter-head"><strong>筛选产品</strong><span>条件会同步更新上方统计</span></div>
          <div class="filter-grid">
            <label class="filter-field"><span>产品大类</span>
              <el-select v-model="productGroup" placeholder="全部大类" @change="changeProductGroup">
                <el-option label="全部大类" value="" />
                <el-option label="盒子" value="BOX" /><el-option label="膜" value="FILM" />
                <el-option label="机器" value="MACHINE" /><el-option label="待归类" value="UNASSIGNED" />
              </el-select>
            </label>
            <label class="filter-field"><span>产品类目</span>
              <el-select v-model="category" clearable filterable placeholder="全部类目" @change="search">
                <el-option v-for="c in availableCategories" :key="c.category" :label="c.category" :value="c.category" />
              </el-select>
            </label>
            <label class="filter-field"><span>核对状态</span>
              <el-select v-model="view" @change="search">
                <el-option label="全部" value="ALL" /><el-option label="待核对" value="REVIEW" />
                <el-option label="ERPNext 候选" value="CANDIDATE" />
              </el-select>
            </label>
            <label class="filter-field filter-field--search"><span>搜索产品</span>
              <el-input v-model="keyword" clearable placeholder="编号或名称" @keyup.enter="search" @clear="search" />
            </label>
            <label class="filter-field filter-field--range"><span>出库日期</span>
              <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
                start-placeholder="起始日" end-placeholder="结束日" unlink-panels clearable
                :shortcuts="dateShortcuts" @change="search" />
            </label>
            <div class="filter-actions"><el-checkbox v-model="onlyInStock" @change="search">仅看非零库存</el-checkbox><el-button type="primary" @click="search">查询</el-button></div>
          </div>
        </section>
        <el-alert v-if="view === 'CANDIDATE'" class="candidate-note" type="info" :closable="false" show-icon
          title="候选需满足出库名称、单位与产品信息一致，且同编号有非零库存。导入 ERPNext 前仍须核对规格、库存身份与计价。" />
        <el-empty v-if="!data.items.length" description="当前条件下没有销售出库产品" />
        <el-table :data="data.items" v-loading="loading" stripe
          :default-sort="defaultSort" @sort-change="handleSortChange">
          <el-table-column label="产品大类" width="100"><template #default="{ row }">{{ productGroupLabels[row.productGroup as ProductGroup] }}</template></el-table-column>
          <el-table-column prop="category" label="产品类目" width="130" />
          <el-table-column prop="productCode" label="产品编号" width="170">
            <template #default="{ row }">
              <div class="cell-main">{{ row.productCode }}</div>
              <div v-if="row.codeRewritten" class="cell-diff">氚云录入：{{ rawCodeText(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="sampleNames" label="出库名称示例" min-width="240" sortable="custom">
            <template #default="{ row }">
              <div class="cell-main">{{ row.sampleNames }}</div>
              <div v-if="catalogNameHint(row)" class="cell-diff">{{ catalogNameHint(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="sampleUnits" label="单位" width="130">
            <template #default="{ row }">
              <div class="cell-main">{{ row.sampleUnits }}</div>
              <div v-if="catalogUnitHint(row)" class="cell-diff">{{ catalogUnitHint(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="orderCount" label="出库单数" width="100" sortable="custom" />
          <el-table-column prop="lineCount" label="明细行数" width="100" sortable="custom" />
          <el-table-column prop="nonZeroStockRows" label="当前库存快照" width="150" sortable="custom">
            <template #default="{ row }">
              <el-button v-if="row.nonZeroStockRows > 0" link type="primary" @click="openStock(row)">
                非零 {{ row.nonZeroStockRows }} 行 / {{ row.stockWarehouseCount }} 仓 →
              </el-button>
              <span v-else class="muted">无非零库存</span>
            </template>
          </el-table-column>
          <el-table-column prop="salesAmount" label="销售金额" width="135" sortable="custom"><template #default="{ row }">¥{{ money(row.salesAmount) }}</template></el-table-column>
          <el-table-column prop="lastShip" label="末次出库" width="115" sortable="custom"><template #default="{ row }">{{ day(row.lastShip) }}</template></el-table-column>
          <el-table-column label="源出库" width="96" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="openOutbound(row)">查看出库 →</el-button></template>
          </el-table-column>
          <el-table-column label="核对状态" min-width="180">
            <template #default="{ row }">
              <el-tag v-if="row.categoryCount > 1" type="danger" size="small">编号跨类目</el-tag>
              <el-tag v-else-if="row.nameCount !== 1 || row.unitCount !== 1" type="danger" size="small">同编号多名称/单位</el-tag>
              <el-tag v-else-if="!row.inCatalog" type="warning" size="small">未见于产品信息</el-tag>
              <el-tag v-else-if="row.sampleNames !== row.catalogName || row.sampleUnits !== row.catalogUnit" type="warning" size="small">出库与产品信息不一致</el-tag>
              <el-tag v-else-if="row.nonZeroStockRows > 0" type="success" size="small">候选 · 待人工确认</el-tag>
              <el-tag v-else type="info" size="small">无非零库存</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination"><el-pagination v-model:current-page="page" :page-size="30" :total="data.total" layout="total, prev, pager, next" @current-change="load" /></div>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchH3SalesProductAnalysis, type SalesProductAnalysis, type SalesProductAnalysisRow } from '@/api/h3SalesProductAnalysis'
import { fetchH3CategoryGroups, productGroupLabels, type H3CategoryGroupRow, type ProductGroup } from '@/api/h3ProductCategoryGroups'
import { notify } from '@/composables/ui/useToast'

const data = ref<SalesProductAnalysis | null>(null)
const router = useRouter()
const categoryGroups = ref<H3CategoryGroupRow[]>([])
const loading = ref(false)
const keyword = ref('')
const category = ref('')
const productGroup = ref('')
const view = ref('ALL')
// 默认不勾「仅看非零库存」：这一页的目的是梳理主营产品，而实测 12,611 个编号类目组合里
// 只有约 1,200 个当前有非零库存（产品卖过、当下无库存是常态）。默认勾选会把 90% 的历史产品藏起来。
const onlyInStock = ref(false)
const page = ref(1)
// 出库日期区间（含端点，值为 yyyy-MM-dd）。null = 不限，即「全部历史」，与加时间维度之前同口径。
// 数据跨 2022-04 ~ 2026-08 共 53 个月，没有时间维度时所有统计都只能看全历史。
const dateRange = ref<[string, string] | null>(null)
const dateShortcuts = [
  { text: '今年', value: () => { const now = new Date(); return [new Date(now.getFullYear(), 0, 1), now] } },
  { text: '去年', value: () => { const y = new Date().getFullYear() - 1; return [new Date(y, 0, 1), new Date(y, 11, 31)] } },
  { text: '近 12 个月', value: () => { const now = new Date(); return [new Date(now.getFullYear(), now.getMonth() - 11, 1), now] } }
]
// 服务端排序；默认「出库单数降序」，与后端未指定 sort 时的默认顺序一致。
// sortField='' 表示回到默认排序，此处统一显式回落到 orderCount/desc，避免表头箭头与列表顺序不一致。
const sortField = ref('orderCount')
const sortDir = ref<'asc' | 'desc'>('desc')
const defaultSort = computed(() => ({
  prop: sortField.value,
  order: (sortDir.value === 'asc' ? 'ascending' : 'descending') as 'ascending' | 'descending'
}))
let requestId = 0
const availableCategories = computed(() => categoryGroups.value.filter(c =>
  !productGroup.value || c.productGroup === productGroup.value).map(c => ({ category: c.sourceCategory })))
// 在标题里显式说明库存过滤与日期区间是否生效，避免「看不到数据」被误当成没有数据。
const stockNote = computed(() => onlyInStock.value ? '已排除当前无库存的编号' : '含当前无库存的编号')
const rangeNote = computed(() => dateRange.value ? `出库日期 ${dateRange.value[0]} ~ ${dateRange.value[1]}` : '')
const summaryNote = computed(() => ['按产品编号（名称优先，编号录错时自动纠正）× 原始类目汇总', '销售金额仅含正数产品出库', stockNote.value, rangeNote.value]
  .filter(Boolean).join('；'))
const num = (v: unknown) => Number(v || 0).toLocaleString('zh-CN')
const money = (v: unknown) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const day = (v: string | null) => v ? v.slice(0, 10) : '—'
// 产品信息侧的对照提示：仅在与出库侧不一致时给出。这样「待核对」列表不用再点开就能看出差在哪，
// 而此前只知道「不一致」，看不到不一致的具体内容。
function catalogNameHint(row: SalesProductAnalysisRow) {
  if (!row.inCatalog) return '未见于产品信息'
  return row.sampleNames === row.catalogName ? '' : `产品信息：${row.catalogName ?? '—'}`
}
function catalogUnitHint(row: SalesProductAnalysisRow) {
  if (!row.inCatalog) return ''
  return row.sampleUnits === row.catalogUnit ? '' : `产品信息：${row.catalogUnit ?? '—'}`
}
async function load() {
  const currentRequest = ++requestId
  loading.value = true
  try {
    const result = await fetchH3SalesProductAnalysis({ keyword: keyword.value, view: view.value, category: category.value, productGroup: productGroup.value, onlyInStock: onlyInStock.value, sort: sortField.value, direction: sortDir.value, dateFrom: dateRange.value?.[0], dateTo: dateRange.value?.[1], page: page.value, size: 30 })
    if (currentRequest === requestId) data.value = result
  }
  catch (e) { if (currentRequest === requestId) notify.error((e as Error).message || '销售出库分析加载失败') }
  finally { if (currentRequest === requestId) loading.value = false }
}
async function loadGroups() {
  try { categoryGroups.value = await fetchH3CategoryGroups() }
  catch (e) { notify.error((e as Error).message || '产品类目加载失败') }
}
/** 允许服务端排序的列：与后端 SORTABLE_FIELDS 保持一一对应，避免前端点出箭头而服务端忽略。 */
const SORTABLE_FIELDS = ['sampleNames', 'orderCount', 'salesAmount', 'lineCount', 'lastShip', 'nonZeroStockRows']
function search() { page.value = 1; void load() }
/** 表头排序：仅在服务端排序，前端不再对当前页做二次排序，避免「只排了本页」的假象。 */
function handleSortChange({ prop, order }: { prop: string | null; order: 'ascending' | 'descending' | null }) {
  if (!order || !prop || !SORTABLE_FIELDS.includes(prop)) {
    sortField.value = 'orderCount'
    sortDir.value = 'desc'
  }
  else {
    sortField.value = prop
    sortDir.value = order === 'ascending' ? 'asc' : 'desc'
  }
  search()
}
function changeProductGroup() { category.value = ''; search() }
function selectGroup(group: ProductGroup) { productGroup.value = productGroup.value === group ? '' : group; changeProductGroup() }
/** 原始编号在接口里是逗号分隔（供台账下钻直接透传），展示时换成更好读的「 / 」。 */
function rawCodeText(row: SalesProductAnalysisRow) {
  return (row.rawCodes ?? '').replace(/,/g, ' / ')
}
function openOutbound(row: SalesProductAnalysisRow) {
  // 台账按「这一行在氚云里实际用过的原始编号」过滤：实体编号是解析出来的，台账里并不存在这个编号，
  // 直接传它会查不到（或只查到一部分）。编号列表实测最多 4 个，接口按逗号分隔的多个值处理。
  void router.push({ path: '/h3-imports', query: {
    tab: 'ledger', ledger: 'outbound', productCode: row.rawCodes || row.productCode, category: row.category
  } })
}
function openStock(row: SalesProductAnalysisRow) {
  void router.push({ path: '/product-inventory', query: { keyword: row.productCode, onlyInStock: '1' } })
}
// 首次进入由顶层 await 加载；KeepAlive 再次激活时才刷新。
let hasActivated = false
onActivated(() => {
  if (hasActivated) void Promise.all([load(), loadGroups()])
  else hasActivated = true
})
await Promise.all([load(), loadGroups()])
</script>

<style scoped>
.analysis-page{padding:20px;display:grid;gap:14px;min-width:0}
.page-head{display:flex;justify-content:space-between;align-items:flex-start;gap:20px}
.page-head h2{margin:0 0 6px;font-size:22px}.page-head p,.source-note{margin:0;color:var(--el-text-color-secondary);font-size:13px}
.source-note{padding:0 2px}
.overview{border:1px solid var(--el-border-color-light);border-radius:10px;background:var(--el-bg-color);padding:16px 18px}
.filter-panel{padding:0 0 16px;margin-bottom:16px;border-bottom:1px solid var(--el-border-color-lighter)}
.filter-head,.overview-title,.list-head{display:flex;align-items:baseline;gap:12px;margin-bottom:14px}
.filter-head strong,.overview-title strong,.list-head strong{font-size:15px}.filter-head span,.overview-title span,.list-head span{color:var(--el-text-color-secondary);font-size:12px}
.filter-grid{display:grid;grid-template-columns:minmax(130px,1fr) minmax(140px,1.05fr) minmax(130px,1fr) minmax(170px,1.3fr) minmax(230px,1.8fr) auto;gap:12px;align-items:end}
.filter-field{display:grid;gap:6px;min-width:0;color:var(--el-text-color-secondary);font-size:12px}.filter-field :deep(.el-select),.filter-field :deep(.el-input),.filter-field :deep(.el-date-editor){width:100%}
.filter-actions{display:flex;align-items:center;justify-content:flex-end;gap:12px;min-height:32px;white-space:nowrap}
.overview{padding-bottom:0}.metric-row{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));padding-bottom:16px}
.metric{display:grid;gap:6px;min-width:0;padding:0 20px;border-left:1px solid var(--el-border-color-lighter)}.metric:first-child{padding-left:0;border-left:0}
.metric span{font-size:12px;color:var(--el-text-color-secondary)}.metric strong{font-size:23px;line-height:1.2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-variant-numeric:tabular-nums}
.group-strip{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));margin:0 -18px;border-top:1px solid var(--el-border-color-lighter)}
.group-item{display:grid;gap:3px;text-align:left;min-width:0;padding:12px 18px;border:0;border-right:1px solid var(--el-border-color-lighter);background:transparent;color:var(--el-text-color-primary);cursor:pointer}
.group-item:last-child{border-right:0}.group-item:hover,.group-item--active{background:var(--el-fill-color-light)}.group-item--active{box-shadow:inset 0 2px var(--el-color-primary)}
.group-item>span{font-size:12px;color:var(--el-text-color-secondary)}.group-item strong{font-size:17px;font-variant-numeric:tabular-nums}.group-item strong small{font-size:12px;font-weight:400}.group-item>small{color:var(--el-text-color-secondary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.list-card :deep(.el-card__body){padding:16px 18px}.list-head{margin-bottom:12px}.muted{color:var(--el-text-color-secondary)}
.cell-main{word-break:break-word}.cell-diff{margin-top:2px;font-size:12px;line-height:1.4;word-break:break-word;color:var(--el-color-warning)}
.pagination{display:flex;justify-content:flex-end;margin-top:16px}.candidate-note{margin-bottom:14px}
.summary-unavailable{margin-bottom:16px}
@media(max-width:1250px){.filter-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.filter-actions{justify-content:flex-start}}
@media(max-width:800px){.metric-row,.group-strip{grid-template-columns:repeat(2,minmax(0,1fr))}.metric:nth-child(3){padding-left:0;border-left:0}.metric:nth-child(n+3){padding-top:12px}.filter-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.filter-actions{grid-column:1/-1}}
@media(max-width:560px){.analysis-page{padding:12px}.page-head{flex-wrap:wrap}.filter-grid{grid-template-columns:1fr}.filter-actions{grid-column:auto}.metric{padding:0 8px}.metric strong{font-size:19px}.group-item{padding:10px}}
</style>
