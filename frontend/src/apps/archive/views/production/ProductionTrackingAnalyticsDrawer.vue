<template>
  <el-drawer :model-value="modelValue" size="90%" :with-header="false" append-to-body @update:model-value="emit('update:modelValue', $event)">
  <main class="tracking-analysis" v-loading="loading">
    <header class="analysis-head">
      <div><h1>生产交期达成分析</h1><p>按当前有效车间预计完成日期归集任务。数据截至 {{ data?.asOf || '今日' }}。</p></div>
      <div class="analysis-head-actions"><el-button @click="load">刷新数据</el-button><el-button aria-label="关闭生产分析" @click="emit('update:modelValue', false)">关闭</el-button></div>
    </header>
    <section class="analysis-filter" aria-label="分析范围">
      <strong>预计完成日期范围</strong>
      <el-date-picker v-model="fromDate" type="date" value-format="YYYY-MM-DD" :placeholder="datePlaceholder('起')" clearable />
      <span>至</span>
      <el-date-picker v-model="toDate" type="date" value-format="YYYY-MM-DD" :placeholder="datePlaceholder('止')" clearable />
      <el-select v-model="workshop" clearable filterable placeholder="全部车间"><el-option v-for="name in workshopOptions" :key="name" :label="name" :value="name" /></el-select>
      <el-button type="primary" @click="load">分析</el-button>
      <el-button @click="reset">清空</el-button>
      <p class="analysis-filter-note">按当前有效预计日期统计；交期调整后任务会重新归入对应区间，历史区间数量可能变化。实际完成日期不影响归属；缺少车间预计日期的任务单独核实。</p>
    </section>
    <el-alert v-if="displayError" :title="displayError" type="error" :closable="false" />
    <div class="analysis-layout">
    <div class="analysis-main">
    <template v-if="data">
      <section class="analysis-cards" aria-label="核心指标">
        <div><span>应完成任务 <el-tooltip content="当前有效预计日期落在范围内，已取消或已停产任务不计入。恢复生产后重新纳入。"><span class="note-icon">ⓘ</span></el-tooltip></span><button class="analysis-link metric" @click="openDrilldown('dueTasks','应完成任务')">{{ data.totals.dueTasks }} 单 ↗</button><small>其中已到期 {{ data.totals.matured }} 单</small></div>
        <div><span>已完工 <el-tooltip content="跟单员确认入库节点优先；尚未确认时，仅同任务编号下入库品名和单位均唯一、累计数量达到计划数，才自动推定整单完工。混合品名或单位需核实。"><span class="note-icon">ⓘ</span></el-tooltip></span><button class="analysis-link metric" @click="openDrilldown('delivered','已完工')">{{ data.totals.delivered }} 单 ↗</button><small>完工率 {{ percent(data.totals.completionRate) }}</small></div>
        <div><span>未完工 <el-tooltip content="尚无整单完工依据；首次或部分采购入库不算整单完工。"><span class="note-icon">ⓘ</span></el-tooltip></span><button class="analysis-link metric" @click="openDrilldown('open','未完工')">{{ data.totals.open }} 单 ↗</button><small>其中已超预计日期 {{ data.totals.openOverdue }} 单</small></div>
        <div><span>按期完工 <el-tooltip content="整单完工日期不晚于当前有效预计日期。按期达成率＝按期完工 ÷ 应完成任务。"><span class="note-icon">ⓘ</span></el-tooltip></span><button class="analysis-link metric" @click="openDrilldown('onTime','按期完工')">{{ data.totals.onTime }} 单 ↗</button><small>按期达成率 {{ percent(data.totals.onTimeAchievementRate) }}</small></div>
        <div class="warning"><span>逾期完工 <el-tooltip content="整单完工日期晚于当前有效预计日期；未完工不会计入。"><span class="note-icon">ⓘ</span></el-tooltip></span><button class="analysis-link metric" @click="openDrilldown('overdue','逾期完工')">{{ data.totals.overdue }} 单 ↗</button><small>仅计已经完工的任务</small></div>
      </section>
      <p class="analysis-definition">完工率＝（按期完工＋逾期完工）÷应完成任务；按期达成率＝按期完工÷应完成任务；已完工准交率＝按期完工÷已完工任务（当前 {{ percent(data.totals.completedOnTimeRate) }}）。三种状态互斥，应完成＝按期＋逾期＋未完工。</p>
      <p v-if="!toDate || toDate > data.asOf" class="analysis-definition">所选范围包含未来交期，当前统计是进度快照，不能直接当作最终考核结果。</p>
      <section class="analysis-section">
        <h2>进度与数据核实</h2>
        <div class="analysis-facts">
          <button v-for="fact in coverageFacts" :key="fact.bucket" type="button" @click="openDrilldown(fact.bucket,fact.label)">{{ fact.label }} {{ fact.count }} 单 ↗</button>
        </div>
      </section>
      <section class="analysis-section">
        <h2>车间对比</h2>
        <p>各车间按当前有效预计完成日期归集；应完成＝按期＋逾期＋未完工。点击数字可查看对应任务。</p>
        <div class="workshop-chart" aria-label="各车间任务状态分布">
          <div v-for="row in data.workshops" :key="row.name" class="chart-row">
            <span>{{ row.name }}</span>
            <div class="chart-track"><b :style="{width:barWidth(row.onTime,row.dueTasks)}" /><em :style="{width:barWidth(row.overdue,row.dueTasks)}" /><i :style="{width:barWidth(row.open,row.dueTasks)}" /></div>
            <small>{{ row.dueTasks }} 单</small>
          </div>
          <p>■ 按期完工　<span>■ 逾期完工</span>　<em>■ 未完工</em></p>
        </div>
        <el-table :data="data.workshops" stripe empty-text="暂无工单">
          <el-table-column prop="name" label="车间" min-width="170"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('dueTasks',`${row.name}应完成任务`,{workshop:row.name})">{{ row.name }} ↗</button></template></el-table-column>
          <el-table-column label="应完成" width="95"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('dueTasks',`${row.name}应完成`,{workshop:row.name})">{{ row.dueTasks }} ↗</button></template></el-table-column>
          <el-table-column label="按期" width="85"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('onTime',`${row.name}按期完工`,{workshop:row.name})">{{ row.onTime }} ↗</button></template></el-table-column>
          <el-table-column label="逾期" width="85"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('overdue',`${row.name}逾期完工`,{workshop:row.name})">{{ row.overdue }} ↗</button></template></el-table-column>
          <el-table-column label="未完工" width="95"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('open',`${row.name}未完工`,{workshop:row.name})">{{ row.open }} ↗</button></template></el-table-column>
          <el-table-column label="完工率" width="105"><template #default="{ row }">{{ percent(row.completionRate) }}</template></el-table-column>
        </el-table>
      </section>
      <section class="analysis-section">
        <h2>预计完成月份</h2><p>按当前有效预计完成月份拆分；延期调整后随新月份移动。下钻与月份统计使用同一日期。</p>
        <el-table :data="data.months" stripe empty-text="暂无工单">
          <el-table-column label="预计完成月份" width="130"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('dueTasks',`${row.name}应完成`,{month:row.name})">{{ row.name }} ↗</button></template></el-table-column>
          <el-table-column label="应完成" width="95"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('dueTasks',`${row.name}应完成`,{month:row.name})">{{ row.dueTasks }} ↗</button></template></el-table-column>
          <el-table-column label="按期" width="85"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('onTime',`${row.name}按期完工`,{month:row.name})">{{ row.onTime }} ↗</button></template></el-table-column>
          <el-table-column label="逾期" width="85"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('overdue',`${row.name}逾期完工`,{month:row.name})">{{ row.overdue }} ↗</button></template></el-table-column>
          <el-table-column label="未完工" width="95"><template #default="{ row }"><button class="analysis-link" @click="openDrilldown('open',`${row.name}未完工`,{month:row.name})">{{ row.open }} ↗</button></template></el-table-column>
          <el-table-column label="完工率" width="105"><template #default="{ row }">{{ percent(row.completionRate) }}</template></el-table-column>
        </el-table>
      </section>
      <div class="analysis-two-columns">
        <section class="analysis-section">
          <div class="section-head">
            <h2>协商改期<el-tooltip placement="top" content="与源任务的原车间预计日期比较；没有原车间预计日期时暂不判定改期。"><span class="note-icon" tabindex="0">ⓘ</span></el-tooltip></h2>
            <div class="analysis-facts"><button v-for="fact in extensionFacts" :key="fact.bucket" @click="openDrilldown(fact.bucket,fact.label)">{{ fact.label }} {{ fact.count }} {{ fact.unit }} ↗<small v-if="fact.note">（{{ fact.note }}）</small></button></div>
          </div>
          <h3>记录的延后原因</h3><ReasonList :items="data.extensionReasons" @select="reason => openDrilldown('extensionReason',`延后原因：${reason}`,{reason})" /><h3>风险类别</h3><ReasonList :items="riskItems(data.riskCategories)" @select="reason => openDrilldown('riskCategory',`风险类别：${riskNames[reason] || reason}`,{reason})" />
        </section>
        <section class="analysis-section">
          <div class="section-head">
            <h2>逾期完工<el-tooltip placement="top" content="只统计整单完工日晚于当前有效车间预计日期的任务。"><span class="note-icon" tabindex="0">ⓘ</span></el-tooltip></h2>
            <div class="analysis-facts"><button v-for="fact in overdueFacts" :key="fact.bucket" @click="openDrilldown(fact.bucket,fact.label)">{{ fact.label }} {{ fact.count }} 单 ↗</button></div>
          </div>
          <h3>核实记录中的原因原文</h3><ReasonList :items="data.overdueReasons" @select="reason => openDrilldown('overdueReason',`逾期原因：${reason}`,{reason})" />
        </section>
      </div>
      <p class="analysis-definition">已确认取消 {{ data.totals.cancelled }} 单、<button class="analysis-link" @click="openDrilldown('stopped','已停产')">已停产 {{ data.totals.stopped }} 单 ↗</button>，均暂不计入正常履约；恢复生产后重新纳入。同权限、同车间另有 <button class="analysis-link" @click="openDrilldown('missingExpected','缺少车间预计日期')">{{ data.missingExpected }} 单缺少车间预计日期 ↗</button>，未纳入任何日期区间，需核实。未完工中：未到期 {{ data.totals.openNotDue }} 单、今天到期 {{ data.totals.openDueToday }} 单、已超期 {{ data.totals.openOverdue }} 单。当前统计按任务单数量，不按台数加权。</p>
    </template>
    </div>
    <aside class="analysis-drill-col">
      <TrackingAnalyticsOrdersTable v-if="ddActive && drillState"
        :items="ddItems" :total="ddTotal" :page="ddPage" :size="ddSize" :loading="ddLoading"
        :title="`${drillState.label} · 对应工单 ${ddTotal} 单`"
        @update:page="onPageChange" @open-order="id => emit('open-order', id)" @close="closeDrilldown">
        <template #extra>
          <el-table-column prop="submittedDate" label="提交日期" width="115" />
          <el-table-column prop="baseline" label="当前预计" width="115" />
          <el-table-column prop="deliveredDate" label="整单完工日" width="125" />
          <el-table-column label="状态" width="115"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
          <el-table-column label="完工逾期" width="95"><template #default="{ row }">{{ row.overdueDays == null ? '—' : `${row.overdueDays} 天` }}</template></el-table-column>
          <el-table-column label="未完工超期" width="110"><template #default="{ row }">{{ row.unfinishedOverdueDays == null ? '—' : `${row.unfinishedOverdueDays} 天` }}</template></el-table-column>
        </template>
      </TrackingAnalyticsOrdersTable>
      <TrackingDrillEmpty v-else />
    </aside>
    </div>
  </main>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchTrackingAnalytics, fetchTrackingAnalyticsOrders, type TrackingAnalytics, type TrackingAnalyticsOrder, type TrackingAnalysisDateBasis } from '@/api/productionTracking'
