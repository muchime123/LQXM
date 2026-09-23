<template>
  <div class="exec-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">MANUFACTURING EXECUTION</p>
        <h1>车间执行</h1>
        <p>车间按工序报工；质检或跟单复核报工，全部工序完成后检验，合格即办理完工入库。</p>
      </div>
      <div class="head-actions">
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="createOpen = true">新建生产任务单</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </header>

    <el-tabs v-model="tab" class="exec-tabs" @tab-change="onTabChange">
      <el-tab-pane v-if="canReadTasks" label="任务单总览" name="tasks">
        <section class="panel">
          <div class="toolbar">
            <el-input v-model="taskKeyword" clearable class="kw-input" placeholder="任务单号 / 客户 / 产品" @keyup.enter="loadTasks(1)" @clear="loadTasks(1)" />
            <el-select v-model="taskStatus" clearable placeholder="全部任务状态" class="sel-state" @change="loadTasks(1)"><el-option v-for="(label, value) in ORDER_STATUS" :key="value" :value="value" :label="label" /></el-select>
            <el-button type="primary" @click="loadTasks(1)">查询</el-button>
          </div>
          <p class="scope-note">一行一张任务单。全部工序完成后进入人工检验；检验合格与入库同次办理。</p>
          <el-table :data="taskItems" v-loading="tasksLoading" stripe>
            <el-table-column label="任务单号" width="170"><template #default="{ row }"><el-button link type="primary" @click="openTaskDetail(row.id)">{{ row.orderNo }}</el-button></template></el-table-column>
            <el-table-column prop="productNameSnapshot" label="产品" min-width="180" />
            <el-table-column prop="customerNameSnapshot" label="客户" min-width="130" />
            <el-table-column prop="workshopType" label="车间" width="130" />
            <el-table-column prop="processTemplateNameSnapshot" label="制程" min-width="140" />
            <el-table-column prop="plannedQuantity" label="任务数量" width="95" />
            <el-table-column prop="dueDate" label="交期" width="120" />
            <el-table-column label="任务状态" width="110"><template #default="{ row }">{{ orderLabel(row.status) }}</template></el-table-column>
            <el-table-column label="操作" width="170"><template #default="{ row }">
              <el-button link type="primary" @click="openTaskDetail(row.id)">详情</el-button>
              <el-button v-if="canInspect && ['PENDING_INSPECTION','PENDING_REINSPECTION'].includes(row.status)" link type="success" @click="openQuality(row.id)">检验入库</el-button>
            </template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="taskPage" :page-size="20" :total="taskTotal" layout="total, prev, pager, next" @current-change="loadTasks" />
        </section>
      </el-tab-pane>
      <!-- ==================== 执行看板 ==================== -->
      <el-tab-pane label="执行看板" name="board">
        <section class="stats-grid">
          <button v-for="s in statCards" :key="s.key" type="button" class="stat-card"
            :class="{ 'stat-card--active': boardState === s.state, 'stat-card--warn': s.warn && s.value > 0 }"
            @click="applyStat(s.state)">
            <span class="stat-card__label">{{ s.label }}</span>
            <span class="stat-card__value">{{ s.value }}</span>
            <span class="stat-card__hint">{{ s.hint }}</span>
          </button>
        </section>

        <section class="panel">
          <div class="toolbar">
            <el-input v-model="boardKeyword" clearable placeholder="工单号 / 销售单号 / 客户 / 产品 / 工序" class="kw-input"
              @keyup.enter="loadBoard()" @clear="loadBoard()">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-select v-model="boardWorkshop" clearable placeholder="全部车间" class="sel-workshop" @change="loadBoard()">
              <el-option v-for="w in boardWorkshops" :key="w" :value="w" :label="w" />
            </el-select>
            <el-select v-model="boardState" clearable placeholder="全部工序状态" class="sel-state" @change="loadBoard()">
              <el-option v-for="s in STATE_OPTIONS" :key="s.value" :value="s.value" :label="s.label" />
            </el-select>
            <el-button type="primary" plain @click="loadBoard()">查询</el-button>
          </div>

          <div class="scope-note">
            <span><b>执行看板</b> = 未完工工单的<b>未完成工序</b>（工序状态 待开工 / 进行中 / 受阻，且工单处于已下达 ~ 返工中）</span>
            <span class="scope-note__sep">·</span>
            <span>报工即落账，可再次报工累计；<b>超额报工需填原因</b>并具备「超额报工与报工冲正」权限</span>
            <span class="scope-note__sep">·</span>
            <span>每次提交都会带请求键，网络重试不会重复落账</span>
          </div>

          <el-table :data="boardItems" v-loading="loading" stripe class="wide-table">
            <el-table-column label="工单 / 销售单号" width="190" fixed>
              <template #default="{ row }">
                <el-button link type="primary" @click="openTaskDetail(row.manufacturingOrderId)">{{ row.orderNo }}</el-button>
                <small class="cell-sub">{{ row.salesOrderRef || '无销售单号' }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="customerNameSnapshot" label="客户" min-width="140" show-overflow-tooltip />
            <el-table-column label="产品" min-width="190" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.productNameSnapshot }}</span>
                <el-tag v-if="row.customRequirement" size="small" type="warning" effect="plain" class="ml6">定制</el-tag>
                <small class="cell-sub">{{ [row.specificationSnapshot, row.workshopType].filter(Boolean).join(' · ') || '—' }}</small>
              </template>
            </el-table-column>
            <el-table-column label="工序" width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="seq-badge">{{ row.sequenceNo }}</span>
                <span>{{ row.operationNameSnapshot }}</span>
                <small class="cell-sub">{{ row.processTemplateNameSnapshot || '无制程模板' }}</small>
              </template>
            </el-table-column>
            <el-table-column label="整单制程进度" width="175">
              <template #default="{ row }">
                <template v-if="stepPercent(row.completedOperationCount, row.requiredOperationCount) !== null">
                  <span>{{ row.completedOperationCount }} / {{ row.requiredOperationCount }} 道必做工序完成</span>
                  <el-progress :percentage="stepPercent(row.completedOperationCount, row.requiredOperationCount) || 0" :stroke-width="6" />
                </template><span v-else>暂无必做工序进度</span>
              </template>
            </el-table-column>
            <el-table-column label="本工序数量进度" width="170">
              <template #default="{ row }">
                <div class="progress-cell">
                  <span>{{ fmtNum(row.reportedQuantity) }} / {{ fmtNum(row.requiredQuantity) }} {{ row.unit }}</span>
                  <el-progress :percentage="progressOf(row)" :stroke-width="6" :show-text="false"
                    :status="progressOf(row) >= 100 ? 'success' : undefined" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="工序状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="operationTagType(row.operationStatus)">{{ operationLabel(row.operationStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="工单状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="orderTagType(row.orderStatus)">{{ orderLabel(row.orderStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="86">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="priorityTagType(row.priority)">{{ priorityLabel(row.priority) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="计划完成" width="110">
              <template #default="{ row }">
                <span>{{ row.plannedFinishDate || '—' }}</span>
                <small class="cell-sub">交付 {{ row.dueDate || '—' }}</small>
              </template>
            </el-table-column>
            <el-table-column label="生产团队 / 责任人" width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.assignedTeamName || row.productionTeamName || '—' }}</span>
                <small class="cell-sub">
                  {{ row.assignedEmployeeName || row.responsibleEmployeeName || '未分派' }}
                  <template v-if="row.assignedEmployeeName && row.responsibleEmployeeName && row.assignedEmployeeName !== row.responsibleEmployeeName">
                    （负责人 {{ row.responsibleEmployeeName }}）
                  </template>
                </small>
              </template>
            </el-table-column>
            <el-table-column label="异常" width="80" align="center">
              <template #default="{ row }">
                <el-tooltip v-if="row.openIssueCount > 0" placement="top"
                  content="该工序有未处理的生产异常。异常上报/处理入口不属本次范围，暂请在生产异常台账处理">
                  <el-tag size="small" type="danger">{{ row.openIssueCount }}</el-tag>
                </el-tooltip>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="135" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openTaskDetail(row.manufacturingOrderId)">详情</el-button>
                <el-button v-if="canReport" link type="primary" :disabled="reportLoading || !['READY','IN_PROGRESS'].includes(row.operationStatus)" @click="openReport(row)">报工</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="没有待执行的工序" /></template>
          </el-table>

          <div class="pager">
            <el-pagination v-model:current-page="boardPage" v-model:page-size="boardSize" :total="boardTotal"
              :page-sizes="[20, 50, 100]" layout="total,sizes,prev,pager,next"
              @current-change="loadBoard()" @size-change="loadBoard(1)" />
          </div>
        </section>
      </el-tab-pane>

      <!-- ==================== 报工记录 ==================== -->
      <el-tab-pane label="报工记录" name="reports">
        <section class="panel">
          <div class="toolbar">
            <el-input v-model="reportKeyword" clearable placeholder="工单号 / 销售单号 / 客户 / 产品" class="kw-input"
              @keyup.enter="loadReports()" @clear="loadReports()">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-input v-model="reportReporter" clearable placeholder="报工人" class="sel-narrow"
              @keyup.enter="loadReports()" @clear="loadReports()" />
            <el-input v-model="reportOperation" clearable placeholder="工序名称" class="sel-narrow"
              @keyup.enter="loadReports()" @clear="loadReports()" />
            <el-select v-model="reportWorkshop" clearable placeholder="全部车间" class="sel-workshop" @change="loadReports()">
              <el-option v-for="w in reportWorkshops" :key="w" :value="w" :label="w" />
            </el-select>
            <el-select v-model="reportStatus" clearable placeholder="全部状态" class="sel-narrow" @change="loadReports()">
              <el-option value="VALID" label="有效" />
              <el-option value="REVERSED" label="已冲正" />
            </el-select>
            <el-date-picker v-model="reportRange" type="daterange" unlink-panels range-separator="至"
              start-placeholder="报工开始" end-placeholder="报工结束" value-format="YYYY-MM-DD"
              class="sel-range" @change="loadReports()" />
            <el-button type="primary" plain @click="loadReports()">查询</el-button>
          </div>

          <div class="scope-note">
            <span><b>报工记录</b> = 本系统记录的工序完成数量；与氚云导入报工台账分别留存</span>
            <span class="scope-note__sep">·</span>
            <span>冲正不回删原记录，而是标注 <b>已冲正</b> 并回退进度；须先冲正后续工序</span>
            <span class="scope-note__sep">·</span>
            <span v-if="!canOverride" class="scope-note__quiet">当前账号无冲正权限，请由质检或跟单人员处理</span>
          </div>

          <el-table :data="reportItems" v-loading="loading" stripe class="wide-table">
            <el-table-column label="报工单号" width="150" fixed>
              <template #default="{ row }">
                <strong class="cell-strong">{{ row.reportNo }}</strong>
                <small class="cell-sub">{{ row.orderNo }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="customerNameSnapshot" label="客户" min-width="140" show-overflow-tooltip />
            <el-table-column prop="productNameSnapshot" label="产品" min-width="170" show-overflow-tooltip />
            <el-table-column label="工序" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.operationNameSnapshot }}</span>
                <small class="cell-sub">{{ row.workshopType || '未指定车间' }}</small>
              </template>
            </el-table-column>
            <el-table-column label="报工数量" width="120" align="right">
              <template #default="{ row }">
                <span :class="{ 'qty-reversed': row.status === 'REVERSED' }">{{ fmtNum(row.reportedQuantity) }}</span>
                <small class="cell-sub">{{ row.unit }}</small>
              </template>
            </el-table-column>
            <el-table-column label="超额" width="130" align="right">
              <template #default="{ row }">
                <template v-if="Number(row.overproductionQuantity) > 0">
                  <span class="over-qty">+{{ fmtNum(row.overproductionQuantity) }}</span>
                  <small class="cell-sub over-reason">{{ row.overproductionReason || '未填原因' }}</small>
                </template>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="报工人" width="120" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.reporterNameSnapshot || '—' }}</span>
                <small class="cell-sub">{{ row.departmentNameSnapshot || '' }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="reportedAt" label="报工时间" width="160" />
            <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'VALID' ? 'success' : 'info'">
                  {{ row.status === 'VALID' ? '有效' : '已冲正' }}
                </el-tag>
                <small v-if="row.status !== 'VALID' && row.reversalReason" class="cell-sub">{{ row.reversalReason }}</small>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canOverride && row.status === 'VALID'" link type="danger" @click="openReportReverse(row)">冲正</el-button>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <template #empty><el-empty description="没有报工记录" /></template>
          </el-table>

          <div class="pager">
            <el-pagination v-model:current-page="reportPage" v-model:page-size="reportSize" :total="reportTotal"
              :page-sizes="[20, 50, 100]" layout="total,sizes,prev,pager,next"
              @current-change="loadReports()" @size-change="loadReports(1)" />
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane v-if="canInspect" label="待完工检验" name="quality">
        <section class="panel">
          <div class="toolbar">
            <el-input v-model="qualityKeyword" clearable placeholder="工单号 / 客户 / 产品" class="kw-input" @keyup.enter="loadQuality(1)" @clear="loadQuality(1)" />
            <el-button type="primary" @click="loadQuality(1)">查询</el-button>
          </div>
          <p class="scope-note">必做工序全部完成后在此核验。合格与入库一次提交；不合格退回指定工序返工。</p>
          <el-table :data="qualityItems" v-loading="qualityLoading" stripe>
            <el-table-column prop="orderNo" label="任务单号" width="155" />
            <el-table-column prop="customerNameSnapshot" label="客户" min-width="140" />
            <el-table-column prop="productNameSnapshot" label="产品" min-width="180" />
            <el-table-column prop="plannedQuantity" label="计划数量" width="100" />
            <el-table-column prop="productionCompletedAt" label="工序完成时间" width="170" />
            <el-table-column label="状态" width="110"><template #default="{ row }">{{ orderLabel(row.status) }}</template></el-table-column>
            <el-table-column label="操作" width="110"><template #default="{ row }"><el-button link type="primary" @click="openQuality(row.id)">检验入库</el-button></template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="qualityPage" :page-size="20" :total="qualityTotal" layout="total, prev, pager, next" @current-change="loadQuality" />
        </section>
      </el-tab-pane>
      <!-- ==================== 完工入库记录 ==================== -->
      <el-tab-pane label="完工入库记录" name="receipts">
        <section class="panel">
          <div class="toolbar">
            <el-input v-model="receiptKeyword" clearable placeholder="入库单号 / 工单号 / 客户 / 成品名称" class="kw-input"
              @keyup.enter="loadReceipts()" @clear="loadReceipts()">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-select v-model="receiptWarehouseId" clearable placeholder="全部仓库" class="sel-workshop" @change="loadReceipts()">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.warehouseName" />
            </el-select>
            <el-select v-model="receiptStatus" clearable placeholder="全部状态" class="sel-narrow" @change="loadReceipts()">
              <el-option value="POSTED" label="已入库" />
              <el-option value="REVERSED" label="已冲销" />
            </el-select>
            <el-date-picker v-model="receiptRange" type="daterange" unlink-panels range-separator="至"
              start-placeholder="入库开始" end-placeholder="入库结束" value-format="YYYY-MM-DD"
              class="sel-range" @change="loadReceipts()" />
            <el-button type="primary" plain @click="loadReceipts()">查询</el-button>
          </div>

          <div class="scope-note">
            <span><b>完工入库记录</b> = 检验合格后形成的入库事实；历史单据也在此查询</span>
            <span class="scope-note__sep">·</span>
            <span>新任务从「待完工检验」办理，不再单独创建收货单</span>
            <span class="scope-note__sep">·</span>
            <span>冲销只允许在批次未被出库消耗时执行</span>
          </div>

          <el-table :data="receiptItems" v-loading="loading" stripe class="wide-table">
            <el-table-column label="入库单号" width="150" fixed>
              <template #default="{ row }">
                <strong class="cell-strong">{{ row.receiptNo }}</strong>
                <small class="cell-sub">{{ row.orderNo }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="customerNameSnapshot" label="客户" min-width="140" show-overflow-tooltip />
            <el-table-column label="成品" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.finishedGoodsName }}</span>
                <small class="cell-sub">{{ row.specificationSnapshot || '无规格' }}</small>
              </template>
            </el-table-column>
            <el-table-column label="入库数量" width="110" align="right">
              <template #default="{ row }">
                <span :class="{ 'qty-reversed': row.status === 'REVERSED' }">{{ fmtNum(row.quantity) }}</span>
                <small class="cell-sub">{{ row.unit || row.orderUnit }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="warehouseName" label="仓库" width="110" show-overflow-tooltip />
            <el-table-column prop="completedAt" label="入库时间" width="160" />
            <el-table-column prop="operatorName" label="办理人" width="110" show-overflow-tooltip />
            <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
            <el-table-column label="状态" width="140">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'POSTED' ? 'success' : 'info'">
                  {{ row.status === 'POSTED' ? '已入库' : '已冲销' }}
                </el-tag>
                <small v-if="row.status !== 'POSTED' && row.reversalReason" class="cell-sub">
                  {{ row.reversedByName || '' }} {{ row.reversalReason }}
                </small>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canReverseReceipt && row.status === 'POSTED'" link type="danger" @click="openReceiptReverse(row)">冲销</el-button>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <template #empty><el-empty description="没有完工入库记录" /></template>
          </el-table>

          <div class="pager">
            <el-pagination v-model:current-page="receiptPage" v-model:page-size="receiptSize" :total="receiptTotal"
              :page-sizes="[20, 50, 100]" layout="total,sizes,prev,pager,next"
              @current-change="loadReceipts()" @size-change="loadReceipts(1)" />
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 报工弹窗 ==================== -->
    <el-dialog v-model="reportDialogOpen" title="工序报工" width="min(720px, calc(100vw - 32px))" :close-on-click-modal="false" :close-on-press-escape="!submitting" :show-close="!submitting">
      <template v-if="reportTarget">
        <div class="target-card">
          <div class="target-card__main">
            <strong>{{ reportTarget.orderNo }}</strong>
            <span>{{ reportTarget.customerNameSnapshot }} · {{ reportTarget.productNameSnapshot }}</span>
          </div>
          <div class="target-card__op">
            第 {{ reportTarget.sequenceNo }} 道 · {{ reportTarget.operationNameSnapshot }}
          </div>
          <div class="target-card__qty">
            需求 {{ fmtNum(reportTarget.requiredQuantity) }} ｜ 已报 {{ fmtNum(reportTarget.reportedQuantity) }}
            ｜ <b>剩余 {{ fmtNum(reportRemaining) }} {{ reportTarget.unit }}</b>
          </div>
        </div>
        <el-alert :title="reportPreview" type="info" :closable="false" class="over-alert" />
        <el-form :model="reportForm" label-width="100px" :disabled="submitting">
          <el-form-item label="本次完成数量" required>
            <el-input-number v-model="reportForm.quantity" :min="0" :precision="6" :step="1" class="num-input" />
            <span class="unit-hint">{{ reportTarget.unit }}</span>
            <el-button link type="primary" @click="reportForm.quantity = Math.max(0, reportRemaining)">填入全部剩余量</el-button>
          </el-form-item>
          <el-form-item v-if="reportOver > 0" label="超额报工">
            <el-alert type="warning" :closable="false" class="over-alert"
              :title="`超出剩余数量 ${fmtNum(reportOver)} ${reportTarget.unit}`"
              :description="canOverride ? '勾选后需填写超额原因，仅登记本工序完成数量，不会自动入库。' : '当前账号没有「超额报工与报工冲正」权限，无法超额报工。'" />
            <el-checkbox v-model="reportForm.allowOverproduction" :disabled="!canOverride" class="over-check">
              允许超额报工
            </el-checkbox>
          </el-form-item>
          <el-form-item v-if="reportOver > 0" label="超额原因" required>
            <el-input v-model="reportForm.overproductionReason" type="textarea" :rows="2"
              :disabled="!canOverride" placeholder="如 客户补件 / 试机损耗 / 前工序误报废后补产" />
          </el-form-item>
          <el-form-item label="报工时间">
            <el-date-picker v-model="reportForm.reportedAt" type="datetime" placeholder="默认当前时间"
              value-format="YYYY-MM-DDTHH:mm:ss" class="full-width" />
          </el-form-item>
          <el-form-item label="完成内容">
            <el-input v-model="reportForm.remark" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="说明本次完成的部件、工作内容及需要交接的事项" />
          </el-form-item>
        </el-form>
        <h4>本工序最近报工</h4>
        <el-table :data="reportHistory" size="small" max-height="200" empty-text="本工序尚无报工">
          <el-table-column prop="reportedAt" label="时间" min-width="155" />
          <el-table-column prop="reportedQuantity" label="数量" width="80" />
          <el-table-column prop="reporterNameSnapshot" label="人员" width="100" />
          <el-table-column label="状态" width="80"><template #default="{ row }">{{ row.status === 'VALID' ? '有效' : '已冲正' }}</template></el-table-column>
          <el-table-column prop="remark" label="完成内容" min-width="130" />
        </el-table>
      </template>
      <template #footer>
        <el-button :disabled="submitting" @click="reportDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReport">提交报工</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 报工冲正弹窗 ==================== -->
    <el-dialog v-model="reportReverseOpen" title="报工冲正" width="min(520px, calc(100vw - 32px))" :close-on-click-modal="false">
      <template v-if="reportReverseTarget">
        <div class="target-card">
          <div class="target-card__main">
            <strong>{{ reportReverseTarget.reportNo }}</strong>
            <span>{{ reportReverseTarget.orderNo }} · {{ reportReverseTarget.operationNameSnapshot }}</span>
          </div>
          <div class="target-card__qty">
            报工 {{ fmtNum(reportReverseTarget.reportedQuantity) }} {{ reportReverseTarget.unit }}
            ｜ 报工人 {{ reportReverseTarget.reporterNameSnapshot || '—' }}
          </div>
        </div>
        <el-alert type="warning" :closable="false" class="kw-alert"
          title="冲正不回删原记录：原记录标注为「已冲正」，工序已报数量同步回退。若后续工序已报工，需先冲正后续工序。" />
        <el-form label-width="90px">
          <el-form-item label="冲正原因" required>
            <el-input v-model="reportReverseReason" type="textarea" :rows="3" placeholder="请填写冲正原因（必填）" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="reportReverseOpen = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitReportReverse">确认冲正</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 收货冲销弹窗 ==================== -->
    <el-dialog v-model="receiptReverseOpen" title="入库冲销（反入库）" width="min(520px, calc(100vw - 32px))" :close-on-click-modal="false">
      <template v-if="receiptReverseTarget">
        <div class="target-card">
          <div class="target-card__main">
            <strong>{{ receiptReverseTarget.receiptNo }}</strong>
            <span>{{ receiptReverseTarget.orderNo }} · {{ receiptReverseTarget.finishedGoodsName }}</span>
          </div>
          <div class="target-card__qty">
            入库 {{ fmtNum(receiptReverseTarget.quantity) }} {{ receiptReverseTarget.unit || receiptReverseTarget.orderUnit }}
            ｜ 仓库 {{ receiptReverseTarget.warehouseName || '—' }}
            ｜ 入库 {{ receiptReverseTarget.completedAt }}
          </div>
        </div>
        <el-alert type="warning" :closable="false" class="kw-alert"
          title="反入库会生成反向库存流水并回退工单入库数量。仅当成品批次尚未被出库消耗时可执行。" />
        <el-form label-width="90px">
          <el-form-item label="反入库原因" required>
            <el-input v-model="receiptReverseReason" type="textarea" :rows="3" placeholder="请填写反入库原因（必填）" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="receiptReverseOpen = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitReceiptReverse">确认冲销</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 新建生产任务单（manufacturing.manage） ==================== -->
    <!-- 形态纪律：下单是抽屉，不是独立侧边栏页面；旧的手工建单弹窗已废弃并删除。 -->
    <ProductionTaskOrderDrawer v-model="createOpen" @created="onOrderCreated" />
    <ProductionTaskDetailDrawer v-model="taskDetailOpen" :order-id="taskDetailId" :refresh-key="taskDetailRefresh" @report="openReport" @reverse="openReportReverse" />
    <ManufacturingQualityDialog v-model="qualityOpen" :order-id="qualityOrderId" @completed="onQualityCompleted" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search, Plus } from '@element-plus/icons-vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import ProductionTaskOrderDrawer from '@/components/production/ProductionTaskOrderDrawer.vue'
