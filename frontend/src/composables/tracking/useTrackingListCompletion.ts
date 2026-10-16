/**
 * 生产跟单**列表批量记录节点完成**（勾选多行 → 指定节点 + 完成日期 → 逐单写入）。
 *
 * 【为什么单独一个模块】它是列表上唯一一个「批量写」入口，且有一条必须守住的时序：
 * 勾选发生在某一时刻，而写入时列表里的版本号可能已经变了 —— 所以**每单写入前先重新拉一次详情**，
 * 用当前 version + 当前节点状态做校验，而不是拿勾选时的快照去写（写旧版本会被后端拒绝或覆盖他人改动）。
 * 这条时序搬出来后不会再被别处「顺手改成直接用 row 上的 stageProgress」绕过。
 *
 * 【边界】只认传入的 `rows`，不持有列表状态；写完后由 `reload()` 刷新列表。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { addTrackingStageEntry, fetchTrackingDetail } from '@/api/productionTracking'
import type { TrackingOrder } from '@/api/productionTracking'
import { listCompletionTarget, stageRelevant, stageValidationError } from '@/utils/tracking/rules'
import { todayKey } from '@/utils/tracking/today'

export function useTrackingListCompletion(options: {
  /** 写完后刷新列表（页面传 `load`）。 */
  reload: () => Promise<unknown>
}) {
  const listCompletionVisible = ref(false)
  const listCompletionRows = ref<TrackingOrder[]>([])
  const listCompletionDate = ref(todayKey())
  const listCompletionSaving = ref(false)
  const listCompletionStageCode = ref<string | null>(null)
  const listCompletionNote = ref('')

  function openListCompletion(rows: TrackingOrder[], stageCode: string | null = null) {
    listCompletionStageCode.value = stageCode
    listCompletionNote.value = ''
    listCompletionRows.value = rows.filter(row => {
      const stage = listCompletionTarget(row, listCompletionStageCode.value)
      return Boolean(!row.stoppedAt && stage && stage.status !== 'DONE')
    })
    if (!listCompletionRows.value.length) return
    listCompletionDate.value = todayKey()
    listCompletionVisible.value = true
  }
  async function confirmListCompletion() {
    if (!listCompletionDate.value || listCompletionSaving.value) return
    listCompletionSaving.value = true
    let saved = 0
    const failed: string[] = []
    try {
      for (const row of listCompletionRows.value) {
        try {
          // Fetch each order immediately before writing: list versions may have changed since selection.
          const current = await fetchTrackingDetail(row.id)
          if (current.order.stoppedAt) { failed.push(`${row.orderNo}（已停止跟单）`); continue }
          const stages = current.milestones.filter(stageRelevant)
          const requested = listCompletionTarget(row, listCompletionStageCode.value)
          const stage = stages.find(item => item.code === requested?.code)
          if (!stage || stage.status === 'DONE' || !requested || requested.status === 'DONE') { failed.push(`${row.orderNo}（节点已变化）`); continue }
          const changed = stages.map(item => item.code === stage.code
            ? { ...item, status: 'DONE' as const, completedDate: listCompletionDate.value,
              note: listCompletionStageCode.value ? listCompletionNote.value.trim() || item.note : item.note } : item)
          const validationError = stageValidationError(changed)
          if (validationError) { failed.push(`${row.orderNo}（${validationError}）`); continue }
          await addTrackingStageEntry(row.id, current.order.version, changed)
          saved++
        } catch (error) { failed.push(`${row.orderNo}（${error instanceof Error ? error.message : '保存失败'}）`) }
      }
      if (saved || !listCompletionStageCode.value) listCompletionVisible.value = false
      if (saved) ElMessage.success(`已记录 ${saved} 单的节点完成，均已写入跟单历史`)
      if (failed.length) ElMessage.warning({ message: `${failed.length} 单未保存：${failed.join('、')}，请打开详情核对`, duration: 8000, showClose: true })
      await options.reload()
    } finally { listCompletionSaving.value = false }
  }

  return {
    listCompletionVisible, listCompletionRows, listCompletionDate, listCompletionSaving,
    listCompletionStageCode, listCompletionNote, openListCompletion, confirmListCompletion
  }
}