import ReasonList from '@/components/production/TrackingAnalysisReasonList.vue'
import TrackingAnalyticsOrdersTable from '@/components/production/TrackingAnalyticsOrdersTable.vue'
import TrackingDrillEmpty from '@/components/production/TrackingDrillEmpty.vue'
import { useTrackingDrilldown } from '@/composables/tracking/useTrackingDrilldown'
// percent / barWidth / statusLabel 与周复盘 / 节点分析共用同一份口径（见 utils/tracking/format.ts）。
import { percent, barWidth, statusLabel } from '@/utils/tracking/format'

const props=defineProps<{ modelValue: boolean }>()
const emit=defineEmits<{ 'update:modelValue': [value: boolean]; 'open-order': [id: number] }>()
const fromDate=ref(''),toDate=ref(''),workshop=ref('')
const dateBasis=ref<TrackingAnalysisDateBasis>('baseline')
const datePlaceholder=(edge:'起'|'止')=>({
  submitted:'提交',inbound:'入库',active:'期间',baseline:'预计完成'
})[dateBasis.value]+'时间'+edge
const workshopOptions=ref<string[]>([])
const data=ref<TrackingAnalytics | null>(null)
const loading=ref(false),error=ref('')
type Drilldown={bucket:string;label:string;reason?:string;month?:string;workshop?:string}
const drillState=ref<Drilldown|null>(null)
const { active: ddActive, items: ddItems, total: ddTotal, page: ddPage, size: ddSize,
  loading: ddLoading, error: ddError, open, close, load: drillLoad, scrollIntoView } = useTrackingDrilldown<TrackingAnalyticsOrder>()
