/**
 * 生产跟单**进度表导入的闭环**（上传预览 → 待处理 → 关联/跳过/归档 → 差异审核 → 归档规则）。
 *
 * 【为什么单独一个模块】这是页面上唯一一条「跨文件、跨会话」的链路：
 * 一份 Excel 进来后会在「待处理记录」里停留很久（等主管关联、等差异审核、按产品名自动归档），
 * 状态横跨 6 个弹窗与 3 张表。它与列表/详情没有共享状态 —— 唯一的交点只有「导入或审核落地后列表要重取」，
 * 所以搬出来后只留一个 `reload` 回调，模块内不引用页面的任何列表状态。
 *
 * 【边界】
 * - `reload()`：导入 / 关联 / 应用差异 / 忽略差异 之后由调用方（页面 `load`）刷新列表；
 * - 待处理条数（`workbookActionable`）由本模块自己拉轻量统计接口，页面 `load()` 只在可见范围为
 *   「全部」时调 `loadWorkbookSummary()`，其余情况写 0 —— 沿用改造前的口径。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  applyTrackingWorkbook, applyTrackingWorkbookReview, archiveTrackingWorkbookRow, batchResolveTrackingWorkbookRows,
  fetchTrackingCandidates, fetchTrackingWorkbookArchiveRules, fetchTrackingWorkbookCandidates, fetchTrackingWorkbookReview,
  fetchTrackingWorkbookRows, fetchTrackingWorkbookSummary, ignoreTrackingWorkbookReview, previewTrackingWorkbook,
  resolveTrackingWorkbookRow, revokeTrackingWorkbookArchiveRule
} from '@/api/productionTracking'
import type {
  TrackingWorkbookArchiveRule, TrackingWorkbookCandidate, TrackingWorkbookPreview, TrackingWorkbookReview, TrackingWorkbookRow
} from '@/api/productionTracking'
import { shortWorkshop } from '@/utils/tracking/rules'

export const WORKBOOK_STATUS_OPTIONS = [
  { value: 'READY', label: '可导入' },
  { value: 'IMPORTED', label: '已导入' },
  { value: 'UPDATED', label: '表格已更新' },
  { value: 'EXISTING', label: '已有跟单' },
  { value: 'REVIEWED', label: '主管已应用' },
  { value: 'REVIEW_IGNORED', label: '主管已保留' },
  { value: 'SKIPPED', label: '已跳过' },
  { value: 'ARCHIVED', label: '已归档' },
  { value: 'SUPERSEDED', label: '旧版已替代' }
]

export function useTrackingWorkbook(options: {
  /** 导入/关联/审核落地后刷新列表（页面传 `load`）。 */
  reload: () => Promise<unknown>
}) {
  const workbookDialog = ref(false)
  const workbookRows = ref<TrackingWorkbookRow[]>([])
  const workbookHistoryRows = ref<TrackingWorkbookRow[]>([])
  const workbookView = ref<'pending' | 'history'>('pending')
  const selectedWorkbookRow = ref<TrackingWorkbookRow | null>(null)
  const candidateDialog = ref(false)
  const candidateKeyword = ref('')
  const candidateLoading = ref(false)
  const candidateSearching = ref(false)
  const candidates = ref<TrackingWorkbookCandidate[]>([])
  /** 「表格待处理」按钮上的数字：走轻量统计接口，不再为了显示一个数拉整个待处理明细。 */
  const workbookActionable = ref(0)
  const selectedWorkbookIds = ref<number[]>([])
  const workbookTableRef = ref()
  const workbookSheetFilter = ref('')
  const workbookStatusFilter = ref('')
  const workbookKeyword = ref('')
  const archiveRules = ref<TrackingWorkbookArchiveRule[]>([])
  const archiveRuleDialog = ref(false)
  const workbookInput = ref<HTMLInputElement | null>(null)
  const importFile = ref<File | null>(null)
  const importPreview = ref<TrackingWorkbookPreview | null>(null)
  const importDialog = ref(false)
  const importSaving = ref(false)
  const reviewDialog = ref(false)
  const reviewSaving = ref(false)
  const activeReview = ref<TrackingWorkbookReview | null>(null)
  const selectedReviewKeys = ref<string[]>([])

  function showWorkbookRows() {
    workbookView.value = 'pending'
    workbookSheetFilter.value = ''
    workbookStatusFilter.value = ''
    workbookKeyword.value = ''
    selectedWorkbookIds.value = []
    workbookDialog.value = true
    void loadWorkbookRows()
  }
  async function loadWorkbookRows() {
    try {
      if (workbookView.value === 'history') workbookHistoryRows.value = await fetchTrackingWorkbookRows(true)
      else workbookRows.value = await fetchTrackingWorkbookRows()
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载导入记录失败') }
  }
  async function loadWorkbookSummary() {
    try { workbookActionable.value = (await fetchTrackingWorkbookSummary()).actionable }
    catch { workbookActionable.value = 0 }
  }
  async function onWorkbookViewChange() {
    selectedWorkbookIds.value = []
    await loadWorkbookRows()
  }
  const workbookSourceRows = computed(() => workbookView.value === 'pending' ? workbookRows.value : workbookHistoryRows.value)
  /** 弹窗内的筛选：车间 + 状态（历史页）+ 关键词，纯前端过滤，避免每次输入都打接口。 */
  const visibleWorkbookRows = computed(() => {
    const keyword = workbookKeyword.value.trim().toLowerCase()
    return workbookSourceRows.value.filter(row => {
      if (workbookSheetFilter.value && row.sheetName !== workbookSheetFilter.value) return false
      if (workbookView.value === 'history' && workbookStatusFilter.value && row.matchStatus !== workbookStatusFilter.value) return false
      if (keyword && !`${row.productName || ''} ${row.sourceOrderNo || ''}`.toLowerCase().includes(keyword)) return false
      return true
    })
  })
  const workbookSheets = computed(() => Array.from(new Set(workbookSourceRows.value.map(row => row.sheetName))))
  /** 「这批待处理都是什么」一眼可见，比逐行看车间列更快判断该整批归档还是逐行关联。 */
  const pendingSheetSummary = computed(() => {
    if (!visibleWorkbookRows.value.length) return '当前没有待处理行。'
    const buckets = new Map<string, number>()
    visibleWorkbookRows.value.forEach(row => buckets.set(row.sheetName, (buckets.get(row.sheetName) || 0) + 1))
    return `待处理分布：${Array.from(buckets.entries()).map(([sheet, count]) => `${shortWorkshop(sheet) || sheet} ${count} 行`).join('、')}`
  })
  function onWorkbookSelectionChange(rows: TrackingWorkbookRow[]) { selectedWorkbookIds.value = rows.map(row => row.id) }
  function workbookStatusLabel(status: TrackingWorkbookRow['matchStatus']) {
    return ({ PENDING: '待匹配', REVIEW: '差异待审核', IMPORTED: '已导入', UPDATED: '表格已更新', EXISTING: '已有跟单',
      REVIEWED: '主管已应用', REVIEW_IGNORED: '主管已保留', SKIPPED: '已跳过', ARCHIVED: '已归档',
      SUPERSEDED: '旧版已替代' })[status] || status
  }
  function workbookStatusTone(status: TrackingWorkbookRow['matchStatus']): 'success' | 'warning' | 'info' | 'primary' {
    if (status === 'PENDING' || status === 'REVIEW') return 'warning'
    if (status === 'IMPORTED' || status === 'UPDATED' || status === 'REVIEWED') return 'success'
    return 'info'
  }
  /** 批量「跳过 / 归档 / 恢复」：归档会按各行产品名建立规则，后续同名行导入即自动归档。 */
  async function batchWorkbook(action: 'skip' | 'archive' | 'restore') {
    if (!selectedWorkbookIds.value.length) return
    const count = selectedWorkbookIds.value.length
    const label = action === 'skip' ? '跳过' : action === 'archive' ? '归档' : '恢复为待处理'
    try {
      await ElMessageBox.confirm(`确认对选中的 ${count} 行执行「${label}」？${action === 'archive' ? '归档会按各自产品名建立规则，后续导入的同名行将自动归档、不再进入待处理。' : ''}`, `批量${label}`)
      const result = await batchResolveTrackingWorkbookRows(selectedWorkbookIds.value, action)
      ElMessage.success(`已${label} ${result.changed} 行`)
      selectedWorkbookIds.value = []
      workbookTableRef.value?.clearSelection()
      await loadWorkbookRows()
      await loadWorkbookSummary()
      if (action === 'archive') await loadArchiveRules()
    } catch (error) {
      if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '批量处理失败')
    }
  }
  /** 单行「归档此类」：归档当前行并按产品名建立规则。 */
  async function archiveRow(row: TrackingWorkbookRow) {
    try {
      const { value } = await ElMessageBox.prompt(
        `把「${row.productName || '该产品'}」归类为不对应氚云工单的表格行。后续导入的同名行会自动归档、不再进入待处理。请填写原因：`,
        '归档此类表格行',
        { inputValue: '非成品行（模具 / 配件 / 片膜等）', inputPlaceholder: '归档原因', confirmButtonText: '归档', cancelButtonText: '取消' }
      )
      const reason = (value || '').trim()
      if (!reason) { ElMessage.warning('请填写归档原因'); return }
      await archiveTrackingWorkbookRow(row.id, reason)
      ElMessage.success('已归档，并建立同名行的归档规则')
      await loadWorkbookRows()
      await loadWorkbookSummary()
      await loadArchiveRules()
    } catch (error) {
      if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '归档失败')
    }
  }
  async function restoreRow(row: TrackingWorkbookRow) {
    try {
      await batchResolveTrackingWorkbookRows([row.id], 'restore')
      if (row.matchStatus === 'ARCHIVED')
        ElMessage.warning('该产品名仍有归档规则，下次导入会自动归档；如需彻底恢复请在「归档规则」中撤销')
      else ElMessage.success('已恢复为待处理')
      await loadWorkbookRows()
      await loadWorkbookSummary()
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '恢复失败') }
  }
  async function loadArchiveRules() {
    try { archiveRules.value = await fetchTrackingWorkbookArchiveRules() }
    catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载归档规则失败') }
  }
  async function openArchiveRules() { await loadArchiveRules(); archiveRuleDialog.value = true }
  async function revokeArchiveRule(rule: TrackingWorkbookArchiveRule) {
    try {
      await ElMessageBox.confirm(`撤销「${rule.productLabel}」的归档规则，并把由它归档的行放回待处理？`, '撤销归档规则')
      const result = await revokeTrackingWorkbookArchiveRule(rule.id, true)
      ElMessage.success(`已撤销规则，恢复 ${result.restored} 行待处理`)
      await loadArchiveRules()
      await loadWorkbookRows()
      await loadWorkbookSummary()
    } catch (error) {
      if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '撤销失败')
    }
  }
  async function onWorkbookSelected(event: Event) {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    importFile.value = file
    importPreview.value = null
    try {
      importPreview.value = await previewTrackingWorkbook(file)
      importDialog.value = true
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '进度表预览失败') }
  }
  async function confirmWorkbookImport() {
    if (!importFile.value || !importPreview.value) return
    importSaving.value = true
    try {
      const result = await applyTrackingWorkbook(importFile.value, importPreview.value.sha256, importPreview.value.planHash)
      ElMessage.success(result.alreadyUploaded ? '这份文件已导入过' : `新导入 ${result.imported} 行，更新表格记录 ${result.updated} 行，${result.review} 行差异待审核，${result.pending} 行待匹配${result.archived ? `，${result.archived} 行按归档规则跳过` : ''}`)
      importDialog.value = false
      await options.reload()
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '导入失败') }
    finally { importSaving.value = false }
  }
  /** 打开关联窗口即按车间/产品/交期/订单号/数量自动推荐候选，不用主管先想关键词。 */
  async function showCandidates(source: TrackingWorkbookRow) {
    selectedWorkbookRow.value = source
    candidateKeyword.value = ''
    candidateSearching.value = false
    candidates.value = []
    candidateDialog.value = true
    candidateLoading.value = true
    try { candidates.value = await fetchTrackingWorkbookCandidates(source.id) }
    catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载候选工单失败') }
    finally { candidateLoading.value = false }
  }
  async function showReview(row: TrackingWorkbookRow) {
    try {
      activeReview.value = await fetchTrackingWorkbookReview(row.id)
      selectedReviewKeys.value = []
      reviewDialog.value = true
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载表格差异失败') }
  }
  async function applyReview() {
    if (!activeReview.value || !selectedReviewKeys.value.length) return
    reviewSaving.value = true
    try {
      await applyTrackingWorkbookReview(activeReview.value.rowId, activeReview.value.version, selectedReviewKeys.value)
      ElMessage.success('已应用所选表格差异，并记录审核历史')
      reviewDialog.value = false
      await options.reload()
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '差异应用失败，请重新核对') }
    finally { reviewSaving.value = false }
  }
  async function ignoreReview() {
    if (!activeReview.value) return
    reviewSaving.value = true
    try {
      await ignoreTrackingWorkbookReview(activeReview.value.rowId)
      ElMessage.success('已保留本地跟单记录')
      reviewDialog.value = false
      await options.reload()
    } catch (error) { ElMessage.error(error instanceof Error ? error.message : '处理失败') }
    finally { reviewSaving.value = false }
  }
  async function searchCandidates() {
    if (candidateKeyword.value.trim().length < 2) { ElMessage.warning('请输入至少 2 个字'); return }
    candidateSearching.value = true
    candidateLoading.value = true
    try { candidates.value = await fetchTrackingCandidates(candidateKeyword.value.trim(), selectedWorkbookRow.value?.id) }
    catch (error) { ElMessage.error(error instanceof Error ? error.message : '查找氚云工单失败') }
    finally { candidateLoading.value = false }
  }
  async function selectCandidate(candidate: TrackingWorkbookCandidate) {
    try {
      if (!selectedWorkbookRow.value) return
      await ElMessageBox.confirm(`将表格第 ${selectedWorkbookRow.value.sheetRow} 行关联到 ${candidate.orderNo}？若已有跟单，会进入逐项审核，不覆盖现有记录。`, '确认关联')
      await resolveTrackingWorkbookRow(selectedWorkbookRow.value.id, candidate.id)
      ElMessage.success('已关联工单；已有跟单的差异请在待处理记录中审核')
      candidateDialog.value = false
      workbookDialog.value = false
      await options.reload()
    } catch (error) {
      if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '操作失败')
    }
  }
  async function skipWorkbookRow(row: TrackingWorkbookRow) {
    try {
      await ElMessageBox.confirm(`确认跳过 ${row.sheetName} 第 ${row.sheetRow} 行？`, '跳过表格记录')
      await resolveTrackingWorkbookRow(row.id, null, true)
      await loadWorkbookRows()
      await loadWorkbookSummary()
    } catch (error) {
      if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '操作失败')
    }
  }

  return {
    WORKBOOK_STATUS_OPTIONS, workbookDialog, workbookRows, workbookHistoryRows, workbookView, selectedWorkbookRow,
    candidateDialog, candidateKeyword, candidateLoading, candidateSearching, candidates, workbookActionable,
    selectedWorkbookIds, workbookTableRef, workbookSheetFilter, workbookStatusFilter, workbookKeyword,
    archiveRules, archiveRuleDialog, workbookInput, importFile, importPreview, importDialog, importSaving,
    reviewDialog, reviewSaving, activeReview, selectedReviewKeys,
    workbookSourceRows, visibleWorkbookRows, workbookSheets, pendingSheetSummary,
    showWorkbookRows, loadWorkbookRows, loadWorkbookSummary, onWorkbookViewChange, onWorkbookSelectionChange,
    workbookStatusLabel, workbookStatusTone, batchWorkbook, archiveRow, restoreRow, loadArchiveRules,
    openArchiveRules, revokeArchiveRule, onWorkbookSelected, confirmWorkbookImport, showCandidates, showReview,
    applyReview, ignoreReview, searchCandidates, selectCandidate, skipWorkbookRow
  }
}