import ProductionTaskDetailDrawer from '@/components/production/ProductionTaskDetailDrawer.vue'
import ManufacturingQualityDialog from '@/components/production/ManufacturingQualityDialog.vue'
import { stepPercent } from '@/utils/manufacturingProgress'
import {
  fetchExecutionBoard, submitWorkReport, reverseWorkReport, fetchAllWorkReports, newRequestKey,
  fetchManufacturingWarehouses, fetchCompletionReceipts,
  reverseCompletionReceipt, fetchManufacturingOrders, fetchManufacturingOrder, fetchPendingInspections,
  type ExecutionBoardItem, type AllWorkReport, type CompletionReceipt, type ManufacturingOrder,
  type Warehouse, type WorkReport, type PendingInspectionItem
} from '@/api/manufacturing'

const { can } = useCurrentUser()
const canReport = computed(() => can('manufacturing.report'))
const canOverride = computed(() => can('manufacturing.report.override'))
const canInspect = computed(() => can('manufacturing.inspect'))
const canReverseReceipt = computed(() => can('manufacturing.receive.reverse'))
const canManage = computed(() => can('manufacturing.manage'))
const canReadTasks = computed(() => can(['manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all']))

const tab = ref<'board' | 'reports' | 'receipts' | 'tasks' | 'quality'>('board')
const loading = ref(false)
const submitting = ref(false)
const loadedTabs = ref<Set<string>>(new Set())