const displayError=computed(()=> error.value || ddError.value)
const riskNames:Record<string,string>={NONE:'无风险类别',MATERIAL:'物料',DESIGN:'设计',CAPACITY:'产能',CUSTOMER:'客户',OTHER:'其他',未记录:'未记录'}
const riskItems=(items:{name:string;count:number}[])=>items.map(item=>({...item,value:item.name,name:riskNames[item.name] || item.name}))
const coverageFacts=computed(()=>{const t=data.value?.totals;return t?[
  {bucket:'matured',label:'已到期任务',count:t.matured},
  {bucket:'openOverdue',label:'超期未完工',count:t.openOverdue},
  {bucket:'openDueToday',label:'今天到期未完工',count:t.openDueToday},
  {bucket:'openNotDue',label:'未到期未完工',count:t.openNotDue},
  {bucket:'cancelled',label:'已确认取消',count:t.cancelled},
  {bucket:'stopped',label:'已停产',count:t.stopped}
]:[]})
/**
 * 「累计延后 N 天」原先是**独立卡片**，但它显示的是天数（`extensionDays`），
 * 点进去下钻出来的却是延后过的单数（`extended`）—— 量纲本就不同，卡片与下钻必然对不上。
 * 现改为同一张卡的**附注行**：主数字＝延后单数＝下钻条数，天数只作展示、不可点。
 */
