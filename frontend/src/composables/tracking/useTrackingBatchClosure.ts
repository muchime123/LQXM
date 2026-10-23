import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { changeTrackingClosure, fetchTrackingDetail } from '@/api/productionTracking'
import type { TrackingOrder } from '@/api/productionTracking'

type ClosureRow = {
  id: number
  orderNo: string
  productNameSnapshot: string
  stageDoneCount?: number
  stageTotal?: number
  result: 'pending' | 'success' | 'failed'
  error: string
}

/** 列表勾选后批量确认结案；逐单复用详情页的结案接口与审计规则。 */
export function useTrackingBatchClosure(options: { reload: () => Promise<unknown> }) {
  const visible = ref(false)
  const rows = ref<ClosureRow[]>([])
  const note = ref('历史跟单已完成，后补按需节点未逐项登记，现核实结案。')
  const saving = ref(false)
  const remaining = computed(() => rows.value.filter(row => row.result !== 'success').length)

  function open(selected: TrackingOrder[]) {
    const eligible = selected.filter(row => !row.stoppedAt && !row.closedAt)
    if (!eligible.length) {
      ElMessage.warning('所选任务均已停止跟单或已经结案')
      return
    }
    if (eligible.length !== selected.length) ElMessage.info('已停止跟单或已结案的任务不会重复处理')
    rows.value = eligible.map(row => ({
      id: row.id,
      orderNo: row.orderNo,
      productNameSnapshot: row.productNameSnapshot,
      stageDoneCount: row.stageDoneCount,
      stageTotal: row.stageTotal,
      result: 'pending',
      error: ''
    }))
    note.value = '历史跟单已完成，后补按需节点未逐项登记，现核实结案。'
    visible.value = true
  }

  async function confirm() {
    const reason = note.value.trim()
    if (saving.value || reason.length < 6 || !remaining.value) return
    saving.value = true
    let saved = 0
    let failed = 0
    try {
      for (const row of rows.value) {
        if (row.result === 'success') continue
        try {
          // 列表版本可能已过期；每单写入前读最新详情，再交给原接口校验并留痕。
          const current = await fetchTrackingDetail(row.id)
          if (current.order.stoppedAt) throw new Error('已停止跟单')
          if (current.order.closedAt) throw new Error('已由其他人结案')
          await changeTrackingClosure(row.id, current.order.version, true, reason)
          row.result = 'success'
          row.error = ''
          saved++
        } catch (error) {
          row.result = 'failed'
          row.error = error instanceof Error ? error.message : '结案失败'
          failed++
        }
      }
      if (saved) ElMessage.success(`已确认结案 ${saved} 单`)
      if (failed) ElMessage.warning(`${failed} 单未结案，请查看弹窗中的结果`)
      if (saved) await options.reload()
      if (!failed) visible.value = false
    } finally {
      saving.value = false
    }
  }

  return { visible, rows, note, saving, remaining, open, confirm }
}
