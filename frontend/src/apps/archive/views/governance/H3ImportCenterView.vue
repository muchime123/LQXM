<template>
  <div class="h3-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">DATA GOVERNANCE · IMPORTS</p>
        <h1>氚云导入中心</h1>
        <p>
          销售出库/生产任务/收款结清按批次导入事实表并留下原始台账；产品信息与仓库明细同样按批次导入台账（逐行比对：新增/更新/无变化），再从台账合并库存。
        </p>
      </div>
      <div class="page-head__actions">
        <div class="import-actions__uploaders" aria-hidden="true">
          <el-upload
            v-for="item in importTypes"
            :key="item.type"
            :ref="(el: any) => setFactUploadRef(item.type, el)"
            :auto-upload="false"
            :show-file-list="false"
            accept=".xlsx,.xls"
            :on-change="(file: UploadFile) => chooseKindFile(item, file)"
          >
            <el-button>{{ item.uploadLabel }}</el-button>
          </el-upload>
        </div>
        <div ref="triggerRef" class="import-trigger-wrap">
          <el-button
            type="primary"
            class="import-actions__primary"
            :class="{ 'is-open': importPopoverVisible }"
            @click="importPopoverVisible = !importPopoverVisible"
          >
            <el-icon class="el-icon--left"><UploadFilled /></el-icon>
            导入氚云 Excel
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <transition name="el-fade-in-linear">
            <div v-show="importPopoverVisible" class="import-popover" role="menu">
              <template v-for="(group, gi) in importDropdownGroups" :key="group.key">
                <div v-if="gi > 0" class="import-popover__divider" />
                <button
                  v-if="group.collapsible"
                  type="button"
                  class="import-popover__group-label import-popover__group-label--toggle"
                  :aria-expanded="!group.collapsed"
                  @click="archiveExpanded = !archiveExpanded"
                >
                  <span>{{ group.label }}（{{ group.total }}）</span>
                  <span class="import-popover__group-hint">{{ group.hint }}</span>
                  <el-icon class="import-popover__group-caret" :class="{ 'is-open': !group.collapsed }">
                    <ArrowDown />
                  </el-icon>
                </button>
                <div v-else class="import-popover__group-label">{{ group.label }}</div>
                <button
                  v-for="item in group.items"
                  :key="item.type"
                  type="button"
                  class="import-popover__item"
                  @click="onImportItemClick(item.type)"
                >
                  <span class="import-popover__chip" :style="cardIconStyle(item)">{{ item.short }}</span>
                  <span class="import-popover__body">
                    <b>{{ item.cardTitle ?? item.title }}</b>
                    <small>{{ item.cardDescription ?? item.description }}</small>
                  </span>
                </button>
              </template>
            </div>
          </transition>
        </div>
        <el-button plain :type="guideVisible ? 'primary' : ''" @click="guideVisible = !guideVisible">
          {{ guideVisible ? '收起指引' : '操作指引' }}
        </el-button>
        <!-- 低频动作（重导准备 / 映射改判）不再与日常导入抢同一排按钮。
             库存对账（生成正式余额）已下线入口：现阶段正式库存账无业务消费者，
             产品库存页即两张台账的自动快照；ERP 生产模块建设时恢复（代码在 git）。 -->
        <el-dropdown trigger="click" @command="onMoreCommand">
          <el-button plain>
            更多
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="reset">重导准备</el-dropdown-item>
              <el-dropdown-item command="mapping">映射改判</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <ReturnToSourceButton label="返回订单并复查" />
      </div>
    </header>
    <el-collapse-transition>
      <H3ImportGuide v-if="guideVisible" @close="guideVisible = false" @reset="openResetPreview" @action="handleGuideAction" />
    </el-collapse-transition>
    <div class="section-tabs" role="tablist">
      <button
        type="button"
        role="tab"
        :aria-selected="section === 'ledger'"
        :class="['section-tab', { active: section === 'ledger' }]"
        @click="switchSection('ledger')"
      >
        <el-icon class="section-tab__icon"><Files /></el-icon>
        历史台账
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="section === 'import'"
        :class="['section-tab', { active: section === 'import' }]"
        @click="switchSection('import')"
      >
        <el-icon class="section-tab__icon"><UploadFilled /></el-icon>
        导入与批次
        <span
          v-if="nonTerminalCount"
          class="section-tab__badge"
          :title="'有 ' + nonTerminalCount + ' 个批次处理中/待确认'"
          >{{ nonTerminalCount }}</span
        >
      </button>
    </div>
    <H3ImportBatchPanel
      v-if="section === 'import'"
      v-model:batch-type="batchType"
      v-model:page="batchPage"
      v-model:size="batchSize"
      :batches="batches"
      :loading="batchLoading"
      :total="batchTotal"
      :last-result="lastImportResult"
      @reload="loadBatches"
      @next="runBatchNextAction"
      @retry="retryBatch"
      @continue="continueBatch"
      @failure-report="downloadFailureReport"
      @update-detail="viewBatchUpdates"
      @dismiss-result="lastImportResult = null"
    />
    <!-- 历史台账：四类氚云导入数据源各保留一份原始数据台账（并入本页，原「废弃台账」独立路由已移除） -->
    <section v-else-if="section === 'ledger'" class="panel ledger-panel">
      <div class="panel-head">
        <div>
          <h2>氚云原始数据台账</h2>
          <p>
            销售出库/生产任务/收款结清确认后保留行级原始快照（h3_import_rows）；产品信息、仓库明细与经销商按批次导入（只增改、不覆盖），确认写入台账后可在此只读核对，产品与仓库台账还可继续执行合并入库。
          </p>
        </div>
        <el-tag type="info" effect="plain">历史台账</el-tag>
      </div>
      <el-radio-group v-model="ledgerType" class="ledger-type-tabs" @change="onLedgerTypeChange">
        <el-radio-button v-for="t in ledgerTypes" :key="t.type" :value="t.type">{{ t.label }}</el-radio-button>
      </el-radio-group>
      <H3LedgerPanel :key="ledgerKey" :ledger-type="ledgerType" :initial-keyword="ledgerKeyword" />
    </section>
    <!-- 导入预览弹窗（内容已拆至 H3ImportPreviewDialog） -->
    <H3ImportPreviewDialog
      v-model:model-value="importVisible"
      :kind="previewDialogKind"
      :file="selectedFile"
      :batch-id="batchId"
      :parse-progress="parseProgress"
      :preview="preview"
      :ledger-rows="ledgerRows"
      :loading="importLoading"
      :confirm-text="confirmBtnText"
      :confirm-enabled="!confirmBtnDisabled"
      @confirm="importConfirm"
      @file-change="onDialogFileChange"
    />
    <el-dialog v-model="resetPreviewVisible" title="氚云全量重导 · 影响预检" width="min(980px, 94vw)" destroy-on-close>
      <div v-loading="resetPreviewLoading">
        <el-alert
          v-if="resetPreview"
          type="warning"
          :closable="false"
          :title="resetPreview.message"
          description="影响预检不会删除数据。先选择身份策略并创建保护快照；快照创建完成后，系统才会开放来源重置操作。"
        />
        <template v-if="resetPreview">
          <h3>重导后身份规则如何处理</h3>
          <el-radio-group v-model="resetIdentityMode" :disabled="!!resetPreparation">
            <el-radio value="PRESERVE_IDENTITIES">保留既有映射（稳定运营）</el-radio>
            <el-radio value="REASSESS_IDENTITIES">重新评估映射（建设期推荐）</el-radio>
          </el-radio-group>
          <el-alert
            style="margin-top: 10px"
            :type="resetIdentityMode === 'REASSESS_IDENTITIES' ? 'warning' : 'info'"
            :closable="false"
            :title="resetIdentityMode === 'REASSESS_IDENTITIES' ? '旧客户身份簇、员工、产品、仓库映射会先完整快照，再随来源重置清理' : '现有映射会在重导后继续复用，规则不会重新评估'"
            description="重新评估只清来源规则，不删除客户、员工、产品和仓库主档；重导后须核对关联。任何提成核算版本引用氚云出库都会阻止重置。"
          />
          <h3>可从氚云重新建立的来源数据</h3>
          <el-table :data="resetPreview.sourceData" stripe max-height="280">
            <el-table-column prop="label" label="数据" min-width="190" />
            <el-table-column prop="count" label="当前行数" width="120" align="right" />
            <el-table-column prop="handling" label="重导处理" min-width="360" />
          </el-table>
          <h3>必须保护或重算的下游数据</h3>
          <el-table :data="resetPreview.downstreamImpacts" stripe max-height="280">
            <el-table-column prop="label" label="影响项" min-width="190" />
            <el-table-column prop="count" label="当前行数" width="120" align="right" />
            <el-table-column prop="handling" label="处理要求" min-width="360" />
          </el-table>
          <el-alert
            v-if="resetPreview.blockers.length"
            style="margin-top: 16px"
            type="error"
            :closable="false"
            :title="`发现 ${resetPreview.blockers.length} 类阻断事项，当前不能执行重置`"
          >
            <ul class="reset-blockers">
              <li v-for="item in resetPreview.blockers" :key="item.code">{{ item.message }}（{{ item.count }}）</li>
            </ul>
          </el-alert>
          <el-alert
            v-if="resetPreparation"
            style="margin-top: 16px"
            type="success"
            :closable="false"
            title="保护快照已创建"
          >
            <div>快照 #{{ resetPreparation.runId }}，身份映射 {{ resetPreparation.identityCount }} 条，完整规则 {{ resetPreparation.ruleSnapshotCount }} 条，业务性质 {{ resetPreparation.outboundNatureCount }} 条。</div>
            <div>策略：{{ resetPreparation.mode === 'REASSESS_IDENTITIES' ? '重新评估旧映射' : '保留旧映射' }}</div>
            <div class="reset-token">重置令牌：<code>{{ resetPreparation.resetToken }}</code></div>
          </el-alert>
        </template>
      </div>
      <template #footer>
        <el-button @click="resetPreviewVisible = false">关闭</el-button>
        <el-button
          v-if="!resetPreparation"
          type="primary"
          :loading="resetPrepareLoading"
          :disabled="!resetPreview?.executionAllowed"
          @click="prepareResetProtection"
        >{{ resetIdentityMode === 'REASSESS_IDENTITIES' ? '创建重新评估快照' : '创建保留映射快照' }}</el-button>
        <el-button
          v-if="resetPreparation"
          :loading="resetPrepareLoading"
          @click="cancelPreparedReset"
        >放弃快照并重新选择</el-button>
        <el-button
          v-if="resetPreparation"
          type="danger"
          :loading="resetExecuteLoading"
          :disabled="!resetPreview?.executionAllowed"
          @click="executePreparedReset"
        >确认清理氚云来源数据</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="mappingVisible" title="建设期身份映射改判" width="min(960px, 94vw)" destroy-on-close>
      <el-alert type="warning" :closable="false" title="来源事实不变；只修改当前确实指向旧目标的氚云客户或员工关联。已进入正式提成版本的员工归属禁止直接改判。" />
      <div class="mapping-toolbar">
        <el-radio-group v-model="mappingType" @change="loadMappingRows"><el-radio-button value="CUSTOMER">客户</el-radio-button><el-radio-button value="EMPLOYEE">员工</el-radio-button></el-radio-group>
        <el-input v-model="mappingKeyword" clearable placeholder="搜索氚云原始名称" @keyup.enter="loadMappingRows" />
        <el-button @click="loadMappingRows">查询</el-button>
      </div>
      <el-table v-loading="mappingLoading" :data="mappingRows" max-height="440" stripe>
        <el-table-column prop="sourceName" label="氚云来源名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="targetName" label="当前目标" min-width="180" show-overflow-tooltip />
        <el-table-column prop="resolutionStatus" label="状态" width="105" />
        <el-table-column prop="resolutionNote" label="依据" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="row.targetId && ['AUTO','CONFIRMED'].includes(row.resolutionStatus)" link type="warning" @click="selectMapping(row)">改判</el-button></template></el-table-column>
      </el-table>
      <el-pagination :current-page="mappingPage" :page-size="20" :total="mappingTotal" layout="total, prev, pager, next" @current-change="mappingPage = $event; loadMappingRows()" />
      <div v-if="selectedMapping" class="mapping-reassign">
        <b>改判：{{ selectedMapping.sourceName }}（当前 {{ selectedMapping.targetName }}）</b>
        <el-select v-model="mappingTargetId" filterable remote clearable :remote-method="searchMappingTargets" placeholder="搜索新的目标客户 / 员工" style="width: 300px">
          <el-option v-for="target in mappingTargets" :key="target.id" :label="target.name" :value="target.id" />
        </el-select>
        <el-button :disabled="!mappingTargetId" :loading="mappingPreviewLoading" @click="previewMappingReassign">预览影响</el-button>
        <el-descriptions v-if="mappingPreview" :column="3" border size="small">
          <el-descriptions-item label="销售出库">{{ mappingPreview.affected.sales_outbounds || 0 }} 张</el-descriptions-item>
          <el-descriptions-item label="生产工单">{{ mappingPreview.affected.production_orders || 0 }} 张</el-descriptions-item>
          <el-descriptions-item label="收款台账">{{ mappingPreview.affected.receipts || 0 }} 条</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="mappingPreview && !mappingPreview.allowed" type="error" :closable="false" :title="`已有 ${mappingPreview.finalCommissionVersions} 个正式提成版本引用旧员工归属，不能直接改判`" />
      </div>
      <template #footer><el-button @click="mappingVisible = false">关闭</el-button><el-button type="primary" :loading="mappingApplying" :disabled="!mappingPreview?.allowed" @click="applyMappingReassign">确认改判</el-button></template>
    </el-dialog>
    <el-drawer v-model="updateDetailVisible" title="批次更新明细" size="min(980px, 94vw)" destroy-on-close>
      <div v-loading="updateDetailLoading">
        <el-alert
          type="info"
          :closable="false"
          :title="`批次 #${updateDetailBatch?.id ?? ''} 仅展示更新数据；新增和无变化不展开记录。`"
        />
        <el-table :data="updateDetailRows" style="margin-top: 14px" max-height="calc(100vh - 180px)" stripe>
          <el-table-column prop="businessKey" label="业务编号" width="190" show-overflow-tooltip />
          <el-table-column prop="title" label="名称 / 标题" min-width="150" show-overflow-tooltip />
          <el-table-column label="变化字段" min-width="440">
            <template #default="{ row }">
              <div v-if="row.changeItems?.length" class="update-diff-list">
                <div v-for="(change, index) in row.changeItems" :key="index" class="update-diff-item">
                  <b>{{ change.path }}</b>
                  <span class="update-diff-old">{{ change.oldValue }}</span>
                  <span class="update-diff-arrow">→</span>
                  <span class="update-diff-new">{{ change.newValue }}</span>
                </div>
              </div>
              <span v-else class="cell-sub">{{ row.changes || '该历史批次没有可还原的上一版快照' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!updateDetailLoading && !updateDetailRows.length" description="该批次没有更新数据" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onUnmounted, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, UploadFilled, Files } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'
import { ElMessageBox } from 'element-plus'
import ReturnToSourceButton from '@/components/common/ReturnToSourceButton.vue'
import H3LedgerPanel from '@/components/h3/H3LedgerPanel.vue'
import H3ImportPreviewDialog from '@/components/h3/H3ImportPreviewDialog.vue'
import H3ImportBatchPanel from '@/components/h3/H3ImportBatchPanel.vue'
import H3ImportGuide from '@/components/h3/H3ImportGuide.vue'
import type { H3GuideAction } from '@/components/h3/H3ImportGuide.vue'
import { notify } from '@/composables/ui/useToast'
import type { H3LedgerKind, H3ResetIdentityMode } from '@/api/business/orders'
import { fetchReassignableIdentities, fetchReassignTargets, fetchIdentityReassignPreview, reassignSourceIdentity, type ReassignIdentityRow, type IdentityReassignPreview } from '@/api/business/orders'
import {
  stageH3Fact,
  waitH3BatchUntil,
  fetchH3StagingPreview,
  fetchH3BatchPreview,
  confirmH3Staging,
  fetchImportBatches,
  fetchH3ResetPreview,
  prepareH3Reset,
  executeH3Reset,
  cancelH3Reset
} from '@/api/business'
import type {
  H3FactType,
  ImportPreview,
  ImportBatch,
  BatchProgress,
  ImportResult
} from '@/api/business'
import type { H3ResetPreview, H3ResetPreparation } from '@/api/business/orders'
import {
  stageLedgerImport,
  waitLedgerBatchUntil,
  fetchLedgerBatchPreview,
  confirmLedgerBatch
} from '@/api/productInventory'
import type { LedgerImportRow, LedgerImportPreview } from '@/api/productInventory'
import {
  H3_IMPORT_KINDS,
  H3_IMPORT_GROUPS,
  H3_BATCH_FALLBACK_ACTION,
  normalizeH3DbType,
  isH3LedgerDbType,
  h3DbTypeLabel as typeLabel,
  h3BatchStatusLabel as batchStatusLabel,
  h3ProgressStageLabel as progressStageLabel
} from '@/constants/h3Import'
import type { H3ImportKindCard, H3ImportNextAction } from '@/constants/h3Import'

const router = useRouter()
const route = useRoute()
const guideVisible = ref(false)
const mappingVisible = ref(false)
const mappingType = ref<'CUSTOMER' | 'EMPLOYEE'>('CUSTOMER')
const mappingKeyword = ref('')
const mappingPage = ref(1)
const mappingTotal = ref(0)
const mappingLoading = ref(false)
const mappingRows = ref<ReassignIdentityRow[]>([])
const selectedMapping = ref<ReassignIdentityRow | null>(null)
const mappingTargetId = ref<number | null>(null)
const mappingTargets = ref<Array<{ id: number; name: string }>>([])
const mappingPreview = ref<IdentityReassignPreview | null>(null)
watch(mappingTargetId, () => { mappingPreview.value = null })
const mappingPreviewLoading = ref(false)
const mappingApplying = ref(false)
async function openMappingReassign() { mappingVisible.value = true; await loadMappingRows() }
async function loadMappingRows() {
  mappingLoading.value = true
  selectedMapping.value = null; mappingPreview.value = null
  try {
    const result = await fetchReassignableIdentities({ entityType: mappingType.value, keyword: mappingKeyword.value || undefined, page: mappingPage.value, size: 20 })
    mappingRows.value = result.items; mappingTotal.value = result.total
  } catch (e: any) { notify.error(e.message || '映射列表加载失败') }
  finally { mappingLoading.value = false }
}
function selectMapping(row: ReassignIdentityRow) { selectedMapping.value = row; mappingTargetId.value = null; mappingTargets.value = []; mappingPreview.value = null }
async function searchMappingTargets(keyword: string) {
  if (!keyword.trim()) { mappingTargets.value = []; return }
  try { mappingTargets.value = (await fetchReassignTargets(mappingType.value, keyword)).items }
  catch (e: any) { notify.error(e.message || '目标档案搜索失败') }
}
async function previewMappingReassign() {
  if (!selectedMapping.value || !mappingTargetId.value) return
  mappingPreviewLoading.value = true
  try { mappingPreview.value = await fetchIdentityReassignPreview(selectedMapping.value.id, mappingTargetId.value) }
  catch (e: any) { mappingPreview.value = null; notify.error(e.message || '映射改判预览失败') }
  finally { mappingPreviewLoading.value = false }
}
async function applyMappingReassign() {
  if (!selectedMapping.value || !mappingPreview.value?.allowed || mappingTargetId.value !== mappingPreview.value.newTargetId) return
  mappingApplying.value = true
  try {
    const result = await reassignSourceIdentity(selectedMapping.value.id, mappingTargetId.value, '建设期映射规则调整')
    notify.success(`映射已改判；销售出库 ${result.affected.sales_outbounds || 0} 张已调整`)
    await loadMappingRows()
  } catch (e: any) { notify.error(e.message || '映射改判失败') }
  finally { mappingApplying.value = false }
}
const resetPreviewVisible = ref(false)
const resetIdentityMode = ref<H3ResetIdentityMode>('REASSESS_IDENTITIES')
const resetPreviewLoading = ref(false)
const resetPreview = ref<H3ResetPreview | null>(null)
const resetPrepareLoading = ref(false)
const resetExecuteLoading = ref(false)
const resetPreparation = ref<H3ResetPreparation | null>(null)
async function openResetPreview() {
  resetPreviewVisible.value = true
  resetPreviewLoading.value = true
  try {
    resetPreview.value = await fetchH3ResetPreview()
  } catch (e: any) {
    notify.error(e.message || '重导影响预检失败')
  } finally {
    resetPreviewLoading.value = false
  }
}
async function prepareResetProtection() {
  resetPrepareLoading.value = true
  try {
    const result = await prepareH3Reset(resetIdentityMode.value)
    resetPreparation.value = result
    notify.success(`保护快照 #${result.runId} 已创建：身份映射 ${result.identityCount} 条、完整规则 ${result.ruleSnapshotCount} 条、退换货确认 ${result.outboundNatureCount} 条`)
    resetPreview.value = await fetchH3ResetPreview()
  } catch (e: any) {
    notify.error(e.message || '保护快照创建失败')
  } finally {
    resetPrepareLoading.value = false
  }
}
async function cancelPreparedReset() {
  const prepared = resetPreparation.value
  if (!prepared) return
  resetPrepareLoading.value = true
  try {
    await cancelH3Reset(prepared.resetToken)
    resetPreparation.value = null
    notify.success('本次快照令牌已作废，请重新选择身份策略')
  } catch (e: any) {
    notify.error(e.message || '保护快照作废失败')
  } finally {
    resetPrepareLoading.value = false
  }
}
async function executePreparedReset() {
  const prepared = resetPreparation.value
  if (!prepared) return
  try {
    const answer = await ElMessageBox.prompt(
      `此操作会删除氚云来源台账和批次。身份策略：${prepared.mode === 'REASSESS_IDENTITIES' ? `旧映射规则 ${prepared.ruleSnapshotCount} 条已快照，执行时将清理并在重导后重新评估` : '保留并复用既有映射'}。请输入下方完整令牌：\n${prepared.resetToken}`,
      '确认执行氚云来源重置',
      {
        confirmButtonText: '执行重置', cancelButtonText: '取消', type: 'warning',
        inputPlaceholder: '粘贴完整重置令牌',
        inputValidator: (value: string) => value === prepared.resetToken || '令牌不一致'
      }
    )
    resetExecuteLoading.value = true
    const result = await executeH3Reset(answer.value)
    notify.success(`重置完成：出库 ${result.outboundCount}，生产 ${result.productionCount}，收款/结清 ${result.receiptCount} 条`)
    resetPreparation.value = null
    resetPreview.value = await fetchH3ResetPreview()
    await loadBatches()
    ledgerKey.value += 1
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return
    notify.error(e.message || '氚云来源重置失败，事务已回滚')
  } finally {
    resetExecuteLoading.value = false
  }
}

// ===== 页面分区：导入与批次 / 历史台账（四类氚云数据源台账并入本页展示，替代原「废弃台账」独立路由） =====
const ledgerTypes: Array<{ type: H3LedgerKind; label: string }> = [
  { type: 'outbound', label: '销售出库' },
  { type: 'production', label: '生产任务工单' },
  { type: 'receipt', label: '收款记录' },
  { type: 'settlement', label: '多订单结清' },
  { type: 'purchase', label: '采购入库' },
  { type: 'otherIn', label: '其他入库' },
  { type: 'otherOut', label: '其他出库' },
  { type: 'transfer', label: '库存调拨' },
  { type: 'product', label: '产品信息' },
  { type: 'stock', label: '仓库明细' },
  { type: 'dealer', label: '经销商' },
  { type: 'supplier', label: '供应商' },
  { type: 'invoiceProfile', label: '开票资料' },
  { type: 'invoiceRequest', label: '开票申请' },
  { type: 'productionReport', label: '生产报工' },
  { type: 'invoiceInput', label: '进项发票' },
  { type: 'invoiceSales', label: '销项发票' },
  { type: 'supplierPayable', label: '供应商应付' },
  { type: 'cashflowJournal', label: '流水日记账' },
  { type: 'cashierLog', label: '收银通流水' }
]
const section = ref<'import' | 'ledger'>('import')
const ledgerType = ref<H3LedgerKind>('outbound')
const ledgerKey = ref(0)
/** H3LedgerPanel 挂载时初始搜索词（外部深链带入；切换类型/重挂载后由对应入口重置） */
const ledgerKeyword = ref('')
function syncSectionToUrl() {
  const q: Record<string, string> = {}
  if (section.value === 'import') {
    q.tab = 'import'
  }
  if (section.value === 'ledger') {
    q.ledger = ledgerType.value
  }
  // 保留 returnTo：从出库单详情等来源进入时，"返回来源"按钮需在整页会话内持续可用
  const rt = route.query.returnTo
  if (rt) q.returnTo = String(rt)
  router.replace({ query: q })
}
function switchSection(next: 'import' | 'ledger') {
  section.value = next
  syncSectionToUrl()
}
/** 从导入批次“建议下一步”直接打开对应数据源台账；keyword 可选（外部定位场景传入初始搜索词） */
function openLedger(type: H3LedgerKind, keyword = '') {
  ledgerType.value = type
  ledgerKeyword.value = keyword
  ledgerKey.value++
  section.value = 'ledger'
  syncSectionToUrl()
}
function onLedgerTypeChange() {
  ledgerKeyword.value = ''
  ledgerKey.value++
  syncSectionToUrl()
}
function handleGuideAction(action: H3GuideAction) {
  if (action === 'reset') return openResetPreview()
  if (['product', 'stock', 'dealer', 'outbound', 'production', 'receipt', 'settlement'].includes(action)) {
    return onImportItemClick(action)
  }
  if (action === 'identity') return router.push('/h3-outbound-relations')
  if (action === 'ledger') return openLedger('outbound')
  if (action === 'customers') return router.push('/customers')
  if (action === 'commission') return router.push('/monthly-commission')
}
// 兼容旧版独立台账页深链 / 书签及浏览器前进后退：/h3-imports?tab=ledger&ledger=production 等；
// 出库单详情"复查"深链带 outboundNo：定位到销售出库台账并以该单号作为初始搜索词
watch(
  () => route.query,
  q => {
    const qOutboundNo = q.outboundNo != null ? String(q.outboundNo) : ''
    if (qOutboundNo) {
      ledgerType.value = 'outbound'
      ledgerKeyword.value = qOutboundNo
      ledgerKey.value++
      section.value = 'ledger'
      // 消费后从 URL 移除 outboundNo（保留 returnTo），避免切 Tab/后退时反复重定位
      const nq: Record<string, string> = { tab: 'ledger', ledger: 'outbound' }
      if (q.returnTo != null) nq.returnTo = String(q.returnTo)
      router.replace({ query: nq })
      return
    }
    const qTab = String(q.tab || '')
    const qLedgerLower = String(q.ledger || '').toLowerCase()
    // ⚠️ otherIn/otherOut 是首批驼峰 kind：URL 归一化到小写后必须与 t.type 同型比较，
    //   否则 'otherOut' === 'otherout' 恒 false → 深链静默落回销售出库（transfer 全小写侥幸不触发）。
    const known = ledgerTypes.find(t => t.type.toLowerCase() === qLedgerLower)
    if (qTab === 'import') {
      section.value = 'import'
    } else if (qTab === 'identity') {
      router.replace('/h3-outbound-relations')
    } else if (qTab === 'ledger' || known) {
      section.value = 'ledger'
      // keyword 深链：产品库存详情「台账详情 →」带编号进入，让台账列表直接定位到该行；
      // 未带 keyword 时按空搜索词处理（普通切换台账类型走这里）
      const qKeyword = q.keyword != null ? String(q.keyword) : ''
      const nextType = known ? known.type : ledgerType.value
      if (nextType !== ledgerType.value || qKeyword !== ledgerKeyword.value) {
        ledgerType.value = nextType
        ledgerKeyword.value = qKeyword
        ledgerKey.value++
      }
    } else {
      section.value = 'import'
    }
  },
  { immediate: true }
)

// ===== 六类导入元数据（单一来源 @/constants/h3Import） =====
const importTypes = H3_IMPORT_KINDS
/** 归档组（历史数据导入）在下拉里默认收起，展开状态只存活于本次弹层会话 */
const archiveExpanded = ref(false)
/**
 * 「导入氚云 Excel」下拉分组：全部氚云 Excel 都在同一入口，按 `group` 字段分三组呈现。
 *
 * 统一入口 ≠ 把低频数据摆在日常动线上——归档组默认折叠，但入口与能力都不删：
 * 生产工单将来接入生产模块、收款/结清作为出库单争议旁证，仍需可导入的通道。
 */
const importDropdownGroups = computed(() =>
  H3_IMPORT_GROUPS.map(g => {
    const all = importTypes.filter(item => item.group === g.key)
    const collapsed = g.collapsed && !archiveExpanded.value
    return {
      key: g.key,
      label: g.label,
      hint: g.hint,
      collapsible: g.collapsed && all.length > 0,
      collapsed,
      total: all.length,
      items: collapsed ? [] : all
    }
  }).filter(g => g.collapsible || g.items.length > 0)
)
/** 隐藏上传入口，type → el-upload 实例（6 类：fact 4 + ledger 2） */
const factUploadRefs = ref<Record<string, { $el?: HTMLElement } | null>>({})
function setFactUploadRef(type: string, el: { $el?: HTMLElement } | null) {
  if (el) factUploadRefs.value[type] = el
}
/** el-dropdown @command：点击某分类时触发对应隐藏 el-upload 的原生文件选择 */
function triggerFactUpload(type: string) {
  const uploader = factUploadRefs.value[type]
  const root = uploader?.$el
  const input = root?.querySelector?.('input[type="file"]') as HTMLInputElement | null
  input?.click()
}
/** 按 domain 路由到对应导入链路：fact 单据编排 / ledger 台账比对 */
function chooseKindFile(card: H3ImportKindCard, file: UploadFile) {
  if (card.domain === 'ledger') chooseLedgerFile(card, file)
  else chooseFile(card, file)
}
/** 自管 popover：替代 el-dropdown（el-button 作 trigger 时 v-bubble 不可靠） */
const importPopoverVisible = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
function onImportItemClick(type: string) {
  importPopoverVisible.value = false
  // 等 popover 收起后再打开文件选择，避免点击穿透被遮罩吞
  nextTick(() => triggerFactUpload(type))
}
function onDocMouseDown(e: MouseEvent) {
  if (!importPopoverVisible.value) return
  const root = triggerRef.value
  if (root && !root.contains(e.target as Node)) importPopoverVisible.value = false
}
onMounted(() => document.addEventListener('mousedown', onDocMouseDown))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocMouseDown))
/** 导入卡图标样式：背景 = card.color；存在 fg 时覆盖默认文字色 #24415d */
function cardIconStyle(card: { color: string; fg?: string }) {
  return { background: card.color, ...(card.fg ? { color: card.fg } : {}) }
}

