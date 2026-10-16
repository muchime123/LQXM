<template>
  <el-drawer :model-value="modelValue" size="90%" :with-header="false" append-to-body @update:model-value="emit('update:modelValue', $event)">
  <main class="tracking-analysis" v-loading="loading">
    <header class="analysis-head">
      <div><h1>生产交期分析</h1><p>可按期间在制、任务单提交或入库日期查看；范围与当前账号可见跟单一致。数据截至 {{ data?.asOf || '今日' }}。</p></div>
      <div class="analysis-head-actions"><el-button @click="load">刷新数据</el-button><el-button aria-label="关闭生产分析" @click="emit('update:modelValue', false)">关闭</el-button></div>
    </header>
    <section class="analysis-filter" aria-label="分析范围">
      <el-select v-model="dateBasis" class="analysis-date-basis" aria-label="分析日期口径"><el-option label="期间在制（含上月延续）" value="active" /><el-option label="按入库时间" value="inbound" /><el-option label="按任务单提交时间" value="submitted" /></el-select>
      <el-date-picker v-model="fromDate" type="date" value-format="YYYY-MM-DD" :placeholder="datePlaceholder('起')" clearable />
      <span>至</span>
      <el-date-picker v-model="toDate" type="date" value-format="YYYY-MM-DD" :placeholder="datePlaceholder('止')" clearable />
      <el-select v-model="workshop" clearable filterable placeholder="全部车间"><el-option v-for="name in workshopOptions" :key="name" :label="name" :value="name" /></el-select>
      <el-button type="primary" @click="load">分析</el-button>
      <el-button @click="reset">全部可见任务</el-button>
      <p class="analysis-filter-note">{{ dateBasisHint }}</p>
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section v-if="drilldown" ref="drilldownPanel" class="analysis-section analysis-drilldown" aria-label="指标对应工单">
      <div class="analysis-head"><div><h2>{{ drilldown.label }} · 对应工单 {{ drilldownTotal }} 单</h2><p>点击工单可打开生产跟单详情。</p></div><el-button @click="drilldown = null">收起</el-button></div>
      <el-table v-loading="drilldownLoading" :data="drilldownItems" stripe empty-text="没有符合条件的工单" @row-click="openDrilldownOrder">
        <el-table-column prop="orderNo" label="生产任务单" min-width="165"><template #default="{ row }"><button class="analysis-link" type="button" @click.stop="emit('open-order', row.id)">{{ row.orderNo }} ↗</button></template></el-table-column>
        <el-table-column prop="productName" label="产品" min-width="150" show-overflow-tooltip />
        <el-table-column prop="workshop" label="车间" min-width="125" show-overflow-tooltip />
        <el-table-column prop="submittedDate" label="提交日期" width="115" />
        <el-table-column prop="baseline" label="车间交期" width="115" />
        <el-table-column prop="deliveredDate" label="入库 / 交付日" width="125" />
        <el-table-column prop="overdueDays" label="逾期天数" width="95" />
      </el-table>
      <el-pagination v-if="drilldownTotal > drilldownSize" v-model:current-page="drilldownPage" :page-size="drilldownSize" :total="drilldownTotal" layout="prev, pager, next, total" @current-change="loadDrilldown" />
    </section>
    <template v-if="data">
      <section class="analysis-cards" aria-label="核心指标">
        <div><span>车间承诺准交率</span><button class="analysis-link metric" @click="openDrilldown('onTime','按车间交期交付')">{{ percent(data.totals.onTimeRate) }} ↗</button><small>按期 {{ data.totals.onTime }} / <button class="analysis-link" @click="openDrilldown('comparable','有可比交期的已交付工单')">可比较 {{ data.totals.comparable }} 单 ↗</button></small></div>
        <div><span>业务员要求达成率</span><button class="analysis-link metric" @click="openDrilldown('requestedOnTime','按业务员要求交付')">{{ percent(data.totals.requestedOnTimeRate) }} ↗</button><small>按要求 {{ data.totals.requestedOnTime }} / <button class="analysis-link" @click="openDrilldown('requestedComparable','有有效业务员要求的已交付工单')">有效要求 {{ data.totals.requestedComparable }} 单 ↗</button></small></div>
        <div class="warning"><span>未交付且已超车间交期</span><button class="analysis-link metric" @click="openDrilldown('openOverdue','未交付逾期')">{{ data.totals.openOverdue }} 单 ↗</button><small>另有 3 天内到期 <button class="analysis-link" @click="openDrilldown('openDueSoon','3 天内到期')">{{ data.totals.openDueSoon }} 单 ↗</button></small></div>
        <div><span>已交付逾期</span><button class="analysis-link metric" @click="openDrilldown('overdue','已交付逾期')">{{ data.totals.overdue }} 单 ↗</button><small>平均晚 {{ number(data.totals.averageOverdueDays) }} 天</small></div>
      </section>
      <p class="analysis-definition">准交率只比较已交付且有有效交期的工单；尚未交付、停止跟单和缺交期均不进入分母。交付日期以跟单员登记的入库节点为准，未登记时采用关联采购首次入库日期；补录或延后入库可能影响判断。</p>
      <p v-if="dateBasis === 'active' && toDate && toDate < (data?.asOf || '')" class="analysis-definition">历史期间筛选的是当时覆盖的工单；下方“已交付、逾期、未交付”等结果仍按截至 {{ data?.asOf }} 的最新记录计算，并非该期间截止日的历史快照。</p>
      <section class="analysis-section">
        <h2>任务状态与数据覆盖</h2>
        <div class="analysis-facts">
          <button v-for="fact in coverageFacts" :key="fact.bucket" type="button" @click="openDrilldown(fact.bucket,fact.label)">{{ fact.label }} {{ fact.count }} 单 ↗</button>
        </div>
      </section>
      <section class="analysis-section">
        <h2>车间对比</h2>
        <el-table :data="data.workshops" stripe empty-text="暂无工单">
          <el-table-column prop="name" label="车间" min-width="170"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('total',`${row.name}全部工单`,{workshop:row.name})">{{ row.name }} ↗</button></template></el-table-column>
          <el-table-column label="工单" width="85"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('total',`${row.name}全部工单`,{workshop:row.name})">{{ row.total }} ↗</button></template></el-table-column>
          <el-table-column label="承诺准交" min-width="160"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('onTime',`${row.name}按期交付`,{workshop:row.name})">{{ percent(row.onTimeRate) }} ({{ row.onTime }}/{{ row.comparable }}) ↗</button></template></el-table-column>
          <el-table-column label="业务员要求达成" min-width="160"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('requestedOnTime',`${row.name}按业务员要求交付`,{workshop:row.name})">{{ percent(row.requestedOnTimeRate) }} ({{ row.requestedOnTime }}/{{ row.requestedComparable }}) ↗</button></template></el-table-column>
          <el-table-column label="协商延后" width="100"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('extended',`${row.name}协商延后`,{workshop:row.name})">{{ row.extended }} ↗</button></template></el-table-column>
          <el-table-column label="未交付逾期" width="115"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('openOverdue',`${row.name}未交付逾期`,{workshop:row.name})">{{ row.openOverdue }} ↗</button></template></el-table-column>
          <el-table-column label="已交付逾期" width="115"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('overdue',`${row.name}已交付逾期`,{workshop:row.name})">{{ row.overdue }} ↗</button></template></el-table-column>
        </el-table>
      </section>
      <section class="analysis-section">
        <h2>提交月份构成</h2><p>所选范围内的工单按原提交月份拆分，可看到上月延续到本月的工单；近期月份的准交率只代表已交付部分。</p>
        <el-table :data="data.months" stripe empty-text="暂无工单">
          <el-table-column label="提交月份" width="120"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('total',`${row.name}提交工单`,{month:row.name})">{{ row.name }} ↗</button></template></el-table-column>
          <el-table-column label="工单" width="85"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('total',`${row.name}提交工单`,{month:row.name})">{{ row.total }} ↗</button></template></el-table-column>
          <el-table-column label="已交付" width="85"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('delivered',`${row.name}已交付`,{month:row.name})">{{ row.delivered }} ↗</button></template></el-table-column>
          <el-table-column label="未交付" width="85"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('open',`${row.name}未交付`,{month:row.name})">{{ row.open }} ↗</button></template></el-table-column>
          <el-table-column label="承诺准交" min-width="145"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('onTime',`${row.name}按期交付`,{month:row.name})">{{ percent(row.onTimeRate) }} ({{ row.onTime }}/{{ row.comparable }}) ↗</button></template></el-table-column>
          <el-table-column label="协商延后" width="100"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('extended',`${row.name}协商延后`,{month:row.name})">{{ row.extended }} ↗</button></template></el-table-column>
          <el-table-column label="当前逾期" width="100"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('openOverdue',`${row.name}当前逾期`,{month:row.name})">{{ row.openOverdue }} ↗</button></template></el-table-column>
        </el-table>
      </section>
      <div class="analysis-two-columns">
        <section class="analysis-section"><h2>协商改期</h2><p>与原车间交期（无车间交期时用有效业务员要求日）比较。</p><div class="analysis-facts"><button v-for="fact in extensionFacts" :key="fact.bucket" @click="openDrilldown(fact.bucket,fact.label)">{{ fact.label }} {{ fact.count }} {{ fact.unit }} ↗</button></div><h3>记录的延后原因</h3><ReasonList :items="data.extensionReasons" @select="reason => openDrilldown('extensionReason',`延后原因：${reason}`,{reason})" /><h3>风险类别</h3><ReasonList :items="riskItems(data.riskCategories)" @select="reason => openDrilldown('riskCategory',`风险类别：${riskNames[reason] || reason}`,{reason})" /></section>
        <section class="analysis-section"><h2>实际逾期</h2><p>只统计实际入库交付日晚于车间当前承诺日的已交付工单。</p><div class="analysis-facts"><button v-for="fact in overdueFacts" :key="fact.bucket" @click="openDrilldown(fact.bucket,fact.label)">{{ fact.label }} {{ fact.count }} 单 ↗</button></div><h3>核实记录中的原因原文</h3><ReasonList :items="data.overdueReasons" @select="reason => openDrilldown('overdueReason',`逾期原因：${reason}`,{reason})" /></section>
      </div>
      <p class="analysis-definition">业务员要求日期采用当前导入源值；如果源单曾被改写，现有数据不能还原首次填写日期。原因按记录原文完全相同的内容合并，未自动猜测归类；风险类别来自跟单状态。单张工单可能同时有协商延期与最终逾期，两组数量不能相加。当前统计按工单数量，未按台数加权，也不把销售出库当作生产交付。</p>
    </template>
  </main>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { fetchTrackingAnalytics, fetchTrackingAnalyticsOrders, type TrackingAnalytics, type TrackingAnalyticsOrder, type TrackingAnalysisDateBasis } from '@/api/productionTracking'
