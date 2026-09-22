<template>
  <div class="followup-page">
    <header class="followup-head">
      <div><h1>生产跟单</h1><p>查看已导入的生产任务，记录阶段日期、交期变化和每次跟进。</p></div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </header>
    <section class="followup-panel">
      <div class="followup-toolbar">
        <el-input v-model="keyword" clearable placeholder="完整工单号 / 产品 / 业务员" @keyup.enter="search" @clear="search" />
        <el-select v-model="workshop" clearable placeholder="全部车间" @change="search"><el-option v-for="w in workshops" :key="w" :label="w" :value="w" /></el-select>
        <el-select v-model="status" @change="search">
          <el-option label="未记录完工" value="active" /><el-option label="已记录完工" value="completed" />
          <el-option label="尚未跟单" value="untracked" /><el-option label="已取消" value="cancelled" /><el-option label="全部工单" value="all" />
        </el-select>
        <el-checkbox v-model="overdue" @change="search">超过原交期</el-checkbox>
        <el-button type="primary" @click="search">查询</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-table v-loading="loading" :data="items" stripe border>
        <el-table-column label="工单 / 产品" width="230" fixed>
          <template #default="{ row }"><el-button link type="primary" @click="open(row.id)">{{ row.workOrderNo || '未编号工单' }}</el-button><div>{{ row.productName || '未填写产品' }}</div><small>{{ [row.moldLayout, row.boxSize].filter(Boolean).join(' · ') }}</small></template>
        </el-table-column>
        <el-table-column prop="productionLine" label="车间" width="140" />
        <el-table-column prop="requiredQuantity" label="数量" width="70" />
        <el-table-column prop="salespersonName" label="业务员" width="95" />
        <el-table-column label="原要求完成 / 交期情况" width="180">
          <template #default="{ row }"><div>{{ row.requiredCompletionDate || '—' }}</div><small>{{ cancelled(row) ? '已取消' : deliveryText(row.requiredCompletionDate, row.state.completedDate) }}</small></template>
        </el-table-column>
        <el-table-column label="预计完成" width="135"><template #default="{ row }"><div>{{ row.state.expectedFinishDate || '—' }}</div><small v-if="!row.state.completedDate && !cancelled(row)">{{ countdown(row.state.expectedFinishDate) }}</small></template></el-table-column>
        <el-table-column v-for="stage in stageColumns" :key="stage" :label="stage" width="110"><template #default="{ row }">{{ row.stageNames.includes(stage) ? row.state.stages[stage] || '未记录' : '—' }}</template></el-table-column>
        <el-table-column label="实际生产完成" width="120"><template #default="{ row }">{{ row.state.completedDate || '未记录' }}</template></el-table-column>
        <el-table-column label="入库日期" width="115"><template #default="{ row }">{{ row.state.warehousedDate || '未记录' }}</template></el-table-column>
        <el-table-column label="发货日期" width="115"><template #default="{ row }">{{ row.state.shippedDate || '未记录' }}</template></el-table-column>
        <el-table-column label="延期原因" width="220" show-overflow-tooltip><template #default="{ row }">{{ row.state.delayReason || '—' }}</template></el-table-column>
        <el-table-column label="最近维护" width="170"><template #default="{ row }">{{ row.updatedByName || '尚未跟单' }}<small class="block">{{ row.updatedAt?.replace('T', ' ') }}</small></template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="open(row.id)">{{ canEdit ? '跟单' : '查看' }}</el-button></template></el-table-column>
      </el-table>
      <p class="followup-note">阶段日期由跟单人员记录；完工、入库、发货分别填写。首次打开时，预计和实际完成日期会带入来源工单值，请核对后保存。</p>
      <el-pagination v-model:current-page="page" :page-size="30" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </section>

    <el-drawer v-model="drawer" title="生产工单跟单" size="min(780px, 96vw)" :close-on-click-modal="false" :before-close="beforeClose" :close-on-press-escape="!saving" :show-close="!saving">
      <div v-loading="detailLoading">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
        <template v-if="detail && form">
          <h2>{{ detail.order.workOrderNo }} · {{ detail.order.productName }}</h2>
          <p>{{ [detail.order.productionLine, detail.order.moldLayout, detail.order.boxSize].filter(Boolean).join(' / ') }} · 数量 {{ detail.order.requiredQuantity ?? '—' }}</p>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="业务员">{{ detail.order.salespersonName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="原要求完成">{{ detail.order.requiredCompletionDate || '未设置' }}</el-descriptions-item>
            <el-descriptions-item label="来源状态">{{ detail.order.status || '—' }}</el-descriptions-item>
            <el-descriptions-item label="来源实际完成">{{ detail.order.actualCompletionDate || '未填写' }}</el-descriptions-item>
            <el-descriptions-item label="生产要求" :span="2">{{ detail.order.productionRequirement || '—' }}</el-descriptions-item>
          </el-descriptions>
          <el-alert v-if="detail.order.sourceRequiredCompletionDate !== detail.order.requiredCompletionDate" :title="`来源交期现为 ${detail.order.sourceRequiredCompletionDate || '未填写'}，本地仍保留首次跟单原交期。`" type="warning" :closable="false" />
          <el-alert v-if="!detail.order.stageNames.length" title="该车间尚无跟单阶段配置，可先记录交期和跟进说明。" type="info" :closable="false" />
          <el-form label-position="top" :disabled="!canEdit || saving" class="followup-form">
            <div class="followup-grid">
              <el-form-item label="计划开工"><el-date-picker v-model="form.plannedStartDate" value-format="YYYY-MM-DD" type="date" clearable /></el-form-item>
              <el-form-item label="预计完成"><el-date-picker v-model="form.expectedFinishDate" value-format="YYYY-MM-DD" type="date" clearable /></el-form-item>
              <el-form-item v-for="stage in detail.order.stageNames" :key="stage" :label="`${stage}完成日期`"><el-date-picker v-model="form.stages[stage]" value-format="YYYY-MM-DD" type="date" :disabled-date="future" clearable /></el-form-item>
              <el-form-item label="实际生产完成"><el-date-picker v-model="form.completedDate" value-format="YYYY-MM-DD" type="date" :disabled-date="future" clearable /></el-form-item>
              <el-form-item label="入库日期（跟单记录）"><el-date-picker v-model="form.warehousedDate" value-format="YYYY-MM-DD" type="date" :disabled-date="future" clearable /></el-form-item>
              <el-form-item label="发货日期（跟单记录）"><el-date-picker v-model="form.shippedDate" value-format="YYYY-MM-DD" type="date" :disabled-date="future" clearable /></el-form-item>
            </div>
            <el-form-item label="延期原因"><el-input v-model="form.delayReason" type="textarea" maxlength="300" show-word-limit /></el-form-item>
            <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="1000" show-word-limit /></el-form-item>
            <el-form-item v-if="canEdit" label="本次跟进 / 修改原因（必填）"><el-input v-model="content" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="记录当前进度、阻碍事项或修改原因" /></el-form-item>
            <el-button v-if="canEdit" type="primary" :loading="saving" @click="save">保存本次跟单</el-button>
          </el-form>
          <h3>跟进历史</h3>
          <p v-if="detail.logCount > detail.logs.length">共 {{ detail.logCount }} 条，当前展示最近 100 条。</p>
          <el-empty v-if="!detail.logs.length" description="尚无本地跟进记录" :image-size="70" />
          <el-timeline v-else>
            <el-timeline-item v-for="log in detail.logs" :key="log.id" :timestamp="`${log.createdAt.replace('T', ' ')} · ${log.createdByName}`">
              <p class="preserve">{{ log.content }}</p>
              <div v-for="change in followUpChanges(log.before, log.after)" :key="change" class="followup-change">{{ change }}</div>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { newRequestKey } from '@/api/manufacturing'
import { fetchFollowUps, fetchFollowUpWorkshops, fetchFollowUp, saveFollowUp, type FollowUpOrder, type FollowUpState, type FollowUpDetail } from '@/api/manufacturing/followup'
import { deliveryText, countdown, followUpChanges } from '@/utils/productionFollowup'

const { can } = useCurrentUser()
const route = useRoute()
const canEdit = computed(() => can('manufacturing.followup'))
const items = ref<FollowUpOrder[]>([]), workshops = ref<string[]>([])
const keyword = ref(''), workshop = ref(''), status = ref('active'), overdue = ref(false)
const page = ref(1), total = ref(0), loading = ref(false), error = ref('')
const drawer = ref(false), detailLoading = ref(false), saving = ref(false), detailError = ref('')
const detail = ref<FollowUpDetail | null>(null), form = ref<FollowUpState | null>(null), content = ref('')
let listSequence = 0, detailSequence = 0, original = '', requestKey = '', lastPayload = ''
const stageColumns = computed(() => [...new Set(items.value.flatMap(row => row.stageNames))])
const cancelled = (row: FollowUpOrder) => ['已取消', 'CANCELLED'].includes(row.status || '')
const future = (date: Date) => date.getTime() > new Date().setHours(23, 59, 59, 999)
async function load() {
  const sequence = ++listSequence
  loading.value = true; error.value = ''
  try {
    const result = await fetchFollowUps({ keyword: keyword.value, workshop: workshop.value, status: status.value, overdue: overdue.value, page: page.value, size: 30 })
    if (sequence === listSequence) { items.value = result.items; total.value = result.total }
  } catch { if (sequence === listSequence) { error.value = '跟单列表加载失败，请重试'; items.value = []; total.value = 0 } }
  finally { if (sequence === listSequence) loading.value = false }
}
function search() { page.value = 1; void load() }
function dirty() { return !!form.value && (JSON.stringify(form.value) !== original || !!content.value.trim()) }
async function allowDiscard() {
  if (!dirty()) return true
  try { await ElMessageBox.confirm('有尚未保存的跟单内容，确定放弃？', '离开跟单', { type: 'warning' }); return true } catch { return false }
}
async function beforeClose(done: () => void) { if (!saving.value && await allowDiscard()) { ++detailSequence; done() } }
async function open(id: number, skipDiscard = false) {
  if (saving.value || (!skipDiscard && drawer.value && !await allowDiscard())) return
  const sequence = ++detailSequence
  drawer.value = true; detailLoading.value = true; detailError.value = ''; detail.value = null; form.value = null
  try {
    const result = await fetchFollowUp(id)
    if (sequence !== detailSequence) return
    detail.value = result; form.value = JSON.parse(JSON.stringify(result.order.state)) as FollowUpState
    original = JSON.stringify(form.value); content.value = ''; requestKey = ''; lastPayload = ''
  } catch { if (sequence === detailSequence) detailError.value = '工单不存在、无权查看或加载失败，请关闭后重试' }
  finally { if (sequence === detailSequence) detailLoading.value = false }
}
async function save() {
  if (!detail.value || !form.value || saving.value) return
  if (!content.value.trim()) { ElMessage.warning('请填写本次跟进或修改原因'); return }
  const id = detail.value.order.id
  const payload = { version: detail.value.order.version, state: form.value, content: content.value.trim() }
  const serialized = JSON.stringify(payload)
  if (serialized !== lastPayload) { requestKey = newRequestKey(); lastPayload = serialized }
  saving.value = true; detailError.value = ''
  try {
    await saveFollowUp(id, payload, requestKey)
    original = JSON.stringify(form.value); content.value = ''
    ElMessage.success('跟单记录已保存'); saving.value = false
    await open(id, true); await load()
  } catch (error: unknown) {
    const message = error && typeof error === 'object' && 'message' in error ? String(error.message) : '请稍后重试'
    detailError.value = `保存失败：${message}。填写内容已保留。`
  }
  finally { saving.value = false }
}
watch(() => route.query.sourceId, value => { const id = Number(value); if (Number.isSafeInteger(id) && id > 0) void open(id) })
onBeforeRouteLeave(async () => !saving.value && await allowDiscard())
await Promise.all([load(), fetchFollowUpWorkshops().then(value => { workshops.value = value }).catch(() => { error.value = '车间选项加载失败，请刷新页面重试' })])
const initialId = Number(route.query.sourceId)
if (Number.isSafeInteger(initialId) && initialId > 0) await open(initialId)
</script>

<style scoped>
.followup-page { padding: var(--space-6, 24px); }
.followup-head { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 20px; }
.followup-head h1 { margin: 0; font-size: 24px; }
.followup-head p, .followup-note, small { color: var(--color-text-secondary); }
.followup-panel { padding: 16px; border: 1px solid var(--color-border); border-radius: var(--radius-lg, 12px); background: var(--color-bg-card); }
.followup-toolbar { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; align-items: center; }
.followup-toolbar .el-input { width: 260px; }.followup-toolbar .el-select { width: 165px; }
.followup-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.followup-form { margin: 20px 0; }.followup-form :deep(.el-date-editor) { width: 100%; }
.block { display: block; }.preserve, .followup-change { white-space: pre-wrap; overflow-wrap: anywhere; }
.followup-change { color: var(--color-text-secondary); margin: 4px 0; font-size: 13px; }
@media (max-width: 600px) { .followup-grid { grid-template-columns: 1fr; }.followup-page { padding: 12px; } }
</style>