const batchType = ref(''),
  batches = ref<ImportBatch[]>([]),
  batchLoading = ref(false)
/** 批次列表分页：与后端 PageResult 对齐 */
const batchPage = ref(1)
const batchSize = ref(10)
const batchTotal = ref(0)
/** 页签徽标：处理中（含待确认）的批次数量 */
const nonTerminalCount = computed(
  () => batches.value.filter(b => ['UPLOADED', 'PARSING', 'PREVIEW_READY', 'CONFIRMING'].includes(b.status)).length
)
const importVisible = ref(false),
  selectedImport = ref<H3ImportKindCard | null>(null),
  selectedFile = ref<File | null>(null),
  preview = ref<ImportPreview | LedgerImportPreview | null>(null),
  importLoading = ref(false),
  batchId = ref<number | null>(null),
  parseProgress = ref<BatchProgress | null>(null),
  lastImportResult = ref<ImportResult | null>(null)
const confirmBtnText = computed(() => {
  if (parseProgress.value?.status === 'FAILED') return '重试解析'
  if (parseProgress.value && !preview.value) return '解析中…'
  if (preview.value) return '确认导入'
  return '开始解析'
})
const confirmBtnDisabled = computed(
  () => !!parseProgress.value && !preview.value && parseProgress.value.status !== 'FAILED'
)
/** 弹窗展示元数据（与 H3ImportPreviewDialog.PreviewKind 兼容；constants 的 catalog=产品信息台账 → product） */
const previewDialogKind = computed(() =>
  selectedImport.value
    ? {
        title: selectedImport.value.title,
        ledgerKind:
          selectedImport.value.ledgerKind === 'catalog'
            ? ('product' as const)
            : (selectedImport.value.ledgerKind ?? null)
      }
    : null
)
const ledgerRows = computed<LedgerImportRow[]>(() => (preview.value?.rows ?? []) as unknown as LedgerImportRow[])
/** COMPLETED 后的「建议下一步」由卡元数据（@/constants/h3Import）驱动，无多余 switch */
function batchNextAction(batch?: ImportBatch | null): H3ImportNextAction {
  const item = importTypes.find(kind => kind.dbType === normalizeH3DbType(batch?.dataType))
  return item?.next ?? H3_BATCH_FALLBACK_ACTION
}
function runBatchNextAction(batch?: ImportBatch | null) {
  const action = batchNextAction(batch)
  if (action.ledger) openLedger(action.ledger)
  else router.push({ path: action.path, query: action.query })
}
function chooseFile(card: H3ImportKindCard, file: UploadFile) {
  selectedImport.value = card
  selectedFile.value = file.raw || null
  preview.value = null
  batchId.value = null
  parseProgress.value = null
  importVisible.value = true
}
function onDialogFileChange(file: UploadFile) {
  selectedFile.value = file.raw || null
  parseProgress.value = null
  batchId.value = null
}
async function importConfirm() {
  // 台账类导入（产品信息/仓库明细）走自己的 登记批次→比对预览→确认 流程
  if (selectedImport.value?.ledgerKind) return importLedgerConfirm()
  if (!selectedFile.value && !preview.value) {
    notify.warning('请先选择 Excel 文件')
    return
  }
  try {
    importLoading.value = true
    if (preview.value && batchId.value != null) {
      const r = await confirmH3Staging(batchId.value)
      // P0-A：confirm 后台自动编排中（CONFIRMING → COMPLETED/PARTIAL_FAILED），不阻塞用户
      notify.success('数据已写入，系统正在刷新销售出库收款状态和利润结果；完成后可直接查看对应台账')
      lastImportResult.value = r
      importVisible.value = false
      await loadBatches()
      // 立刻触发一次轮询检查（编排可能很快完成）
      scheduleBatchPolling()
      return
    }
    if (parseProgress.value?.status === 'FAILED') {
      parseProgress.value = null
      batchId.value = null
    }
    if (!selectedFile.value) return
    const accepted = await stageH3Fact(selectedImport.value!.type as H3FactType, selectedFile.value)
    batchId.value = accepted.batchId
    if (accepted.hashHit) notify.warning(accepted.message || '命中历史解析结果，跳过重复解析')
    const finalP = await waitH3BatchUntil(accepted.batchId, ['PREVIEW_READY', 'COMPLETED'], {
      onProgress(p) {
        parseProgress.value = p
      }
    })
    parseProgress.value = finalP
    if (finalP.status === 'FAILED') {
      notify.error(finalP.errorMessage || '解析失败，请查看 error_detail')
      return
    }
    const staging = await fetchH3StagingPreview(accepted.batchId)
    preview.value = staging.preview
    batchId.value = staging.batchId
  } catch (e) {
    notify.error((e as any).message || '导入失败')
  } finally {
    importLoading.value = false
  }
}
function chooseLedgerFile(card: H3ImportKindCard, file: UploadFile) {
  selectedImport.value = card.ledgerKind ? card : null
  selectedFile.value = file.raw || null
  preview.value = null
  batchId.value = null
  parseProgress.value = null
  importVisible.value = true
}
/** 台账类导入：上传登记批次 → 后台解析轮询进度 → 比对预览（新增/更新/无变化） → 确认写入台账 */
async function importLedgerConfirm() {
  const kind = selectedImport.value?.ledgerKind
  if (!kind) return
  if (!selectedFile.value && !preview.value) {
    notify.warning('请先选择 Excel 文件')
    return
  }
  try {
    importLoading.value = true
    if (preview.value && batchId.value != null) {
      const r = await confirmLedgerBatch(batchId.value)
      notify.success(
        `批次 #${r.batchId} 已确认：新增 ${r.createdCount}、更新 ${r.updatedCount}、无变化 ${r.unchangedCount}，可在「历史台账」中查看`
      )
      lastImportResult.value = {
        batchId: r.batchId,
        dataType: r.dataType,
        totalCount: r.totalCount,
        createdCount: r.createdCount,
        updatedCount: r.updatedCount,
        unchangedCount: r.unchangedCount,
        childCount: 0
      }
      importVisible.value = false
      await loadBatches()
      return
    }
    if (!selectedFile.value) return
    const res = await stageLedgerImport(kind, selectedFile.value)
    batchId.value = res.batchId
    // 立刻展示进度条初态，再轮询后台解析进度（PARSING → STAGING → PREVIEW_READY）
    parseProgress.value = {
      batchId: res.batchId,
      status: res.hashHit ? 'PREVIEW_READY' : res.status || 'UPLOADED',
      progressPct: res.hashHit ? 100 : 0,
      progressStage: res.hashHit ? 'PREVIEW' : 'UPLOAD'
    }
    if (res.hashHit) notify.warning(res.message || '命中未确认的相同文件，已复用解析结果')
    const finalP = await waitLedgerBatchUntil(res.batchId, ['PREVIEW_READY', 'COMPLETED'], {
      onProgress(p) {
        parseProgress.value = p
      }
    })
    parseProgress.value = finalP
    if (finalP.status === 'FAILED') {
      notify.error(finalP.errorMessage || '解析失败，请查看 error_detail')
      return
    }
    const pv = await fetchLedgerBatchPreview(res.batchId)
    preview.value = pv
    selectedFile.value = null
  } catch (e) {
    notify.error((e as any).message || '导入失败')
  } finally {
    importLoading.value = false
  }
}
async function loadBatches(page?: number) {
  if (page !== undefined) batchPage.value = page
  batchLoading.value = true
  try {
    const r = await fetchImportBatches({
      type: batchType.value || undefined,
      page: batchPage.value,
      size: batchSize.value
    })
    batches.value = r.items
    batchTotal.value = r.total
  } catch (e: any) {
    notify.error(e.message || '导入记录加载失败')
  } finally {
    batchLoading.value = false
  }
}
function retryBatch(row: ImportBatch) {
  const normalized = normalizeH3DbType(row.dataType)
  selectedImport.value = importTypes.find(item => item.dbType === normalized) || null
  selectedFile.value = null
  preview.value = null
  batchId.value = null
  parseProgress.value = null
  importVisible.value = true
  notify.warning('请重新选择原始 Excel，系统会创建新批次并保留本次失败记录')
}
async function continueBatch(row: ImportBatch) {
  const normalized = normalizeH3DbType(row.dataType)
  selectedImport.value = importTypes.find(item => item.dbType === normalized) || null
  if (!selectedImport.value) {
    notify.error('无法识别该批次的数据类型')
    return
  }
  importLoading.value = true
  try {
    const staging = isH3LedgerDbType(row.dataType)
      ? { batchId: row.id, preview: (await fetchLedgerBatchPreview(row.id)) as ImportPreview | LedgerImportPreview }
      : await fetchH3StagingPreview(row.id)
    batchId.value = staging.batchId
    preview.value = staging.preview
    selectedFile.value = null
    parseProgress.value = { batchId: row.id, status: 'PREVIEW_READY', progressPct: 100, progressStage: 'PREVIEW' }
    importVisible.value = true
  } catch (e: any) {
    notify.error(e.message || '导入预览加载失败')
  } finally {
    importLoading.value = false
  }
}
function downloadFailureReport(row: ImportBatch) {
  const details = String(row.errorDetail || row.errorMessage || '未记录错误原因')
    .split(/\r?\n/)
    .filter(Boolean)
  const lines: unknown[][] = [['批次', '文件', '数据类型', '状态', '失败阶段', '明细序号', '错误明细', '创建时间']]
  details.forEach((detail, index) =>
    lines.push([
      row.id,
      row.fileName,
      typeLabel(row.dataType),
      batchStatusLabel(row.status),
      progressStageLabel(row.progressStage),
      index + 1,
      detail,
      dateCell(null, null, row.createdAt)
    ])
  )
  const csv =
    '\ufeff' +
    lines.map(line => line.map(value => `"${String(value ?? '').replace(/"/g, '""')}"`).join(',')).join('\r\n')
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `氚云导入失败报告_${row.id}.csv`
  link.click()
  URL.revokeObjectURL(url)
}
function dateCell(_r: any, _c: any, v: any) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
// P0-A：批次轮询——存在非终态批次（UPLOADED/PARSING/PREVIEW_READY/CONFIRMING）时每 5s 刷新
const NON_TERMINAL_STATUSES = ['UPLOADED', 'PARSING', 'PREVIEW_READY', 'CONFIRMING']
let batchPollTimer: ReturnType<typeof setInterval> | null = null
function scheduleBatchPolling() {
  if (batchPollTimer) return
  batchPollTimer = setInterval(async () => {
    if (!batches.value.some(b => NON_TERMINAL_STATUSES.includes(b.status))) {
      // 全部终态，停止轮询
      if (batchPollTimer) {
        clearInterval(batchPollTimer)
        batchPollTimer = null
      }
      return
    }
    try {
      const r = await fetchImportBatches({
        type: batchType.value || undefined,
        page: batchPage.value,
        size: batchSize.value
      })
      const prevById = new Map(batches.value.map(b => [b.id, b.status]))
      // 若任一非终态批次刚刚切到终态（COMPLETED/PARTIAL_FAILED/FAILED），提示用户
      const justFinished = r.items.filter(b => {
        const prev = prevById.get(b.id)
        return prev && NON_TERMINAL_STATUSES.includes(prev) && !NON_TERMINAL_STATUSES.includes(b.status)
      })
      batches.value = r.items
      batchTotal.value = r.total
      if (justFinished.length) {
        const b = justFinished[0]
        const label =
          b.status === 'COMPLETED' ? '编排完成' : b.status === 'PARTIAL_FAILED' ? '编排部分失败' : '编排失败'
        if (b.status === 'COMPLETED')
          notify.success('批次 #' + b.id + ' ' + label + '：' + batchNextAction(b).description)
        else notify.warning('批次 #' + b.id + ' ' + label + '，请查看错误信息')
      }
    } catch (e) {
      /* 静默失败，避免轮询错误打扰用户 */
    }
  }, 5000)
}

