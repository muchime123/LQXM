/**
 * 生产跟单**节点参考完成基准天数**的配置（管理员）。
 *
 * 这块很小，但权限与跟单操作刻意分开：`manufacturing.baseline.manage` 决定「一般要几天」这个判据，
 * 跟单员能推节点（`manufacturing.followup`），却不该改判据本身 —— 所以权限判定也随模块一起搬出来，
 * 页面只负责把 `auth.can` 注入进来。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchTrackingNodeBenchmarks, saveTrackingNodeBenchmarks } from '@/api/productionTracking'
import type { TrackingBenchmarkWorkshop } from '@/api/productionTracking'

export function useTrackingBaseline(options: {
  /** 是否具备 manufacturing.baseline.manage 权限。 */
  canManage: () => boolean
  /** 保存后刷新列表（基准天数变了，滞后判据要跟着重算）。 */
  reload: () => Promise<unknown>
}) {
  /**
   * 仅系统管理员可改「节点参考完成基准天数」（独立权限 manufacturing.baseline.manage）。
   * 与跟单操作 manufacturing.followup 刻意分离：跟单员能推节点，但不该改「一般要几天」的判据。
   */
  const canManageBaseline = computed(() => options.canManage())
  const baselineVisible = ref(false)
  const baselineLoading = ref(false)
  const baselineSaving = ref(false)
  /** 基准配置矩阵：两个车间类别 × 各自全部节点（含尚未配置的，typicalDays 为 null）。 */
  const baselineWorkshops = ref<TrackingBenchmarkWorkshop[]>([])

  /** 打开基准配置：每次重新拉取，避免显示上一次的旧值。 */
  async function openBaseline() {
    baselineVisible.value = true
    baselineLoading.value = true
    try {
      const res = await fetchTrackingNodeBenchmarks()
      baselineWorkshops.value = res.workshops || []
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载基准配置失败')
      baselineVisible.value = false
    } finally {
      baselineLoading.value = false
    }
  }

  /** 保存基准：全量提交两个车间的所有节点；天数清空＝不设基准（后端删该行）。 */
  async function saveBaseline() {
    const rows = baselineWorkshops.value.flatMap((w) => w.stages.map((s) => {
      const raw: unknown = s.typicalDays
      return {
        workshopKind: w.workshopKind,
        stageCode: s.stageCode,
        typicalDays: typeof raw === 'number' && Number.isFinite(raw) ? raw : null
      }
    }))
    if (!rows.length) return
    baselineSaving.value = true
    try {
      const res = await saveTrackingNodeBenchmarks(rows)
      ElMessage.success(`已保存 ${res.changed} 项基准`)
      baselineVisible.value = false
      await options.reload()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存基准失败')
    } finally {
      baselineSaving.value = false
    }
  }

  return {
    canManageBaseline, baselineVisible, baselineLoading, baselineSaving, baselineWorkshops,
    openBaseline, saveBaseline
  }
}