function errText(e: unknown) {
  const m = (e as { message?: string })?.message
  return m && m.trim() ? m : '操作失败，请重试'
}
function fmtNum(v: unknown) {
  if (v === null || v === undefined || v === '') return '0'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}

// ==================== 字典 ====================
const STATE_OPTIONS = [
  { value: 'READY', label: '待开工' },
  { value: 'IN_PROGRESS', label: '进行中' },
  { value: 'BLOCKED', label: '受阻' },
  { value: 'OVERDUE', label: '已超交付期' },
  { value: 'REWORK', label: '返工中' }
] as const

const ORDER_STATUS: Record<string, string> = {
  DRAFT: '草稿', RELEASED: '已下达', SCHEDULED: '已排产', IN_PRODUCTION: '生产中',
  REWORK: '返工中', PENDING_INSPECTION: '待完工检验', PENDING_REINSPECTION: '待复检',
  PENDING_RECEIPT: '历史待入库', PARTIALLY_RECEIVED: '历史部分入库',
  COMPLETED: '已完成', CANCELLED: '已取消', CLOSED: '已关闭'
}
const orderLabel = (v?: string) => (v ? ORDER_STATUS[v] || v : '—')
const orderTagType = (v?: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' => {
  if (v === 'COMPLETED') return 'success'
  if (v === 'REWORK' || v === 'CANCELLED') return 'danger'
  if (v === 'PENDING_INSPECTION' || v === 'PENDING_REINSPECTION' || v === 'PENDING_RECEIPT' || v === 'PARTIALLY_RECEIVED') return 'warning'
  if (v === 'IN_PRODUCTION') return 'primary'
  return 'info'
}

