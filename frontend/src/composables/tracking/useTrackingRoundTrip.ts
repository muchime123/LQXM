/**
 * 生产跟单**导回**（把本系统导出的 Excel 改完再导回）。
 *
 * 文件由本系统导出，因此格式口径（表头文字、阶段列标签）天然一致：预检与写入都在后端，
 * 前端只负责选文件、展示「哪几行能回写／改了什么／为什么跳过」。
 * 只回写跟单员维护的四类字段（跟单备注 / 车间预计完成＝延期至 / 延期原因 / 各节点完成日期），
 * 空白单元格＝不修改；写入走与手工跟进同一条路径，因此同样产生跟单历史与审计。
 *
 * 【边界】本模块与列表唯一的交点是「回写成功后刷新列表」⇒ `reload()`。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { applyTrackingRoundTrip, previewTrackingRoundTrip } from '@/api/productionTracking'
import type { TrackingRoundTripPreview } from '@/api/productionTracking'

const ROUND_TRIP_TEXT: Record<string, string> = {
  WRITE: '可回写',
  UNCHANGED: '已一致',
  UNMATCHED: '未匹配',
  FORBIDDEN: '不在可见范围',
  DUPLICATE: '重复行',
  INVALID: '无法识别'
}

export function useTrackingRoundTrip(options: {
  /** 是否具备 manufacturing.followup 权限（导回写的是跟单字段，与手工跟进同一条路径）。 */
  canFollowup: () => boolean
  /** 回写成功后刷新列表。 */
  reload: () => Promise<unknown>
}) {
  const canFollowup = computed(() => options.canFollowup())
  const roundTripInput = ref<HTMLInputElement | null>(null)
  const roundTripDialog = ref(false)
  const roundTripFile = ref<File | null>(null)
  const roundTripPreview = ref<TrackingRoundTripPreview | null>(null)
  const roundTripSaving = ref(false)

  function roundTripStatusText(status: string) { return ROUND_TRIP_TEXT[status] || status }
  function roundTripTagType(status: string) {
    if (status === 'WRITE') return 'success'
    if (status === 'UNCHANGED') return 'info'
    if (status === 'INVALID') return 'danger'
    return 'warning'
  }

  /** 选中文件即预检（零写入）：把「能写什么、为什么跳过」先摆出来，确认后才整批写入。 */
  async function onRoundTripSelected(event: Event) {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    try {
      roundTripPreview.value = await previewTrackingRoundTrip(file)
      roundTripFile.value = file
      roundTripDialog.value = true
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '预检失败，请确认是本系统导出的 .xlsx')
    }
  }

  async function confirmRoundTripImport() {
    const preview = roundTripPreview.value
    const file = roundTripFile.value
    if (!preview || !file) return
    roundTripSaving.value = true
    try {
      const result = await applyTrackingRoundTrip(file, preview.sha256, preview.planHash)
      ElMessage.success(`已回写 ${result.written} 行跟单`
        + (result.unchanged ? `，${result.unchanged} 行与库内一致` : '')
        + (result.skipped ? `，${result.skipped} 行跳过` : ''))
      roundTripDialog.value = false
      roundTripPreview.value = null
      roundTripFile.value = null
      await options.reload()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '导回失败，请稍后重试')
    } finally { roundTripSaving.value = false }
  }

  return {
    canFollowup, roundTripInput, roundTripDialog, roundTripFile, roundTripPreview, roundTripSaving,
    roundTripStatusText, roundTripTagType, onRoundTripSelected, confirmRoundTripImport
  }
}
