/**
 * 生产跟单**详情抽屉**（打开/保存跟进/延期/处置/时间核实/采购入库/历史对照）。
 *
 * 【为什么单独一个模块】这是页面上最大的一块，也是唯一一处「可编辑的本地副本」：
 * 抽屉打开时把后端节点表深拷一份 `milestones`，用户改的是副本，`originalStages` 存快照，
 * 保存时才把副本提交回去；历史对照（跟单条目 / 时间核实 / 节点回放）也全在这份副本上算。
 * 搬出来后，这份「副本 + 快照 + 回放」的生命周期收敛在一个作用域里，页面只保留模板绑定。
 *
 * 【边界】模块与页面只有两个交点：
 * - `items()`：抽屉底部「保存并下一单」要在当前列表里找下一行（页面传 `items`）；
 * - `reload()`：任何写入（保存跟进 / 延期 / 处置 / 时间核实）之后刷新列表。
 *
 * 节点/日期的判据不在这里写第二份，全部取自 `@/utils/tracking/rules` 与 `@/utils/trackingDateGuard`。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addTrackingEntry, addTrackingStageEntry, addTrackingTimeReview, changeTrackingClosure,
  changeTrackingDisposition, updateTrackingTimeReview,
  fetchTrackingDetail, fetchTrackingPurchaseReceiptDetail, fetchTrackingPurchaseReceipts
} from '@/api/productionTracking'
import type {
  TrackingEntry, TrackingMilestone, TrackingOperation, TrackingOrder, TrackingPurchaseReceipt,
  TrackingTimeReview, TrackingTimeReviewKind, TrackingTimeReviewStatus
} from '@/api/productionTracking'
import type { LedgerDetailResult } from '@/types/h3Import'
import { formatTime, deadlineOf, reviewKindLabel, reviewStatusLabel, riskText, stageRelevant, stageStatusText, stageValidationError } from '@/utils/tracking/rules'
import { daysBetween } from '@/utils/trackingDateGuard'
import { todayKey } from '@/utils/tracking/today'

/** 顺序核实备注的统一前缀：`gapStages` 靠它判断「漏登记的节点是否已书面说明」。 */
const STAGE_SEQUENCE_REVIEW_PREFIX = '顺序核实：'
const STATE_FIELDS: Array<{ key: string, label: string }> = [
  { key: 'plannedStartDate', label: '计划开工' },
  { key: 'forecastFinishDate', label: '车间预计完成调整' },
  { key: 'nextFollowupDate', label: '下次跟进' },
  { key: 'assigneeName', label: '跟单责任人' },
  { key: 'riskCategory', label: '风险分类' },
  { key: 'deliveryResult', label: '交付结果' },
  { key: 'shipmentStatus', label: '发货状态' },
  { key: 'delayReason', label: '延期原因' },
  { key: 'remark', label: '跟单备注' },
  { key: 'stoppedAt', label: '停止跟单时间' },
  { key: 'stoppedReason', label: '停止跟单原因' },
  { key: 'closedAt', label: '结案时间' },
  { key: 'closedNote', label: '结案说明' }
]
interface EntrySnapshotMilestone { code?: string | null, status?: string | null, completedDate?: string | null, note?: string | null, manualReferenceDate?: string | null, manualReferenceReason?: string | null }
interface EntrySnapshot { state?: Record<string, unknown> | null, milestones?: EntrySnapshotMilestone[] | null }
interface DiffRow { label: string, from: string, to: string }
interface TimeHistoryItem { key: string; time: string; by: string; title: string; lines: string[] }
interface StageHistoryItem { version: number, time: string, by: string, text: string }

/**
 * ⚠️ 快照节点键归一（唯一入口）：后端 add() 把 SQL 列名原样写进 JSON
 * （stage_code/stage_status/completed_date，snake_case），表格导入写的却是
 * camelCase（code/status/completedDate）。不归一的话 code/status 全读成
 * undefined——节点历史整条被跳过、变更对照只剩 note（两种写法恰好同名），
 * 就是「节点·undefined」的来源。
 */