import ReasonList from '@/components/production/TrackingAnalysisReasonList.vue'

const props=defineProps<{ modelValue: boolean }>()
const emit=defineEmits<{ 'update:modelValue': [value: boolean]; 'open-order': [id: number] }>()
const fromDate=ref(''),toDate=ref(''),workshop=ref('')
const dateBasis=ref<TrackingAnalysisDateBasis>('active')
const datePlaceholder=(edge:'起'|'止')=>`${dateBasis.value==='submitted'?'提交':dateBasis.value==='inbound'?'入库':'期间'}时间${edge}`
const dateBasisHint=computed(()=>({
  active:'期间在制：包含开始日之前提交、在该期间仍未入库或期间内入库的延续工单；开始日前已交付或停止的单不计入。',
  inbound:'入库时间：优先使用跟单员登记的入库节点日期；未登记时才使用采购首次入库日期。未入库工单不进入这一范围。',
  submitted:'提交时间：只按生产任务单提交日期纳入工单，早于该期间提交的延续工单不会出现。'
})[dateBasis.value])
const workshopOptions=ref<string[]>([])
const data=ref<TrackingAnalytics | null>(null)
const loading=ref(false),error=ref('')
type Drilldown={bucket:string;label:string;reason?:string;month?:string;workshop?:string}
const drilldown=ref<Drilldown|null>(null)
const drilldownPanel=ref<HTMLElement|null>(null)
const drilldownItems=ref<TrackingAnalyticsOrder[]>([])
const drilldownTotal=ref(0),drilldownPage=ref(1),drilldownSize=30,drilldownLoading=ref(false)
let drilldownRequest=0
const percent=(value:number|null)=>value===null?'样本不足':`${value.toFixed(1)}%`
const number=(value:number|null)=>value===null?'—':String(value)
const riskNames:Record<string,string>={NONE:'无风险类别',MATERIAL:'物料',DESIGN:'设计',CAPACITY:'产能',CUSTOMER:'客户',OTHER:'其他',未记录:'未记录'}
const riskItems=(items:{name:string;count:number}[])=>items.map(item=>({...item,value:item.name,name:riskNames[item.name] || item.name}))
const coverageFacts=computed(()=>{const t=data.value?.totals;return t?[
  {bucket:'total',label:'范围内',count:t.total},{bucket:'delivered',label:'已交付',count:t.delivered},
  {bucket:'open',label:'未交付',count:t.open},{bucket:'stopped',label:'停止跟单',count:t.stopped},
  {bucket:'deliveredNoBaseline',label:'已交付缺基准',count:t.deliveredNoBaseline},
  {bucket:'openNoBaseline',label:'未交付缺基准',count:t.openNoBaseline},
  {bucket:'receiptOnly',label:'只见采购入库',count:t.receiptOnly},
  {bucket:'manualOnly',label:'只见跟单入库',count:t.manualOnly},
  {bucket:'bothSources',label:'两个来源都有',count:t.bothSources}
]:[]})
const extensionFacts=computed(()=>{const t=data.value?.totals;return t?[
  {bucket:'extended',label:'协商延后',count:t.extended,unit:'单'},
  {bucket:'extended',label:'累计延后',count:t.extensionDays,unit:'天'},
  {bucket:'advanced',label:'提前',count:t.advanced,unit:'单'},
  {bucket:'extensionReasonMissing',label:'延后原因未记',count:t.extensionReasonMissing,unit:'单'}
]:[]})
const overdueFacts=computed(()=>{const t=data.value?.totals;return t?[
  {bucket:'overdue1to3',label:'晚 1–3 天',count:t.overdue1to3},
  {bucket:'overdue4to7',label:'晚 4–7 天',count:t.overdue4to7},
  {bucket:'overdue8plus',label:'晚 8 天及以上',count:t.overdue8plus},
  {bucket:'overdueReasonMissing',label:'逾期原因未核实',count:t.overdueReasonMissing}
]:[]})
async function openDrilldown(bucket:string,label:string,extra:Partial<Drilldown>={}){
  drilldown.value={bucket,label,...extra}
  drilldownPage.value=1
  await loadDrilldown()
  await nextTick()
  drilldownPanel.value?.scrollIntoView({behavior:'smooth',block:'start'})
}
async function loadDrilldown(){
  if(!drilldown.value)return
  const request=++drilldownRequest
  drilldownLoading.value=true
  try{
    const result=await fetchTrackingAnalyticsOrders({fromDate:fromDate.value,toDate:toDate.value,dateBasis:dateBasis.value,
      workshop:drilldown.value.workshop || workshop.value,month:drilldown.value.month,
      bucket:drilldown.value.bucket,reason:drilldown.value.reason,page:drilldownPage.value,size:drilldownSize})
    if(request===drilldownRequest){drilldownItems.value=result.items;drilldownTotal.value=result.total}
  }catch(e){if(request===drilldownRequest)error.value=e instanceof Error?e.message:'工单清单加载失败'}
  finally{if(request===drilldownRequest)drilldownLoading.value=false}
}
function openDrilldownOrder(row:TrackingAnalyticsOrder){emit('open-order',row.id)}
async function load(){
  loading.value=true;error.value='';drilldown.value=null;++drilldownRequest
  try{
    const result=await fetchTrackingAnalytics({fromDate:fromDate.value,toDate:toDate.value,workshop:workshop.value,dateBasis:dateBasis.value})
    data.value=result
    if(!workshop.value)workshopOptions.value=result.workshops.map(row=>row.name || '').filter(Boolean)
  }catch(e){error.value=e instanceof Error?e.message:'分析数据加载失败'}
  finally{loading.value=false}
}
function reset(){fromDate.value='';toDate.value='';workshop.value='';dateBasis.value='active';void load()}
watch(() => props.modelValue, open => { if(open) void load() }, { immediate: true })
</script>

