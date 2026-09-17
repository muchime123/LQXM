<template>
  <ElProgress
    class="lq-progress"
    :type="type"
    :percentage="clampedPercentage"
    :status="elStatus"
    :stroke-width="strokeWidth"
    :stroke-linecap="strokeLinecap"
    :width="width"
    :color="color || undefined"
    :duration="duration"
    :indeterminate="indeterminate"
    :striped="striped"
    :striped-flow="stripedFlow"
    :show-text="showText"
    :format="format || undefined"
    :aria-label="ariaLabel"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElProgress } from 'element-plus'

export type ProgressType = 'line' | 'circle' | 'dashboard'
export type ProgressStatus = 'primary' | 'success' | 'warning' | 'exception'
export type ProgressLineCap = 'round' | 'square' | 'butt'

const props = withDefaults(
  defineProps<{
    /** 进度百分比 0-100 */
    percentage: number
    /** 进度条类型 */
    type?: ProgressType
    /** 状态：primary 走默认色，其余映射 el-progress status */
    status?: ProgressStatus
    /** 线宽（line 模式高度 / circle&dashboard 直径） */
    strokeWidth?: number
    /** 线段端点形状 */
    strokeLinecap?: ProgressLineCap
    /** circle/dashboard 画布宽度（像素） */
    width?: number
    /** 自定义颜色（覆盖 status 默认配色） */
    color?: string
    /** 动画时长（秒） */
    duration?: number
    /** 不确定状态（条纹滚动） */
    indeterminate?: boolean
    /** 条纹背景 */
    striped?: boolean
    /** 条纹流动 */
    stripedFlow?: boolean
    /** 是否显示百分比文本 */
    showText?: boolean
    /** 自定义文本格式函数 */
    format?: (percentage: number) => string
    /** 无障碍标签 */
    ariaLabel?: string
  }>(),
  {
    type: 'line',
    status: 'primary',
    strokeWidth: 6,
    strokeLinecap: 'round',
    width: 126,
    color: '',
    duration: 3,
    indeterminate: false,
    striped: false,
    stripedFlow: false,
    showText: true,
    format: undefined,
    ariaLabel: '进度'
  }
)

const clampedPercentage = computed(() => Math.max(0, Math.min(100, props.percentage)))

// el-progress status 仅接受 success/exception/warning/空；primary 通过不传 status 走默认色
const elStatus = computed(() => {
  if (props.status === 'primary') return undefined
  return props.status
})
</script>

<style scoped>
.lq-progress {
  width: 100%;
}

/* 圆形/仪表盘居中 */
.lq-progress:deep(.el-progress--circle),
.lq-progress:deep(.el-progress--dashboard) {
  width: auto;
}
</style>
