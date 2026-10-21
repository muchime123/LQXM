<template>
  <LqDialog
    v-model:model-value="visible"
    title="氚云导入预览"
    :subtitle="kind?.title || ''"
    width="min(1100px,94vw)"
    :loading="loading"
    :confirm-text="confirmText"
    :confirm-enabled="confirmEnabled"
    @confirm="emit('confirm')"
  >
    <el-alert
      v-if="duplicateHint"
      class="dup-hint"
      type="warning"
      :closable="false"
      show-icon
      :title="`该文件曾于 ${hintTime(duplicateHint.completedAt)} 导入（批次 #${duplicateHint.batchId}）`"
      description="你仍然可以继续导入。系统会按当前数据更新业务记录：新增的插入、有变化的更新、无变化的不动。"
    />
    <div v-if="parseProgress && !preview" class="import-progress">
      <div class="import-progress__head">
        <el-icon :class="['import-progress__icon', parseProgress.status]"><Promotion /></el-icon>
        <div>
          <b>后台解析中：{{ parseProgress.progressPct }}%</b>
          <span>阶段：{{ progressStageLabel(parseProgress.progressStage) }}</span>
        </div>
      </div>
      <el-progress :percentage="parseProgress.progressPct" :status="progressElStatus(parseProgress.status)" :stroke-width="14" />
      <p v-if="parseProgress.status === 'FAILED'" class="import-progress__fail">
        解析失败：{{ parseProgress.errorMessage }}
      </p>
      <p v-else class="import-progress__tip">
        大文件（数万行出库）约需 1~3 分钟，完成后自动进入预览，期间可切走页面做其他工作。
      </p>
    </div>
    <div v-else-if="!preview && file" class="import-file-selected">
      <el-icon class="import-file-selected__icon"><Document /></el-icon>
      <div class="import-file-selected__meta">
        <b>{{ file.name }}</b>
        <span>{{ batchId ? '文件已上传，准备开始后台解析' : '文件已就绪，点击下方「开始解析」即可后台异步解析并预览。' }}</span>
      </div>
      <el-upload
        :auto-upload="false"
        :show-file-list="false"
        accept=".xlsx,.xls"
        :on-change="onRechoose"
      >
        <el-button link type="primary">重新选择</el-button>
      </el-upload>
    </div>
    <el-upload
      v-else-if="!preview"
      drag
      :auto-upload="false"
      :limit="1"
      accept=".xlsx,.xls"
      :on-change="onFileChange"
      ><div class="upload-copy">拖拽或点击选择 {{ kind?.title }} 导出文件</div>
      <template #tip><div class="el-upload__tip">请使用氚云“全选字段”导出的原始 Excel。</div></template></el-upload
    >
    <template v-else
      ><div class="preview-stats">
        <span
          >共 <b>{{ preview.totalCount }}</b></span
        ><span class="ok">新增 {{ preview.createCount }}</span
        ><span class="warn">更新 {{ preview.updateCount }}</span
        ><span>无变化 {{ preview.unchangedCount }}</span>
        <span v-if="batchId" class="meta">批次 #{{ batchId }}</span>
      </div>
      <!-- 经销商信息台账预览：字段与产品/仓库台账不同，单独一表 -->
      <el-table v-if="kind?.ledgerKind === 'dealer'" :data="ledgerRows" height="440" stripe size="small"
        ><el-table-column label="动作" width="90" fixed="left"
          ><template #default="{ row }"
            ><el-tag :type="row.action === 'CREATE' ? 'success' : row.action === 'UPDATE' ? 'warning' : 'info'">{{
              actionLabel(row.action)
            }}</el-tag></template
          ></el-table-column
        ><el-table-column prop="dealerCode" label="经销商编号" width="150" show-overflow-tooltip /><el-table-column
          prop="dealerName"
          label="经销商名称"
          min-width="170"
          show-overflow-tooltip
        /><el-table-column prop="contact" label="联系方式" width="140" show-overflow-tooltip /><el-table-column
          prop="ownerSalesperson"
          label="归属业务员"
          width="120"
          show-overflow-tooltip
        /><el-table-column prop="address" label="地址" min-width="200" show-overflow-tooltip /><el-table-column
          prop="changes"
          label="变化"
          min-width="220"
          show-overflow-tooltip
        /></el-table
      >
      <!-- 台账类导入（产品信息/仓库明细）预览：逐行 新增/更新/无变化 -->
      <el-table v-else-if="isLedgerImport" :data="ledgerRows" height="440" stripe size="small"
        ><el-table-column label="动作" width="90" fixed="left"
          ><template #default="{ row }"
            ><el-tag :type="row.action === 'CREATE' ? 'success' : row.action === 'UPDATE' ? 'warning' : 'info'">{{
              actionLabel(row.action)
            }}</el-tag></template
          ></el-table-column
        ><el-table-column v-if="kind?.ledgerKind === 'stock'" prop="warehouse" label="仓库" width="120" show-overflow-tooltip /><el-table-column
          prop="productCode"
          label="编号"
          width="170"
          show-overflow-tooltip
        /><el-table-column prop="productName" label="产品名称" min-width="180" show-overflow-tooltip /><el-table-column
          prop="category"
          label="类目"
          width="120"
          show-overflow-tooltip
        /><el-table-column prop="specification" label="规格" min-width="140" show-overflow-tooltip /><el-table-column
          prop="unit"
          label="单位"
          width="90"
        /><el-table-column
          v-if="kind?.ledgerKind === 'stock'"
          prop="quantity"
          label="数量"
          width="120"
          align="right"
          :formatter="numberCell"
        /><el-table-column prop="purchasePrice" label="采购价" width="110" align="right" :formatter="priceCell" /><el-table-column
          prop="changes"
          label="变化"
          min-width="220"
          show-overflow-tooltip
        /></el-table
      >
      <!-- 业务事实类导入（销售出库等）预览 -->
      <el-table v-else :data="preview.rows" height="440" stripe
        ><el-table-column prop="businessKey" label="业务编号" width="210" /><el-table-column
          prop="title"
          label="标题"
          min-width="170"
          show-overflow-tooltip
        /><el-table-column
          prop="amount"
          label="金额/数量"
          width="130"
          align="right"
          :formatter="amountCell"
        /><el-table-column
          prop="customerName"
          label="客户"
          min-width="140"
          show-overflow-tooltip
        /><el-table-column
          prop="salespersonName"
          label="业务员"
          width="120"
          show-overflow-tooltip
        /><el-table-column prop="childCount" label="子表" width="80" align="right" /><el-table-column
          prop="action"
          label="动作"
          width="100"
          ><template #default="{ row }"
            ><el-tag :type="row.action === 'CREATE' ? 'success' : row.action === 'UPDATE' ? 'warning' : 'info'">{{
              actionLabel(row.action)
            }}</el-tag></template
          ></el-table-column
        ><el-table-column label="告警" width="150"
          ><template #default="{ row }"
            ><el-tooltip v-if="row.warnings" :content="row.warnings" placement="top" width="420">
              <el-tag type="danger" effect="plain">{{ row.warnings.split('；').length }} 项待核对</el-tag>
            </el-tooltip>
            <span v-else class="diff-empty">—</span></template
          ></el-table-column
        ><el-table-column label="更新内容" min-width="150"
          ><template #default="{ row }">
            <el-popover v-if="row.action === 'UPDATE' && row.changes?.length" placement="left" width="560" trigger="click">
              <template #reference><el-button link type="warning">{{ row.changes.length }} 个字段变化</el-button></template>
              <div class="preview-diff-list">
                <div v-for="(change, index) in row.changes" :key="index" class="preview-diff-item">
                  <b>{{ change.path }}</b><span class="old">{{ change.oldValue }}</span><i>→</i><span class="new">{{ change.newValue }}</span>
                </div>
              </div>
            </el-popover>
            <span v-else-if="row.action === 'UPDATE'" class="diff-empty">无可用上一版快照</span>
            <span v-else>—</span>
          </template></el-table-column
        ></el-table
      ></template
    >
  </LqDialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { UploadFile } from 'element-plus'