<style scoped>
.tracking-analysis{padding:8px 12px 28px;color:#263448}.analysis-head,.analysis-filter{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.analysis-head-actions{display:flex;gap:8px}.analysis-head h1{margin:8px 0;font-size:24px}.analysis-head p,.analysis-section p,.analysis-definition{color:#64748b;line-height:1.6}.analysis-filter{justify-content:flex-start;background:#f7f9fc;padding:16px;border:1px solid #e3e8ef;border-radius:10px;margin:20px 0}.analysis-filter .el-date-editor{width:170px}.analysis-filter .el-select{width:190px}.analysis-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.analysis-cards>div,.analysis-section{border:1px solid #e3e8ef;border-radius:12px;background:white;padding:18px}.analysis-cards>div{display:flex;flex-direction:column;gap:10px}.analysis-cards span{color:#64748b}.analysis-cards strong{font-size:27px;color:#127b71}.analysis-cards .warning strong{color:#c45620}.analysis-cards small,.analysis-section small{color:#64748b}.analysis-section{margin-top:18px}.analysis-section h2{font-size:18px;margin:0 0 10px}.analysis-section h3{font-size:15px;margin:20px 0 8px}.analysis-facts{display:flex;flex-wrap:wrap;gap:8px}.analysis-facts button{background:#f3f6fa;padding:8px 12px;border:0;border-radius:7px;cursor:pointer;color:#276477}.analysis-facts button:hover{background:#e7f5f3;color:#087c72}.analysis-link{border:0;background:none;padding:0;color:#087c72;cursor:pointer;font:inherit;text-align:left}.analysis-link:hover{text-decoration:underline}.analysis-link.metric{font-size:27px;font-weight:700}.analysis-cards .warning .analysis-link.metric{color:#c45620}.analysis-drilldown{border-color:#80c7bf;background:#fbfefd;scroll-margin-top:8px}.analysis-drilldown :deep(.el-table__row){cursor:pointer}.analysis-drilldown .el-pagination{margin-top:14px;justify-content:flex-end}.analysis-two-columns{display:grid;grid-template-columns:1fr 1fr;gap:18px}.analysis-definition{margin:16px 2px;font-size:13px}@media(max-width:900px){.analysis-cards{grid-template-columns:repeat(2,1fr)}.analysis-two-columns{grid-template-columns:1fr}}@media(max-width:560px){.analysis-cards{grid-template-columns:1fr}}
.analysis-filter .analysis-date-basis{width:225px}.analysis-filter-note{width:100%;margin:0;color:#64748b;font-size:13px;line-height:1.5}
</style>