const OPERATION_STATUS: Record<string, string> = {
  READY: '待开工', IN_PROGRESS: '进行中', BLOCKED: '受阻', COMPLETED: '已完成', SKIPPED: '已跳过'
}
const operationLabel = (v?: string) => (v ? OPERATION_STATUS[v] || v : '—')
const operationTagType = (v?: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' => {
  if (v === 'IN_PROGRESS') return 'primary'
  if (v === 'BLOCKED') return 'danger'
  if (v === 'COMPLETED') return 'success'
  return 'info'
}

const PRIORITY: Record<string, string> = { URGENT: '紧急', HIGH: '高', NORMAL: '普通', LOW: '低' }
const priorityLabel = (v?: string) => (v ? PRIORITY[v] || v : '—')
const priorityTagType = (v?: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' => {
  if (v === 'URGENT') return 'danger'
  if (v === 'HIGH') return 'warning'
  return 'info'
}

// ==================== 新建生产任务单 ====================
// 下单表单已抽成抽屉组件（components/production/ProductionTaskOrderDrawer.vue）：
//   产线 → 产品名称/可选配置 联动；可售产品锚定 SKU（商品身份）；产品配置随单落库（V330 product_config_json）；
//   产品名称即后端制程解析的 processCategory（同读 manufacturing_task_config_items.config_name）。
// 本页只负责「打开抽屉」与「建单成功后刷新看板」；旧的手工建单弹窗（字段少、无配置联动）已删除。
const createOpen = ref(false)
const taskDetailOpen = ref(false), taskDetailId = ref<number | null>(null), taskDetailRefresh = ref(0)
function openTaskDetail(id: number) { taskDetailId.value = id; taskDetailOpen.value = true }
const qualityOpen = ref(false), qualityOrderId = ref<number | null>(null)
const qualityKeyword = ref(''), qualityItems = ref<PendingInspectionItem[]>([])
const qualityPage = ref(1), qualityTotal = ref(0), qualityLoading = ref(false)
function openQuality(id: number) { qualityOrderId.value = id; qualityOpen.value = true }
async function loadQuality(page = qualityPage.value) {
  qualityPage.value = page; qualityLoading.value = true
  try {
    const data = await fetchPendingInspections({ keyword: qualityKeyword.value, page, size: 20 })
    qualityItems.value = data.items; qualityTotal.value = data.total
  } catch (e) { ElMessage.error(errText(e)) }
  finally { qualityLoading.value = false }
}
function onQualityCompleted() {
  taskDetailRefresh.value++
  loadedTabs.value.delete('tasks')
  void Promise.all([loadQuality(), loadBoard(), loadReceipts(1)])
}
const taskItems = ref<ManufacturingOrder[]>([]), taskKeyword = ref(''), taskStatus = ref('')
const taskPage = ref(1), taskTotal = ref(0), tasksLoading = ref(false)
let taskLoadSequence = 0
async function loadTasks(page = taskPage.value) {
  const sequence = ++taskLoadSequence
  taskPage.value = page; tasksLoading.value = true
  try {
    const result = await fetchManufacturingOrders({ keyword: taskKeyword.value, status: taskStatus.value, page, size: 20 })
    if (sequence === taskLoadSequence) { taskItems.value = result.items; taskTotal.value = result.total }
  } catch (e) { if (sequence === taskLoadSequence) ElMessage.error(errText(e)) }
  finally { if (sequence === taskLoadSequence) tasksLoading.value = false }
}

