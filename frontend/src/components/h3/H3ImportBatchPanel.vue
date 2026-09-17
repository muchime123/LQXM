<template>
  <div class="batch-workspace">
    <div class="overview-strip">
      <div class="overview-strip__title">
        <b>导入概览</b><small>基于当前页 {{ batches.length }} 个批次</small>
      </div>
      <div class="overview-strip__stats">
        <div v-for="s in overviewStats" :key="s.key" class="ov-stat" :style="{ '--ov-color': s.color }">
          <span class="ov-stat__icon"
            ><el-icon><component :is="s.icon" /></el-icon
          ></span>
          <span class="ov-stat__meta"
            ><b>{{ s.value }}</b
            ><small>{{ s.label }}</small></span
          >
        </div>
      </div>
    </div>

    <section v-if="lastResult" class="import-result-panel">
      <el-icon class="import-result-panel__icon"><CircleCheckFilled /></el-icon>
      <div class="import-result-panel__body">
        <b>批次 #{{ lastResult.batchId }} {{ lastBatchCompleted ? '处理完成' : '数据已写入，正在刷新收款状态与利润结果' }}</b>
        <p>
          新增 {{ lastResult.createdCount }}、更新 {{ lastResult.updatedCount }}、忽略无变化
          {{ lastResult.unchangedCount }}。{{
            lastBatchCompleted
              ? nextAction(lastBatch).description
              : '请等待状态变为“已完成”，期间可以继续使用其他页面。'
          }}
        </p>
      </div>
      <div class="result-actions">
        <el-button type="primary" :disabled="!lastBatchCompleted" @click="$emit('next', lastBatch)">{{
          nextAction(lastBatch).label
        }}</el-button>
        <el-button link @click="$emit('dismiss-result')">关闭</el-button>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>最近导入</h2>
          <p>先看导入结果，再按“建议下一步”处理；没有异常的批次无需额外操作。</p>
        </div>
        <el-select
          :model-value="batchType"
          clearable
          placeholder="全部类型"
          style="width: 150px"
          @update:model-value="$emit('update:batchType', $event)"
          @change="$emit('reload', 1)"
        >
          <el-option v-for="item in importTypes" :key="item.type" :label="item.title" :value="item.dbType" />
        </el-select>
      </div>
      <el-table v-loading="loading" :data="batches" stripe>
        <el-table-column prop="id" label="批次" width="78"
          ><template #default="{ row }">#{{ row.id }}</template></el-table-column
        >
        <el-table-column prop="fileName" label="文件" min-width="260" show-overflow-tooltip />
        <el-table-column label="数据类型" width="130"
          ><template #default="{ row }"
            ><el-tag>{{ typeLabel(row.dataType) }}</el-tag></template
          ></el-table-column
        >
        <el-table-column label="状态" width="112"
          ><template #default="{ row }"
            ><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template
          ></el-table-column
        >
        <el-table-column label="导入结果" width="210"
          ><template #default="{ row }"
            ><div class="result-counts">
              <b>共 {{ row.totalCount }}</b
              ><span class="ok">新增 {{ row.createdCount }}</span
              ><span class="warn">更新 {{ row.updatedCount }}</span
              ><span>忽略 {{ row.unchangedCount }}</span>
            </div></template
          ></el-table-column
        >
        <el-table-column label="处理结果" width="132">
          <template #default="{ row }">
            <el-tag v-if="resultTag(row)" :type="resultTag(row)?.tag" effect="plain">{{
              resultTag(row)?.label
            }}</el-tag>
            <span v-else class="cell-sub">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="completedAt" label="完成时间" width="180"
          ><template #default="{ row }">{{ dateTime(row.completedAt) }}</template></el-table-column
        >
        <el-table-column label="建议下一步" width="250" fixed="right"
          ><template #default="{ row }">
            <template v-if="['FAILED', 'PARTIAL_FAILED'].includes(row.status)"
              ><el-button link type="danger" @click="$emit('failure-report', row)">错误报告</el-button
              ><el-button link type="primary" @click="$emit('retry', row)">重新处理</el-button></template
            >
            <div v-else-if="row.status === 'PREVIEW_READY'" class="next-action-cell">
              <el-button link type="primary" @click="$emit('continue', row)">继续确认导入</el-button
              ><small>文件已解析，核对结果后确认写入。</small>
            </div>
            <template v-else-if="row.status === 'COMPLETED'"
              ><div class="next-action-cell">
                <el-button link type="primary" @click="$emit('next', row)">{{ nextAction(row).label }}</el-button
                ><small>{{ nextAction(row).description }}</small>
              </div>
              <el-button v-if="row.updatedCount > 0" link type="warning" @click="$emit('update-detail', row)">
                查看 {{ row.updatedCount }} 条更新
              </el-button>
              ></template
            >
            <span v-else class="cell-sub">处理完成后显示下一步</span>
          </template></el-table-column
        >
      </el-table>
      <el-pagination
        class="batch-pager"
        :current-page="page"
        :page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @update:current-page="$emit('update:page', $event)"
        @update:page-size="$emit('update:size', $event)"
        @current-change="$emit('reload')"
        @size-change="$emit('reload', 1)"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheckFilled, Clock, RefreshRight, WarningFilled } from '@element-plus/icons-vue'