function snapshotStage(raw: EntrySnapshotMilestone): EntrySnapshotMilestone {
  const row = raw as unknown as Record<string, unknown>
  const val = (...keys: string[]) => {
    for (const key of keys) if (row[key] !== null && row[key] !== undefined) return row[key]
    return undefined
  }
  return {
    code: val('code', 'stage_code') as string | undefined,
    status: val('status', 'stage_status') as string | undefined,
    completedDate: val('completedDate', 'completed_date') as string | undefined,
    note: val('note') as string | undefined,
    manualReferenceDate: val('manualReferenceDate', 'manual_reference_date') as string | undefined,
    manualReferenceReason: val('manualReferenceReason', 'manual_reference_reason') as string | undefined
  }
}
function parseSnapshot(raw?: string | null): EntrySnapshot | null {
  if (!raw) return null
  try { return JSON.parse(raw) as EntrySnapshot } catch { return null }
}
function normalizeValue(value: unknown) { return value === null || value === undefined ? '' : String(value).trim() }
/** 历史快照里同一字段存在 camelCase（手工/工作簿）与 snake_case（早期版本）两种写法，都要认。 */
function pickField(source: Record<string, unknown> | null, key: string) {
  if (!source) return undefined
  if (key in source) return source[key]
  const snake = key.replace(/[A-Z]/g, ch => `_${ch.toLowerCase()}`)
  return snake in source ? source[snake] : undefined
}
function displayStateValue(key: string, value: unknown) {
  if (normalizeValue(value) === '') return '未填'
  if (key === 'riskCategory') return riskText(String(value))
  return String(value)
}
function snapshotStageStatus(stage: EntrySnapshotMilestone) {
  return stage.status || (stage.completedDate ? 'DONE' : 'PENDING')
}
function snapshotStageText(stage: EntrySnapshotMilestone) {
  const label = stageStatusText(snapshotStageStatus(stage) as TrackingMilestone['status'])
  return stage.completedDate ? `${label} ${stage.completedDate}` : label
}