const extensionFacts=computed(()=>{const t=data.value?.totals;return t?[
  {bucket:'extended',label:'协商延后',count:t.extended,unit:'单',note:`累计延后 ${t.extensionDays} 天`},
  {bucket:'advanced',label:'提前',count:t.advanced,unit:'单'},
  {bucket:'extensionReasonMissing',label:'延后原因未记',count:t.extensionReasonMissing,unit:'单'}
]:[]})
const overdueFacts=computed(()=>{const t=data.value?.totals;return t?[
  {bucket:'overdue1to3',label:'晚 1–3 天',count:t.overdue1to3},
  {bucket:'overdue4to7',label:'晚 4–7 天',count:t.overdue4to7},
  {bucket:'overdue8plus',label:'晚 8 天及以上',count:t.overdue8plus},
  {bucket:'overdueReasonMissing',label:'逾期原因未核实',count:t.overdueReasonMissing}
]:[]})
/** 下钻请求：读取当前 drillState，分页共用同一份 loader。 */
function drillLoader(page:number,size:number){
  const d=drillState.value
  return fetchTrackingAnalyticsOrders({fromDate:fromDate.value,toDate:toDate.value,dateBasis:dateBasis.value,
    workshop:d?.workshop||workshop.value,month:d?.month,bucket:d?.bucket ?? '',reason:d?.reason,page,size})
}
function openDrilldown(bucket:string,label:string,extra:Partial<Drilldown>={}){
  drillState.value={bucket,label,...extra}
  open()
  void drillLoad(drillLoader)
  void scrollIntoView()
}
function onPageChange(p:number){ ddPage.value=p; void drillLoad(drillLoader) }
function closeDrilldown(){ drillState.value=null; close() }
async function load(){
  loading.value=true;error.value='';drillState.value=null;close()
  try{
    const result=await fetchTrackingAnalytics({fromDate:fromDate.value,toDate:toDate.value,workshop:workshop.value,dateBasis:dateBasis.value})
    data.value=result
    if(!workshop.value)workshopOptions.value=result.workshops.map(row=>row.name || '').filter(Boolean)
  }catch(e){error.value=e instanceof Error?e.message:'分析数据加载失败'}
  finally{loading.value=false}
}
function reset(){fromDate.value='';toDate.value='';workshop.value='';dateBasis.value='baseline';void load()}
watch(() => props.modelValue, open => { if(open) void load() }, { immediate: true })
</script>