import type { ImportBatch, ImportResult } from '@/api/business'
import {
  H3_BATCH_KINDS,
  H3_BATCH_FALLBACK_ACTION,
  normalizeH3DbType,
  h3DbTypeLabel,
  h3BatchStatusLabel,
  h3BatchStatusTagType,
  h3ParticipationMeta
} from '@/constants/h3Import'
import type { H3ElTagType } from '@/constants/h3Import'
const props = defineProps<{
  batches: ImportBatch[]
  loading: boolean
  batchType: string
  page: number
  size: number
  total: number
  lastResult: ImportResult | null
}>()
const emit = defineEmits([
  'update:batchType',
  'update:page',
  'update:size',
  'reload',
  'next',
  'retry',
  'continue',
  'failure-report',
  'update-detail',
  'dismiss-result'
])
// 批次列表展示用种类集合：七类文件导入卡 + 合并入库批次（合并入库无上传入口，仅出现在筛选下拉与 nextAction）
const importTypes = H3_BATCH_KINDS
const lastBatch = computed(() => props.batches.find(b => b.id === props.lastResult?.batchId))
const lastBatchCompleted = computed(() => lastBatch.value?.status === 'COMPLETED')
const overviewStats = computed(() => {
  const count = (s: string[]) => props.batches.filter(b => s.includes(b.status)).length
  return [
    {
      key: 'completed',
      label: '已完成',
      value: count(['COMPLETED']),
      color: 'var(--color-success)',
      icon: CircleCheckFilled
    },
    { key: 'preview', label: '待确认', value: count(['PREVIEW_READY']), color: 'var(--color-primary)', icon: Clock },
    {
      key: 'running',
      label: '处理中',
      value: count(['UPLOADED', 'PARSING', 'CONFIRMING']),
      color: 'var(--color-info)',
      icon: RefreshRight
    },
    {
      key: 'failed',
      label: '异常',
      value: count(['FAILED', 'PARTIAL_FAILED']),
      color: 'var(--color-danger)',
      icon: WarningFilled
    }
  ]
})
const typeLabel = h3DbTypeLabel,
  statusLabel = h3BatchStatusLabel,
  statusType = h3BatchStatusTagType
function nextAction(batch?: ImportBatch) {
  return importTypes.find(k => k.dbType === normalizeH3DbType(batch?.dataType))?.next ?? H3_BATCH_FALLBACK_ACTION
}
/**
 * 批次「处理结果」标签：语义来自导入类型的 participation 声明，不在这里按 data_type 另写一套判断。
 * 异常优先；未完成的批次不显示结果（状态列已表达）。
 */
function resultTag(row: ImportBatch): { label: string; tag: H3ElTagType } | null {
  if (row.status === 'FAILED' || row.status === 'PARTIAL_FAILED') return { label: '存在异常', tag: 'danger' }
  if (row.status !== 'COMPLETED') return null
  return h3ParticipationMeta(row.dataType)
}
function dateTime(v?: string | null) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.overview-strip,
.import-result-panel,
.panel-head,
.overview-strip__stats,
.ov-stat,
.result-actions,
.result-counts {
  display: flex;
  align-items: center;
}
.overview-strip,
.import-result-panel,
.panel-head {
  justify-content: space-between;
}
.overview-strip {
  padding: 14px 18px;
  margin: 16px 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}
.overview-strip__title,
.ov-stat__meta,
.next-action-cell {
  display: flex;
  flex-direction: column;
}
.overview-strip__title small,
.ov-stat__meta small,
.next-action-cell small,
.cell-sub {
  color: var(--color-text-muted);
  font-size: 12px;
}
.overview-strip__stats {
  gap: 24px;
}
.ov-stat {
  gap: 8px;
}
.ov-stat__icon {
  color: var(--ov-color);
  font-size: 20px;
}
.import-result-panel {
  gap: 18px;
  padding: 16px 18px;
  margin: 14px 0;
  border: 1px solid color-mix(in srgb, var(--color-success) 35%, var(--color-border));
  border-radius: var(--radius-lg);
  background: color-mix(in srgb, var(--color-success) 7%, var(--color-surface));
}
.import-result-panel__icon {
  color: var(--color-success);
  font-size: 24px;
}
.import-result-panel__body {
  flex: 1;
}
.import-result-panel p {
  margin: 5px 0 0;
  color: var(--color-text-muted);
  font-size: 13px;
}
.result-counts,
.result-actions {
  gap: 9px;
  flex-wrap: wrap;
  font-size: 12px;
}
.result-counts .ok {
  color: var(--color-success);
}
.result-counts .warn {
  color: var(--color-warning);
}
.next-action-cell {
  align-items: flex-start;
  max-width: 175px;
}
.batch-pager {
  justify-content: flex-end;
  margin-top: 16px;
}
.panel-head {
  margin-bottom: 14px;
}
.panel-head h2,
.panel-head p {
  margin: 0;
}
.panel-head p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 13px;
}
</style>