function onOrderCreated() {
  loadBoard(1)
  if (canReadTasks.value) void loadTasks(1)
}

// ==================== 执行看板 ====================
const boardKeyword = ref('')
const boardWorkshop = ref('')
const boardState = ref('')
const boardPage = ref(1)
const boardSize = ref(20)
const boardTotal = ref(0)
const boardItems = ref<ExecutionBoardItem[]>([])
const boardWorkshops = ref<string[]>([])
const boardSummary = ref({ total: 0, ready: 0, inProgress: 0, blocked: 0, overdue: 0, rework: 0 })

const statCards = computed(() => [
  { key: 'total', label: '待执行工序', value: boardSummary.value.total, state: '', hint: '当前筛选范围内', warn: false },
  { key: 'ready', label: '待开工', value: boardSummary.value.ready, state: 'READY', hint: '点击只看待开工', warn: false },
  { key: 'inProgress', label: '进行中', value: boardSummary.value.inProgress, state: 'IN_PROGRESS', hint: '点击只看进行中', warn: false },
  { key: 'blocked', label: '受阻', value: boardSummary.value.blocked, state: 'BLOCKED', hint: '点击只看受阻', warn: true },
  { key: 'overdue', label: '已超交付期', value: boardSummary.value.overdue, state: 'OVERDUE', hint: '点击只看超期', warn: true },
  { key: 'rework', label: '返工中', value: boardSummary.value.rework, state: 'REWORK', hint: '点击只看返工', warn: true }
])