<style scoped>
.tracking-analysis{padding:8px 12px 28px;color:#263448}.analysis-head,.analysis-filter{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.analysis-head-actions{display:flex;gap:8px}.analysis-head h1{margin:8px 0;font-size:24px}.analysis-head p,.analysis-section p,.analysis-definition{color:#64748b;line-height:1.6}.analysis-filter{justify-content:flex-start;background:#f7f9fc;padding:16px;border:1px solid #e3e8ef;border-radius:10px;margin:20px 0}.analysis-filter .el-date-editor{width:170px}.analysis-filter .el-select{width:190px}.analysis-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.analysis-cards>div,.analysis-section{border:1px solid #e3e8ef;border-radius:12px;background:white;padding:18px}.analysis-cards>div{display:flex;flex-direction:column;gap:10px}.analysis-cards span{color:#64748b}.analysis-cards strong{font-size:27px;color:#127b71}.analysis-cards .warning strong{color:#c45620}.analysis-cards small,.analysis-section small{color:#64748b}.analysis-section{margin-top:18px}.analysis-section h2{font-size:18px;margin:0 0 10px}.analysis-section h3{font-size:15px;margin:20px 0 8px}.analysis-facts{display:flex;flex-wrap:wrap;gap:8px}.analysis-facts button{background:#f3f6fa;padding:8px 12px;border:0;border-radius:7px;cursor:pointer;color:#276477}.analysis-facts button:hover{background:#e7f5f3;color:#087c72}
/* 分区头：标题在左，下钻按钮靠右；说明文字收进标题旁的 ⓘ，悬停显示。 */
.section-head{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:10px}
.section-head h2{margin:0;display:flex;align-items:center;gap:6px}
.section-head .analysis-facts{margin-left:auto}
.note-icon{display:inline-flex;align-items:center;justify-content:center;width:16px;height:16px;border:1px solid #94a3b8;border-radius:50%;font-size:11px;font-style:normal;color:#64748b;cursor:help;line-height:1;user-select:none}
.note-icon:hover{color:#087c72;border-color:#087c72}.analysis-link{border:0;background:none;padding:0;color:#087c72;cursor:pointer;font:inherit;text-align:left}.analysis-link:hover{text-decoration:underline}.analysis-link.metric{font-size:27px;font-weight:700}.analysis-cards .warning .analysis-link.metric{color:#c45620}.analysis-two-columns{display:grid;grid-template-columns:1fr 1fr;gap:18px}.analysis-definition{margin:16px 2px;font-size:13px}@media(max-width:900px){.analysis-cards{grid-template-columns:repeat(2,1fr)}.analysis-two-columns{grid-template-columns:1fr}}@media(max-width:560px){.analysis-cards{grid-template-columns:1fr}}
.analysis-filter .analysis-date-basis{width:225px}.analysis-filter-note{width:100%;margin:0;color:#64748b;font-size:13px;line-height:1.5}
/* 两栏：左分析内容、右下钻列表 */
.analysis-layout{display:grid;grid-template-columns:minmax(0,5fr) minmax(0,7fr);gap:18px;align-items:start}
.analysis-main{min-width:0}
.analysis-drill-col{min-width:0;align-self:start}
@media(max-width:1000px){.analysis-layout{grid-template-columns:1fr}}
.analysis-cards{grid-template-columns:repeat(6,minmax(0,1fr))}.analysis-cards>div:first-child{grid-column:span 2;background:#f0f9f7;border-color:#b9ded6}.analysis-cards>div:first-child .analysis-link.metric{font-size:32px}.workshop-chart{margin:10px 0 16px;padding:12px 14px;background:#f8fafb;border-radius:9px}.chart-row{display:grid;grid-template-columns:150px 1fr 55px;gap:10px;align-items:center;margin:9px 0;font-size:13px}.chart-track{display:flex;height:13px;border-radius:8px;background:#e9eef1;overflow:hidden}.chart-track b{background:#319c81}.chart-track em{background:#db9869}.chart-track i{background:#a7b4c1}.workshop-chart p{margin:10px 0 0;color:#319c81;font-size:12px}.workshop-chart p span{color:#db9869}.workshop-chart p em{color:#a7b4c1;font-style:normal}@media(max-width:1100px){.analysis-cards{grid-template-columns:repeat(2,minmax(0,1fr))}.analysis-cards>div:first-child{grid-column:span 2}}@media(max-width:700px){.chart-row{grid-template-columns:100px 1fr 45px}}@media(max-width:480px){.analysis-cards{grid-template-columns:1fr}.analysis-cards>div:first-child{grid-column:span 1}}
</style>