import LqDialog from '@/components/common/LqDialog.vue'
import { Promotion, Document } from '@element-plus/icons-vue'
import {
  h3ActionLabel as actionLabel,
  h3ProgressElStatus as progressElStatus,
  h3ProgressStageLabel as progressStageLabel,
} from '@/constants/h3Import'
import { amountCell, numberCell, priceCell } from '@/utils/h3ImportUi'
import type { BatchProgress, ImportPreview, LedgerImportPreview, LedgerImportRow, DuplicateImportHint } from '@/types/h3Import'

/** 导入预览卡片结构（与 @/constants/h3Import 的卡片元数据兼容，仅取弹窗所需字段） */
export interface PreviewKind {
  title?: string
  ledgerKind?: 'product' | 'stock' | 'dealer' | null
}

const props = defineProps<{
  modelValue: boolean
  kind: PreviewKind | null
  file: File | null
  batchId: number | null
  parseProgress: BatchProgress | null
  preview: ImportPreview | LedgerImportPreview | null
  ledgerRows: LedgerImportRow[]
  loading: boolean
  confirmText: string
  confirmEnabled: boolean
  /**
   * 「这份文件此前导过」提示。仅展示，不禁用确认——氚云无法实时同步，
   * 同一天多次全量重导是正常操作，拦截会直接堵住业务。
   */
  duplicateHint?: DuplicateImportHint | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm'): void
  (e: 'fileChange', f: UploadFile): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v),
})

