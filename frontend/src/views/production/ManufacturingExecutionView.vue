<template>
  <div class="exec-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">MANUFACTURING EXECUTION</p>
        <h1>车间执行</h1>
        <p>车间按工序报工，成品由仓库按「交付收货」办理入库。领料、BOM、材料单位由各车间自行管理，不在本页。</p>
      </div>
      <div class="head-actions">
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">新建工单</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </header>

    <el-tabs v-model="tab" class="exec-tabs" @tab-change="onTabChange">
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
                <strong class="cell-strong">{{ row.orderNo }}</strong>
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
            <el-table-column label="工序进度" width="170">
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
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canReport" link type="primary" @click="openReport(row)">报工</el-button>
                <span v-else class="cell-muted">无报工权限</span>
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
            <span><b>报工记录</b> = 氚云生产报工的本地落账，数量进入工序已报数并推进工单进度</span>
            <span class="scope-note__sep">·</span>
            <span>冲正不回删原记录，而是标注 <b>已冲正</b> 并回退进度；须先冲正后续工序</span>
            <span class="scope-note__sep">·</span>
            <span v-if="!canOverride" class="scope-note__quiet">当前账号无冲正权限（需「超额报工与报工冲正」）</span>
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

      <!-- ==================== 交付收货 ==================== -->
      <el-tab-pane label="交付收货" name="receipts">
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
              start-placeholder="收货开始" end-placeholder="收货结束" value-format="YYYY-MM-DD"
              class="sel-range" @change="loadReceipts()" />
            <el-button type="primary" plain @click="loadReceipts()">查询</el-button>
            <el-button v-if="canReceive" type="primary" @click="openReceiptCreate">办理收货</el-button>
          </div>

          <div class="scope-note">
            <span><b>交付收货</b> = 车间把完工成品交给公司仓库，落成品批次 + 仓库库存流水 + 工单入库数量</span>
            <span class="scope-note__sep">·</span>
            <span>这是「车间自治」下替代领料段的接口：车间与公司之间是<b>收货 / 交付</b>关系，不是领料关系</span>
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
            <el-table-column prop="completedAt" label="收货时间" width="160" />
            <el-table-column prop="operatorName" label="收货人" width="110" show-overflow-tooltip />
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
            <template #empty><el-empty description="没有收货单" /></template>
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
    <el-dialog v-model="reportDialogOpen" title="工序报工" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false">
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
        <el-form :model="reportForm" label-width="100px">
          <el-form-item label="报工数量" required>
            <el-input-number v-model="reportForm.quantity" :min="0" :precision="3" :step="1" class="num-input" />
            <span class="unit-hint">{{ reportTarget.unit }}</span>
          </el-form-item>
          <el-form-item v-if="reportOver > 0" label="超额报工">
            <el-alert type="warning" :closable="false" class="over-alert"
              :title="`超出剩余数量 ${fmtNum(reportOver)} ${reportTarget.unit}`"
              :description="canOverride ? '勾选后需填写超额原因，将按超额登记入库。' : '当前账号没有「超额报工与报工冲正」权限，无法超额报工。'" />
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
          <el-form-item label="备注">
            <el-input v-model="reportForm.remark" type="textarea" :rows="2" placeholder="可选" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="reportDialogOpen = false">取消</el-button>
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

    <!-- ==================== 办理收货弹窗 ==================== -->
    <el-dialog v-model="receiptCreateOpen" title="办理交付收货" width="min(980px, calc(100vw - 32px))"
      :close-on-click-modal="false" @opened="loadReceivableOrders()">
      <div class="toolbar">
        <el-input v-model="receivableKeyword" clearable placeholder="工单号 / 销售单号 / 客户 / 产品" class="kw-input"
          @keyup.enter="loadReceivableOrders()" @clear="loadReceivableOrders()">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button :icon="Refresh" @click="loadReceivableOrders()">刷新可收货工单</el-button>
        <span class="toolbar-hint">仅列出<b>待入库 / 部分入库</b>的工单（工序未全部完成或检验未通过的工单不会出现在这里）</span>
      </div>

      <el-table :data="receivableOrders" v-loading="receivableLoading" stripe highlight-current-row
        class="receivable-table" @current-change="pickReceivableOrder">
        <el-table-column label="工单" width="150">
          <template #default="{ row }">
            <strong class="cell-strong">{{ row.orderNo }}</strong>
            <small class="cell-sub">{{ row.salesOrderRef || '无销售单号' }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="customerNameSnapshot" label="客户" min-width="130" show-overflow-tooltip />
        <el-table-column label="产品" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.productNameSnapshot }}</span>
            <small class="cell-sub">{{ row.specificationSnapshot || '无规格' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="计划 / 已入" width="140" align="right">
          <template #default="{ row }">
            {{ fmtNum(row.completedQuantity) }} / {{ fmtNum(row.plannedQuantity) }} {{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="待入" width="100" align="right">
          <template #default="{ row }">
            <b>{{ fmtNum(Number(row.plannedQuantity) - Number(row.completedQuantity)) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="orderTagType(row.status)">{{ orderLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="workshopType" label="车间" width="110" show-overflow-tooltip />
        <template #empty><el-empty description="没有待收货的工单" /></template>
      </el-table>

      <el-divider content-position="left">收货信息</el-divider>
      <el-alert v-if="!receiptOrder" type="info" :closable="false" class="kw-alert"
        title="请先在上表点选一张工单，再填写收货信息。" />
      <el-form v-else :model="receiptForm" label-width="110px">
        <div class="form-grid">
          <el-form-item label="工单">
            <span class="readonly-text">{{ receiptOrder.orderNo }} · {{ receiptOrder.customerNameSnapshot }}</span>
          </el-form-item>
          <el-form-item label="入库仓库" required>
            <el-select v-model="receiptForm.warehouseId" placeholder="选择仓库" class="full-width">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.warehouseName" />
            </el-select>
          </el-form-item>
          <el-form-item label="收货时间">
            <el-date-picker v-model="receiptForm.completedAt" type="datetime" placeholder="默认当前时间"
              value-format="YYYY-MM-DDTHH:mm:ss" class="full-width" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="receiptForm.remark" placeholder="可选" />
          </el-form-item>
        </div>

        <el-divider content-position="left">按产出清单逐项确认实际数量</el-divider>
        <el-alert type="info" :closable="false" class="kw-alert"
          title="成品身份来自工单产出清单，不再手填名称；未填的行视为本次不收货（差异按「已收 < 计划」显式呈现）。" />
        <el-table :data="receiptOutputs" v-loading="receiptOutputsLoading" stripe class="receivable-table">
          <el-table-column label="产出" min-width="220">
            <template #default="{ row }">
              <strong class="cell-strong">{{ row.productNameSnapshot }}</strong>
              <small class="cell-sub">
                {{ row.productCodeSnapshot || '—' }}
                <span v-if="row.isMold"> · 模具</span>
                <span v-if="row.isPrimary"> · 主产品</span>
              </small>
            </template>
          </el-table-column>
          <el-table-column label="计划 / 已收" width="160" align="right">
            <template #default="{ row }">
              {{ fmtNum(row.plannedQuantity) }} / {{ fmtNum(row.receivedQuantity) }} {{ row.unit }}
            </template>
          </el-table-column>
          <el-table-column label="本次收货数量" width="170">
            <template #default="{ row }">
              <el-input-number v-model="row.receiptQuantity" :min="0"
                :max="Math.max(0, Number(row.plannedQuantity) - Number(row.receivedQuantity))"
                :precision="3" :step="1" :controls="false" style="width: 130px" />
            </template>
          </el-table-column>
          <el-table-column label="单位成本" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.unitCost" :min="0" :precision="2" :step="10" :controls="false" style="width: 110px" />
            </template>
          </el-table-column>
          <template #empty><el-empty description="这张工单还没有产出清单" /></template>
        </el-table>
        <div class="field-hint">只有数量 &gt; 0 的行会入账；留 0 表示本次不收该项。</div>
      </el-form>

      <template #footer>
        <el-button @click="receiptCreateOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!receiptOrder" @click="submitReceipt">确认收货</el-button>
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
            ｜ 收货 {{ receiptReverseTarget.completedAt }}
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

    <!-- ==================== 新建工单（manufacturing.manage） ==================== -->
    <el-dialog v-model="createOpen" title="新建生产任务" width="640px" :close-on-click-modal="false" @closed="createKey = ''">
      <el-alert type="info" :closable="false" class="kw-alert"
        title="建单即按所选车间的默认制程展开工序；选「生产车间」后无需再选制程模板，车间没有默认制程时才需要手动指定。" />
      <el-form label-width="100px">
        <el-form-item label="任务类型" required>
          <el-radio-group v-model="createForm.taskType">
            <el-radio-button v-for="t in TASK_TYPES" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="客户名称" required>
          <el-input v-model="createForm.customerName" maxlength="300" placeholder="手工输入客户名称" />
        </el-form-item>
        <el-form-item label="生产车间" required>
          <el-select v-model="createForm.workshopId" placeholder="选择车间（决定制程品类与默认制程）" style="width: 100%"
            :loading="createOptionsLoading" @change="onCreateWorkshopChange">
            <el-option v-for="w in workshopOptions" :key="w.id" :value="w.id"
              :label="w.workshopName + (w.defaultProcessTemplateId ? '（默认制程）' : '（无默认制程）')" />
          </el-select>
        </el-form-item>
        <!-- 制程品类：决定这张单走哪套工序。只列该车间的配置项，保证「选得到」必然「解析得出制程」。 -->
        <el-form-item label="制程品类" required>
          <el-select v-model="createForm.processCategory" filterable placeholder="先选车间，再选制程品类" style="width: 100%"
            :loading="categoryLoading" :disabled="!createForm.workshopId">
            <el-option v-for="c in processCategories" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <!-- 主产品：商品身份（SKU），不再手打名称。单位随之带入，避免工单与库存单位分叉。 -->
        <el-form-item label="主产品 SKU" required>
          <el-select v-model="createForm.salesProductId" filterable remote reserve-keyword clearable
            :remote-method="searchProducts" :loading="productSearching" placeholder="输入编码 / 名称搜索产品" style="width: 100%"
            @change="onPrimaryProductChange">
            <el-option v-for="p in productOptions" :key="p.id" :value="p.id" :label="productLabel(p)" />
          </el-select>
          <div class="field-hint">这是完工时实际产出的商品身份；单位取自主数据（{{ primaryUnit || '—' }}）</div>
        </el-form-item>
        <el-form-item label="规格 / 定制要求">
          <el-input v-model="createForm.specification" maxlength="500" placeholder="选填，如：设备类型：灌装机" />
        </el-form-item>
        <el-form-item label="计划数量" required>
          <div class="qty-row">
            <el-input-number v-model="createForm.plannedQuantity" :min="0.000001" :max="99999999" :precision="4" :controls="false" class="qty-input" />
            <span class="unit-hint">{{ primaryUnit || '（由主产品带入）' }}</span>
          </div>
        </el-form-item>
        <!-- 产出清单：主产品自动成为第 1 行；配套件 / 模具在这里补行（实测 86% 的工单单 SKU）。 -->
        <el-form-item label="配套产出">
          <div class="output-lines">
            <div v-for="(row, idx) in createForm.outputs" :key="idx" class="output-line">
              <el-select v-model="row.salesProductId" filterable remote reserve-keyword
                :remote-method="searchProducts" :loading="productSearching" placeholder="配套件 / 模具"
                style="flex: 1; min-width: 220px">
                <el-option v-for="p in productOptions" :key="p.id" :value="p.id" :label="productLabel(p)" />
              </el-select>
              <el-input-number v-model="row.plannedQuantity" :min="0.000001" :precision="4" :controls="false"
                style="width: 120px" />
              <el-checkbox v-model="row.isMold">模具</el-checkbox>
              <el-button text type="danger" @click="createForm.outputs.splice(idx, 1)">移除</el-button>
            </div>
            <el-button text type="primary" @click="addOutputRow">+ 添加配套产出（模具等）</el-button>
            <div class="field-hint">收货时按这份清单逐项确认实际数量 —— 清单是「这张单该产出什么」的唯一权威</div>
          </div>
        </el-form-item>
        <el-form-item label="制程模板">
          <el-select v-model="createForm.processTemplateId" clearable placeholder="跟随车间默认制程" style="width: 100%"
            :loading="createOptionsLoading" :disabled="!!createWorkshopDefault">
            <el-option v-for="t in processTemplates" :key="t.id" :value="t.id" :label="`${t.templateName}（${t.operationCount} 道工序）`" />
          </el-select>
          <div v-if="createForm.workshopId && !createWorkshopDefault && !createForm.processTemplateId" class="field-hint field-hint--warn">
            该车间没有默认制程，必须手动指定制程模板后才能建单
          </div>
        </el-form-item>
        <el-form-item label="交货日期">
          <el-date-picker v-model="createForm.dueDate" type="date" value-format="YYYY-MM-DD" placeholder="选填" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" maxlength="500" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!createReady" @click="submitCreate">创建并展开工序</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search, Plus } from '@element-plus/icons-vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import {
  fetchExecutionBoard, submitWorkReport, reverseWorkReport, fetchAllWorkReports, newRequestKey,
  fetchManufacturingWarehouses, completeManufacturingOrder, fetchCompletionReceipts,
  reverseCompletionReceipt, fetchManufacturingOrders, fetchManufacturingOrder,
  fetchWorkshopOptions, fetchProcessTemplates, createManufacturingOrder,
  fetchProductOptions, fetchProcessCategories,
  type ExecutionBoardItem, type AllWorkReport, type CompletionReceipt, type ManufacturingOrder,
  type Warehouse, type WorkshopOption, type ProcessTemplate, type ProductOption, type ManufacturingOutput
} from '@/api/manufacturing'

const { can } = useCurrentUser()
const canReport = computed(() => can('manufacturing.report'))
const canOverride = computed(() => can('manufacturing.report.override'))
const canReceive = computed(() => can('manufacturing.receive'))
const canReverseReceipt = computed(() => can('manufacturing.receive.reverse'))
const canManage = computed(() => can('manufacturing.manage'))

const tab = ref<'board' | 'reports' | 'receipts'>('board')
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
  REWORK: '返工中', PENDING_RECEIPT: '待入库', PARTIALLY_RECEIVED: '部分入库',
  COMPLETED: '已完成', CANCELLED: '已取消', CLOSED: '已关闭'
}
const orderLabel = (v?: string) => (v ? ORDER_STATUS[v] || v : '—')
const orderTagType = (v?: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' => {
  if (v === 'COMPLETED') return 'success'
  if (v === 'REWORK' || v === 'CANCELLED') return 'danger'
  if (v === 'PENDING_RECEIPT' || v === 'PARTIALLY_RECEIVED') return 'warning'
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

// ==================== 新建工单 ====================
// 后端契约（ManufacturingService.CreateOrder，P3 ①）：
//   customerName / processCategory / salesProductId / plannedQuantity / taskType 必填；
//   outputs 是**附加产出**（配套件 / 模具），可空 —— 实测 86% 的工单只产 1 个 SKU。
// ⚠️ 「制程品类」与「主产品 SKU」是两个独立维度，必须都给：
//   制程解析按 manufacturing_task_config_items.config_name（**品类级**）精确匹配，
//   而产品身份必须是 SKU（**商品级**）。拿 SKU 名反推品类实测只有 25% 可用
//   （74.5% 零命中 + 13% 命中多个）→ 不存在自动推导，只能分别选。
// 与报工/收货同一条请求键纪律：打开弹窗生成，失败保留重试，成功丢弃。
const TASK_TYPES = [
  { value: 'SAMPLE', label: '打样' },
  { value: 'SALES_ORDER', label: '销售排产' },
  { value: 'REPLACEMENT', label: '返工替换' },
  { value: 'REWORK', label: '返工' },
  { value: 'INTERNAL', label: '内部领用' },
  { value: 'SPECIAL', label: '特殊定制' }
] as const

const createOpen = ref(false)
const createKey = ref('')
const createOptionsLoading = ref(false)
const workshopOptions = ref<WorkshopOption[]>([])
const processTemplates = ref<ProcessTemplate[]>([])
/** 该车间可选的制程品类（随车间切换重新加载；与后端制程解析读同一张配置表）。 */
const processCategories = ref<string[]>([])
const categoryLoading = ref(false)
/** 产品选择器候选（主产品行与配套产出行**共用**一份，按关键词远程搜索）。 */
const productOptions = ref<ProductOption[]>([])
const productSearching = ref(false)
const createForm = ref({
  taskType: 'SAMPLE',
  customerName: '',
  processCategory: '',
  salesProductId: undefined as number | undefined,
  specification: '',
  plannedQuantity: undefined as number | undefined,
  workshopId: undefined as number | undefined,
  processTemplateId: undefined as number | undefined,
  dueDate: '',
  remark: '',
  outputs: [] as Array<{ salesProductId: number | undefined; plannedQuantity: number | undefined; isMold: boolean }>
})
/** 主产品单位：由所选 SKU 带入，界面只读展示（不允许在工单上另写一个单位）。 */
const primaryUnit = computed(() =>
  productOptions.value.find(x => x.id === createForm.value.salesProductId)?.unit || '')
const productLabel = (p: ProductOption) =>
  `${p.productCode} ${p.productName}${p.specification ? ' · ' + p.specification : ''}${p.custom ? '（自建）' : ''}`
const createWorkshopDefault = computed(() =>
  workshopOptions.value.find(w => w.id === createForm.value.workshopId)?.defaultProcessTemplateId || null)

let productSearchTimer: ReturnType<typeof setTimeout> | undefined
/** 远程搜索产品（节流 250ms，避免每敲一个字符打一次接口）。 */
function searchProducts(keyword: string) {
  if (productSearchTimer) clearTimeout(productSearchTimer)
  productSearchTimer = setTimeout(async () => {
    productSearching.value = true
    try {
      productOptions.value = await fetchProductOptions(keyword || '')
    } catch (e) {
      ElMessage.error(errText(e))
    } finally {
      productSearching.value = false
    }
  }, 250)
}
function addOutputRow() {
  createForm.value.outputs.push({ salesProductId: undefined, plannedQuantity: undefined, isMold: false })
}
function onPrimaryProductChange() {
  // 换产品必须重新填数量：沿用上一件货的数量是最典型的"看起来填了其实错了"
  createForm.value.plannedQuantity = undefined
}

const createReady = computed(() => {
  const f = createForm.value
  // 配套产出只要开了行就必须填全 —— 半填的行提交后被静默丢掉，是最难查的一类"我明明填了"
  const outputsOk = f.outputs.every(r => !!r.salesProductId && Number(r.plannedQuantity) > 0)
  return !!(f.taskType && f.customerName.trim() && f.processCategory
    && f.salesProductId && f.plannedQuantity && Number(f.plannedQuantity) > 0
    && f.workshopId && (createWorkshopDefault.value || f.processTemplateId) && outputsOk)
})

async function openCreate() {
  createForm.value = {
    taskType: 'SAMPLE', customerName: '', processCategory: '', salesProductId: undefined,
    specification: '', plannedQuantity: undefined, workshopId: undefined,
    processTemplateId: undefined, dueDate: '', remark: '', outputs: []
  }
  processCategories.value = []
  productOptions.value = []
  createKey.value = newRequestKey()
  createOpen.value = true
  if (workshopOptions.value.length === 0) {
    createOptionsLoading.value = true
    try {
      const [ws, ts] = await Promise.all([fetchWorkshopOptions(), fetchProcessTemplates()])
      workshopOptions.value = ws
      processTemplates.value = ts
    } catch (e) {
      ElMessage.error(errText(e))
    } finally {
      createOptionsLoading.value = false
    }
  }
  // 先铺一批候选，否则用户不输入关键词时下拉是空的、看起来像"没有产品"
  searchProducts('')
}

async function onCreateWorkshopChange() {
  // 车间有默认制程时锁定模板选择，交由后端解析并回写 workshop_type
  if (createWorkshopDefault.value) createForm.value.processTemplateId = undefined
  // 制程品类来自该车间的配置：换车间必须重取，并清掉上一个车间的选择
  createForm.value.processCategory = ''
  processCategories.value = []
  if (!createForm.value.workshopId) return
  categoryLoading.value = true
  try {
    processCategories.value = await fetchProcessCategories(createForm.value.workshopId)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    categoryLoading.value = false
  }
}

async function submitCreate() {
  const f = createForm.value
  if (!createReady.value) return
  submitting.value = true
  try {
    await createManufacturingOrder({
      taskType: f.taskType,
      customerName: f.customerName.trim(),
      processCategory: f.processCategory,
      salesProductId: f.salesProductId,
      specification: f.specification.trim() || null,
      plannedQuantity: f.plannedQuantity,
      workshopId: f.workshopId,
      processTemplateId: createWorkshopDefault.value ? null : f.processTemplateId,
      dueDate: f.dueDate || null,
      remark: f.remark.trim() || null,
      outputs: f.outputs
        .filter(r => r.salesProductId && Number(r.plannedQuantity) > 0)
        .map(r => ({ salesProductId: r.salesProductId, plannedQuantity: r.plannedQuantity, isMold: r.isMold }))
    }, createKey.value)
    ElMessage.success('工单已创建，产出清单与工序已展开')
    createOpen.value = false
    createKey.value = ''
    loadBoard(1)
  } catch (e) {
    // 失败时保留请求键：用户重试即复用同一次动作，不会重复建单
    ElMessage.error(errText(e))
  } finally {
    submitting.value = false
  }
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
const reportTarget = ref<ExecutionBoardItem | null>(null)
const reportForm = ref({ quantity: 1, reportedAt: '', remark: '', allowOverproduction: false, overproductionReason: '' })
/** 本次提交的请求键：弹窗打开时生成，失败重试沿用，成功后丢弃（同键重放不会重复落账）。 */
const reportKey = ref('')

const reportRemaining = computed(() => {
  const t = reportTarget.value
  if (!t) return 0
  return Number(t.requiredQuantity) - Number(t.reportedQuantity)
})
const reportOver = computed(() => Math.max(0, Number(reportForm.value.quantity || 0) - reportRemaining.value))

function openReport(row: ExecutionBoardItem) {
  reportTarget.value = row
  const remaining = Number(row.requiredQuantity) - Number(row.reportedQuantity)
  reportForm.value = {
    quantity: remaining > 0 ? remaining : 0,
    reportedAt: '',
    remark: '',
    allowOverproduction: false,
    overproductionReason: ''
  }
  reportKey.value = newRequestKey()
  reportDialogOpen.value = true
}

async function submitReport() {
  const t = reportTarget.value
  if (!t) return
  if (!(Number(reportForm.value.quantity) > 0)) { ElMessage.warning('报工数量必须大于 0'); return }
  const over = reportOver.value > 0
  if (over) {
    if (!canOverride.value) { ElMessage.error('本次报工超过剩余数量，需要「超额报工与报工冲正」权限'); return }
    if (!reportForm.value.allowOverproduction) { ElMessage.warning('本次报工超过剩余数量，请先勾选「允许超额报工」'); return }
    if (!reportForm.value.overproductionReason.trim()) { ElMessage.warning('超额报工必须填写原因'); return }
  }
  submitting.value = true
  try {
    await submitWorkReport(t.manufacturingOrderId, t.operationId, {
      quantity: reportForm.value.quantity,
      reportedAt: reportForm.value.reportedAt || null,
      remark: reportForm.value.remark || null,
      allowOverproduction: over && reportForm.value.allowOverproduction,
      overproductionReason: over ? reportForm.value.overproductionReason.trim() : null
    }, reportKey.value)
    ElMessage.success('报工已提交')
    reportDialogOpen.value = false
    reportKey.value = ''
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
    await Promise.all([loadReports(), loadBoard()])
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    submitting.value = false
  }
}

// ==================== 交付收货 ====================
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

const receiptCreateOpen = ref(false)
const receivableLoading = ref(false)
const receivableKeyword = ref('')
const receivableOrders = ref<ManufacturingOrder[]>([])
const receiptOrder = ref<ManufacturingOrder | null>(null)
const receiptForm = ref({
  warehouseId: undefined as number | undefined,
  completedAt: '',
  remark: ''
})
/** 产出清单行 + 本次收货输入（P3 ②：收货按清单逐项确认，不再手填成品名）。 */
type ReceiptOutputRow = ManufacturingOutput & { receiptQuantity: number; unitCost: number }
const receiptOutputs = ref<ReceiptOutputRow[]>([])
const receiptOutputsLoading = ref(false)
const receiptKey = ref('')

function openReceiptCreate() {
  receiptOrder.value = null
  receiptOutputs.value = []
  receivableKeyword.value = ''
  receivableOrders.value = []
  receiptKey.value = newRequestKey()
  receiptCreateOpen.value = true
  if (!warehouses.value.length) loadWarehouses()
}

/** 可收货工单 = 待入库 + 部分入库（后端 complete() 只接受这两个状态）。 */
async function loadReceivableOrders() {
  receivableLoading.value = true
  try {
    const base = { keyword: receivableKeyword.value, page: 1, size: 50 }
    const [pending, partial] = await Promise.all([
      fetchManufacturingOrders({ ...base, status: 'PENDING_RECEIPT' }),
      fetchManufacturingOrders({ ...base, status: 'PARTIALLY_RECEIVED' })
    ])
    receivableOrders.value = [...pending.items, ...partial.items]
  } catch (e) {
    ElMessage.error(errText(e))
    receivableOrders.value = []
  } finally {
    receivableLoading.value = false
  }
}

async function pickReceivableOrder(row: ManufacturingOrder | null) {
  if (!row) return
  receiptOrder.value = row
  receiptForm.value = {
    warehouseId: warehouses.value[0]?.id,
    completedAt: '',
    remark: ''
  }
  // 产出清单是收货的唯一入口：选中工单后立刻取清单，默认把「剩余未收」整批填入
  receiptOutputsLoading.value = true
  receiptOutputs.value = []
  try {
    const detail = await fetchManufacturingOrder(row.id)
    receiptOutputs.value = (detail.outputs || []).map(o => ({
      ...o,
      receiptQuantity: Math.max(0, Number(o.plannedQuantity) - Number(o.receivedQuantity)),
      unitCost: 0
    }))
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    receiptOutputsLoading.value = false
  }
}

async function submitReceipt() {
  const o = receiptOrder.value
  if (!o) { ElMessage.warning('请先选择工单'); return }
  if (!receiptForm.value.warehouseId) { ElMessage.warning('请选择入库仓库'); return }
  // 只提交数量 > 0 的行；留 0 表示本次不收该项（不是"收 0 件"）
  const items = receiptOutputs.value
    .filter(r => Number(r.receiptQuantity) > 0)
    .map(r => ({ outputId: r.id, quantity: r.receiptQuantity, unitCost: r.unitCost || 0, remark: null }))
  if (!items.length) { ElMessage.warning('请至少填写一项收货数量'); return }
  submitting.value = true
  try {
    await completeManufacturingOrder(o.id, {
      warehouseId: receiptForm.value.warehouseId,
      completedAt: receiptForm.value.completedAt || null,
      items,
      remark: receiptForm.value.remark || null
    }, receiptKey.value)
    ElMessage.success('交付收货已入账')
    receiptCreateOpen.value = false
    receiptKey.value = ''
    await Promise.all([loadReceipts(1), loadBoard()])
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    submitting.value = false
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
  if (tab.value === 'board') return loadBoard()
  if (tab.value === 'reports') return loadReports()
  return loadReceipts()
}

function onTabChange(name: string) {
  if (loadedTabs.value.has(name)) return
  loadedTabs.value.add(name)
  if (name === 'reports') loadReports(1)
  else if (name === 'receipts') { loadWarehouses(); loadReceipts(1) }
}

onMounted(async () => {
  loadedTabs.value.add('board')
  await loadBoard(1)
  // 收货 tab 需要仓库下拉，先静默预取（无权限时静默降级为空列表）
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

/* ============ 新建工单弹窗 ============ */
.qty-row { display: flex; gap: 10px; width: 100%; }
.qty-input { flex: 1; }
.unit-input { width: 120px; flex: none; }
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
