<template>
  <div class="tracking-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">生产任务</p>
        <h1>生产跟单</h1>
        <p>默认只看<strong>还没交付</strong>的任务（未停工、未结案）；已交付的历史记录与九月前的旧单需在「更多筛选 → 跟单状态」里切到「全部跟单状态」。</p>
      </div>
      <div class="head-actions">
        <input v-if="visibility === 'ALL'" ref="workbookInput" type="file" accept=".xlsx" hidden @change="onWorkbookSelected" />
        <el-button v-if="visibility === 'ALL'" @click="workbookInput?.click()">导入生产跟单进度表</el-button>
        <el-button v-if="visibility === 'ALL'" @click="showWorkbookRows">表格待处理{{ workbookActionable ? `（${workbookActionable}）` : '' }}</el-button>
        <!-- 生产任务单导入：跟单员自己的入口（不需要「氚云导入中心」权限）。
             复用导入中心的 上传→解析→预览→确认 四步，后端把类型锁定为「生产任务单」。 -->
        <el-button v-if="canFollowup" title="上传氚云导出的生产任务单 Excel，预检确认后写入系统；与氚云导入中心同一套解析规则" @click="taskImportVisible = true">导入生产任务单</el-button>
        <input v-if="canFollowup" ref="roundTripInput" type="file" accept=".xlsx" hidden @change="onRoundTripSelected" />
        <el-button v-if="canFollowup" @click="roundTripInput?.click()">导入跟单表（回写）</el-button>
        <el-button @click="analysisVisible = true">生产分析</el-button>
        <el-button @click="weeklyReviewVisible = true">周复盘</el-button>
        <el-button :loading="exporting" @click="openExport">导出</el-button>
        <el-button @click="refreshTracking">刷新</el-button>
      </div>
    </header>

    <div ref="filterPanelRef" class="tracking-filter-panel">
      <div class="toolbar">
        <div class="tracking-filter-fields">
      <!-- 搜索框唯一入口（10-19 LQ）：任务号 / 产品 / 客户 / 规格共用一个框，后端同一段谓词匹配。 -->
      <el-input v-model="keyword" class="tracking-keyword" clearable placeholder="任务号、产品、客户或规格" aria-label="关键词（任务号、产品、客户、规格）" @keyup.enter="search" @clear="search" />
      <el-select v-model="countdownFilter" multiple collapse-tags clearable placeholder="生产倒计时（全部）" aria-label="生产倒计时" @change="search"><el-option v-for="option in COUNTDOWN_FILTER_OPTIONS" :key="option.value" :label="option.label" :value="option.value" /></el-select>
      <!-- 业务员：2026-10-21 从「更多筛选」上移到常用行（真库 12 人都有量，是最常按人看的一维）。 -->
      <el-select v-model="salespersonFilter" multiple filterable collapse-tags clearable placeholder="业务员（全部）" aria-label="业务员" @change="search"><el-option v-for="name in salespersonOptions" :key="name" :label="name" :value="name" /></el-select>
      <!-- 2026-10-21 精简：「生产交付结果」「跟单待办」撤出常用行——工作台默认口径（未交付）下交付结果恒为「未交付」，
           待办升成快捷标签「已报工待入库」；交付结果移入更多筛选，待办靠列表徽标呈现。 -->
      <div class="tracking-common-date" aria-label="生产任务单提交时间" title="按生产任务单提交时间筛选"><div class="tracking-extra-date-inputs"><el-date-picker v-model="dateFilters.created.from" type="date" value-format="YYYY-MM-DD" placeholder="提交时间起" aria-label="生产任务单提交起始日期" @change="onCreatedDateChange('from')" /><span>至</span><el-date-picker v-model="dateFilters.created.to" type="date" value-format="YYYY-MM-DD" placeholder="提交时间止" aria-label="生产任务单提交截止日期" @change="onCreatedDateChange('to')" /></div></div>
      <!-- 预计完成时间：后端口径＝当前承诺日（延期至优先，回退车间源预计、有效业务员要求日），
           与本页「预计完成」列显示同源；导出共用同一段筛选。 -->
      <div class="tracking-common-date" aria-label="预计完成时间" title="按预计完成时间筛选"><div class="tracking-extra-date-inputs"><el-date-picker v-model="dateFilters.expected.from" type="date" value-format="YYYY-MM-DD" placeholder="预计完成起" aria-label="预计完成起始日期" @change="onExpectedDateChange('from')" /><span>至</span><el-date-picker v-model="dateFilters.expected.to" type="date" value-format="YYYY-MM-DD" placeholder="预计完成止" aria-label="预计完成截止日期" @change="onExpectedDateChange('to')" /></div></div>
      <el-checkbox v-model="urgentOnly" class="tracking-urgent-only" @change="search">仅看加急</el-checkbox>
        </div>
        <div class="tracking-filter-bottom">
          <div class="tracking-filter-actions">
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="resetTrackingFilters">重置</el-button>
      <el-button @click="advancedFiltersVisible = !advancedFiltersVisible">更多筛选{{ activeAdvancedFilterCount ? `（${activeAdvancedFilterCount}）` : '' }}{{ advancedFiltersVisible ? ' ▲' : ' ▼' }}</el-button>
      <el-popover v-model:visible="columnSettingVisible" placement="bottom-end" trigger="click" :width="300">
        <template #reference>
          <el-button :type="hiddenListColumns.length ? 'primary' : 'default'" plain>
            列设置{{ hiddenListColumns.length ? `（已隐藏 ${hiddenListColumns.length}）` : '' }}
          </el-button>
        </template>
        <div class="column-setting">
          <div class="column-setting-head">
            <span>显示列</span>
            <span class="column-setting-actions">
              <el-button link type="primary" :disabled="!hiddenListColumns.length" @click="resetListColumns">全部显示</el-button>
              <el-button link type="primary" @click="restoreColumnDefault">恢复默认</el-button>
            </span>
          </div>
          <el-checkbox-group v-model="visibleListColumns" class="column-setting-list">
            <el-checkbox v-for="column in LIST_COLUMN_DEFS" :key="column.key" :value="column.key">{{ column.label }}</el-checkbox>
          </el-checkbox-group>
          <p class="column-setting-note">编号 / 产品 / 操作 为常显列，不在此处控制。</p>
          <div v-if="canManageColumnDefaults" class="column-setting-global">
            <p class="column-setting-note">当前全站默认：{{ globalDefaultSummary }}</p>
            <el-button size="small" type="primary" plain :loading="savingColumnDefaults" @click="saveGlobalColumnDefaults">把当前勾选存为全站默认</el-button>
          </div>
        </div>
      </el-popover>
          </div>
        </div>
      </div>

    <div class="tracking-quick-filters" aria-label="常用筛选">
      <el-button v-for="preset in quickFilterPresets" :key="preset.key" size="small" :type="quickFilterActive(preset.key) ? 'primary' : 'default'" :plain="!quickFilterActive(preset.key)" @click="applyQuickFilter(preset.key)">{{ preset.label }}</el-button>
    </div>
    <div v-if="advancedFiltersVisible" class="tracking-extra-filters" aria-label="更多生产跟单筛选条件">
      <!-- 2026-10-21 精简：删除「任务单状态」（与跟单状态同一件事）与「跟单员」（与车间同一刀），
           「交付结果」从常用行移入本区；本区只剩切历史/全部视图才用得到的条件。 -->
      <div class="tracking-extra-filter"><el-select v-model="trackingStatus" aria-label="跟单状态" @change="search"><el-option label="全部跟单状态" value="all" /><el-option label="待处理（未交付）" value="open" title="未停工、未结案、且还没有入库的任务。入库口径：跟单员填写的入库日期优先，没填则看采购入库；两者都没有＝未入库" /><el-option label="待跟单" value="pending" /><el-option label="跟单中" value="in_progress" /><el-option label="跟单已完成" value="completed" /><el-option label="停止生产跟单" value="stopped" /><el-option-group label="完成方式"><el-option label="人工结案" value="closed" /></el-option-group></el-select></div>
      <div class="tracking-extra-filter"><el-select v-model="deliveryFilter" multiple collapse-tags clearable placeholder="生产交付结果（全部）" aria-label="生产交付结果" @change="search"><el-option v-for="option in DELIVERY_FILTER_OPTIONS" :key="option.value" :label="option.label" :value="option.value" /></el-select></div>
      <div class="tracking-extra-filter"><el-select v-model="workshop" clearable filterable placeholder="车间（全部）" aria-label="车间" @change="search"><el-option v-for="line in filterOptions.workshops" :key="line" :label="shortWorkshop(line)" :value="line" /></el-select></div>
      <div class="tracking-extra-filter tracking-extra-filter-check"><el-checkbox v-model="mine" @change="search">我的跟单</el-checkbox></div>
      <div class="tracking-extra-filter"><el-select v-model="optionalStageFilter" clearable placeholder="按需工序（全部）" aria-label="按需工序" @change="search"><el-option label="全部按需工序" value="" /><el-option v-for="option in optionalStageOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></div>
      <!-- 「模具生产」跟进：三档可多选。只筛「模具之后没有任何节点跟进」的单——后面一旦动了就已越过模具这一关；
           模具之前（画图/备料/钣金）跟没跟都不影响。可多选组合，例如只勾前两项＝模具还没完工。 -->
      <div class="tracking-extra-filter"><el-select v-model="moldStageFilter" multiple collapse-tags clearable placeholder="模具生产（全部）" aria-label="模具生产跟进" @change="search"><el-option v-for="option in MOLD_STAGE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" /></el-select></div>
      <div class="tracking-extra-filter"><el-select v-model="timeFilter" aria-label="时间核实" @change="search">
        <el-option label="全部时间核实" value="all" />
        <el-option label="待核实问题" value="time_attention" title="仅筛出尚待处理的问题；已核实的源日期异常不在此项中" />
        <el-option-group label="源日期异常 · 含已核实记录">
          <el-option label="日期异常（含已核实）" value="time_source" title="申请日期或业务员要求日期异常，包含已核实但源数据仍异常的任务" />
          <el-option label="申请日期异常" value="time_application" title="申请日期早于任务单提交日期" />
          <el-option label="业务员要求日期异常" value="time_required" title="要求日期早于申请日期或任务单提交日期" />
        </el-option-group>
        <el-option-group label="生产时间异常 · 待核实">
          <el-option label="生产时间异常" value="time_production" title="接单、车间交期、报工或入库与报工的时间问题" />
          <el-option label="接单时间待核" value="time_acceptance" title="排产与申请或要求日期有冲突，或已过要求日期仍未排产" />
          <el-option label="车间交期待核" value="time_commitment" title="车间预计完成时间晚于业务要求，或协商记录与当前日期不一致" />
          <el-option label="报工时间待核" value="time_reporting" title="报工日晚于车间预计，或已过预计日期仍未报工" />
          <el-option label="入库与报工待核" value="time_inbound_reporting" title="已入库但未报工，或入库日期早于报工日期" />
        </el-option-group>
        <el-option-group label="原因与后续跟进">
          <el-option label="逾期原因待记录" value="time_overdue" title="入库记录逾期且当前交期与入库日期尚未登记原因" />
          <el-option label="核实事项应跟进" value="time_due" title="已登记的核实事项到达下次跟进日期" />
        </el-option-group>
      </el-select></div>
    </div>
    <div v-if="activeFilterChips.length" class="tracking-active-filters" aria-label="当前筛选条件"><span>已筛选：</span><el-tag v-for="chip in activeFilterChips" :key="chip.key" closable type="info" @close="clearFilterChip(chip.key)">{{ chip.label }}</el-tag><el-button link type="primary" @click="resetTrackingFilters">清除全部</el-button></div>
    </div>

    <p v-if="visibility !== 'ALL'" class="muted">当前为跟单员视图：仅显示按车间分配给本人的生产任务（生产跟单主管可见全部）。</p>

    <div v-if="selectedTrackingRows.length" class="list-batch-bar">
      <span>已选 {{ selectedTrackingRows.length }} 单</span>
      <el-button @click="openExport">导出选中</el-button>
      <el-button v-if="canFollowup" type="primary" :disabled="!selectedTrackingRows.some(canQuickComplete)" @click="openListCompletion(selectedTrackingRows)">批量完成当前节点</el-button>
      <span class="muted">{{ canFollowup ? '勾选当前页工单可导出；批量完成仅处理尚有未完成节点的工单。' : '勾选当前页工单可导出。' }}</span>
    </div>

    <el-table v-loading="loading" :data="items" row-key="id" class="tracking-table" :style="stickyHeaderStyle" @row-click="openOrderDetail" @selection-change="onTrackingSelectionChange">
      <el-table-column type="selection" width="38" fixed />
      <el-table-column label="编号" min-width="78" fixed>
        <template #default="{ row }"><span :title="row.orderNo">{{ shortOrderNo(row.orderNo) }}</span><el-tag v-if="row.stoppedAt" class="urgent-list-tag" size="small" type="info" effect="plain" :title="row.stoppedReason || ''">停止跟单</el-tag><el-tag v-else-if="isSourceUrgent(row.sourceOrderRemark)" class="urgent-list-tag" size="small" type="danger" effect="dark" :title="row.sourceOrderRemark">加急</el-tag></template>
      </el-table-column>
      <el-table-column label="产品" min-width="240">
        <template #default="{ row }"><strong>{{ row.productNameSnapshot }}</strong><span class="tracking-product-qty">{{ row.plannedQuantity }}{{ row.unit }}</span><small v-if="productSize(row) || row.moldLayout?.trim()">{{ productSize(row) }}<template v-if="row.moldLayout?.trim()">{{ productSize(row) ? ' / ' : '' }}{{ row.moldLayout.trim() }}</template></small></template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('salesperson')" label="业务员" width="128">
        <template #default="{ row }"><span v-if="row.salespersonNameSnapshot">{{ row.salespersonNameSnapshot }}</span><span v-else class="muted">—</span></template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('workshop')" label="车间" min-width="110"><template #default="{ row }">{{ shortWorkshop(row.workshopType) }}</template></el-table-column>
      <el-table-column v-if="isListColumnVisible('sourceStatus')" label="任务单状态" width="112"><template #default="{ row }">{{ row.sourceStatus || '未提供' }}</template></el-table-column>
      <el-table-column v-if="isListColumnVisible('trackingStatus')" label="跟单状态" width="128"><template #default="{ row }"><el-tag :type="trackingStatusTone(row.trackingStatus)">{{ trackingStatusText(row.trackingStatus) }}</el-tag><el-tooltip v-if="row.closedAt" placement="top" :content="`跟单员主动结案：${row.closedByName || ''} · ${formatTime(row.closedAt)}${row.closedNote ? ' · ' + row.closedNote : ''}`"><small class="closure-marker"> 人工结案</small></el-tooltip></template></el-table-column>
      <el-table-column v-if="isListColumnVisible('trackingAction')" label="跟单待办" width="152">
        <template #default="{ row }">
          <el-tag v-if="row.trackingAction" :type="actionTone(row.trackingAction)" effect="light" :title="actionHint(row)">{{ actionLabel(row.trackingAction) }}</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('sourceCreatedAt')" label="任务单提交" width="142"><template #default="{ row }">{{ formatMinute(row.sourceCreatedAt) || '未提供' }}</template></el-table-column>
      <el-table-column v-if="isListColumnVisible('applicationDate')" label="申请时间" width="125">
        <template #default="{ row }"><el-tooltip v-if="invalidApplicationDate(row)" content="申请日期早于任务单提交日期，疑似填写错误" placement="top"><span class="is-danger">{{ shortDate(row.applicationDate) || '未填写' }}</span></el-tooltip><span v-else>{{ shortDate(row.applicationDate) || '未填写' }}</span></template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('scheduleTime')" label="排产时间" width="112">
        <template #default="{ row }"><span v-if="row.scheduleTime">{{ shortDate(row.scheduleTime) }}</span><span v-else class="muted">未排产</span></template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('dueDate')" label="要求完成" width="100"><template #default="{ row }"><el-tooltip v-if="invalidRequiredDate(row)" content="要求日期早于申请日期或任务单提交日期，疑似填写错误" placement="top"><span class="is-danger">{{ shortDate(row.dueDate) || '未定' }}</span></el-tooltip><span v-else>{{ shortDate(row.dueDate) || '未定' }}</span></template></el-table-column>
      <el-table-column v-if="isListColumnVisible('requestedLeadDays')" label="工期" width="178">
        <template #default="{ row }">
          <el-tooltip placement="top" raw-content :content="leadDaysTooltipHtml(row)">
            <span class="lead-cell">
              <span :class="leadDaysCellClass(row)">{{ effectiveLeadDaysText(row) }}<small class="lead-kind">{{ leadDaysKindText(row) }}</small></span>
              <small v-if="otherLeadDaysText(row)" class="lead-others">{{ otherLeadDaysText(row) }}</small>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('expectedFinish')" label="预计完成" width="125">
        <template #default="{ row }"><span>{{ shortDate(currentCompletionDate(row)) || '未定' }}</span><small v-if="row.forecastFinishDate" class="metric-delay">{{ forecastAdjustText(row) }}</small></template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('countdown')" label="生产倒计时" width="145">
        <template #default="{ row }"><span :class="countdownTone(row, countdownToday)">{{ productionCountdown(row, countdownToday) }}</span></template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('completion')" label="完工 / 入库" width="130">
        <template #default="{ row }">
          <span :class="{ 'is-danger': inboundBeforeReporting(row) }">{{ shortDate(row.actualFinishDate) || '报工未完成' }}</span>
          <small v-if="row.inboundDate" :class="inboundBeforeReporting(row) ? 'metric-exception-text' : 'muted'">入库 {{ shortDate(row.inboundDate) }}{{ inboundHint(row) ? ` · ${inboundHint(row)}` : '' }}</small>
          <small v-else class="muted">无采购入库</small>
        </template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('deliveryResult')" label="生产交付结果" width="158">
        <template #default="{ row }">
          <el-tag v-if="row.stoppedAt" type="info" effect="plain">已停止跟单</el-tag>
          <el-tag v-else-if="row.deliveryOutcome === 'OVERDUE'" type="danger" effect="light">{{ deliveryOutcomeText(row) }}</el-tag>
          <el-tag v-else-if="row.deliveryOutcome === 'ON_TIME'" type="success" effect="light">{{ deliveryOutcomeText(row) }}</el-tag>
          <el-tag v-else-if="row.deliveryOutcome === 'NO_BASELINE'" type="info" effect="plain">{{ deliveryOutcomeText(row) }}</el-tag>
          <span v-else class="muted">{{ deliveryOutcomeText(row) }}</span>
          <small v-if="!row.stoppedAt && row.deliveredDate" class="muted"> {{ shortDate(row.deliveredDate) }}</small>
          <small v-else-if="!row.stoppedAt && row.expectedFinishDate" class="muted"> {{ shortDate(row.expectedFinishDate) }}</small>
        </template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('stages')" label="跟单节点" min-width="380">
        <template #default="{ row }">
          <div class="list-stage-actions" :aria-label="`${row.stageDoneCount || 0}/${row.stageTotal || rowStages(row).length} 节点已完成`">
            <el-button v-for="stage in rowStages(row)" :key="stage.code" size="small" :type="stage.status === 'DONE' ? 'success' : stage.status === 'IN_PROGRESS' ? 'warning' : 'default'"
              :plain="stage.status !== 'DONE'" :title="stage.status === 'DONE' ? `${stage.label}：${stage.completedDate || '已完成'}，点击查看详情` : `${stage.label}：${stageStatusText(stage.status)}，点击核对并登记完成`"
              @click.stop="stage.status === 'DONE' || row.stoppedAt || !canFollowup ? openOrderDetail(row) : openListCompletion([row], stage.code)">
              {{ stage.status === 'DONE' ? '✓ ' : '' }}{{ stage.label }}
            </el-button>
          </div>
          <small>{{ row.stageDoneCount || 0 }}/{{ row.stageTotal || rowStages(row).length }} 已完成</small>
        </template>
      </el-table-column>
      <el-table-column v-if="isListColumnVisible('timeSignals')" label="时间核实" min-width="150"><template #default="{ row }"><div class="time-signal-list"><el-tag v-for="signal in row.stoppedAt ? [] : rowTimeSignals(row)" :key="signal" size="small" :type="signal.endsWith('有误') ? 'info' : 'danger'" effect="plain" :title="signal.endsWith('有误') ? '已核实，源工单日期仍异常' : '尚待核实或跟进'">{{ signal }}</el-tag><span v-if="row.stoppedAt || !rowTimeSignals(row).length" class="muted">—</span></div></template></el-table-column>
      <el-table-column v-if="isListColumnVisible('salesTrace')" label="销售去向" width="150"><template #default="{ row }">
        <span v-if="row.salesTraceStale" class="is-danger">来源已变更，需复核</span>
        <span v-else-if="row.salesTraceMixedUnits">多单位，查看详情</span>
        <span v-else-if="Number(row.salesTraceReceivedQuantity) <= 0" class="muted">当前未见关联入库</span>
        <span v-else-if="row.salesTraceBeyondCoverage" class="muted">出库数据尚未覆盖</span>
        <span v-else-if="Number(row.salesTraceConfirmedQuantity) <= 0" class="muted">销售归属待核</span>
        <span v-else-if="Number(row.salesTraceConfirmedQuantity) >= Number(row.salesTraceReceivedQuantity)">入库部分已确认售出</span>
        <span v-else>部分确认销售</span>
        <small v-if="Number(row.salesTraceReceivedQuantity) > 0 && row.salesTraceBeyondCoverage">台账截至 {{ shortDate(row.salesTraceCoverageThrough) || '未导入' }}</small>
        <small v-else-if="Number(row.salesTraceReceivedQuantity) > 0">{{ row.salesTraceConfirmedQuantity || 0 }} / {{ row.salesTraceReceivedQuantity }} 已确认</small>
      </template></el-table-column>
      <el-table-column v-if="isListColumnVisible('assignee')" label="跟单员" width="110"><template #default="{ row }">{{ row.assigneeName }}</template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="openOrderDetail(row)">跟进</el-button></template></el-table-column>
    </el-table>
    <LqDialog
      v-model="listCompletionVisible"
      title="确认节点完成"
      width="min(700px, 94vw)"
      mode="detail"
      :show-footer="true"
      confirm-text="确认登记"
      :loading="listCompletionSaving"
      :confirm-disabled="!listCompletionDate"
      @confirm="confirmListCompletion"
    >
      <p>将以下 {{ listCompletionRows.length }} 单的<strong>{{ listCompletionStageCode ? '所选节点' : '当前未完成节点' }}</strong>登记为已完成。请核对实际完成日期；每单独立写入跟单历史。</p>
      <el-date-picker v-model="listCompletionDate" type="date" value-format="YYYY-MM-DD" placeholder="实际完成日期" />
      <el-input v-if="listCompletionStageCode" v-model="listCompletionNote" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="节点备注（可选）" style="margin-top: 12px" />
      <el-table :data="listCompletionRows" max-height="340">
        <el-table-column prop="orderNo" label="工单编号" min-width="155" />
        <el-table-column prop="productNameSnapshot" label="产品" min-width="170" show-overflow-tooltip />
        <el-table-column label="本次完成节点" min-width="115"><template #default="{ row }">{{ listCompletionTarget(row, listCompletionStageCode)?.label }}</template></el-table-column>
      </el-table>
    </LqDialog>
    <div class="pager">
      <LqPagination :pagination="pagination" :page-sizes="[20, 30, 50, 100]" @change="() => load(false)" />
    </div>

    <!-- 导出：列可选。导出的每个值都取页面同一套显示函数，保证与列表所见完全一致。 -->
    <LqDialog v-model="exportVisible" title="导出生产跟单" width="min(900px, 94vw)" mode="detail" :show-footer="true">
      <div class="export-scope">
        <span>导出范围</span>
        <el-radio-group v-model="exportScope">
          <el-radio value="filtered">当前筛选的全部 {{ total }} 条</el-radio>
          <el-radio value="selected" :disabled="!exportSelectedIds.length">已选中的 {{ exportSelectedIds.length }} 条</el-radio>
        </el-radio-group>
      </div>
      <p class="muted">
        {{ exportScope === 'selected' ? '仅导出打开弹窗时选中的工单（当前页）' : '按当前筛选条件导出全部工单（不受分页限制）' }}；
        <strong>每个车间一块</strong>，「生产进度」按该车间节点展开成各阶段完成日期列。
        跟单员视图导出的仍只是本人可见的任务。
        Excel 版可直接在表里填写<strong>跟单备注 / 车间预计完成（延期至）/ 延期原因 / 各节点完成日期</strong>，
        改完用「导入跟单表（回写）」导回；可回写列里没勾上的列导回时不会被写入。
        列已按<strong>字段来源</strong>分三组：氚云同步（只读）/ 跟单登记（人工，可导回）/ 系统计算（只读）；网页版导出件的表头颜色与之一一对应。
      </p>
      <div class="export-format">
        <el-radio-group v-model="exportFormat">
          <el-radio-button value="html">网页（.html）</el-radio-button>
          <el-radio-button value="xlsx">Excel（.xlsx）</el-radio-button>
        </el-radio-group>
        <span class="muted">{{ exportFormatHint }}</span>
      </div>
      <div class="export-presets">
        <el-button link type="primary" @click="applyExportPreset('list')">与列表一致</el-button>
        <el-button link type="primary" @click="applyExportPreset('core')">只导核心字段</el-button>
        <el-button link type="primary" @click="toggleAllExportColumns">{{ allExportSelected ? '全不选' : '全选' }}</el-button>
        <span class="muted">{{ exportSelectionSummary }}</span>
      </div>
      <!-- 列按「字段来源」分三段：蓝＝氚云同步只读、绿＝跟单登记人工、橙＝系统计算只读。
           分组只重排不增删列，勾选记忆（v-model）与原来完全同一份 key。 -->
      <el-checkbox-group v-model="exportColumns" class="export-columns">
        <div v-for="group in EXPORT_COLUMN_GROUPS" :key="group.source" class="export-column-group">
          <div class="export-column-group-head">
            <el-tag size="small" :type="group.meta.tagType" effect="light">{{ group.meta.label }}</el-tag>
            <span class="muted">{{ group.meta.hint }}</span>
            <span class="muted">{{ group.columns.length }} 列</span>
          </div>
          <div class="export-column-list">
            <el-checkbox v-for="column in group.columns" :key="column.key" :value="column.key">{{ column.label }}</el-checkbox>
          </div>
        </div>
      </el-checkbox-group>
      <template #footer>
        <button type="button" :disabled="exporting" @click="exportVisible = false">取消</button>
        <button type="button" class="primary" :disabled="exporting || !exportColumns.length" @click="doExport(exportFormat)">
          {{ exporting ? '导出中…' : exportFormat === 'html' ? '导出网页' : '导出 Excel' }}
        </button>
      </template>
    </LqDialog>

    <!-- 导回：把「导出件改完」再导回。只回写跟单员维护的四类字段，其余列只读；空白＝不修改。 -->
    <LqDialog v-model="roundTripDialog" title="导入跟单表（回写）" width="min(1180px, 96vw)" mode="detail" :show-footer="true">
      <template v-if="roundTripPreview">
        <p class="muted">
          {{ roundTripFile?.name }} · 共 {{ roundTripPreview.total }} 行。按<strong>任务号</strong>匹配生产任务；
          只回写<strong>跟单备注 / 车间预计完成（延期至）/ 延期原因 / 各节点完成日期</strong>，
          <strong>空白单元格＝不修改</strong>，其余列改了不会写回。
        </p>
        <div class="import-counts">
          <el-tag type="success">可回写 {{ roundTripPreview.write }}</el-tag>
          <el-tag type="info">已一致 {{ roundTripPreview.unchanged }}</el-tag>
          <el-tag type="warning">未匹配 {{ roundTripPreview.unmatched }}</el-tag>
          <el-tag type="warning">不在可见范围 {{ roundTripPreview.forbidden }}</el-tag>
          <el-tag type="warning">重复行 {{ roundTripPreview.duplicate }}</el-tag>
          <el-tag type="danger">无法识别 {{ roundTripPreview.invalid }}</el-tag>
        </div>
        <el-table :data="roundTripPreview.rows" max-height="56vh">
          <el-table-column prop="sheet" label="车间" min-width="120" />
          <el-table-column prop="excelRow" label="行号" width="65" />
          <el-table-column prop="orderNo" label="任务号" min-width="150" />
          <el-table-column prop="productName" label="产品" min-width="150" show-overflow-tooltip />
          <el-table-column label="结果" width="105">
            <template #default="{ row }"><el-tag :type="roundTripTagType(row.status)">{{ roundTripStatusText(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="本次改动 / 跳过原因" min-width="360">
            <template #default="{ row }">
              <div v-if="row.changes.length" class="change-list">
                <span v-for="change in row.changes" :key="`${change.field}-${change.after}`" class="change-chip">
                  {{ change.field }}：{{ change.before || '（空）' }} → {{ change.after }}
                </span>
              </div>
              <small v-if="row.note" class="muted">{{ row.note }}</small>
              <small v-if="!row.changes.length && !row.note" class="muted">—</small>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <button type="button" :disabled="roundTripSaving" @click="roundTripDialog = false">关闭</button>
        <button
          type="button"
          class="primary"
          :disabled="roundTripSaving || !roundTripPreview || !roundTripPreview.write"
          @click="confirmRoundTripImport"
        >
          {{ roundTripSaving ? '回写中…' : `确认回写 ${roundTripPreview?.write || 0} 行` }}
        </button>
      </template>
    </LqDialog>

    <!-- 生产任务单导入（跟单员自助）：与氚云导入中心同一套解析/预览/确认，只锁死导入类型。 -->
    <ProductionTaskImportDialog v-model="taskImportVisible" @imported="onProductionTaskImported" />

    <LqDialog v-model="importDialog" title="导入生产跟单进度表" width="min(1180px, 96vw)" mode="detail" :show-footer="true">
      <template v-if="importPreview">
        <p class="muted">{{ importFile?.name }} · 共 {{ importPreview.total }} 行。只有表格导入历史的任务随本次确认更新；已有人工跟进的变化进入主管逐项审核。</p>
        <div class="import-counts">
          <el-tag type="success">可导入 {{ importPreview.ready }}</el-tag>
          <el-tag type="success">表格记录可更新 {{ importPreview.update }}</el-tag>
          <el-tag type="info">已有跟单 {{ importPreview.existing }}</el-tag>
          <el-tag type="warning">差异待审核 {{ importPreview.review }}</el-tag>
          <el-tag type="warning">待核对 {{ importPreview.pending }}</el-tag>
          <el-tag type="info">已归档 {{ importPreview.archived }}</el-tag>
        </div>
        <el-alert v-if="importPreview.alreadyUploaded" title="这份文件已经导入过" type="info" :closable="false" />
        <el-table :data="importPreview.rows" max-height="56vh">
          <el-table-column prop="sheet" label="车间" min-width="125" />
          <el-table-column prop="rowNumber" label="行号" width="65" />
          <el-table-column prop="sourceOrderNo" label="短编号" width="90" />
          <el-table-column prop="productName" label="表格产品" min-width="180" />
          <el-table-column prop="requiredCompletionDate" label="交期" width="110" />
          <el-table-column prop="workOrderNo" label="对应工单" min-width="155" />
          <el-table-column label="结果" min-width="200">
            <template #default="{ row }"><el-tag :type="['READY', 'UPDATE'].includes(row.status) ? 'success' : ['PENDING', 'REVIEW'].includes(row.status) ? 'warning' : 'info'">{{ row.status === 'READY' ? '可导入' : row.status === 'UPDATE' ? '可更新表格记录' : row.status === 'EXISTING' ? '已有跟单且无变化' : row.status === 'REVIEW' ? '差异待审核' : row.status === 'ARCHIVED' ? '已归档' : '待匹配' }}</el-tag><small>{{ row.reason }}</small></template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <button type="button" :disabled="importSaving" @click="importDialog = false">关闭</button>
        <button
          type="button"
          class="primary"
          :disabled="importSaving || !importPreview || importPreview.alreadyUploaded"
          @click="confirmWorkbookImport"
        >
          {{ importSaving ? '导入中…' : '确认导入' }}
        </button>
      </template>
    </LqDialog>

    <LqDialog v-model="workbookDialog" title="进度表导入记录" width="min(1400px, 96vw)" mode="detail">
      <p class="muted">待匹配行需核对工单；已有跟单的变化由主管选择字段后应用。表格版本和原始记录保留。</p>
      <el-radio-group v-model="workbookView" @change="onWorkbookViewChange">
        <el-radio-button value="pending">待处理{{ workbookRows.length ? `（${workbookRows.length}）` : '' }}</el-radio-button>
        <el-radio-button value="history">历史版本</el-radio-button>
      </el-radio-group>

      <div class="workbook-tools">
        <el-select v-model="workbookSheetFilter" clearable filterable placeholder="车间（全部）" style="width: 165px">
          <el-option v-for="sheet in workbookSheets" :key="sheet" :label="sheet" :value="sheet" />
        </el-select>
        <el-select v-if="workbookView === 'history'" v-model="workbookStatusFilter" clearable placeholder="状态（全部）" style="width: 150px">
          <el-option v-for="opt in WORKBOOK_STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input v-model="workbookKeyword" clearable placeholder="产品 / 编号" style="width: 190px" />
        <span class="muted">共 {{ visibleWorkbookRows.length }} 行</span>
        <span class="workbook-spacer"></span>
        <template v-if="workbookView === 'pending'">
          <el-button size="small" :disabled="!selectedWorkbookIds.length" @click="batchWorkbook('skip')">批量跳过（{{ selectedWorkbookIds.length }}）</el-button>
          <el-button size="small" :disabled="!selectedWorkbookIds.length" @click="batchWorkbook('archive')">批量归档</el-button>
        </template>
        <el-button v-else size="small" :disabled="!selectedWorkbookIds.length" @click="batchWorkbook('restore')">恢复为待处理（{{ selectedWorkbookIds.length }}）</el-button>
        <el-button size="small" link type="primary" @click="openArchiveRules">归档规则{{ archiveRules.length ? `（${archiveRules.length}）` : '' }}</el-button>
      </div>

      <p v-if="workbookView === 'pending' && pendingSheetSummary" class="muted workbook-summary">{{ pendingSheetSummary }}</p>

      <el-table ref="workbookTableRef" :data="visibleWorkbookRows" max-height="56vh" row-key="id" @selection-change="onWorkbookSelectionChange">
        <el-table-column type="selection" width="42" />
        <el-table-column label="状态" width="105">
          <template #default="{ row }">
            <el-tag size="small" :type="workbookStatusTone(row.matchStatus)" effect="light">{{ workbookStatusLabel(row.matchStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文件版本" min-width="165">
          <template #default="{ row }">
            <span>{{ row.sourceFile || '进度表' }}</span>
            <small>{{ formatTime(row.createdAt) }} · {{ row.sourceHash?.slice(0, 8) }}</small>
            <small v-if="row.stale" class="is-danger">该车间已有更新版本，本行未被覆盖</small>
          </template>
        </el-table-column>
        <el-table-column prop="sheetName" label="车间" min-width="120" />
        <el-table-column prop="sheetRow" label="表格行" width="70" />
        <el-table-column prop="sourceOrderNo" label="短编号" width="80" />
        <el-table-column prop="productName" label="表格产品" min-width="170" />
        <el-table-column prop="sourceQuantity" label="数量" width="65" />
        <el-table-column prop="requiredCompletionDate" label="表格交期" width="105" />
        <el-table-column prop="matchNote" label="待核原因" min-width="165" />
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <template v-if="workbookView === 'pending'">
              <template v-if="row.matchStatus === 'REVIEW'">
                <el-button link type="primary" @click="showReview(row)">核对差异</el-button>
              </template>
              <template v-else>
                <el-button link type="primary" @click="showCandidates(row)">关联工单</el-button>
                <el-button link type="warning" @click="archiveRow(row)">归档此类</el-button>
                <el-button link @click="skipWorkbookRow(row)">跳过</el-button>
              </template>
            </template>
            <template v-else>
              <el-button v-if="row.matchStatus !== 'PENDING' && row.matchStatus !== 'REVIEW'" link type="primary" @click="restoreRow(row)">恢复待处理</el-button>
              <span v-else class="muted">—</span>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </LqDialog>

    <LqDialog v-model="reviewDialog" title="逐项确认表格差异" width="min(680px, 96vw)" mode="detail" :show-footer="true">
      <template v-if="activeReview">
        <p class="muted">仅勾选的字段会写入本地跟单，并生成一条主管审核记录。未勾选字段保留当前值；氚云源工单不变。</p>
        <el-checkbox-group v-model="selectedReviewKeys" class="review-differences">
          <el-checkbox v-for="difference in activeReview.differences" :key="difference.key" :value="difference.key">
            {{ difference.label }}：{{ difference.currentValue || '未填' }} → {{ difference.proposedValue }}
          </el-checkbox>
        </el-checkbox-group>
        <p v-if="!activeReview.differences.length" class="muted">本地跟单已变化，当前没有可应用的差异；可忽略此版本。</p>
      </template>
      <template #footer>
        <button type="button" :disabled="reviewSaving" @click="reviewDialog = false">关闭</button>
        <button type="button" :disabled="reviewSaving" @click="ignoreReview">保留本地记录</button>
        <button
          type="button"
          class="primary"
          :disabled="reviewSaving || !selectedReviewKeys.length"
          @click="applyReview"
        >
          {{ reviewSaving ? '处理中…' : '应用所选差异' }}
        </button>
      </template>
    </LqDialog>

    <LqDialog v-model="candidateDialog" title="关联氚云工单" width="min(1080px, 96vw)" mode="detail">
      <p v-if="selectedWorkbookRow" class="muted">{{ selectedWorkbookRow.sheetName }}第 {{ selectedWorkbookRow.sheetRow }} 行：{{ selectedWorkbookRow.productName }}，{{ selectedWorkbookRow.sourceQuantity }} 台；交期 {{ selectedWorkbookRow.requiredCompletionDate || '未填' }}。</p>
      <div class="candidate-search">
        <el-input v-model="candidateKeyword" clearable placeholder="默认按车间/产品/交期自动推荐；输入工单号、产品或客户可精确查找" @keyup.enter="searchCandidates" />
        <el-button @click="searchCandidates">查找</el-button>
      </div>
      <p class="muted candidate-hint">
        {{ candidateSearching ? '按关键词查找结果（命中字段仅供参考，请人工核对）' : '按车间、产品、交期、订单号、数量自动评分排序；分数越高越可能是同一张工单。' }}
      </p>
      <el-table v-loading="candidateLoading" :data="candidates" max-height="460">
        <el-table-column label="匹配度" width="92">
          <template #default="{ row }">
            <el-tag v-if="row.score !== undefined && row.score !== null" size="small" effect="light" :type="row.score >= 60 ? 'success' : row.score >= 30 ? 'warning' : 'info'">{{ row.score }} 分</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="orderNo" label="氚云工单号" min-width="150" />
        <el-table-column prop="productName" label="产品" min-width="160" />
        <el-table-column label="车间" min-width="105"><template #default="{ row }">{{ shortWorkshop(row.workshopType) }}</template></el-table-column>
        <el-table-column prop="plannedQuantity" label="数量" width="66" />
        <el-table-column label="申请时间" width="105"><template #default="{ row }">{{ row.plannedStartDate || '未填' }}</template></el-table-column>
        <el-table-column label="车间预计完成" width="120"><template #default="{ row }">{{ row.expectedFinishDate || '未填' }}</template></el-table-column>
        <el-table-column label="命中 / 冲突" min-width="185">
          <template #default="{ row }">
            <el-tag v-for="field in row.matchedFields || []" :key="`m-${field}`" size="small" type="success" effect="plain">{{ field }}</el-tag>
            <el-tag v-for="field in row.conflictFields || []" :key="`c-${field}`" size="small" type="info" effect="plain">{{ field }} 不符</el-tag>
            <span v-if="!(row.matchedFields || []).length && !(row.conflictFields || []).length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="105" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="selectCandidate(row)">确认导入</el-button></template>
        </el-table-column>
      </el-table>
    </LqDialog>

    <LqDialog v-model="archiveRuleDialog" title="归档规则" width="min(820px, 96vw)" mode="detail">
      <p class="muted">命中以下产品名的表格行，在导入进度表时会直接归为「已归档」，不再进入待处理队列。规则只作用于<strong>匹配不上工单</strong>的行，能唯一匹配的行不受影响。</p>
      <el-table :data="archiveRules" max-height="50vh" empty-text="暂无归档规则">
        <el-table-column prop="productLabel" label="表格产品名" min-width="200" />
        <el-table-column prop="reason" label="归档原因" min-width="140" />
        <el-table-column label="建立" width="180">
          <template #default="{ row }"><span>{{ row.createdByName }}</span><small>{{ formatTime(row.createdAt) }}</small></template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }"><el-button link type="danger" @click="revokeArchiveRule(row)">撤销并恢复</el-button></template>
        </el-table-column>
      </el-table>
    </LqDialog>

    <TrackingDetailDrawer v-model="detailVisible" :order-id="detailOrderId" :items="items" @saved="load()" />
    <ProductionTrackingAnalyticsDrawer v-model="analysisVisible" @open-order="openFromAnalysis" />
    <ProductionTrackingWeeklyReviewDrawer v-model="weeklyReviewVisible" @open-order="openFromAnalysis" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchTracking } from '@/api/productionTracking'
import type { TrackingOrder } from '@/api/productionTracking'
import ProductionTaskImportDialog from '@/components/production/ProductionTaskImportDialog.vue'
import TrackingDetailDrawer from '@/components/production/TrackingDetailDrawer.vue'
import ProductionTrackingAnalyticsDrawer from './ProductionTrackingAnalyticsDrawer.vue'
import ProductionTrackingWeeklyReviewDrawer from './ProductionTrackingWeeklyReviewDrawer.vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { isSourceUrgent } from '@/utils/trackingUrgency'
import { trackingActionHint as actionHint, trackingActionLabel as actionLabel, trackingActionTone as actionTone } from '@/utils/trackingAction'
// 源工单日期判据与「业务员要求工期」的口径唯一来源（列表 / 详情 / 导出共用，见该模块头注释）。
import { leadDaysCellClass, leadDaysTooltipHtml } from '@/utils/tracking/leadDaysDisplay'
import { effectiveLeadDaysText, invalidApplicationDate, invalidRequiredDate, leadDaysKindText, otherLeadDaysText } from '@/utils/trackingDateGuard'
// 页面的纯业务规则（节点 / 日期组合取数 / 时间核实 / 交付结果）不在这里写第二份，见该模块头注释。
import { canQuickComplete, countdownTone, currentCompletionDate, deliveryOutcomeText, forecastAdjustText, formatMinute, formatTime, inboundBeforeReporting, inboundHint, listCompletionTarget, listStages, productSize, productionCountdown, shortDate, shortOrderNo, shortWorkshop, stageStatusText, timeSignals, trackingStatusText, trackingStatusTone } from '@/utils/tracking/rules'
import { todayKey } from '@/utils/tracking/today'
import { TRACKING_ORDER_QUERY } from '@/utils/tracking/detailLink'
import { useTrackingFilters } from '@/composables/tracking/useTrackingFilters'
import { useTrackingExport } from '@/composables/tracking/useTrackingExport'
import { useTrackingListColumns } from '@/composables/tracking/useTrackingListColumns'
import { useTrackingWorkbook } from '@/composables/tracking/useTrackingWorkbook'
import { useTrackingRoundTrip } from '@/composables/tracking/useTrackingRoundTrip'
import { useTrackingListCompletion } from '@/composables/tracking/useTrackingListCompletion'
import { useListPagination } from '@/composables/list/useListPagination'
import LqPagination from '@/components/common/LqPagination.vue'
import LqDialog from '@/components/common/LqDialog.vue'

/**
 * 筛选条件（关键词 / 车间 / 跟单状态 / 交付结果 / 提交时间 …）的状态与全部派生都收在 useTrackingFilters 里，
 * 解构出来的名字与模板一致；改筛选后做什么由 onChange＝search 决定（本页只负责分页与请求）。
 */
const {
  keyword, advancedFiltersVisible, dateFilters, countdownFilter, deliveryFilter, optionalStageFilter,
  moldStageFilter, salespersonFilter, workshop, trackingStatus, timeFilter, urgentOnly, mine,
  filterOptions, COUNTDOWN_FILTER_OPTIONS, DELIVERY_FILTER_OPTIONS, optionalStageOptions, MOLD_STAGE_OPTIONS,
  quickFilterPresets, quickFilterActive, applyQuickFilter, activeAdvancedFilterCount,
  activeFilterChips, clearFilterChip, resetTrackingFilters, onCreatedDateChange, onExpectedDateChange,
  trackingFilters, loadOptions: loadFilterOptions
} = useTrackingFilters({ onChange: search })
/** 「今天」的唯一来源见 @/utils/tracking/today；这里的心跳只为让「生产倒计时」列跨天自动刷新。 */
const countdownToday = ref(todayKey())
let countdownTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => { countdownTimer = setInterval(() => { countdownToday.value = todayKey() }, 60_000) })
onUnmounted(() => { if (countdownTimer) clearInterval(countdownTimer) })
/**
 * 吸顶：筛选栏固定在滚动容器（.workspace__body）顶部，列表表头紧贴在筛选栏下沿。
 * 表头的 sticky top 不能写死——筛选栏会因「更多筛选」展开、窄屏换行、已选条件 chips 而变高，
 * 写死就会在展开时把表头压在筛选栏下面（或留白）。故用 ResizeObserver 量真实高度，
 * 经 --tracking-sticky-top 传给表头（见 <style> 里 .el-table__header-wrapper 的 sticky top）。
 */
const filterPanelRef = ref<HTMLElement | null>(null)
const filterPanelHeight = ref(0)
const stickyHeaderStyle = computed(() => ({ '--tracking-sticky-top': `${filterPanelHeight.value}px` }))
let filterPanelObserver: ResizeObserver | null = null
onMounted(() => {
  const panel = filterPanelRef.value
  if (!panel || typeof ResizeObserver === 'undefined') return
  const sync = () => { filterPanelHeight.value = Math.round(panel.getBoundingClientRect().height) }
  sync()
  filterPanelObserver = new ResizeObserver(sync)
  filterPanelObserver.observe(panel)
})
onUnmounted(() => { filterPanelObserver?.disconnect(); filterPanelObserver = null })
/** 后端返回的跟单可见范围：ALL=看全部（主管/管理员），其余=只看本人负责车间。 */
const visibility = ref('ALL')
const analysisVisible = ref(false)
const weeklyReviewVisible = ref(false)
const route = useRoute()
const router = useRouter()
watch(() => route.query.analysis, value => { if (value === '1') analysisVisible.value = true }, { immediate: true })
function openFromAnalysis(id: number) { openOrderDetail({ id }) }
const auth = useCurrentUser()
/**
 * 分页状态唯一来源：公共 useListPagination（0 基）。
 * 后端 /api/manufacturing/followups 的 page 是 **1 基**（默认 1），故下面只在此处做一次 +1 转换，
 * 页面其余部分与模板一律沿用与改造前同义的 `page` / `size` / `total`。
 */
const pagination = useListPagination({ page: 0, size: 30 })
const page = computed(() => pagination.pageInfo.page + 1)
/** 每页条数：由分页器「条/页」下拉选择（后端 list 接受 1~100）。 */
const size = computed(() => pagination.pageInfo.size)
const total = computed(() => pagination.pageInfo.total)
const items = ref<TrackingOrder[]>([])
/**
 * 「业务员」列头可选项：优先取后端 filter-options 的 salespersons（与可见性同口径），
 * 再并入当前页已出现的姓名兜底 —— 后端未升级时也不至于下拉为空。
 */
const salespersonOptions = computed(() => {
  const names = new Set<string>(filterOptions.value.salespersons || [])
  for (const row of items.value) if (row.salespersonNameSnapshot) names.add(row.salespersonNameSnapshot)
  return [...names].sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'))
})
const loading = ref(false)
const selectedTrackingRows = ref<TrackingOrder[]>([])
/**
 * 列表「批量记录节点完成」收在 useTrackingListCompletion 里：逐单**写入前重新拉详情**取当前版本，
 * 不用勾选时的快照去写（列表版本可能已变）。
 */
const {
  listCompletionVisible, listCompletionRows, listCompletionDate, listCompletionSaving,
  listCompletionStageCode, listCompletionNote, openListCompletion, confirmListCompletion
} = useTrackingListCompletion({ reload: () => load() })
function onTrackingSelectionChange(rows: TrackingOrder[]) {
  selectedTrackingRows.value = rows
}
/**
 * 详情抽屉本身抽成 `TrackingDetailDrawer`（2026-10-22）：生产跟单页与「生产产品与工期管理」页
 * 共用同一份模板与保存逻辑，产品页从历史任务 / 样本明细下钻时在原地打开、不再跳转过来。
 * 页面这边只留「打开哪一单」，`items` 只为「保存并下一单」提供列表顺序。
 */
const detailVisible = ref(false)
const detailOrderId = ref<number | null>(null)
function openOrderDetail(row: { id: number }) {
  detailOrderId.value = row.id
  detailVisible.value = true
}
/**
 * 深链打开某张工单的详情：`/production-tracking?order=<production_orders.id>`。
 * 来源是「生产产品与工期管理」页的「历史任务 / 样本明细」里的「跟单详情」——
 * 那边只认任务单号与 id，不认当前列表筛选，所以必须靠 query 直接把抽屉拉到前台。
 * 打开后立刻把参数抹掉：否则关掉抽屉再点同一张单，watch 的值没变、不会重新触发（点了像没反应）。
 */
watch(() => route.query[TRACKING_ORDER_QUERY], value => {
  if (value == null || value === '') return
  const orderId = Number(value)
  if (!Number.isInteger(orderId) || orderId <= 0) return
  openOrderDetail({ id: orderId })
  void router.replace({ path: route.path, query: { ...route.query, [TRACKING_ORDER_QUERY]: undefined } })
}, { immediate: true })
/**
 * 【日期语义 · 唯一说明】三个日期是三种性质，禁止互相顶替：车间预计完成 expectedFinishDate（源数据，
 * 本页只读）/ 订单要求完成 dueDate（业务员提出）/ 延期至 forecastFinishDate（协商后的新完成日）。
 * 它们共同构成的「截止基准 / 当前承诺日」口径已下沉到 `@/utils/tracking/rules`：
 * deadlineOf / currentCompletionDate / validRequiredDate 即唯一实现，页面只调用，不再自留一份 COALESCE。
 */
/**
 * 进度表导入闭环（预览 / 待处理 / 关联 / 归档规则 / 差异审核）收在 useTrackingWorkbook 里：
 * 这条链路与列表、详情没有共享状态，唯一的交点是「导入或审核落地后刷新列表」⇒ reload。
 */
const {
  WORKBOOK_STATUS_OPTIONS, workbookDialog, workbookRows, workbookView, selectedWorkbookRow,
  candidateDialog, candidateKeyword, candidateLoading, candidateSearching, candidates, workbookActionable,
  selectedWorkbookIds, workbookTableRef, workbookSheetFilter, workbookStatusFilter, workbookKeyword,
  archiveRules, archiveRuleDialog, workbookInput, importFile, importPreview, importDialog, importSaving,
  reviewDialog, reviewSaving, activeReview, selectedReviewKeys,
  visibleWorkbookRows, workbookSheets, pendingSheetSummary,
  showWorkbookRows, loadWorkbookSummary, onWorkbookViewChange, onWorkbookSelectionChange,
  workbookStatusLabel, workbookStatusTone, batchWorkbook, archiveRow, restoreRow,
  openArchiveRules, revokeArchiveRule, onWorkbookSelected, confirmWorkbookImport, showCandidates, showReview,
  applyReview, ignoreReview, searchCandidates, selectCandidate, skipWorkbookRow
} = useTrackingWorkbook({ reload: () => load() })

let latestListRequest = 0
/**
 * 列表 / 进度表待办数 / 筛选下拉三者的加载顺序。
 *
 * ⚠️ 2026-10-20 改并行：原先 list → workbook-summary → filter-options **依次 await**，
 * 三个接口各自 0.5~0.8s，首屏「最后一个接口完成」被串成 2.3s（实测瀑布 911→1520→1530→2363ms）。
 * 三者互不依赖（summary 只要一个汇总数、filter-options 只要候选值），故改为同时发出。
 *
 * 语义与串行版**逐条等价**：
 *   · 首次进入页面 = 跟单员（可见范围非 ALL）时，summary 那次请求会多算一遍，故拿到 list 结果后
 *     立刻按真实可见范围归零——与串行版「先知道 visibility 再决定调不调」结果相同；
 *   · 失败提示仍只挂在「列表自身失败」上；summary 失败由它自己置 0（见 useTrackingWorkbook）。
 * 唯一的变化是**加载顺序**：`loading` 只等列表，表格因此早约 1.4s 可读。
 */
async function load(refreshOverview = true) {
  const requestId = ++latestListRequest
  loading.value = true
  // 首屏（visibility 还是默认 ALL）或刷新时才拉待办数；只翻页/改筛选时不拉（与原逻辑一致）
  const refreshSummary = refreshOverview && visibility.value === 'ALL'
  const summaryTask = refreshSummary ? loadWorkbookSummary() : Promise.resolve()
  const optionsTask = loadFilterOptions(refreshOverview)
  try {
    const result = await fetchTracking({ ...trackingFilters(), page: page.value, size: size.value })
    if (requestId !== latestListRequest) return
    selectedTrackingRows.value = []
    items.value = result.items
    // 只回写总数：响应的 page 是 1 基，直接 updateFromResponse 会把 0 基状态整体偏移一页
    pagination.pageInfo.total = result.total
    pagination.pageInfo.pages = Math.ceil(result.total / size.value)
    visibility.value = result.visibility || 'ALL'
    if (visibility.value !== 'ALL') workbookActionable.value = 0
  } catch (error) { if (requestId === latestListRequest) ElMessage.error(error instanceof Error ? error.message : '加载跟单任务失败') }
  finally { if (requestId === latestListRequest) loading.value = false }
  await Promise.all([summaryTask, optionsTask])
}
function search() { pagination.resetPage(); void load(false) }
function refreshTracking() { void load(true) }
/** 切换「条/页」后回到第一页：由 LqPagination 内部 onPageSizeChange 完成（同时触发 change → load）。 */
/**
 * 列表列的显示/隐藏（本机勾选 > 全站默认 > 代码兜底）收在 useTrackingListColumns 里；
 * 列 key 清单由该模块导出，前后端列契约测试直接读它，不再读本页源码。
 */
const {
  LIST_COLUMN_DEFS, hiddenListColumns, columnSettingVisible, visibleListColumns,
  isListColumnVisible, resetListColumns, canManageColumnDefaults, savingColumnDefaults,
  globalDefaultSummary, loadColumnDefaults, restoreColumnDefault, saveGlobalColumnDefaults
} = useTrackingListColumns({ canManage: () => auth.can('manufacturing.listcolumns.manage') })
/**
 * 列表行的派生展示：节点清单与时间核实信号都是纯函数，但模板里会被 v-for / v-if / aria-label
 * 反复调用（一行最多 3 次，每次都重新 filter 一遍 stageProgress）。这里按行算一次缓存；
 * 「时间核实」列被隐藏时干脆不算。
 * 跟进信号里含「核实事项到期」判据，故依赖 countdownToday 心跳，跨天会随心跳重算。
 */
const listRowStages = computed(() => new Map(items.value.map(row => [row.id, listStages(row)] as const)))
const listRowTimeSignals = computed(() => new Map(isListColumnVisible('timeSignals')
  ? items.value.map(row => [row.id, timeSignals(row, countdownToday.value)] as const)
  : []))
function rowStages(row: TrackingOrder) { return listRowStages.value.get(row.id) || [] }
function rowTimeSignals(row: TrackingOrder) { return listRowTimeSignals.value.get(row.id) || [] }
/**
 * ============================ 导出 ============================
 * 列定义 / 勾选记忆 / 取数分组 / 两种格式的落盘全部收在 useTrackingExport 里（见该模块头注释）。
 * 「导出的值与列表所见同源」靠注入的 `trackingFilters()` 保证 —— 导出侧不再有第二份筛选入参。
 */
const {
  exportVisible, exporting, exportScope, exportSelectedIds, exportColumns, exportFormat,
  EXPORT_COLUMN_GROUPS, exportSelectionSummary, allExportSelected, exportFormatHint,
  openExport, toggleAllExportColumns, applyExportPreset, doExport
} = useTrackingExport({
  filters: trackingFilters,
  visibility,
  selectedIds: () => selectedTrackingRows.value.map(row => row.id)
})
/**
 * 导回（导出件改完再导回）收在 useTrackingRoundTrip 里：预检与写入都在后端，
 * 前端只负责选文件与展示「哪几行能回写／为什么跳过」。
 */
const {
  canFollowup, roundTripInput, roundTripDialog, roundTripFile, roundTripPreview, roundTripSaving,
  roundTripStatusText, roundTripTagType, onRoundTripSelected, confirmRoundTripImport
} = useTrackingRoundTrip({ canFollowup: () => auth.can('manufacturing.followup'), reload: () => load() })

/**
 * 生产任务单导入（跟单员自助）：弹窗负责 上传→解析→预览→确认，成功后重载列表。
 * 新导入的工单按车间默认跟单员自动归入可见范围（与列表可见性同一判据），不需要再手工指派。
 */
const taskImportVisible = ref(false)
function onProductionTaskImported() { void load(true) }
await Promise.all([loadColumnDefaults(), load()])
</script>


<style scoped>
/* ⚠️ 必须是 flex 列，不能是 grid：grid 项的包含块是「它自己的网格区」，高度就等于元素本身，
   position:sticky 没有可位移空间 ⇒ 筛选栏吸顶直接失效（flex 项的包含块是容器内容框，有盈余高度）。
   单列 grid 与 flex 列在间距/拉伸上表现一致，改过来不影响其余排版。 */
.tracking-page { display: flex; flex-direction: column; gap: 18px; min-width: 0; }
.urgent-list-tag { display: table; margin-top: 4px; }
.closure-marker { color: #047857; font-size: 12px; margin-left: 2px; }
.urgent-detail-alert { margin: 0 0 12px; }
/* 小屏收紧页面自身的留白（外壳间距由全局 responsive.css 的令牌分层处理）。 */
@media (max-width: 1440px) { .tracking-page { padding: 18px; gap: 14px; } }
@media (max-width: 1024px) { .tracking-page { padding: 14px; gap: 12px; } }
.source-order-remark p { margin: 0; padding: 14px 16px; border: 1px solid #e4e7ed; border-radius: 8px; background: #f8fafc; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
/* 页头：窄屏时操作按钮整体换到标题下方，而不是把标题挤成一字一行（1024 实测灾难）。 */
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.page-head > div:first-child { flex: 1 1 260px; min-width: 0; }
.head-actions { flex-wrap: wrap; justify-content: flex-end; }
.head-actions, .candidate-search { display: flex; gap: 8px; align-items: center; }
.candidate-search { margin-bottom: 12px; }
.workbook-tools { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 12px 0 8px; }
.workbook-spacer { flex: 1 1 auto; }
.workbook-summary { margin: 0 0 8px; }
.candidate-hint { margin: 0 0 8px; font-size: 12px; }
.import-counts { display: flex; gap: 8px; margin: 12px 0; flex-wrap: wrap; }
.import-counts + .el-alert { margin-bottom: 12px; }
/* 导回预检：逐项变更用「字段：旧值 → 新值」的小标签排开，一眼看清这次会改什么。 */
.change-list { display: flex; flex-wrap: wrap; gap: 4px 6px; }
.change-chip { background: #f1f5f9; border: 1px solid #e2e8f0; border-radius: 6px; padding: 1px 6px; font-size: 12px; color: #334155; }
.page-head h1 { margin: 2px 0 6px; font-size: 26px; }
.page-head p { margin: 0; color: var(--color-text-secondary, #667085); }
.eyebrow { color: var(--color-primary, #0f766e) !important; font-size: 12px; letter-spacing: .12em; font-weight: 700; }
/* 筛选栏吸顶：滚动容器是 .workspace__body（overflow-y:auto），它夹在 topbar 之下。
   top 取负的内边距令牌——sticky 的参照系是内容盒，若写 top:0 面板会停在「内容盒顶」，
   与滚动区顶端之间留出一条内边距缝隙，滚动中的表格行会从缝里露出来；
   负值让面板贴到滚动区最顶端，把缝隙盖住。z-index 只需压过 el-table 内部（表头 3 / 固定列 ~2）。 */
.tracking-filter-panel { position: sticky; top: calc(-1 * var(--page-block-padding, 0px)); z-index: 20; overflow: hidden; border: 1px solid #dce5e3; border-radius: 12px; background: #fff; box-shadow: 0 2px 12px rgba(16, 56, 50, .035); }
.toolbar { display: flex; gap: 10px 18px; align-items: center; flex-wrap: wrap; padding: 13px 16px; }
.tracking-filter-fields { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; }
.tracking-filter-fields .tracking-keyword { flex: 0 0 260px; width: 260px; }
.tracking-common-date { display: flex; align-items: center; flex: 0 0 296px; min-width: 0; }
.tracking-common-date .tracking-extra-date-inputs { width: 100%; }
.tracking-urgent-only { margin: 0 4px; white-space: nowrap; }
.tracking-filter-fields :deep(.el-input__wrapper), .tracking-filter-fields :deep(.el-select__wrapper) { min-height: 32px; }
.tracking-filter-fields > :deep(.el-select) { flex: 0 0 180px; width: 180px; }
.tracking-filter-bottom { display: flex; align-items: center; gap: 16px; margin-left: auto; flex-wrap: wrap; }
.tracking-filter-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.tracking-filter-actions :deep(.el-button + .el-button) { margin-left: 0; }
.tracking-quick-filters, .tracking-active-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 10px 16px; border-top: 1px solid #e9eeec; }
.tracking-quick-filters :deep(.el-button + .el-button) { margin-left: 0; }
.tracking-active-filters { background: #f7faf9; font-size: 12px; color: #475467; }
.tracking-active-filters :deep(.el-tag) { max-width: 100%; }
.tracking-extra-filters { display: grid; grid-template-columns: repeat(auto-fill, 180px); gap: 10px 12px; align-items: start; padding: 12px 16px 14px; border-top: 1px solid #e9eeec; background: #fff; }
.tracking-extra-filter { display: flex; align-items: center; min-width: 0; min-height: 32px; }
.tracking-extra-filter-check :deep(.el-checkbox) { margin: 0; }
.tracking-extra-filter :deep(.el-select) { width: 100%; }
.tracking-extra-filter :deep(.el-select__wrapper), .tracking-extra-date-inputs :deep(.el-input__wrapper) { min-height: 32px; }
.tracking-extra-date-inputs { display: flex; align-items: center; gap: 7px; min-width: 0; }
.tracking-extra-date-inputs :deep(.el-date-editor) { flex: 1 1 0; width: 0; min-width: 0; }
.tracking-extra-date-inputs > span { color: #667085; font-size: 12px; flex: none; }
@media (max-width: 960px) { .tracking-filter-bottom { margin-left: 0; width: 100%; justify-content: space-between; } .tracking-extra-filters { grid-template-columns: repeat(auto-fill, minmax(145px, 1fr)); } }
@media (max-width: 600px) { .toolbar { padding: 12px; } .tracking-filter-fields { width: 100%; } .tracking-filter-fields .tracking-keyword, .tracking-common-date { flex: 1 1 100%; width: 100%; } .tracking-filter-fields > :deep(.el-select) { flex: 1 1 calc(50% - 4px); width: calc(50% - 4px); } .tracking-filter-bottom { align-items: stretch; } .tracking-filter-actions { width: 100%; } .tracking-filter-actions :deep(.el-button) { flex: 1 1 auto; } .tracking-quick-filters, .tracking-active-filters { padding: 10px 12px; } .tracking-extra-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 12px; } }
.list-batch-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.tracking-table { width: 100%; }
/* 表头吸顶（10-15）：
   ① .el-table 自带 overflow:hidden，会把表头的 sticky 拘在表格内部——而表格自身不滚动
      （高度随内容撑开），表头会纹丝不动地跟着页面滚走。放开这一层，sticky 的参照系才回到
      滚动容器 .workspace__body。x 向裁剪由 .el-table__body-wrapper 内部的 el-scrollbar 负责，不受影响。
   ② 表头 top 取 --tracking-sticky-top（= 筛选栏实测高度，脚本里 ResizeObserver 写入），
      否则「更多筛选」展开 / 窄屏换行时表头会错位到筛选栏下面。
   ③ 固定列（编号 / 操作）是同一套表内的 sticky 单元格，跟着这层一起吸顶，无需另配。 */
.tracking-table.el-table { overflow: visible; }
.tracking-table :deep(.el-table__header-wrapper) {
  position: sticky;
  /* sticky 参照系是滚动容器的内容盒（比滚动区顶端低一个内边距），面板吸顶时又上浮了
     一个内边距（见 .tracking-filter-panel 的负 top），两层相抵后表头的停靠位 = 面板底沿。 */
  top: calc(var(--tracking-sticky-top, 0px) - var(--page-block-padding, 0px));
  z-index: 3;
}
.countdown-normal { color: #13775e; font-weight: 600; }
.countdown-soon { color: #b65c00; font-weight: 600; }
.tracking-table small { display: block; color: #667085; margin-top: 3px; }
/* ⚠️ .tracking-table small 的 (0,1,1) 会压过单类选择器 ⇒ 提醒小字的语义色必须同样带前缀提权，
   否则「早于任务单提交日期」红、「已协商延期」橙在列表里全被刷成灰（2026-10-15 LQ 反馈）。 */
.tracking-table small.metric-exception-text { color: #b42318; }
.tracking-table small.metric-delay { color: #b54708; }
/* 工期偏差小字：比要求晚=红、早=绿（同样要带前缀提权，否则被通用小字刷灰）。 */
.tracking-table small.lead-delta-late { color: #b42318; font-weight: 600; }
.tracking-table small.lead-delta-early { color: #067647; font-weight: 600; }
/* 工期列主数值直接着色（小字已撤，颜色+悬停提示，2026-10-15 LQ 定）。 */
.tracking-table span.lead-delta-late { color: #b42318; font-weight: 600; }
.tracking-table span.lead-delta-early { color: #067647; font-weight: 600; }
/* 表格内异常日期 / 待核实数值的红（申请时间、要求完成、工期、倒计时逾期共用）。 */
.tracking-table .is-danger { color: #d92d20; font-weight: 600; }
/* 工期列两行：主数值 + 真身徽章 / 其余真身紧凑小字（LQ 提「只显示一个数分不清是哪个工期」）。
   ⚠️ 徽章与小字都要带前缀提权，否则被 `.tracking-table small` 刷成统一灰。 */
.tracking-table .lead-cell { display: flex; flex-direction: column; gap: 2px; line-height: 1.3; }
.tracking-table .lead-cell > span { display: flex; align-items: baseline; gap: 4px; }
.tracking-table small.lead-kind { color: #475467; background: #f2f4f7; border-radius: 4px; padding: 0 4px; font-size: 11px; font-weight: 600; }
.lead-detail-heading { cursor: help; }
.tracking-table small.lead-others { color: #98a2b3; font-size: 11px; }
.countdown-undated { color: #175cd3; font-weight: 600; }
/* 产品列数量徽标：与 HTML 导出 cell-badge 同款（trackingHtml.ts）。 */
.tracking-product-qty { display: inline-block; margin-left: 6px; border-radius: 999px; background: #eef2f7; color: #334155; border: 1px solid #dbe1ea; font-size: 11px; line-height: 1.45; padding: 0 6px; white-space: nowrap; vertical-align: 1px; }
.pager { display: flex; justify-content: flex-end; }
/* 导出弹窗：预设一行 + 复选列网格（列多时自动折行，避免弹窗被撑高） */
.export-scope { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
.export-format { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: 2px 0 12px; }
.export-presets { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: 4px 0 12px; }
.export-columns { max-height: 46vh; overflow: auto; padding-right: 4px; }
.export-columns :deep(.el-checkbox) { height: 26px; margin-right: 0; }
/* 列按字段来源分三段：组头＝彩色标签 + 一句说明 + 列数；组内仍是原来的网格勾选。 */
.export-column-group { margin-bottom: 10px; }
.export-column-group-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 2px 0 5px; }
.export-column-group-head .muted { font-size: 12px; }
.export-column-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 4px 12px; }
/* 列表列设置弹层：竖向勾选列表（列名短，无需网格） */
.column-setting-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.column-setting-list { display: grid; gap: 2px; }
.column-setting-list :deep(.el-checkbox) { height: 26px; margin-right: 0; }
.column-setting-note { margin: 8px 0 0; color: #667085; font-size: 12px; line-height: 1.5; }
.column-setting-actions { display: flex; align-items: center; gap: 10px; }
/* 管理员区：与「我自己的勾选」隔开，避免误以为存默认只影响自己。 */
.column-setting-global { margin-top: 10px; padding-top: 10px; border-top: 1px solid #e4e7ed; display: grid; gap: 8px; justify-items: start; }
.column-setting-global .column-setting-note { margin: 0; }
.list-stage-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; }
.list-stage-actions :deep(.el-button + .el-button) { margin-left: 0; }
.list-stage-actions :deep(.el-button) { padding: 5px 7px; height: 26px; }
.muted { color: #667085; font-size: 13px; }
.metric-delay { color: #b54708; font-weight: 600; overflow-wrap: anywhere; }
.metric-exception-text { color: #b42318; }
.time-signal-list { display: flex; gap: 4px; flex-wrap: wrap; }
</style>

<style>
/*
 * 详情抽屉 append-to-body，DOM 被传送到组件之外，scoped 样式（含 :deep）够不着标题栏；
 * 因此经 header-class 在标题栏元素上挂了专属 class，用非 scoped 块收紧与内容的间距
 * （Element Plus 默认 margin-bottom 30px，过大）。双类名提高优先级，避免被组件库默认样式覆盖。
 */
.el-drawer__header.tracking-drawer-header { margin-bottom: 14px; }
</style>