loadBatches()
// P0-A：必须在 batchPollTimer 初始化后启动，避免 setup 阶段触发暂时性死区错误。
scheduleBatchPolling()

// ===== 台账批次导入 =====
// 「库存合并入库 / 库存对账」入口已下线（2026-09-16）：现阶段正式库存账无业务消费者，
// 产品库存页即两张台账的自动快照；后端 /merge/** 端点与组件 H3MergeInventoryDialog 保留，
// ERP 生产模块建设时恢复（历史实现见 git）。

/** 「更多」菜单：低频治理动作的统一入口（重导准备 / 映射改判） */
function onMoreCommand(cmd: string) {
  if (cmd === 'reset') return openResetPreview()
  if (cmd === 'mapping') return void openMappingReassign()
}

interface UpdateDetailRow {
  businessKey: string
  title?: string
  changes?: string
  changeItems?: Array<{ path: string; oldValue: string; newValue: string }>
}
const updateDetailVisible = ref(false)
const updateDetailLoading = ref(false)
const updateDetailBatch = ref<ImportBatch | null>(null)
const updateDetailRows = ref<UpdateDetailRow[]>([])
async function viewBatchUpdates(row: ImportBatch) {
  updateDetailBatch.value = row
  updateDetailRows.value = []
  updateDetailVisible.value = true
  updateDetailLoading.value = true
  try {
    const payload: any = await fetchH3BatchPreview(row.id)
    const previewData = payload?.preview ?? payload
    updateDetailRows.value = (previewData?.rows ?? [])
      .filter((item: any) => item.action === 'UPDATE')
      .map((item: any) => ({
        businessKey: item.businessKey || [item.warehouse, item.productCode].filter(Boolean).join(' / '),
        title: item.title || item.productName,
        changes: item.changes,
        changeItems: Array.isArray(item.changes) ? item.changes : undefined
      }))
  } catch (e: any) {
    notify.error(e.message || '更新明细加载失败')
  } finally {
    updateDetailLoading.value = false
  }
}

onUnmounted(() => {
  if (batchPollTimer) {
    clearInterval(batchPollTimer)
    batchPollTimer = null
  }
})
</script>

<style scoped src="@/styles/h3-import-center.css"></style>
