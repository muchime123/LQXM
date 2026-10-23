<template>
  <div class="preview-stage">
    <!-- 概览统计 -->
    <div class="summary-panel">
      <div class="summary-main">
        <div class="summary-stat">
          <span class="summary-label">归属系列</span>
          <strong class="summary-series-name">{{ preview.modelName || '—' }}</strong>
        </div>
        <div class="summary-stat">
          <span class="summary-label">解析行数</span>
          <strong class="summary-value">{{ preview.totalRows }}</strong>
        </div>
      </div>
      <div class="summary-group">
        <div class="summary-stat">
          <span class="summary-label">新增</span>
          <strong class="summary-value is-success">{{ preview.newCount }}</strong>
        </div>
        <div class="summary-stat">
          <span class="summary-label">合并</span>
          <strong class="summary-value is-warning">{{ preview.mergeCount }}</strong>
        </div>
        <div class="summary-stat">
          <span class="summary-label">待核实</span>
          <strong class="summary-value is-danger">{{ preview.unverifiedCount }}</strong>
        </div>
      </div>
      <div class="summary-group">
        <div class="summary-stat">
          <span class="summary-label">已匹配</span>
          <strong class="summary-value is-success">{{ matchSummary.matched }}</strong>
        </div>
        <div class="summary-stat">
          <span class="summary-label">多候选</span>
          <strong class="summary-value is-warning">{{ matchSummary.multi }}</strong>
        </div>
        <div class="summary-stat">
          <span class="summary-label">未匹配</span>
          <strong class="summary-value">{{ matchSummary.none }}</strong>
        </div>
      </div>
    </div>

    <!-- 问题警告列表 -->
    <div v-if="preview.errors.length > 0" class="warnings-banner">
      <el-alert type="warning" :closable="false" show-icon :title="`解析过程中有 ${preview.errors.length} 个问题`">
        <div class="warnings-body">
          <div class="warnings-head">
            <span class="warnings-count">共 {{ preview.errors.length }} 条</span>
            <el-button v-if="preview.errors.length > 5" link type="primary" size="small" class="warnings-toggle"
              @click="$emit('update:warningsCollapsed', !warningsCollapsed)">
              {{ warningsCollapsed ? '展开全部' : '收起' }}
            </el-button>
          </div>
          <div v-show="!warningsCollapsed" class="warnings-list">
            <div v-for="(e, i) in preview.errors" :key="i" class="w-item">
              <span v-if="e.rowNumber" class="w-row">#{{ e.rowNumber }}</span>
              <span v-if="e.field" class="w-field">{{ e.field }}:</span>
              <span class="w-msg">{{ e.message }}</span>
              <el-tag size="small" :type="e.severity === 'error' ? 'danger' : 'warning'">{{ e.severity }}</el-tag>
            </div>
          </div>
        </div>
      </el-alert>
    </div>

    <!-- 预览表格 -->
    <div class="preview-table-container">
      <LqTable :data="visibleRows" border density="compact" :row-class-name="rowClassName"
        :span-method="spanMethod"
        empty-text="没有可显示的行" max-height="calc(96vh - 280px)"
        @selection-change="onSelectionChange">
        <el-table-column type="selection" width="38" />
        <el-table-column label="类" width="80" align="center">
          <template #default="{ row }">
            <span class="disp-badge" :class="`disp-${row.disposition}`">
              <span class="disp-dot" />
              {{ dispLabel(row.disposition) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="sourceRowNumber" label="原行" width="70" align="center" />
        <el-table-column prop="moldCode" label="模号" min-width="120">
          <template #default="{ row }">
            <span class="mold-code-cell" :class="{ 'has-issue': row.disposition === 'unverified' }">
              <strong>{{ row.moldCode || '—' }}</strong>
              <span v-if="moldRowCount(row) > 1" class="mold-row-count">{{ moldRowCount(row) }} 个高度</span>
              <span v-if="moldRowCount(row) > 1" class="mold-group-actions">
                <el-button link size="small" @click.stop="selectMold(row)">整组选中</el-button>
                <el-button link size="small" @click.stop="toggleMold(row)">{{ collapsedMolds.has(row.moldCode || '') ? '展开' : '折叠' }}</el-button>
              </span>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="productName" label="产品名（规格族）" min-width="100" show-overflow-tooltip />
        <el-table-column label="匹配系列" min-width="150">
          <template #default="{ row }">
            <el-select :model-value="matchState(row).modelId" placeholder="选择系列" size="small" style="width: 100%"
              filterable @update:model-value="setModelOverride(row, $event)">
              <el-option v-for="m in packagingModels" :key="m.id"
                :label="m.name + (m.packagingKind ? ` (${m.packagingKind})` : '')" :value="m.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="口径" min-width="120" align="center">
          <template #default="{ row }">
            <span v-if="row.caliber" class="caliber-cell">{{ row.caliber }}</span>
            <span v-else-if="row.lengthMm && row.widthMm" class="caliber-cell">
              {{ row.lengthMm }}×{{ row.widthMm }}
            </span>
            <span v-else-if="row.topDiameterMm" class="caliber-cell">
              Ø{{ row.topDiameterMm }}
            </span>
            <span v-else-if="row.lengthMm" class="caliber-cell">{{ row.lengthMm }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="heightMm" label="高度" width="80" align="center">
          <template #default="{ row }"><strong>{{ row.heightMm || '—' }}</strong></template>
        </el-table-column>
        <el-table-column label="规格匹配" min-width="220">
          <template #default="{ row }">
            <!-- 有候选：统一用下拉框（matched + multi_match） -->
            <div v-if="matchState(row).candidates.length" class="match-select-wrap">
              <el-tag v-if="matchState(row).candidates.length === 1 && matchState(row).isLoose" size="small"
                type="warning" effect="light">近似</el-tag>
              <el-tag v-else-if="matchState(row).candidates.length === 1" size="small" type="success" effect="light">已匹配</el-tag>
              <el-tag v-else size="small" type="warning"
                effect="light">多候选</el-tag>
              <el-select v-model="localVariantOverrides[globalRowIndex(row)]" placeholder="选择规格" size="small" style="width: 180px"
                clearable
                @change="commitOverride(row, $event)">
                <el-option v-for="c in matchState(row).candidates" :key="c.variantId" :label="formatCandidateLabel(c)"
                  :value="c.variantId" />
              </el-select>
              <div v-if="matchState(row).selectedCandidate" class="match-reason">
                {{ formatMatchReason(row, matchState(row).selectedCandidate!) }}
              </div>
            </div>
            <!-- 无匹配 -->
            <div v-else class="match-none">
              <el-tag size="small" type="info" effect="light">未匹配</el-tag>
              <span class="muted">{{ matchState(row).modelId ? '当前系列无候选' : '待人工建立' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="capacityMl" label="容量" width="80" align="center">
          <template #default="{ row }"><span>{{ row.capacityMl || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="盒型/出" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.moldTypeCode" class="type-chip">{{ row.moldTypeCode }}</span>
            <span v-if="row.cavityCount">/ <strong>{{ row.cavityCount }}</strong></span>
            <span v-else-if="!row.moldTypeCode">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="compartmentCount" label="格数" width="70" align="center">
          <template #default="{ row }">
            <span>{{ row.compartmentCount || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="surfaceStructure" label="外形" width="90" align="center">
          <template #default="{ row }">
            <span>{{ row.surfaceStructure || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="配件" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.accessoryList?.length">
              <el-tag v-for="a in row.accessoryList.slice(0, 3)" :key="a" size="small" effect="plain"
                class="acc-chip">{{ a }}</el-tag>
              <span v-if="row.accessoryList.length > 3">+{{ row.accessoryList.length - 3 }}</span>
            </span>
            <span v-else-if="row.accessories" class="raw-acc">{{ row.accessories }}</span>
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column prop="bottomEngraving" label="杯底刻字" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.bottomEngraving">{{ row.bottomEngraving }}</span>
            <span v-else class="muted">空白</span>
          </template>
        </el-table-column>
        <el-table-column label="问题" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.issue" class="issue-text">{{ row.issue }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </LqTable>
    </div>
  </div>
</template>

<script setup lang="ts">
import LqTable from '@/components/common/LqTable.vue'
import {
  useMoldImportPreviewTable,
  type MoldImportPreviewTableEmit,
  type MoldImportPreviewTableProps
} from '@/composables/mold/useMoldImportPreviewTable'

const props = defineProps<MoldImportPreviewTableProps>()

const emit = defineEmits<{
  'override-variant': [globalIdx: number, variantId: number | null]
  'override-model': [globalIdx: number, modelId: number]
  'selection-change': [globalIndexes: number[]]
  'select-mold': [globalIndexes: number[]]
  'update:warningsCollapsed': [v: boolean]
}>()

const {
  collapsedMolds,
  localVariantOverrides,
  visibleRows,
  matchSummary,
  globalRowIndex,
  matchState,
  commitOverride,
  setModelOverride,
  toggleMold,
  selectMold,
  moldRowCount,
  spanMethod,
  onSelectionChange,
  dispLabel,
  rowClassName,
  formatCandidateLabel,
  formatMatchReason
} = useMoldImportPreviewTable(props, emit as MoldImportPreviewTableEmit)

/** 顶部匹配统计随系列和规格人工调整实时变化，不再沿用初始预览快照。 */
</script>

<style scoped>
.preview-stage {
  display: flex;
  flex-direction: column;
  gap: var(--space-s, 8px);
  max-height: calc(96vh - 140px);
  min-height: 0;
  overflow: hidden;
}

.summary-panel {
  display: flex;
  align-items: center;
  gap: var(--space-m, 12px);
  flex-wrap: wrap;
  padding: var(--space-s, 8px) var(--space-m, 12px);
  background: var(--color-surface-alt, #f8fafc);
  border: 1px solid var(--color-border-light, #e3e8ee);
  border-radius: var(--radius-lg, 8px);
}

.summary-main {
  display: flex;
  align-items: center;
  gap: var(--space-m, 12px);
  flex-wrap: wrap;
}

.summary-group {
  display: flex;
  align-items: center;
  gap: var(--space-m, 12px);
  flex-wrap: wrap;
  padding-left: var(--space-m, 12px);
  border-left: 1px solid var(--color-border, #e2e8f0);
}

.summary-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.summary-label {
  font-size: var(--fs-xxs, 11px);
  color: var(--color-text-muted, #64748b);
  font-weight: var(--fw-medium, 500);
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.summary-value {
  font-size: var(--fs-md, 15px);
  font-weight: var(--fw-extrabold, 800);
  color: var(--color-text-heading, #0f172a);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.summary-value.is-success {
  color: var(--color-success, #16a34a);
}

.summary-value.is-warning {
  color: var(--color-warning, #d97706);
}

.summary-value.is-danger {
  color: var(--color-danger, #dc2626);
}

.summary-series-name {
  font-size: var(--fs-sm, 13px);
  font-weight: var(--fw-bold, 700);
  color: var(--color-primary, #0f766e);
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.warnings-banner {
  margin-bottom: var(--space-xs, 4px);
}

.warnings-banner :deep(.el-alert) {
  padding: 10px 12px;
}

.warnings-body {
  display: flex;
  flex-direction: column;
}

.warnings-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md, 10px);
  margin-bottom: 4px;
}

.warnings-count {
  font-size: var(--fs-xs, 12px);
  color: var(--color-warning-text, #92400e);
  font-weight: var(--fw-semibold, 600);
}

.warnings-toggle {
  margin-right: 4px;
}

.warnings-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 150px;
  overflow-y: auto;
  font-size: var(--fs-xs, 12px);
}

.w-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.w-item .w-row {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  color: var(--color-text-tertiary, #9ca3af);
}

.w-item .w-field {
  font-weight: 500;
  color: var(--color-text-secondary, #374151);
}

.w-item .w-msg {
  color: var(--color-text-secondary, #374151);
  flex: 1;
  min-width: 0;
}

.preview-table-container {
  flex: 1;
  min-height: 300px;
  border: 1px solid var(--color-border-subtle, #e5e7eb);
  border-radius: var(--radius-card, 10px);
  overflow: hidden;
}

.preview-table-container :deep(.el-table__row.row-unverified > td.el-table__cell) {
  background: var(--color-danger-bg-soft, #fff1f2);
}

.preview-table-container :deep(.el-table__row.row-conflict > td.el-table__cell) {
  background: var(--color-danger-bg-soft, #fff1f2);
}

.preview-table-container :deep(.el-table__row.row-unverified:hover > td.el-table__cell) {
  background: var(--color-danger-bg, #fee2e2);
}

.preview-table-container :deep(.el-table__row.row-conflict:hover > td.el-table__cell) {
  background: var(--color-danger-bg, #fee2e2);
}

.disp-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-xs, 12px);
  color: var(--color-text-secondary, #4b5563);
  white-space: nowrap;
}

.disp-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-text-muted, #64748b);
  flex-shrink: 0;
}

.disp-new {
  color: var(--color-success-text, #166534);
}

.disp-new .disp-dot {
  background: var(--color-success, #16a34a);
}

.disp-merge {
  color: var(--color-warning-text, #92400e);
}

.disp-merge .disp-dot {
  background: var(--color-warning, #d97706);
}

.disp-unverified {
  color: var(--color-danger-text, #be123c);
}

.disp-unverified .disp-dot {
  background: var(--color-danger, #dc2626);
}

.disp-conflict {
  color: var(--color-danger-text, #be123c);
}

.disp-conflict .disp-dot {
  background: var(--color-danger, #dc2626);
}

.type-chip {
  display: inline-block;
  padding: 1px 6px;
  background: var(--color-bg-elevated, #f3f4f6);
  border-radius: 4px;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  font-size: 12px;
}

.acc-chip {
  margin-right: 4px;
}

.raw-acc {
  font-size: 12px;
  color: var(--color-text-secondary, #6b7280);
  font-family: var(--font-mono, ui-monospace, monospace);
  word-break: break-all;
}

.muted {
  color: var(--color-text-tertiary, #9ca3af);
  font-size: 12px;
}

.issue-text {
  font-size: 12px;
  color: var(--color-warning-700, #b45309);
}

.mold-code-cell {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.mold-row-count {
  display: block;
  margin-top: 3px;
  color: var(--color-text-muted, #64748b);
  font-size: 11px;
  font-family: var(--font-sans, sans-serif);
}

.mold-group-actions {
  display: flex;
  gap: 2px;
  margin-top: 2px;
  font-family: var(--font-sans, sans-serif);
}

.match-reason {
  width: 100%;
  margin-top: 4px;
  color: var(--color-text-muted, #64748b);
  font-size: 11px;
  line-height: 1.35;
}

.mold-code-cell.has-issue {
  color: var(--color-danger);
}

.caliber-cell {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12.5px;
  color: var(--color-text-secondary, #4b5563);
}

.match-none {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
</style>