export function useTrackingDetail(options: {
  /** 「保存并下一单」要在当前列表里找下一行（页面传 `items`）。 */
  items: () => TrackingOrder[]
  /** 任何写入之后刷新列表（页面传 `load`）。 */
  reload: () => Promise<unknown>
}) {
  const drawer = ref(false)
  const nextTrackingOrder = computed(() => {
    const index = options.items().findIndex(row => row.id === detail.value?.order.id)
    return index >= 0 ? options.items()[index + 1] || null : null
  })
  const purchaseLinksLoading = ref(false)
  const purchaseReceipts = ref<TrackingPurchaseReceipt[]>([])
  const purchaseChooserVisible = ref(false)
  const purchaseDetailVisible = ref(false)
  const purchaseDetailLoading = ref(false)
  const purchaseDetailData = ref<LedgerDetailResult | null>(null)
  const purchaseDetailError = ref('')
  const purchaseDetailId = ref<number | null>(null)
  const detail = ref<{ order: TrackingOrder; milestones: TrackingMilestone[]; operations: TrackingOperation[]; entries: TrackingEntry[]; timeReviews: TrackingTimeReview[]; timeReviewHistory: TrackingTimeReview[]; stageTotal?: number } | null>(null)
  /** 抽屉里的**可编辑副本**：打开详情时从后端节点表深拷一份，保存时才提交回去。 */
  const milestones = ref<TrackingMilestone[]>([])
  const previousStageStatuses = ref<Record<string, TrackingMilestone['status']>>({})
  const requiredTotal = computed(() => {
    const total = detail.value?.stageTotal
    if (typeof total === 'number' && total > 0) return total
    return milestones.value.filter(stageRelevant).length
  })
  const doneStages = computed(() => milestones.value.filter(stage => stageRelevant(stage) && stage.status === 'DONE').length)
  /** 销售去向从已完成的跟单节点或已发生的采购入库开始展示；未保存的节点编辑不提前触发。 */
  const showSalesTrace = computed(() => {
    if (!detail.value) return false
    if (Number(detail.value.order.inboundCount) > 0 || Boolean(detail.value.order.inboundDate)) return true
    const savedStages = detail.value.milestones.filter(stageRelevant)
    return savedStages.length > 0 && savedStages.every(stage => stage.status === 'DONE')
  })
  const gapStages = computed(() => {
    // Only persisted notes acknowledge a gap; an unsaved draft must not dismiss the reminder.
    const relevant = (detail.value?.milestones || []).filter(stageRelevant)
    let lastDone = -1
    relevant.forEach((stage, index) => { if (stage.status === 'DONE') lastDone = index })
    if (lastDone <= 0) return []
    return relevant.slice(0, lastDone).filter(stage => stage.status !== 'DONE'
      && !stage.note?.trim().startsWith(STAGE_SEQUENCE_REVIEW_PREFIX))
  })
  const stageGapLabels = computed(() => gapStages.value.map(stage => stage.label))
  /**
   * 提交给后端的节点：不适用的按需工序必须剔除——提交成「未开始」会在库里生成里程碑行，
   * 反而让该单多出一个永远完不成的节点（后端分母含已登记的按需工序）。
   */
  const submittableStages = computed(() => milestones.value.filter(stage =>
    stageRelevant(stage) || stage.status === 'DONE' || stage.status === 'IN_PROGRESS'))
  const stagePercent = computed(() => requiredTotal.value ? Math.round(doneStages.value / requiredTotal.value * 100) : 0)
  const currentStageIndex = computed(() => {
    const index = milestones.value.findIndex(stage => stageRelevant(stage) && stage.status !== 'DONE')
    return index === -1 ? milestones.value.length : index
  })
  const currentStage = computed(() => milestones.value[currentStageIndex.value] || null)
  const referencePanels = ref<string[]>([])
  const expandedEntries = ref(new Set<number>())

  const timeReviewVisible = ref(false)
  const timeReviewSaving = ref(false)
  /** 正在改的那条记录的 id：null＝新增一条，有值＝改这条（只许改最新一条，见后端 updateTimeReview 说明）。 */
  const timeReviewEditingId = ref<number | null>(null)
  const timeReviewForm = ref<{ kind: TrackingTimeReviewKind; status: TrackingTimeReviewStatus; reason: string; contactName: string; agreedDate: string | null; nextFollowupDate: string | null }>({ kind: 'ACCEPTANCE', status: 'OPEN', reason: '', contactName: '', agreedDate: null, nextFollowupDate: null })
  /**
   * @param previous 传入则在**编辑模式**下打开：表单预填这条记录的原文与状态，保存走 update。
   *
   * 过去这里一律把 reason 清空 ⇒ 用户想改一个错字也只能再录一遍，历史里堆出「入库延迟」+「入库延迟7天」这类
   * 同义记录（10-20 真库有 7 单是这种形态），而统计与导出只认最新一条，旧写法照样污染原文榜。
   */
  function openTimeReview(kind: TrackingTimeReviewKind, previous?: TrackingTimeReview) {
    const order = detail.value?.order
    const negotiated = kind === 'COMMITMENT' && ((daysBetween(order?.dueDate, order?.expectedFinishDate) ?? 0) > 0)
    timeReviewEditingId.value = previous?.id ?? null
    timeReviewForm.value = {
      kind,
      status: previous?.status ?? (kind === 'COMMITMENT' ? (negotiated ? 'NEGOTIATED' : 'ON_TIME') : 'OPEN'),
      reason: previous?.reason || '',
      contactName: previous?.contactName || '',
      agreedDate: previous?.agreedDate || (negotiated ? order?.expectedFinishDate || null : null),
      nextFollowupDate: previous?.nextFollowupDate || null
    }
    timeReviewVisible.value = true
  }
  async function saveTimeReview() {
    if (!detail.value) return
    const form = timeReviewForm.value
    if (!form.reason.trim()) { ElMessage.warning('请填写原因或核实结果'); return }
    if (form.status === 'NEGOTIATED' && !form.agreedDate) { ElMessage.warning('请填写协商后的交期'); return }
    timeReviewSaving.value = true
    const payload = { ...form, reason: form.reason.trim(), contactName: form.contactName.trim() || null, agreedDate: form.status === 'NEGOTIATED' ? form.agreedDate : null }
    try {
      if (timeReviewEditingId.value) await updateTrackingTimeReview(detail.value.order.id, timeReviewEditingId.value, payload)
      else await addTrackingTimeReview(detail.value.order.id, payload)
      timeReviewVisible.value = false
      ElMessage.success(timeReviewEditingId.value ? '核实记录已更新' : '时间核实记录已保存')
      timeReviewEditingId.value = null
      await openDetail(detail.value.order)
      await options.reload()
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存时间核实记录失败') }
    finally { timeReviewSaving.value = false }
  }
  const deadlineDate = computed(() => {
    const order = detail.value?.order
    return order ? deadlineOf(order) : null
  })
  const opDone = computed(() => detail.value?.operations.filter(op => op.status === 'COMPLETED').length || 0)
  const saving = ref(false)
  const dispositionVisible = ref(false)
  const dispositionStopped = ref(true)
  const dispositionReason = ref('')
  const dispositionSaving = ref(false)
  function openDisposition(stopped: boolean) {
    if (!detail.value || hasUnsavedStageChanges()) {
      ElMessage.warning('请先保存或撤销未保存的节点修改')
      return
    }
    dispositionStopped.value = stopped
    dispositionReason.value = ''
    dispositionVisible.value = true
  }
  async function saveDisposition() {
    const order = detail.value?.order
    const reason = dispositionReason.value.trim()
    if (!order || !reason || dispositionSaving.value) return
    dispositionSaving.value = true
    try {
      await changeTrackingDisposition(order.id, order.version, dispositionStopped.value, reason)
      dispositionVisible.value = false
      ElMessage.success(dispositionStopped.value ? '已停止生产跟单，原任务单及历史保留' : '已恢复生产跟单')
      await openDetail(order)
      await options.reload()
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存处置失败') }
    finally { dispositionSaving.value = false }
  }
  /** 跟单结案确认：与「标记不再生产」互斥的本地处置，状态按「结案优先」展示为已完成。 */
  const closureVisible = ref(false)
  const closureClosing = ref(true)
  const closureNote = ref('')
  const closureSaving = ref(false)
  function openClosure(closing: boolean) {
    if (!detail.value || hasUnsavedStageChanges()) {
      ElMessage.warning('请先保存或撤销未保存的节点修改')
      return
    }
    if (closing && detail.value.order.closedAt) { ElMessage.warning('该任务已结案'); return }
    if (!closing && !detail.value.order.closedAt) { ElMessage.warning('该任务未结案'); return }
    closureClosing.value = closing
    closureNote.value = ''
    closureVisible.value = true
  }
  async function saveClosure() {
    const order = detail.value?.order
    const note = closureNote.value.trim()
    if (!order || !note || closureSaving.value) return
    // 前端先做一道最弱校验（最少 6 字），与服务端一致；后端会再次校验，未登记交付的工单强制写明。
    if (closureClosing.value && !order.deliveredDate && note.length < 6) {
      ElMessage.warning('该任务尚未登记交付，结案说明请写明实际情况与核实结果（不少于 6 字）')
      return
    }
    closureSaving.value = true
    try {
      await changeTrackingClosure(order.id, order.version, closureClosing.value, note)
      closureVisible.value = false
      ElMessage.success(closureClosing.value ? '已确认结案，状态按「结案优先」展示为已完成' : '已撤销结案，状态恢复按节点进度派生')
      await openDetail(order)
      await options.reload()
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存结案失败') }
    finally { closureSaving.value = false }
  }
  /** 打开抽屉时的节点快照，用于判断「保存跟进」是否真的推进了节点。 */
  const originalStages = ref<TrackingMilestone[]>([])
  const delayDialog = ref(false)
  const delaySaving = ref(false)
  const delayDraftDate = ref<string | null>(null)
  const delayDraftReason = ref('')

  async function openDetail(row: TrackingOrder) {
    try {
      detail.value = await fetchTrackingDetail(row.id)
      milestones.value = detail.value.milestones.map(stage => ({ ...stage }))
      originalStages.value = milestones.value.map(stage => ({ ...stage }))
      previousStageStatuses.value = Object.fromEntries(milestones.value.map(stage => [stage.code, stage.status]))
      drawer.value = true
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载任务详情失败') }
  }
  async function openPurchaseReceipts() {
    if (!detail.value || purchaseLinksLoading.value) return
    purchaseLinksLoading.value = true
    try {
      const orderId = detail.value.order.id
      const receipts = await fetchTrackingPurchaseReceipts(orderId)
      if (detail.value?.order.id !== orderId) return
      purchaseReceipts.value = receipts
      if (receipts.length === 1) await openPurchaseDetail(receipts[0]!)
      else if (receipts.length > 1) purchaseChooserVisible.value = true
      else ElMessage.info('该生产工单暂无关联的采购入库单')
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '查询采购入库单失败') }
    finally { purchaseLinksLoading.value = false }
  }
  async function openPurchaseDetail(receipt: TrackingPurchaseReceipt) {
    const orderId = detail.value?.order.id
    if (!orderId) return
    purchaseChooserVisible.value = false
    purchaseDetailId.value = receipt.id
    purchaseDetailData.value = null
    purchaseDetailError.value = ''
    purchaseDetailLoading.value = true
    purchaseDetailVisible.value = true
    try {
      const result = await fetchTrackingPurchaseReceiptDetail(orderId, receipt.id)
      if (purchaseDetailId.value === receipt.id && detail.value?.order.id === orderId) purchaseDetailData.value = result
    }
    catch (error) { purchaseDetailError.value = error instanceof Error ? error.message : '加载采购入库详情失败' }
    finally { purchaseDetailLoading.value = false }
  }
  function hasUnsavedStageChanges() {
    return milestones.value.some((stage, index) => {
      const before = originalStages.value[index]
      return !before || before.status !== stage.status || before.completedDate !== stage.completedDate || (before.note || '') !== (stage.note || '')
        || (before.manualReferenceDate || '') !== (stage.manualReferenceDate || '')
        || (before.manualReferenceReason || '') !== (stage.manualReferenceReason || '')
    })
  }
  const unsavedStageCount = computed(() => pendingStageChanges.value.size)
  function quickStartCurrentStage() {
    const stage = currentStage.value
    if (!stage || stage.status !== 'PENDING') return
    stage.status = 'IN_PROGRESS'
    stage.completedDate = null
    previousStageStatuses.value[stage.code] = stage.status
  }
  function quickCompleteCurrentStage() {
    const stage = currentStage.value
    if (!stage) return
    stage.status = 'DONE'
    stage.completedDate = todayKey()
    stage.manualReferenceDate = null
    stage.manualReferenceReason = null
    previousStageStatuses.value[stage.code] = stage.status
  }
  function resetStageDraft() {
    milestones.value = originalStages.value.map(stage => ({ ...stage }))
    previousStageStatuses.value = Object.fromEntries(milestones.value.map(stage => [stage.code, stage.status]))
  }
  async function recordStageSequenceReason(stage: TrackingMilestone) {
    try {
      const result = await ElMessageBox.prompt(`说明「${stage.label}」为何尚未登记完成，而后续节点已有完成记录。`, '核实节点顺序', {
        inputType: 'textarea', inputValue: stage.note?.startsWith(STAGE_SEQUENCE_REVIEW_PREFIX)
          ? stage.note.slice(STAGE_SEQUENCE_REVIEW_PREFIX.length) : '',
        inputPlaceholder: '例如：该工单不经过此节点；或现场已完成但完成日期待核实',
        inputValidator: value => Boolean(value?.trim()) && value.trim().length <= 500 - STAGE_SEQUENCE_REVIEW_PREFIX.length
          || '请填写原因，最多 495 字'
      })
      stage.note = `${STAGE_SEQUENCE_REVIEW_PREFIX}${result.value.trim()}`
      ElMessage.info('已填入节点备注，请点击底部“保存跟单”后生效')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '填写核实说明失败')
    }
  }
  function openDelayDialog() {
    if (!detail.value) return
    delayDraftDate.value = detail.value.order.forecastFinishDate || null
    delayDraftReason.value = detail.value.order.delayReason || ''
    delayDialog.value = true
  }
  async function saveDelay() {
    if (!detail.value) return
    const baseline = deadlineOf(detail.value.order)
    if (!baseline) { ElMessage.warning('车间预计完成和业务员要求日期均未填写，请先核对源工单'); return }
    if (!delayDraftDate.value || delayDraftDate.value <= baseline) {
      ElMessage.warning('延期完成日期必须晚于车间预计完成日期'); return
    }
    if (!delayDraftReason.value.trim()) { ElMessage.warning('请填写延期原因与协商结果'); return }
    if (hasUnsavedStageChanges()) { ElMessage.warning('节点有未保存的修改，请先保存节点进度'); return }
    if (delayDraftDate.value === detail.value.order.forecastFinishDate
      && delayDraftReason.value.trim() === (detail.value.order.delayReason || '').trim()) {
      ElMessage.warning('延期日期和原因没有变化'); return
    }
    delaySaving.value = true
    try {
      const order = detail.value.order
      await addTrackingEntry(order.id, order.version, [], delayDraftDate.value, delayDraftReason.value.trim())
      delayDialog.value = false
      ElMessage.success('延期完成日期已登记')
      await openDetail(order)
      await options.reload()
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存延期失败，请刷新任务后重试') }
    finally { delaySaving.value = false }
  }
  async function save() {
    if (!detail.value) return false
    if (!hasUnsavedStageChanges()) { ElMessage.warning('还没有节点进度的变更'); return false }
    const validationError = stageValidationError(submittableStages.value)
    if (validationError) { ElMessage.warning(validationError); return false }
    if (submittableStages.value.some(stage => stage.manualReferenceDate && !stage.manualReferenceReason?.trim())) {
      ElMessage.warning('填写人工预计日期时请说明原因'); return false
    }
    saving.value = true
    try {
      const id = detail.value.order.id
      await addTrackingStageEntry(id, detail.value.order.version, submittableStages.value)
      ElMessage.success('跟进已记录')
      await openDetail(detail.value.order)
      await options.reload()
      return true
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败，请刷新任务后重试'); return false }
    finally { saving.value = false }
  }
  async function saveAndNext() {
    const next = nextTrackingOrder.value
    if (!next) return
    if (hasUnsavedStageChanges() && !await save()) return
    await openDetail(next)
  }

  function diffOf(entry: TrackingEntry): DiffRow[] {
    const before = parseSnapshot(entry.beforeJson)
    const after = parseSnapshot(entry.afterJson)
    if (!before || !after) return []
    // 手工跟进写入 {state,milestones}；表格导入把同样的字段放在根级，两种形状都要认。
    const beforeState = (before.state || before) as Record<string, unknown>
    const afterState = (after.state || after) as Record<string, unknown>
    const rows: DiffRow[] = []
    for (const { key, label } of STATE_FIELDS) {
      const from = pickField(beforeState, key)
      const to = pickField(afterState, key)
      if (normalizeValue(from) !== normalizeValue(to)) rows.push({ label, from: displayStateValue(key, from), to: displayStateValue(key, to) })
    }
    const beforeStages = new Map((before.milestones || []).map(raw => { const stage = snapshotStage(raw); return [stage.code, stage] }))
    for (const raw of after.milestones || []) {
      const stage = snapshotStage(raw)
      const previous = beforeStages.get(stage.code)
      const untouched = !stage.status && !stage.completedDate
      if (!previous && untouched) continue
      const name = milestones.value.find(item => item.code === stage.code)?.label || stage.code || '未知节点'
      const label = `节点·${name}`
      if (!previous) {
        rows.push({ label, from: '未记录', to: snapshotStageText(stage) })
        if (normalizeValue(stage.note)) rows.push({ label: `${label}备注`, from: '未填', to: stage.note || '未填' })
        continue
      }
      if (snapshotStageStatus(previous) !== snapshotStageStatus(stage) || normalizeValue(previous.completedDate) !== normalizeValue(stage.completedDate)) {
        rows.push({ label, from: snapshotStageText(previous), to: snapshotStageText(stage) })
      }
      if (normalizeValue(previous.note) !== normalizeValue(stage.note)) {
        rows.push({ label: `${label}备注`, from: previous.note || '未填', to: stage.note || '未填' })
      }
    }
    return rows
  }
  const entryDiffs = computed(() => {
    const map = new Map<number, DiffRow[]>()
    for (const entry of detail.value?.entries || []) map.set(entry.id, diffOf(entry))
    return map
  })
  function timeReviewSourceContext(review: TrackingTimeReview): string[] {
    const pairs: Array<[string, string | null | undefined]> = []
    switch (review.kind) {
      case 'SOURCE_DATE': pairs.push(['申请', review.sourceApplicationDate], ['业务员要求', review.sourceDueDate], ['任务单提交', review.sourceCreatedAt]); break
      case 'ACCEPTANCE': pairs.push(['申请', review.sourceApplicationDate], ['排产', review.sourceScheduleTime]); break
      case 'COMMITMENT': pairs.push(['业务员要求', review.sourceDueDate], ['车间原预计', review.sourceExpectedDate]); break
      case 'REPORTING': pairs.push(['车间原预计', review.sourceExpectedDate], ['报工', review.sourceReportDate], ['入库', review.sourceInboundDate]); break
      case 'WAREHOUSING': pairs.push(['报工', review.sourceReportDate], ['入库', review.sourceInboundDate]); break
      case 'OVERDUE': pairs.push(['交付基准', review.sourceExpectedDate], ['实际交付', review.sourceInboundDate]); break
    }
    const values = pairs.filter(([, value]) => Boolean(value)).map(([label, value]) => `${label} ${value}`)
    return values.length ? [`记录时的时间：${values.join(' · ')}`] : []
  }
  /** 本地交期调整来自带前后快照的跟单条目；异常原因来自只追加的时间核实记录。 */
  const timeHistoryItems = computed<TimeHistoryItem[]>(() => {
    if (!detail.value) return []
    const items: TimeHistoryItem[] = []
    for (const entry of detail.value.entries) {
      const changes = (entryDiffs.value.get(entry.id) || []).filter(row =>
        row.label === '车间预计完成调整' || row.label === '延期原因' || row.label === '计划开工')
      if (!changes.length) continue
      items.push({ key: `entry-${entry.id}`, time: formatTime(entry.createdAt), by: entry.createdByName,
        title: '车间时间变更', lines: changes.map(row => `${row.label}：${row.from} → ${row.to}`) })
    }
    for (const review of detail.value.timeReviewHistory || []) {
      items.push({ key: `review-${review.id}`, time: formatTime(review.createdAt), by: review.createdByName || '未知记录人',
        title: `${reviewKindLabel(review.kind)} · ${reviewStatusLabel(review.status)}`,
        lines: [review.reason,
          ...(review.contactName ? [`核实对象：${review.contactName}`] : []),
          ...(review.agreedDate ? [`协商交期：${review.agreedDate}`] : []),
          ...(review.nextFollowupDate ? [`下次跟进：${review.nextFollowupDate}`] : []),
          ...timeReviewSourceContext(review)] })
    }
    return items.sort((a, b) => b.time.localeCompare(a.time) || b.key.localeCompare(a.key))
  })
  function entryDiff(id: number) { return entryDiffs.value.get(id) || [] }
  function visibleDiff(id: number) {
    const rows = entryDiff(id)
    return expandedEntries.value.has(id) ? rows : rows.slice(0, 4)
  }
  function toggleEntry(id: number) {
    const next = new Set(expandedEntries.value)
    if (next.has(id)) next.delete(id); else next.add(id)
    expandedEntries.value = next
  }

  /** 每个节点的变更记录：按版本正序回放各次跟进的节点快照，状态/完成日期/备注任一变化记一条。 */
  const stageHistories = computed(() => {
    const map = new Map<string, StageHistoryItem[]>()
    if (!detail.value) return map
    for (const entry of [...detail.value.entries].sort((a, b) => a.version - b.version)) {
      const before = parseSnapshot(entry.beforeJson)
      const after = parseSnapshot(entry.afterJson)
      if (!after) continue
      const beforeStages = new Map((before?.milestones || []).map(raw => { const stage = snapshotStage(raw); return [stage.code, stage] }))
      for (const raw of after.milestones || []) {
        const stage = snapshotStage(raw)
        if (!stage.code) continue
        const prev = beforeStages.get(stage.code)
        const statusChanged = !prev
          || snapshotStageStatus(prev) !== snapshotStageStatus(stage)
          || normalizeValue(prev.completedDate) !== normalizeValue(stage.completedDate)
        const noteChanged = normalizeValue(prev?.note) !== normalizeValue(stage.note)
        const manualChanged = normalizeValue(prev?.manualReferenceDate) !== normalizeValue(stage.manualReferenceDate)
          || normalizeValue(prev?.manualReferenceReason) !== normalizeValue(stage.manualReferenceReason)
        if (prev && !statusChanged && !noteChanged && !manualChanged) continue
        const parts: string[] = []
        if (!prev) {
          parts.push(`首次记录：${snapshotStageText(stage)}`)
          if (normalizeValue(stage.note)) parts.push(`备注：${stage.note?.trim() || ''}`)
        } else {
          if (statusChanged) parts.push(`${snapshotStageText(prev)} → ${snapshotStageText(stage)}`)
          if (noteChanged) parts.push(`备注：${prev.note?.trim() || '未填'} → ${stage.note?.trim() || '未填'}`)
        }
        if (manualChanged) parts.push(stage.manualReferenceDate
          ? `人工预计：${stage.manualReferenceDate}（${stage.manualReferenceReason || '未说明'}）`
          : '已恢复系统参考日期')
        const list = map.get(stage.code) || []
        list.push({ version: entry.version, time: formatTime(entry.createdAt), by: entry.createdByName, text: parts.join('；') })
        map.set(stage.code, list)
      }
    }
    return map
  })
  function stageNodeHistory(code: string) { return stageHistories.value.get(code) || [] }
  /** 展开的节点记录（code 集合）；与 expandedEntries 同一套写法。 */
  const expandedStages = ref<Set<string>>(new Set())
  function toggleStage(code: string) {
    const next = new Set(expandedStages.value)
    if (next.has(code)) next.delete(code); else next.add(code)
    expandedStages.value = next
  }
  /**
   * 本次编辑会话里还没「保存跟进」的节点修改：与打开详情时的 originalStages 对比，
   * 状态/完成日期变化、备注变化实时拼成一行预览，保存后由 stageHistories 正式接手。
   */
  const pendingStageChanges = computed(() => {
    const map = new Map<string, string>()
    const beforeStages = new Map(originalStages.value.map(stage => [stage.code, stage]))
    for (const stage of milestones.value) {
      const prev = beforeStages.get(stage.code)
      if (!prev) continue
      const parts: string[] = []
      if (snapshotStageStatus(prev) !== snapshotStageStatus(stage) || normalizeValue(prev.completedDate) !== normalizeValue(stage.completedDate)) {
        parts.push(`${snapshotStageText(prev)} → ${snapshotStageText(stage)}`)
      }
      if (normalizeValue(prev.note) !== normalizeValue(stage.note)) {
        parts.push(`备注：${prev.note?.trim() || '未填'} → ${stage.note?.trim() || '未填'}`)
      }
      if (normalizeValue(prev.manualReferenceDate) !== normalizeValue(stage.manualReferenceDate)
        || normalizeValue(prev.manualReferenceReason) !== normalizeValue(stage.manualReferenceReason)) {
        parts.push(stage.manualReferenceDate
          ? `人工预计：${stage.manualReferenceDate}（${stage.manualReferenceReason || '未说明'}）`
          : '恢复系统参考日期')
      }
      if (parts.length) map.set(stage.code, parts.join('；'))
    }
    return map
  })
  function stageRecordCount(code: string) { return stageNodeHistory(code).length + (pendingStageChanges.value.get(code) ? 1 : 0) }
  async function onStageStatusChange(stage: TrackingMilestone) {
    const previous = previousStageStatuses.value[stage.code] || 'PENDING'
    if (previous === 'DONE' && stage.status !== 'DONE') {
      try {
        await ElMessageBox.confirm(`确认把「${stage.label}」从已完成回退为${stageStatusText(stage.status)}？原完成日期仍会保存在跟进历史中。`, '回退跟单节点', { type: 'warning' })
      } catch {
        stage.status = previous
        return
      }
    }
    if (stage.status === 'DONE' && !stage.completedDate) stage.completedDate = todayKey()
    if (stage.status === 'DONE') { stage.manualReferenceDate = null; stage.manualReferenceReason = null }
    if (stage.status !== 'DONE') stage.completedDate = null
    previousStageStatuses.value[stage.code] = stage.status
  }

  return {
    drawer, nextTrackingOrder, purchaseLinksLoading, purchaseReceipts, purchaseChooserVisible, purchaseDetailVisible,
    purchaseDetailLoading, purchaseDetailData, purchaseDetailError, purchaseDetailId,
    detail, milestones, previousStageStatuses, requiredTotal, doneStages, showSalesTrace, gapStages, stageGapLabels,
    submittableStages, stagePercent, currentStageIndex, currentStage, referencePanels, expandedEntries,
    timeReviewVisible, timeReviewSaving, timeReviewForm, timeReviewEditingId, deadlineDate, opDone, saving,
    dispositionVisible, dispositionStopped, dispositionReason, dispositionSaving,
    closureVisible, closureClosing, closureNote, closureSaving,
    originalStages, delayDialog, delaySaving, delayDraftDate, delayDraftReason, unsavedStageCount,
    entryDiff, visibleDiff, toggleEntry, entryDiffs, timeHistoryItems, stageHistories, stageNodeHistory,
    expandedStages, toggleStage, pendingStageChanges, stageRecordCount,
    openDetail, openPurchaseReceipts, openPurchaseDetail, hasUnsavedStageChanges, quickStartCurrentStage,
    quickCompleteCurrentStage, resetStageDraft, recordStageSequenceReason, openDelayDialog, saveDelay, save, saveAndNext,
    openTimeReview, saveTimeReview, openDisposition, saveDisposition, openClosure, saveClosure, onStageStatusChange
  }
}
