/**
 * 生产跟单**节点参考完成基准天数**（通用参考）的读取与保存（管理员）。
 *
 * 这块很小，但权限与跟单操作刻意分开：`manufacturing.baseline.manage` 决定「一般要几天」这个判据，
 * 跟单员能推节点（`manufacturing.followup`），却不该改判据本身 —— 所以权限判定留在页面，
 * 本 composable 只负责「拉矩阵 / 存矩阵」。
 *
 * 2026-10-22：入口从生产跟单页迁到「生产产品与工期管理」页右上角（通用参考设置），
 * 显隐由 TrackingGeneralBaselineDialog 控制，这里不再持有 visible。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchTrackingNodeBenchmarks, saveTrackingNodeBenchmarks } from '@/api/productionTracking'
import type { TrackingBenchmarkWorkshop } from '@/api/productionTracking'

export function useTrackingBaseline(options: {
  /** 保存成功后回调（基准天数变了，滞后判据与右栏「通用参考」列都要跟着重算）。 */
  reload?: () => Promise<unknown>
} = {}) {
  const baselineLoading = ref(false)
  const baselineSaving = ref(false)
  /** 基准配置矩阵：两个跟单范围类别 × 各自全部节点（含尚未配置的，typicalDays 为 null）。 */
  const baselineWorkshops = ref<TrackingBenchmarkWorkshop[]>([])

  /** 拉取基准配置：每次打开都重新拉，避免显示上一次的旧值。返回是否成功。 */
  async function loadBaseline(): Promise<boolean> {
    baselineLoading.value = true
    try {
      const res = await fetchTrackingNodeBenchmarks()
      baselineWorkshops.value = res.workshops || []
      return true
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载通用参考配置失败')
      return false
    } finally {
      baselineLoading.value = false
    }
  }

  /** 保存基准：全量提交两个跟单范围的所有节点；天数清空＝不设参考（后端删该行）。返回是否成功。 */
  async function saveBaseline(): Promise<boolean> {
    const rows = baselineWorkshops.value.flatMap((w) => w.stages.map((s) => {
      const raw: unknown = s.typicalDays
      return {
        workshopKind: w.workshopKind,
        stageCode: s.stageCode,
        typicalDays: typeof raw === 'number' && Number.isFinite(raw) ? raw : null
      }
    }))
    if (!rows.length) return false
    baselineSaving.value = true
    try {
      const res = await saveTrackingNodeBenchmarks(rows)
      ElMessage.success(`已保存 ${res.changed} 项通用参考`)
      await options.reload?.()
      return true
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存通用参考失败')
      return false
    } finally {
      baselineSaving.value = false
    }
  }

  return { baselineLoading, baselineSaving, baselineWorkshops, loadBaseline, saveBaseline }
}