function applyStat(state: string) {
  boardState.value = boardState.value === state ? '' : state
  loadBoard(1)
}
function progressOf(row: ExecutionBoardItem) {
  const required = Number(row.requiredQuantity) || 0
  if (required <= 0) return 0
  return Math.min(100, Math.round((Number(row.reportedQuantity) / required) * 100))
}

async function loadBoard(page?: number) {
  if (page) boardPage.value = page
  loading.value = true
  try {
    const res = await fetchExecutionBoard({
      keyword: boardKeyword.value,
      workshop: boardWorkshop.value,
      state: boardState.value,
      page: boardPage.value,
      size: boardSize.value
    })
    boardItems.value = res.items
    boardTotal.value = res.total
    boardSummary.value = res.summary
    boardWorkshops.value = res.workshops
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}

// ==================== 报工 ====================
const reportDialogOpen = ref(false)
const reportLoading = ref(false), reportHistory = ref<WorkReport[]>([])
const reportTarget = ref<ExecutionBoardItem | null>(null)
const reportForm = ref({ quantity: 1, reportedAt: '', remark: '', allowOverproduction: false, overproductionReason: '' })
/** 本次提交的请求键：弹窗打开时生成，失败重试沿用，成功后丢弃（同键重放不会重复落账）。 */
const reportKey = ref('')
let reportLoadSequence = 0, reportPayloadSignature = ''

const reportRemaining = computed(() => {
  const t = reportTarget.value
  if (!t) return 0
  return Number(t.requiredQuantity) - Number(t.reportedQuantity)
})
const reportOver = computed(() => Math.max(0, Number(reportForm.value.quantity || 0) - reportRemaining.value))
const reportPreview = computed(() => {
  const target = reportTarget.value
  if (!target) return ''
  const after = Number(target.reportedQuantity) + Number(reportForm.value.quantity || 0)
  return `提交后本工序累计 ${fmtNum(after)} / ${fmtNum(target.requiredQuantity)}；${after >= Number(target.requiredQuantity) ? '本工序达到完成条件，整单按必做工序完成状态推进' : '本工序仍在进行中'}。报工不会自动入库。`
})

async function openReport(row: ExecutionBoardItem) {
  if (submitting.value) return
  const sequence = ++reportLoadSequence
  reportLoading.value = true
  try {
    const current = await fetchManufacturingOrder(row.manufacturingOrderId)
    if (sequence !== reportLoadSequence) return
    const op = current.operations.find(op => op.id === row.operationId)
    if (!op || !['READY','IN_PROGRESS'].includes(op.status)) { ElMessage.warning('该工序当前不可报工，请刷新任务详情'); return }
    row = { ...row, requiredQuantity: op.requiredQuantity, reportedQuantity: op.reportedQuantity, operationStatus: op.status }
    reportHistory.value = current.workReports.filter(report => report.manufacturingOrderOperationId === op.id).slice(0, 10)
  } catch (e) { ElMessage.error(errText(e)); return }
  finally { if (sequence === reportLoadSequence) reportLoading.value = false }
  reportTarget.value = row
  const remaining = Number(row.requiredQuantity) - Number(row.reportedQuantity)
  reportForm.value = {
    quantity: remaining > 0 ? Math.min(1, remaining) : 0,
    reportedAt: '',
    remark: '',
    allowOverproduction: false,
    overproductionReason: ''
  }
  reportKey.value = newRequestKey()
  reportPayloadSignature = ''
  reportDialogOpen.value = true
}

async function submitReport() {
  const t = reportTarget.value
  if (!t || submitting.value) return
  if (!(Number(reportForm.value.quantity) > 0)) { ElMessage.warning('报工数量必须大于 0'); return }
  const over = reportOver.value > 0
  if (over) {
    if (!canOverride.value) { ElMessage.error('本次报工超过剩余数量，需要「超额报工与报工冲正」权限'); return }
    if (!reportForm.value.allowOverproduction) { ElMessage.warning('本次报工超过剩余数量，请先勾选「允许超额报工」'); return }
    if (!reportForm.value.overproductionReason.trim()) { ElMessage.warning('超额报工必须填写原因'); return }
  }
  submitting.value = true
  try {
    const payload = {
      quantity: reportForm.value.quantity,
      reportedAt: reportForm.value.reportedAt || null,
      remark: reportForm.value.remark || null,
      allowOverproduction: over && reportForm.value.allowOverproduction,
      overproductionReason: over ? reportForm.value.overproductionReason.trim() : null
    }
    const signature = JSON.stringify(payload)
    if (reportPayloadSignature && signature !== reportPayloadSignature) reportKey.value = newRequestKey()
    reportPayloadSignature = signature
    await submitWorkReport(t.manufacturingOrderId, t.operationId, payload, reportKey.value)
    ElMessage.success('报工已提交')
    reportDialogOpen.value = false
    reportKey.value = ''
    taskDetailRefresh.value++
    loadedTabs.value.delete('reports')
    loadedTabs.value.delete('tasks')
    await loadBoard()
  } catch (e) {
    // 失败时保留请求键：用户重试即复用同一次动作，避免「服务端已成功但响应丢失」被重复落账
    ElMessage.error(errText(e))
  } finally {
    submitting.value = false
  }
}

// ==================== 报工记录 ====================
const reportKeyword = ref('')
const reportReporter = ref('')
const reportOperation = ref('')
const reportWorkshop = ref('')
const reportStatus = ref('')
const reportRange = ref<string[] | null>(null)
const reportPage = ref(1)
const reportSize = ref(20)
const reportTotal = ref(0)
const reportItems = ref<AllWorkReport[]>([])
const reportWorkshops = ref<string[]>([])

async function loadReports(page?: number) {
  if (page) reportPage.value = page
  loading.value = true
  try {
    const res = await fetchAllWorkReports({
      keyword: reportKeyword.value,
      reporterName: reportReporter.value,
      operationName: reportOperation.value,
      workshop: reportWorkshop.value,
      status: reportStatus.value,
      dateFrom: reportRange.value?.[0] || '',
      dateTo: reportRange.value?.[1] || '',
      page: reportPage.value,
      size: reportSize.value
    })
    reportItems.value = res.items
    reportTotal.value = res.total
    reportWorkshops.value = res.workshops
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}

const reportReverseOpen = ref(false)
const reportReverseTarget = ref<AllWorkReport | null>(null)
const reportReverseReason = ref('')
const reportReverseKey = ref('')

function openReportReverse(row: AllWorkReport) {
  reportReverseTarget.value = row
  reportReverseReason.value = ''
  reportReverseKey.value = newRequestKey()
  reportReverseOpen.value = true
}

async function submitReportReverse() {
  const t = reportReverseTarget.value
  if (!t) return
  if (!reportReverseReason.value.trim()) { ElMessage.warning('请填写冲正原因'); return }
  submitting.value = true
  try {
    await reverseWorkReport(t.id, reportReverseReason.value.trim(), reportReverseKey.value)
    ElMessage.success('报工已冲正')
    reportReverseOpen.value = false
    reportReverseKey.value = ''
    taskDetailRefresh.value++
    loadedTabs.value.delete('tasks')
    await Promise.all([loadReports(), loadBoard()])
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    submitting.value = false
  }
}

// ==================== 完工入库历史 ====================
const receiptKeyword = ref('')
const receiptWarehouseId = ref<number | undefined>(undefined)
const receiptStatus = ref('')
const receiptRange = ref<string[] | null>(null)
const receiptPage = ref(1)
const receiptSize = ref(20)
const receiptTotal = ref(0)
const receiptItems = ref<CompletionReceipt[]>([])
const warehouses = ref<Warehouse[]>([])

async function loadWarehouses() {
  try { warehouses.value = await fetchManufacturingWarehouses() } catch { warehouses.value = [] }
}

async function loadReceipts(page?: number) {
  if (page) receiptPage.value = page
  loading.value = true
  try {
    const res = await fetchCompletionReceipts({
      keyword: receiptKeyword.value,
      status: receiptStatus.value,
      warehouseId: receiptWarehouseId.value,
      dateFrom: receiptRange.value?.[0] || '',
      dateTo: receiptRange.value?.[1] || '',
      page: receiptPage.value,
      size: receiptSize.value
    })
    receiptItems.value = res.items
    receiptTotal.value = res.total
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}

const receiptReverseOpen = ref(false)
const receiptReverseTarget = ref<CompletionReceipt | null>(null)
const receiptReverseReason = ref('')
const receiptReverseKey = ref('')

function openReceiptReverse(row: CompletionReceipt) {
  receiptReverseTarget.value = row
  receiptReverseReason.value = ''
  receiptReverseKey.value = newRequestKey()
  receiptReverseOpen.value = true
}

async function submitReceiptReverse() {
  const t = receiptReverseTarget.value
  if (!t) return
  if (!receiptReverseReason.value.trim()) { ElMessage.warning('请填写反入库原因'); return }
  submitting.value = true
  try {
    await reverseCompletionReceipt(t.id, receiptReverseReason.value.trim(), receiptReverseKey.value)
    ElMessage.success('入库单已冲销')
    receiptReverseOpen.value = false
    receiptReverseKey.value = ''
    await Promise.all([loadReceipts(), loadBoard()])
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    submitting.value = false
  }
}

// ==================== 调度 ====================
function reload() {
  if (tab.value === 'tasks') return loadTasks()
  if (tab.value === 'quality') return loadQuality()
  if (tab.value === 'board') return loadBoard()
  if (tab.value === 'reports') return loadReports()
  return loadReceipts()
}

function onTabChange(name: string) {
  if (loadedTabs.value.has(name)) return
  loadedTabs.value.add(name)
  if (name === 'reports') loadReports(1)
  else if (name === 'tasks') loadTasks(1)
  else if (name === 'quality') loadQuality(1)
  else if (name === 'receipts') { loadWarehouses(); loadReceipts(1) }
}

onMounted(async () => {
  loadedTabs.value.add('board')
  await loadBoard(1)
  // 入库记录页需要仓库下拉，先静默预取（无权限时静默降级为空列表）
  loadWarehouses()
})
</script>

<style scoped>
.exec-page { display: grid; gap: var(--space-section-gap-wide, 16px); padding: var(--space-2xl, 24px); min-width: 0; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-lg-xl, 16px); flex-wrap: wrap; }
.page-head h1 { margin: 2px 0 6px; font-size: 26px; line-height: 1.2; }
.page-head p { margin: 0; color: var(--color-text-secondary, #5f6f82); }
.eyebrow { font-size: 11px; letter-spacing: .16em; color: var(--color-primary, #0f766e) !important; font-weight: 700; margin: 0; }
.head-actions { display: flex; gap: var(--space-md-lg, 12px); flex-wrap: wrap; }

.exec-tabs { min-width: 0; }
.exec-tabs :deep(.el-tabs__header) { margin-bottom: var(--space-lg-xl, 16px); }

/* ============ 统计卡片 ============ */
.stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--space-md-lg, 12px); margin-bottom: var(--space-lg-xl, 16px); }
.stat-card {
  display: grid; gap: 4px; text-align: left; cursor: pointer;
  padding: var(--space-lg-xl, 14px) var(--space-xl-2xl, 18px);
  background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0);
  border-radius: var(--radius-2xl, 12px); box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04));
  transition: border-color .15s, transform .15s;
}
.stat-card:hover { border-color: var(--color-primary-border, #a7f3d0); transform: translateY(-1px); }
.stat-card--active { border-color: var(--color-primary, #0f766e); box-shadow: 0 0 0 1px var(--color-primary, #0f766e) inset; }
.stat-card__label { font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.stat-card__value { font-size: 24px; font-weight: 700; color: var(--color-text-heading, #0f172a); line-height: 1.2; }
.stat-card__hint { font-size: 11px; color: var(--color-text-secondary, #94a3b8); }
.stat-card--warn .stat-card__value { color: var(--color-danger, #dc2626); }

/* ============ 面板 ============ */
.panel { background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: var(--space-xl-2xl, 20px); box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04)); min-width: 0; }
.toolbar { display: flex; gap: var(--space-md-lg, 12px); margin-bottom: var(--space-lg-xl, 16px); flex-wrap: wrap; align-items: center; }
.kw-input { width: 260px; }
.sel-workshop { width: 160px; }
.sel-state { width: 160px; }
.sel-narrow { width: 140px; }
.sel-range { width: 260px; max-width: 100%; }
.toolbar-hint { font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
.panel :deep(.el-table th.el-table__cell) { background: var(--color-surface-alt, #f8fafc); color: var(--color-text-heading, #0f172a); font-weight: 600; }
.panel :deep(.el-table) { --el-table-header-bg-color: var(--color-surface-alt, #f8fafc); --el-table-border-color: var(--color-border, #e2e8f0); }
.wide-table { width: 100%; }

.scope-note {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
  margin-bottom: var(--space-lg-xl, 14px); padding: 10px 14px; font-size: 12px; line-height: 1.6;
  color: var(--color-text-secondary, #5f6f82);
  background: var(--color-surface-alt, #f8fafc);
  border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-xl, 10px);
}
.scope-note b { color: var(--color-text-heading, #0f172a); }
.scope-note__sep { color: var(--color-border, #cbd5e1); }
.scope-note__quiet { color: var(--color-text-secondary, #94a3b8); }

/* ============ 单元格 ============ */
.cell-strong { display: block; font-weight: 600; color: var(--color-text-heading, #0f172a); }
.cell-sub { display: block; font-size: 11px; color: var(--color-text-secondary, #94a3b8); }
.cell-muted { color: var(--color-text-secondary, #94a3b8); }
.ml6 { margin-left: 6px; }
.seq-badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 18px; height: 18px; margin-right: 6px; padding: 0 4px;
  font-size: 11px; font-weight: 600; color: var(--color-primary, #0f766e);
  background: var(--color-primary-bg-light, #f0fdfa); border-radius: 6px;
}
.progress-cell { display: grid; gap: 4px; font-size: 12px; }
.qty-reversed { text-decoration: line-through; color: var(--color-text-secondary, #94a3b8); }
.over-qty { color: var(--color-warning, #d97706); font-weight: 600; }
.over-reason { color: var(--color-warning, #d97706); }
.pager { display: flex; justify-content: flex-end; margin-top: var(--space-lg-xl, 16px); }
.readonly-text { color: var(--color-text-heading, #0f172a); }
.num-input { width: 160px; }
.unit-hint { margin-left: 10px; color: var(--color-text-secondary, #94a3b8); font-size: 12px; }
.full-width { width: 100%; }
.kw-alert { margin-bottom: var(--space-md-lg, 12px); }

/* ============ 新建生产任务单 ============ */
/* 下单表单的样式随抽屉组件走（components/production/ProductionTaskOrderDrawer.vue） */
.field-hint { margin-top: 4px; font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
.field-hint--warn { color: var(--color-warning, #d97706); }

/* ============ 目标卡片 ============ */
.target-card {
  display: grid; gap: 6px; margin-bottom: var(--space-lg-xl, 16px);
  padding: var(--space-lg-xl, 14px) var(--space-xl-2xl, 18px);
  background: linear-gradient(135deg, var(--color-primary-bg-light, #f0fdfa) 0%, var(--color-surface-raised, #fbfcfd) 100%);
  border: 1px solid var(--color-primary-border, #a7f3d0); border-radius: var(--radius-xl, 10px);
}
.target-card__main { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.target-card__main strong { font-size: 16px; color: var(--color-text-heading, #0f172a); }
.target-card__main span { font-size: 13px; color: var(--color-text-secondary, #5f6f82); }
.target-card__op { font-size: 14px; color: var(--color-text-heading, #0f172a); }
.target-card__qty { font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.target-card__qty b { color: var(--color-primary, #0f766e); }
.over-alert { margin-bottom: 8px; }
.over-check { margin-top: 4px; }

.receivable-table { margin-bottom: 4px; cursor: pointer; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 0 var(--space-lg-xl, 16px); }

:deep(.el-dialog) { border-radius: var(--radius-2xl, 14px); }
:deep(.el-dialog__header) { padding: var(--space-lg-xl, 16px) var(--space-2xl, 24px) var(--space-md-lg, 12px); margin-right: 0; border-bottom: 1px solid var(--color-border, #e2e8f0); }
:deep(.el-dialog__title) { font-size: 16px; font-weight: 700; color: var(--color-text-heading, #0f172a); }
:deep(.el-dialog__body) { padding: var(--space-xl-2xl, 20px) var(--space-2xl, 24px); }
:deep(.el-dialog__footer) { padding: var(--space-md-lg, 12px) var(--space-2xl, 24px) var(--space-lg-xl, 16px); border-top: 1px solid var(--color-border, #e2e8f0); }
:deep(.el-dialog .el-form-item__label) { font-weight: 600; color: var(--color-text-heading, #0f172a); }

@media (max-width: 900px) {
  .exec-page { padding: 14px; }
  .kw-input, .sel-workshop, .sel-state, .sel-narrow, .sel-range { width: 100%; }
  .head-actions { width: 100%; }
}
</style>