/** 台账类导入（产品信息/仓库明细）：展示逐行 新增/更新/无变化 */
const isLedgerImport = computed(() => !!props.kind?.ledgerKind)

/** 重复导入提示的时间文案：当天显示「今天 HH:mm」，跨天显示「MM-DD HH:mm」。 */
function hintTime(iso: string | null | undefined): string {
  if (!iso) return '此前'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '此前'
  const pad = (n: number) => String(n).padStart(2, '0')
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const now = new Date()
  const sameDay =
    d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  return sameDay ? `今天 ${hm}` : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`
}

function onFileChange(file: UploadFile) {
  emit('fileChange', file)
}
function onRechoose(file: UploadFile) {
  emit('fileChange', file)
}
</script>

<style scoped>
.dup-hint {
  margin-bottom: 12px;
}

.import-file-selected {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
}
.import-file-selected__icon {
  font-size: 28px;
  color: #38bdf8;
}
.import-file-selected__meta {
  flex: 1;
  min-width: 0;
}
.import-file-selected__meta b {
  display: block;
  font-size: 14px;
}
.import-file-selected__meta span {
  display: block;
  color: #64748b;
  font-size: 12px;
  margin-top: 2px;
}
.upload-copy {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.preview-diff-list { display: grid; gap: 7px; max-height: 360px; overflow: auto; }
.preview-diff-item { display: grid; grid-template-columns: minmax(130px, 1fr) minmax(90px, 1fr) 20px minmax(90px, 1fr); gap: 7px; align-items: center; font-size: 12px; }
.preview-diff-item b { font-weight: 600; }
.preview-diff-item .old { color: var(--color-danger); text-decoration: line-through; overflow-wrap: anywhere; }
.preview-diff-item i { color: var(--color-text-muted); text-align: center; font-style: normal; }
.preview-diff-item .new { color: var(--color-success); font-weight: 600; overflow-wrap: anywhere; }
.diff-empty { color: var(--color-text-muted); font-size: 12px; }
.import-progress__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.import-progress__icon {
  font-size: 26px;
  color: #38bdf8;
}
.import-progress__icon.FAILED {
  color: #f56c6c;
}
.import-progress__icon.PREVIEW_READY {
  color: #67c23a;
}
.import-progress__icon.PARSING {
  color: #38bdf8;
}
.import-progress__head b {
  display: block;
}
.import-progress__head span {
  color: #64748b;
  font-size: 12px;
}
.import-progress__tip {
  color: #64748b;
  font-size: 12px;
  margin: 8px 0 0;
}
.import-progress__fail {
  color: #f56c6c;
  font-size: 12px;
  margin: 8px 0 0;
}
.preview-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 13px;
  margin-bottom: 12px;
}
.preview-stats .ok {
  color: #67c23a;
}
.preview-stats .warn {
  color: #e6a23c;
}
.preview-stats .meta {
  color: #94a3b8;
  font-size: 12px;
}
</style>
