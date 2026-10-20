<template>
  <div class="tracking-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">生产任务</p>
        <h1>生产跟单</h1>
        <p>默认查看需要处理的生产任务；可通过筛选查看全部任务与历史记录。</p>
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
        <el-button v-if="canManageBaseline" @click="openBaseline">基准设置</el-button>
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
      <el-select v-model="deliveryFilter" multiple collapse-tags clearable placeholder="生产交付结果（全部）" aria-label="生产交付结果" @change="search"><el-option v-for="option in DELIVERY_FILTER_OPTIONS" :key="option.value" :label="option.label" :value="option.value" /></el-select>
      <el-select v-model="actionFilter" aria-label="跟单待办" @change="search"><el-option label="全部跟单待办" value="all" /><el-option label="有提醒" value="any" /><el-option v-for="code in TRACKING_ACTION_CODES" :key="code" :label="actionLabel(code)" :value="code" /></el-select>
      <div class="tracking-common-date" aria-label="生产任务单提交时间" title="按生产任务单提交时间筛选"><div class="tracking-extra-date-inputs"><el-date-picker v-model="dateFilters.created.from" type="date" value-format="YYYY-MM-DD" placeholder="提交时间起" aria-label="生产任务单提交起始日期" @change="onCreatedDateChange('from')" /><span>至</span><el-date-picker v-model="dateFilters.created.to" type="date" value-format="YYYY-MM-DD" placeholder="提交时间止" aria-label="生产任务单提交截止日期" @change="onCreatedDateChange('to')" /></div></div>
      <!-- 预计完成时间：后端口径＝车间预计完成日，车间未填时回落有效的业务员要求日。 -->
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
      <!-- 「跟单状态」「业务员」低频，收进更多筛选（10-19 LQ），常用行留给倒计时 / 交付结果 / 待办 / 两段日期。 -->
      <div class="tracking-extra-filter"><el-select v-model="trackingStatus" aria-label="跟单状态" @change="search"><el-option label="全部跟单状态" value="all" /><el-option label="未结束跟单" value="open" /><el-option label="待跟单" value="pending" /><el-option label="跟单中" value="in_progress" /><el-option label="跟单已完成" value="completed" /><el-option label="停止生产跟单" value="stopped" /><el-option-group label="完成方式"><el-option label="人工结案" value="closed" /></el-option-group></el-select></div>
      <div class="tracking-extra-filter"><el-select v-model="salespersonFilter" multiple filterable collapse-tags clearable placeholder="业务员（全部）" aria-label="业务员" @change="search"><el-option v-for="name in salespersonOptions" :key="name" :label="name" :value="name" /></el-select></div>
      <div class="tracking-extra-filter"><el-select v-model="workshop" clearable filterable placeholder="车间（全部）" aria-label="车间" @change="search"><el-option v-for="line in filterOptions.workshops" :key="line" :label="shortWorkshop(line)" :value="line" /></el-select></div>
      <div class="tracking-extra-filter"><el-select v-model="sourceStatus" clearable placeholder="任务单状态（全部）" aria-label="生产任务单状态" @change="search"><el-option v-for="status in filterOptions.sourceStatuses" :key="status" :label="status" :value="status" /></el-select></div>
      <div class="tracking-extra-filter"><el-select v-model="assignee" clearable placeholder="跟单员（全部）" aria-label="跟单员" @change="search"><el-option v-for="person in filterOptions.assignees" :key="person.id" :label="person.name" :value="String(person.id)" /></el-select></div>
      <div class="tracking-extra-filter tracking-extra-filter-check"><el-checkbox v-model="mine" @change="search">我的跟单</el-checkbox></div>
      <div class="tracking-extra-filter"><el-select v-model="optionalStageFilter" clearable placeholder="按需工序（全部）" aria-label="按需工序" @change="search"><el-option label="全部按需工序" value="" /><el-option v-for="option in optionalStageOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></div>
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

    <el-table v-loading="loading" :data="items" row-key="id" class="tracking-table" :style="stickyHeaderStyle" @row-click="openDetail" @selection-change="onTrackingSelectionChange">
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
              @click.stop="stage.status === 'DONE' || row.stoppedAt || !canFollowup ? openDetail(row) : openListCompletion([row], stage.code)">
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
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="openDetail(row)">跟进</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="listCompletionVisible" title="确认节点完成" width="min(700px, 94vw)" append-to-body>
      <p>将以下 {{ listCompletionRows.length }} 单的<strong>{{ listCompletionStageCode ? '所选节点' : '当前未完成节点' }}</strong>登记为已完成。请核对实际完成日期；每单独立写入跟单历史。</p>
      <el-date-picker v-model="listCompletionDate" type="date" value-format="YYYY-MM-DD" placeholder="实际完成日期" />
      <el-input v-if="listCompletionStageCode" v-model="listCompletionNote" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="节点备注（可选；需说明原因时填写）" style="margin-top: 12px" />
      <el-table :data="listCompletionRows" max-height="340">
        <el-table-column prop="orderNo" label="工单编号" min-width="155" />
        <el-table-column prop="productNameSnapshot" label="产品" min-width="170" show-overflow-tooltip />
        <el-table-column label="本次完成节点" min-width="115"><template #default="{ row }">{{ listCompletionTarget(row, listCompletionStageCode)?.label }}</template></el-table-column>
      </el-table>
      <template #footer><el-button :disabled="listCompletionSaving" @click="listCompletionVisible = false">取消</el-button><el-button type="primary" :loading="listCompletionSaving" :disabled="!listCompletionDate" @click="confirmListCompletion">确认登记</el-button></template>
    </el-dialog>
    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[20, 30, 50, 100]" :total="total"
        layout="total, sizes, prev, pager, next" @current-change="() => load(false)" @size-change="onSizeChange" />
    </div>

    <!-- 导出：列可选。导出的每个值都取页面同一套显示函数，保证与列表所见完全一致。 -->
    <el-dialog v-model="exportVisible" title="导出生产跟单" width="min(900px, 94vw)">
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
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" :disabled="!exportColumns.length" @click="doExport(exportFormat)">
          {{ exportFormat === 'html' ? '导出网页' : '导出 Excel' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 导回：把「导出件改完」再导回。只回写跟单员维护的四类字段，其余列只读；空白＝不修改。 -->
    <el-dialog v-model="roundTripDialog" title="导入跟单表（回写）" width="min(1180px, 96vw)">
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
        <el-button @click="roundTripDialog = false">关闭</el-button>
        <el-button type="primary" :loading="roundTripSaving" :disabled="!roundTripPreview || !roundTripPreview.write" @click="confirmRoundTripImport">
          确认回写 {{ roundTripPreview?.write || 0 }} 行
        </el-button>
      </template>
    </el-dialog>

    <!-- 生产任务单导入（跟单员自助）：与氚云导入中心同一套解析/预览/确认，只锁死导入类型。 -->
    <ProductionTaskImportDialog v-model="taskImportVisible" @imported="onProductionTaskImported" />

    <el-dialog v-model="importDialog" title="导入生产跟单进度表" width="min(1180px, 96vw)">
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
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button type="primary" :loading="importSaving" :disabled="!importPreview || importPreview.alreadyUploaded" @click="confirmWorkbookImport">确认导入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="workbookDialog" title="进度表导入记录" width="min(1400px, 96vw)">
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
    </el-dialog>

    <el-dialog v-model="reviewDialog" title="逐项确认表格差异" width="min(680px, 96vw)" append-to-body>
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
        <el-button @click="reviewDialog = false">关闭</el-button>
        <el-button :loading="reviewSaving" @click="ignoreReview">保留本地记录</el-button>
        <el-button type="primary" :disabled="!selectedReviewKeys.length" :loading="reviewSaving" @click="applyReview">应用所选差异</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="candidateDialog" title="关联氚云工单" width="min(1080px, 96vw)">
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
    </el-dialog>

    <el-dialog v-model="archiveRuleDialog" title="归档规则" width="min(820px, 96vw)" append-to-body>
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
    </el-dialog>

    <el-drawer v-model="drawer" :title="detail?.order.orderNo ? `生产跟单 · ${detail.order.orderNo}` : '生产跟单'" size="min(880px, 96vw)" header-class="tracking-drawer-header" append-to-body destroy-on-close>
      <div v-if="detail" :class="['detail-content', { 'detail-stopped': detail.order.stoppedAt }]">
        <section class="summary-block">
          <div class="summary-head">
            <div class="summary-title">
              <strong>{{ detail.order.productNameSnapshot }}</strong>
              <el-tag :type="trackingStatusTone(detail.order.trackingStatus)" effect="light" size="small">{{ trackingStatusText(detail.order.trackingStatus) }}</el-tag>
            </div>
            <div class="summary-spec">
              <span>{{ detail.order.specificationSnapshot || '规格未提供' }}</span>
              <el-tag size="small" type="info" effect="plain">{{ detail.order.plannedQuantity }} {{ detail.order.unit }}</el-tag>
              <el-tag v-if="detail.order.moldLayout?.trim()" size="small" type="info" effect="plain">{{ detail.order.moldLayout }}</el-tag>
            </div>
            <div class="summary-tags">
              <el-tag size="small" type="info" effect="plain">{{ shortWorkshop(detail.order.workshopType) || '车间未提供' }}</el-tag>
              <el-tag size="small" type="info" effect="plain">{{ detail.order.externalSource === 'H3YUN' ? '氚云工单' : '本地工单' }}</el-tag>
              <el-tag size="small" type="info" effect="plain">任务单：{{ detail.order.sourceStatus || '状态未提供' }}</el-tag>
              <el-tag size="small" type="info" effect="plain">跟单责任人：{{ detail.order.assigneeName || '未指定' }}</el-tag>
              <el-tag size="small" type="info" effect="plain">客户：{{ detail.order.customerNameSnapshot || '未提供' }}<template v-if="detail.order.salespersonNameSnapshot"> · {{ detail.order.salespersonNameSnapshot }}</template></el-tag>
              <el-tag v-if="detail.order.riskCategory" size="small" type="warning" effect="plain">风险：{{ riskText(detail.order.riskCategory) }}</el-tag>
              <el-tag v-if="isSourceUrgent(detail.order.sourceOrderRemark)" size="small" type="danger" effect="dark">任务单备注加急</el-tag>
              <span class="muted">最近跟进 {{ formatTime(detail.order.lastFollowupAt) || '暂无' }}</span>
            </div>
          </div>
          <div class="summary-progress">
            <el-progress type="circle" :percentage="stagePercent" :width="72" :stroke-width="7">
              <template #default><span class="progress-num">{{ doneStages }}/{{ requiredTotal }}</span></template>
            </el-progress>
            <span class="muted">节点已完成</span>
          </div>
        </section>

        <el-alert v-if="!detail.order.stoppedAt && isSourceUrgent(detail.order.sourceOrderRemark)" class="urgent-detail-alert" type="error" :closable="false" show-icon title="氚云生产任务单标注加急" :description="detail.order.sourceOrderRemark || ''" />

        <el-alert v-if="detail.order.stoppedAt" type="info" :closable="false" show-icon title="已停止生产跟单"
          :description="`${detail.order.stoppedByName || '跟单员'} · ${formatTime(detail.order.stoppedAt)} · 原因：${detail.order.stoppedReason || '见跟单历史'}。仅本地跟单停止，氚云任务单保持原状。`" />

        <el-alert v-if="!detail.order.stoppedAt && detail.order.closedAt" type="success" :closable="false" show-icon title="跟单已结案（人工确认）"
          :description="`${detail.order.closedByName || '跟单员'} · ${formatTime(detail.order.closedAt)} · 说明：${detail.order.closedNote || '见跟单历史'}。状态按「结案优先」展示为「跟单已完成」，节点未补齐无需补充。`" />

        <el-alert v-if="detail.order.trackingAction" type="warning" :closable="false" show-icon
          :title="`跟单待办 · ${actionLabel(detail.order.trackingAction)}`"
          :description="actionHint(detail.order)" />

        <div class="metric-grid">
          <div :class="['metric', { 'metric-exception': invalidApplicationDate(detail.order) }]"><span>生产工单申请时间</span><strong>{{ detail.order.applicationDate || '未填写' }}</strong><small v-if="detail.order.sourceCreatedAt" class="muted">任务单提交 {{ formatMinute(detail.order.sourceCreatedAt) }}</small><small v-if="invalidApplicationDate(detail.order)" class="metric-exception-text">申请日期早于任务单提交日期，疑似填写错误</small><el-button v-if="invalidApplicationDate(detail.order) && needsTimeReason(detail.order, 'SOURCE_DATE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('SOURCE_DATE')">记录日期核实</el-button><small v-else-if="invalidApplicationDate(detail.order)" class="metric-reviewed">已记录核实：{{ reviewOf(detail.order, 'SOURCE_DATE')?.reason }}</small></div>
          <div :class="['metric', { 'metric-exception': invalidRequiredDate(detail.order) }]"><span>订单要求完成（业务员）</span><strong :class="{ 'is-danger': invalidRequiredDate(detail.order) }">{{ detail.order.dueDate || '未定' }}</strong><small v-if="invalidRequiredDate(detail.order)" class="metric-exception-text">早于申请日期或任务单提交日期，疑似填写错误；请核对源工单日期</small><el-button v-if="invalidRequiredDate(detail.order) && !invalidApplicationDate(detail.order) && needsTimeReason(detail.order, 'SOURCE_DATE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('SOURCE_DATE')">记录日期核实</el-button><small v-else-if="invalidRequiredDate(detail.order) && !invalidApplicationDate(detail.order)" class="metric-reviewed">已记录核实：{{ reviewOf(detail.order, 'SOURCE_DATE')?.reason }}</small></div>
          <div class="metric"><el-tooltip placement="top" raw-content :content="leadDaysTooltipHtml(detail.order)"><span class="lead-detail-heading">工期（当前） ⓘ</span></el-tooltip><strong :class="{ 'is-danger': leadDaysAlarm(detail.order) }">{{ effectiveLeadDaysText(detail.order) }}<small class="lead-kind">{{ leadDaysKindText(detail.order) }}</small></strong><small v-if="otherLeadDaysText(detail.order)" class="muted">{{ otherLeadDaysText(detail.order) }}</small><small v-if="leadDaysDeltaText(detail.order)" :class="leadDeltaClass(detail.order)">{{ leadDaysDeltaText(detail.order) }}</small><small v-if="negativeLeadDays(detail.order)" class="metric-exception-text">{{ negativeLeadReason(detail.order) }}</small></div>
          <div :class="['metric', { 'metric-exception': timeException(detail.order, 'ACCEPTANCE') }]"><span>排产时间（确认是否开工）</span><strong>{{ formatMinute(detail.order.scheduleTime) || '未排产' }}</strong><small v-if="scheduleLagDays(detail.order) != null" class="muted">提交后 {{ scheduleLagDays(detail.order) }} 天排产</small><small v-if="timeException(detail.order, 'ACCEPTANCE')" class="metric-exception-text">{{ timeException(detail.order, 'ACCEPTANCE') }}</small><el-button v-if="needsTimeReason(detail.order, 'ACCEPTANCE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('ACCEPTANCE')">注明接单原因</el-button><small v-else-if="timeException(detail.order, 'ACCEPTANCE')" class="metric-reviewed">已记录原因：{{ reviewOf(detail.order, 'ACCEPTANCE')?.reason }}</small></div>
          <div :class="['metric', { 'metric-exception': timeException(detail.order, 'COMMITMENT') }]"><div class="metric-heading"><span>车间预计完成</span><el-button link type="primary" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openDelayDialog">{{ detail.order.forecastFinishDate ? '修改延期' : '延期' }}</el-button></div><strong>{{ currentCompletionDate(detail.order) || '未定' }}</strong><small class="muted">{{ detail.order.forecastFinishDate ? '原车间预计：' : '' }}{{ expectedCompareText(detail.order) }}</small><small v-if="detail.order.forecastFinishDate" class="metric-delay">{{ forecastAdjustText(detail.order) }}<template v-if="deadlineOf(detail.order)"> · 原预计 {{ deadlineOf(detail.order) }}</template><template v-if="detail.order.delayReason"> · {{ detail.order.delayReason }}</template></small><small v-else-if="detail.order.delayReason" class="muted">原有延期 / 风险说明：{{ detail.order.delayReason }}</small><small v-if="timeException(detail.order, 'COMMITMENT')" class="metric-exception-text">{{ timeException(detail.order, 'COMMITMENT') }}</small><el-button v-if="needsTimeReason(detail.order, 'COMMITMENT')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('COMMITMENT')">注明协商原因</el-button><small v-else-if="timeException(detail.order, 'COMMITMENT')" class="metric-reviewed">已记录原因：{{ reviewOf(detail.order, 'COMMITMENT')?.reason }}</small></div>
          <div :class="['metric', { 'metric-exception': timeException(detail.order, 'OVERDUE') }]"><span>生产交付结果</span><strong :class="{ 'is-danger': detail.order.deliveryOutcome === 'OVERDUE' }">{{ deliveryOutcomeText(detail.order) }}</strong><small v-if="detail.order.deliveryBaseline" class="muted">基准：车间预计完成 {{ detail.order.deliveryBaseline }}</small><small class="muted">按实际交付（跟单入库确认或采购首次入库）对照，仅供进度参考；整单完工看交期分析。</small><el-button v-if="needsTimeReason(detail.order, 'OVERDUE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('OVERDUE')">记录逾期原因</el-button><small v-else-if="timeException(detail.order, 'OVERDUE')" class="metric-reviewed">已记录原因：{{ reviewOf(detail.order, 'OVERDUE')?.reason }}</small></div>
          <div :class="['metric', { 'metric-exception': timeException(detail.order, 'REPORTING') }]"><span>氚云报工完工（仅供对照）</span><strong>{{ detail.order.actualFinishDate || '源工单未填' }}</strong><small v-if="!detail.order.expectedFinishDate" class="muted">车间预计未填，暂无法比较报工日期</small><small v-else-if="!timeException(detail.order, 'REPORTING') && detail.order.actualFinishDate" class="muted">{{ reportCompareText(detail.order) }}</small><small v-if="timeException(detail.order, 'REPORTING')" class="metric-exception-text">{{ timeException(detail.order, 'REPORTING') }}</small><el-button v-if="needsTimeReason(detail.order, 'REPORTING')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('REPORTING')">核实并注明原因</el-button><small v-else-if="timeException(detail.order, 'REPORTING')" class="metric-reviewed">已记录原因：{{ reviewOf(detail.order, 'REPORTING')?.reason }}</small></div>
          <button v-if="detail.order.inboundCount" type="button" :class="['metric', 'metric-link', { 'metric-exception': inboundBeforeReporting(detail.order) }]" :disabled="purchaseLinksLoading" @click="openPurchaseReceipts">
            <span>采购入库时间（仅供对照）</span>
            <strong>{{ detail.order.inboundDate || '尚无采购入库' }}</strong>
            <span v-if="inboundHint(detail.order)" class="muted">{{ inboundHint(detail.order) }}</span>
            <span v-else-if="inboundGapText(detail.order)" class="muted">{{ inboundGapText(detail.order) }}</span>
            <span v-if="inboundBeforeReporting(detail.order)" class="metric-exception-text">{{ inboundReportingHint(detail.order) }}</span>
            <span class="metric-link-hint">{{ purchaseLinksLoading ? '正在查询入库单…' : '点击查看采购入库详情 →' }}</span>
          </button>
          <div v-else class="metric"><span>采购入库时间（仅供对照）</span><strong>尚无采购入库</strong><small v-if="detail.order.actualFinishDate" class="muted">报工后尚未入库，不据此判定未完工</small><el-button v-if="detail.order.actualFinishDate" link type="info" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('WAREHOUSING')">记录暂缓入库安排（可选）</el-button></div>
        </div>
        <section v-if="detail.order.sourceOptionalConfiguration?.trim() || detail.order.sourceOrderRemark?.trim()" class="source-order-remark">
          <header class="section-head"><h3>生产任务单配置与备注</h3><span class="muted">氚云原文 · 与本地跟单备注分开</span></header>
          <p v-if="detail.order.sourceOptionalConfiguration?.trim()"><strong>可选配置：</strong>{{ detail.order.sourceOptionalConfiguration }}</p>
          <p v-if="detail.order.sourceOrderRemark?.trim()"><strong>备注：</strong>{{ detail.order.sourceOrderRemark }}</p>
        </section>
        <section>
          <header class="section-head">
            <h3>节点进度</h3>
            <div class="stage-quick-actions">
              <span class="muted">{{ detail.order.stoppedAt ? '已停止跟单，节点记录只读' : '快捷操作只填写当前节点，核对后点底部“保存跟单”' }}</span>
              <el-button v-if="canFollowup && !detail.order.stoppedAt && currentStage && currentStage.status === 'PENDING'" size="small" @click="quickStartCurrentStage">开始{{ currentStage.label }}</el-button>
              <el-button v-if="canFollowup && !detail.order.stoppedAt && currentStage" size="small" type="primary" plain @click="quickCompleteCurrentStage">今天完成{{ currentStage.label }}</el-button>
            </div>
          </header>
          <el-alert v-if="!detail.order.stoppedAt && stageGapLabels.length" type="warning" :closable="false" show-icon
            :title="`后续节点已完成，前面的 ${stageGapLabels.join('、')} 尚未完成或未核实顺序。`"
            description="确实完成的节点请补完成日期；未按常规顺序经过的节点可逐项记录原因并保存，保存后不再重复提醒。" />
          <div class="stage-list">
            <div class="stage-head" aria-hidden="true">
              <span>节点</span><span>阶段状态</span><span>参考完成</span><span>完成时间</span><span>节点备注</span><span>节点记录</span>
            </div>
            <div v-for="(stage, index) in milestones" :key="stage.code" :class="['stage-item', stage.optional && !stage.applicable ? 'na' : '', { current: index === currentStageIndex }]">
              <div class="stage-grid">
                <span class="cell-node">
                  <span class="row-dot" :class="stage.status.toLowerCase()" />
                  <span class="row-name">{{ stage.label }}<em v-if="stage.optional" class="optional-mark">按需</em></span>
                </span>
                <el-select v-model="stage.status" size="small" class="cell-status" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @change="onStageStatusChange(stage)">
                  <el-option value="PENDING" label="未开始" />
                  <el-option value="IN_PROGRESS" label="进行中" />
                  <el-option value="DONE" label="已完成" />
                </el-select>
                <span v-if="stage.optional && !stage.applicable" class="cell-reference muted">—</span>
                <span v-else :class="['cell-reference', { 'is-overdue': stageOverdue(stage) }]">
                  <template v-if="stage.referenceDate">{{ stage.referenceDate }}<small v-if="stageOverdue(stage)">超 {{ stage.referenceOverdueDays }} 天</small></template>
                  <template v-else><span class="muted">无基准</span></template>
                </span>
                <el-date-picker v-model="stage.completedDate" type="date" value-format="YYYY-MM-DD" size="small" clearable placeholder="完成日期" :disabled="!canFollowup || Boolean(detail.order.stoppedAt) || stage.status !== 'DONE'" class="cell-date" />
                <el-input v-model="stage.note" size="small" maxlength="500" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" :placeholder="stageOverdue(stage) ? '已超参考完成日期，请说明原因' : '节点备注（可选）'" class="cell-note" />
                <div class="cell-history">
                <el-button v-if="canFollowup && !detail.order.stoppedAt && gapStages.some(gap => gap.code === stage.code) && stage.status !== 'DONE'" link type="warning" size="small" @click="recordStageSequenceReason(stage)">核实顺序</el-button>
                  <el-button link type="primary" size="small" :disabled="!stageRecordCount(stage.code)" @click="toggleStage(stage.code)">{{ expandedStages.has(stage.code) ? '收起历史' : `历史(${stageRecordCount(stage.code)})` }}</el-button>
                </div>
              </div>
              <div v-if="expandedStages.has(stage.code) && stageRecordCount(stage.code)" class="row-history">
                <p v-for="(item, i) in stageNodeHistory(stage.code)" :key="i"><span class="muted">第 {{ item.version }} 次 · {{ item.time }} · {{ item.by }}</span>{{ item.text }}</p>
                <p v-if="pendingStageChanges.get(stage.code)" class="pending">本次修改（未保存）：{{ pendingStageChanges.get(stage.code) }}</p>
              </div>
            </div>
          </div>
        </section>

        <ProductionSalesTracePanel v-if="showSalesTrace" :order-id="detail.order.id" :can-confirm="auth.can('manufacturing.followup') && !detail.order.stoppedAt" />

        <section>
          <header class="section-head">
            <h3>时间记录</h3>
            <span class="muted">车间预计完成调整与时间异常核实，按记录时间展示</span>
          </header>
          <el-timeline v-if="timeHistoryItems.length">
            <el-timeline-item v-for="item in timeHistoryItems" :key="item.key" :timestamp="item.time" placement="top">
              <div class="entry">
                <div class="entry-head"><strong>{{ item.title }}</strong><span class="muted">{{ item.by }}</span></div>
                <p v-for="line in item.lines" :key="line" class="time-history-line">{{ line }}</p>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无车间时间调整或时间核实记录" :image-size="52" />
        </section>

        <section>
          <header class="section-head">
            <h3>跟单历史</h3>
            <span class="muted">共 {{ detail.entries.length }} 次 · 保存节点或车间交期变更时留存前后快照</span>
          </header>
          <el-timeline v-if="detail.entries.length">
            <el-timeline-item v-for="entry in detail.entries" :key="entry.id" placement="top">
              <div class="entry">
                <div class="entry-head">
                  <strong>第 {{ entry.version }} 次跟单</strong>
                  <span class="muted">{{ formatTime(entry.createdAt) }} · {{ entry.createdByName }}</span>
                </div>
                <p v-if="entry.content.startsWith('停止生产跟单：') || entry.content.startsWith('恢复生产跟单：') || entry.content.startsWith('跟单结案确认：') || entry.content.startsWith('撤销跟单结案：')" class="entry-content">{{ entry.content }}</p>
                <div v-if="entryDiff(entry.id).length" class="entry-diff">
                  <span v-for="row in visibleDiff(entry.id)" :key="row.label" class="diff-chip">
                    {{ row.label }}：<i>{{ row.from }}</i><b>→</b>{{ row.to }}
                  </span>
                  <el-button v-if="entryDiff(entry.id).length > 4" link type="primary" size="small" @click="toggleEntry(entry.id)">
                    {{ expandedEntries.has(entry.id) ? '收起' : `还有 ${entryDiff(entry.id).length - 4} 项变更` }}
                  </el-button>
                </div>
                <p v-else-if="!entry.content.startsWith('停止生产跟单：') && !entry.content.startsWith('恢复生产跟单：') && !entry.content.startsWith('跟单结案确认：') && !entry.content.startsWith('撤销跟单结案：')" class="entry-content">{{ entry.content }}</p>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无跟单记录，点击上方节点开始第一次跟单" :image-size="52" />
        </section>

        <el-collapse v-model="referencePanels" class="reference-panel">
          <el-collapse-item name="ops">
            <template #title>
              <span class="more-title">氚云零部件制程 <span class="muted">（{{ opDone }}/{{ detail.operations.length }} 已完成 · 仅供对照，不随跟单修改）</span></span>
            </template>
            <el-timeline v-if="detail.operations.length">
              <el-timeline-item v-for="op in detail.operations" :key="op.id" :timestamp="op.completedAt || '未完成'" :type="op.status === 'COMPLETED' ? 'success' : 'primary'">
                {{ op.operationNameSnapshot }} · {{ op.reportedQuantity || 0 }}/{{ op.requiredQuantity || 0 }}
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else description="该任务暂无工序" :image-size="52" />
          </el-collapse-item>
        </el-collapse>
      </div>
      <template #footer>
        <div class="drawer-footer">
          <span :class="unsavedStageCount ? 'pending-change-count' : 'muted'">{{ unsavedStageCount ? `${unsavedStageCount} 个节点有未保存修改` : requiredTotal ? `${doneStages}/${requiredTotal} 节点已完成` : '' }}</span>
          <div>
            <el-button v-if="canFollowup && detail && !detail.order.stoppedAt && detail.order.trackingStatus !== 'completed' && !detail.order.deliveredDate" type="danger" plain @click="openDisposition(true)">标记不再生产</el-button>
            <el-button v-if="canFollowup && detail?.order.stoppedAt" type="primary" plain @click="openDisposition(false)">恢复跟单</el-button>
            <el-button v-if="canFollowup && detail && !detail.order.stoppedAt && !detail.order.closedAt" type="success" plain @click="openClosure(true)">确认结案</el-button>
            <el-button v-if="canFollowup && detail && !detail.order.stoppedAt && detail.order.closedAt" type="primary" plain @click="openClosure(false)">撤销结案</el-button>
            <el-button v-if="unsavedStageCount" @click="resetStageDraft">撤销未保存修改</el-button>
            <el-button @click="drawer = false">关闭</el-button>
            <el-button v-if="canFollowup && detail && !detail.order.stoppedAt" type="primary" :loading="saving" :disabled="!unsavedStageCount" @click="save">保存跟单</el-button>
            <el-button v-if="nextTrackingOrder" type="primary" plain :loading="saving" :disabled="!detail" @click="saveAndNext">{{ unsavedStageCount && !detail?.order.stoppedAt ? '保存并下一单' : '下一单' }}</el-button>
          </div>
        </div>
      </template>
    </el-drawer>
    <el-dialog v-model="dispositionVisible" :title="dispositionStopped ? '标记不再生产' : '恢复生产跟单'" width="min(500px, 94vw)" append-to-body>
      <p class="muted">{{ dispositionStopped ? '仅停止本项目的生产跟单，不修改氚云生产任务单。已有节点和记录会保留，并可日后恢复。' : '恢复后重新进入待跟单或跟单中，原有节点和历史继续保留。' }}</p>
      <el-alert v-if="dispositionStopped && detail?.order.actualFinishDate" type="warning" :closable="false" title="氚云已有报工完工日期，请核实是否属于误报或补录，并在原因中说明。" />
      <el-input v-model="dispositionReason" type="textarea" :rows="4" maxlength="500" show-word-limit :placeholder="dispositionStopped ? '请写明不再生产的具体原因及核实情况（必填）' : '请写明恢复生产的原因（必填）'" />
      <template #footer><el-button @click="dispositionVisible = false">取消</el-button><el-button :type="dispositionStopped ? 'danger' : 'primary'" :loading="dispositionSaving" :disabled="!dispositionReason.trim()" @click="saveDisposition">{{ dispositionStopped ? '确认停止跟单' : '确认恢复跟单' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="closureVisible" :title="closureClosing ? '确认跟单结案' : '撤销跟单结案'" width="min(520px, 94vw)" append-to-body>
      <p class="muted">{{ closureClosing ? '跟单员主动确认结束，状态按「结案优先」展示为「跟单已完成」，节点是否齐全不再强制要求；后续可以撤销结案恢复按节点进度派生。仅本地跟单结案，氚云任务单与节点记录保持原状。' : '撤销后状态按节点进度重新派生：节点齐全仍为「跟单已完成」，缺节点则回退为「跟单中」或「待跟单」。' }}</p>
      <el-alert v-if="closureClosing && !detail?.order.deliveredDate" type="warning" :closable="false" title="该任务尚未登记交付（入库），请在结案说明中写明实际情况与核实结果（不少于 6 字）。" />
      <el-input v-model="closureNote" type="textarea" :rows="4" maxlength="500" show-word-limit :placeholder="closureClosing ? '请写明结案说明，例如：配件任务无需走全部工序；交付已核实；本任务不适用某些节点…（必填）' : '请写明撤销结案的原因（必填）'" />
      <template #footer><el-button @click="closureVisible = false">取消</el-button><el-button :type="closureClosing ? 'success' : 'primary'" :loading="closureSaving" :disabled="!closureNote.trim() || (closureClosing && !detail?.order.deliveredDate && closureNote.trim().length < 6)" @click="saveClosure">{{ closureClosing ? '确认结案' : '确认撤销' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="delayDialog" title="登记延期完成日期" width="min(520px, 94vw)" append-to-body>
      <p class="delay-dialog-note">车间预计完成实现不了时，登记与车间/业务员协商后的延期完成日期。</p>
      <el-form label-position="top">
        <el-form-item label="车间预计完成"><strong>{{ deadlineDate || '未定' }}</strong></el-form-item>
        <el-form-item label="延期至" required><el-date-picker v-model="delayDraftDate" type="date" value-format="YYYY-MM-DD" placeholder="选择协商后的完成日期" style="width: 100%" /></el-form-item>
        <el-form-item label="延期原因与协商结果" required><el-input v-model="delayDraftReason" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="记录无法按原预计完成的原因，以及与车间/业务员协商的结果" /></el-form-item>
      </el-form>
      <p v-if="delayDraftDate && deadlineDate && delayDraftDate > deadlineDate" class="muted">比车间预计完成晚 {{ daysBetween(deadlineDate, delayDraftDate) }} 天</p>
      <template #footer><el-button @click="delayDialog = false">取消</el-button><el-button type="primary" :loading="delaySaving" @click="saveDelay">保存延期</el-button></template>
    </el-dialog>
    <el-dialog v-model="timeReviewVisible" :title="`记录${reviewKindLabel(timeReviewForm.kind)}`" width="min(580px, 94vw)" append-to-body>
      <el-form label-position="top">
        <el-form-item label="核实结果"><el-select v-model="timeReviewForm.status" style="width: 100%"><template v-if="timeReviewForm.kind === 'COMMITMENT'"><el-option label="车间确认可按业务员要求完成" value="ON_TIME" /><el-option label="协商后确定新交期" value="NEGOTIATED" /></template><template v-else><el-option label="已了解原因，仍需跟进" value="OPEN" /><el-option label="已处理 / 已核实完成" value="RESOLVED" /></template></el-select></el-form-item>
        <el-form-item v-if="timeReviewForm.status === 'NEGOTIATED'" label="协商后的交期"><el-date-picker v-model="timeReviewForm.agreedDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="原因及处理记录"><el-input v-model="timeReviewForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="写明实际情况、核实结果或暂缓入库原因" /></el-form-item>
        <el-form-item label="核实对象"><el-input v-model="timeReviewForm.contactName" maxlength="100" placeholder="例如车间负责人、业务员（可选）" /></el-form-item>
        <el-form-item label="下次跟进"><el-date-picker v-model="timeReviewForm.nextFollowupDate" type="date" value-format="YYYY-MM-DD" clearable style="width: 100%" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="timeReviewVisible = false">取消</el-button><el-button type="primary" :loading="timeReviewSaving" @click="saveTimeReview">保存核实记录</el-button></template>
    </el-dialog>
    <el-dialog v-model="purchaseChooserVisible" title="选择采购入库单" width="min(620px, 94vw)" append-to-body>
      <p class="muted">该生产工单关联 {{ purchaseReceipts.length }} 张采购入库单，选择一张查看台账详情。</p>
      <el-table :data="purchaseReceipts" max-height="360" @row-click="openPurchaseDetail">
        <el-table-column prop="sourceNo" label="采购入库单号" min-width="155" />
        <el-table-column prop="purchaseDate" label="入库日期" width="120" />
        <el-table-column prop="warehouseName" label="仓库" min-width="110" />
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" @click.stop="openPurchaseDetail(row)">详情</el-button></template></el-table-column>
      </el-table>
    </el-dialog>
    <el-dialog v-model="baselineVisible" title="节点参考完成基准" width="min(760px, 94vw)" append-to-body>
      <p class="muted baseline-hint">
        参考完成时间＝按「一般情况下这道工序需要几天」逐节点推算，供跟单员判断进度是否偏慢；节点超参考未完成会被要求说明原因。
        基准按车间类别配置，改完立即对全部在制单生效（参考值为派生、不落库，不保留旧判定）。
      </p>
      <div v-loading="baselineLoading" class="baseline-body">
        <section v-for="w in baselineWorkshops" :key="w.workshopKind" class="baseline-group">
          <h4>{{ w.workshopLabel }}</h4>
          <el-table :data="w.stages" size="small" border>
            <el-table-column prop="intervalLabel" label="节点区间" min-width="190" />
            <el-table-column label="参考天数" width="150">
              <template #default="{ row }">
                <el-input-number v-model="row.typicalDays" :min="0" :max="60" :step="0.5" size="small" controls-position="right" placeholder="不设" class="baseline-days" />
              </template>
            </el-table-column>
            <el-table-column label="现有基准来源" min-width="220">
              <template #default="{ row }">
                <span class="muted">{{ row.baselineNote || '未配置' }}<template v-if="row.sampleCount">（样本 {{ row.sampleCount }} 单）</template></span>
                <el-tag v-if="row.optionalStage" size="small" type="info" effect="plain" class="baseline-tag">按需工序</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>
        <p v-if="!baselineLoading && !baselineWorkshops.length" class="muted">未获取到基准配置。</p>
      </div>
      <template #footer>
        <el-button @click="baselineVisible = false">取消</el-button>
        <el-button type="primary" :loading="baselineSaving" @click="saveBaseline">保存</el-button>
      </template>
    </el-dialog>
    <TrackingSourceDetailDrawer v-model="purchaseDetailVisible" kind="purchase" :loading="purchaseDetailLoading" :data="purchaseDetailData" :error="purchaseDetailError" />
    <ProductionTrackingAnalyticsDrawer v-model="analysisVisible" @open-order="openFromAnalysis" />
    <ProductionTrackingWeeklyReviewDrawer v-model="weeklyReviewVisible" @open-order="openFromAnalysis" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchTracking } from '@/api/productionTracking'
import type { TrackingOrder } from '@/api/productionTracking'
import TrackingSourceDetailDrawer from '@/components/production/TrackingSourceDetailDrawer.vue'
import ProductionSalesTracePanel from '@/components/production/ProductionSalesTracePanel.vue'
import ProductionTaskImportDialog from '@/components/production/ProductionTaskImportDialog.vue'
import ProductionTrackingAnalyticsDrawer from './ProductionTrackingAnalyticsDrawer.vue'
import ProductionTrackingWeeklyReviewDrawer from './ProductionTrackingWeeklyReviewDrawer.vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { isSourceUrgent } from '@/utils/trackingUrgency'
import { TRACKING_ACTION_CODES, trackingActionHint as actionHint, trackingActionLabel as actionLabel, trackingActionTone as actionTone } from '@/utils/trackingAction'
// 源工单日期判据与「业务员要求工期」的口径唯一来源（列表 / 详情 / 导出共用，见该模块头注释）。
import { daysBetween, effectiveLeadDaysText, invalidApplicationDate, invalidRequiredDate, leadDaysAlarm, leadDaysBreakdown, leadDaysDeltaText, leadDaysDeltaTone, leadDaysKindText, negativeLeadDays, negativeLeadReason, otherLeadDaysText } from '@/utils/trackingDateGuard'
// 页面的纯业务规则（节点 / 日期组合取数 / 时间核实 / 交付结果）不在这里写第二份，见该模块头注释。
import { canQuickComplete, countdownTone, currentCompletionDate, deadlineOf, deliveryOutcomeText, expectedCompareText, forecastAdjustText, formatMinute, formatTime, inboundBeforeReporting, inboundGapText, inboundHint, inboundReportingHint, listCompletionTarget, listStages, needsTimeReason, productSize, productionCountdown, reportCompareText, reviewKindLabel, reviewOf, riskText, scheduleLagDays, shortDate, shortOrderNo, shortWorkshop, stageOverdue, stageStatusText, timeException, timeSignals, trackingStatusText, trackingStatusTone } from '@/utils/tracking/rules'
import { todayKey } from '@/utils/tracking/today'
import { useTrackingFilters } from '@/composables/tracking/useTrackingFilters'
import { useTrackingExport } from '@/composables/tracking/useTrackingExport'
import { useTrackingListColumns } from '@/composables/tracking/useTrackingListColumns'
import { useTrackingWorkbook } from '@/composables/tracking/useTrackingWorkbook'
import { useTrackingBaseline } from '@/composables/tracking/useTrackingBaseline'
import { useTrackingRoundTrip } from '@/composables/tracking/useTrackingRoundTrip'
import { useTrackingDetail } from '@/composables/tracking/useTrackingDetail'
import { useTrackingListCompletion } from '@/composables/tracking/useTrackingListCompletion'

/**
 * 筛选条件（关键词 / 车间 / 跟单状态 / 交付结果 / 提交时间 …）的状态与全部派生都收在 useTrackingFilters 里，
 * 解构出来的名字与模板一致；改筛选后做什么由 onChange＝search 决定（本页只负责分页与请求）。
 */
const {
  keyword, advancedFiltersVisible, dateFilters, countdownFilter, deliveryFilter, optionalStageFilter,
  salespersonFilter, workshop, sourceStatus, assignee, trackingStatus, timeFilter, actionFilter, urgentOnly, mine,
  filterOptions, COUNTDOWN_FILTER_OPTIONS, DELIVERY_FILTER_OPTIONS, optionalStageOptions,
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
watch(() => route.query.analysis, value => { if (value === '1') analysisVisible.value = true }, { immediate: true })
function openFromAnalysis(id: number) { void openDetail({ id } as TrackingOrder) }
const auth = useCurrentUser()
/**
 * 节点参考基准天数的配置收在 useTrackingBaseline 里（权限判定也随模块走，页面只注入 auth.can）。
 */
const {
  canManageBaseline, baselineVisible, baselineLoading, baselineSaving, baselineWorkshops, openBaseline, saveBaseline
} = useTrackingBaseline({ canManage: () => auth.can('manufacturing.baseline.manage'), reload: () => load() })
const page = ref(1)
/** 每页条数：由分页器「条/页」下拉选择（后端 list 接受 1~100）。 */
const size = ref(30)
const total = ref(0)
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
 * 详情抽屉（打开 / 保存跟进 / 延期 / 处置 / 时间核实 / 采购入库 / 历史对照）收在 useTrackingDetail 里：
 * 抽屉里改的是节点副本、originalStages 存快照、保存时才提交；页面只保留模板绑定。
 */
const {
  drawer, nextTrackingOrder, purchaseLinksLoading, purchaseReceipts, purchaseChooserVisible, purchaseDetailVisible,
  purchaseDetailLoading, purchaseDetailData, purchaseDetailError,
  detail, milestones, requiredTotal, doneStages, showSalesTrace, gapStages, stageGapLabels,
  stagePercent, currentStageIndex, currentStage, referencePanels, expandedEntries,
  timeReviewVisible, timeReviewSaving, timeReviewForm, deadlineDate, opDone, saving,
  dispositionVisible, dispositionStopped, dispositionReason, dispositionSaving,
  closureVisible, closureClosing, closureNote, closureSaving,
  delayDialog, delaySaving, delayDraftDate, delayDraftReason, unsavedStageCount,
  entryDiff, visibleDiff, toggleEntry, timeHistoryItems, stageNodeHistory,
  expandedStages, toggleStage, pendingStageChanges, stageRecordCount,
  openDetail, openPurchaseReceipts, openPurchaseDetail, quickStartCurrentStage,
  quickCompleteCurrentStage, resetStageDraft, recordStageSequenceReason, openDelayDialog, saveDelay, save, saveAndNext,
  openTimeReview, saveTimeReview, openDisposition, saveDisposition, openClosure, saveClosure, onStageStatusChange
} = useTrackingDetail({ items: () => items.value, reload: () => load() })
/**
 * 【日期语义 · 唯一说明】三个日期是三种性质，禁止互相顶替：车间预计完成 expectedFinishDate（源数据，
 * 本页只读）/ 订单要求完成 dueDate（业务员提出）/ 延期至 forecastFinishDate（协商后的新完成日）。
 * 它们共同构成的「截止基准 / 当前承诺日」口径已下沉到 `@/utils/tracking/rules`：
 * deadlineOf / currentCompletionDate / validRequiredDate 即唯一实现，页面只调用，不再自留一份 COALESCE。
 */
/** 偏差小字的配色：晚=红、早=绿、一致=中性灰。 */
function leadDeltaClass(row: TrackingOrder) {
  const tone = leadDaysDeltaTone(row)
  return tone === 'late' ? 'lead-delta-late' : tone === 'early' ? 'lead-delta-early' : ''
}
/**
 * 工期列主数值的配色：负数 / 「日期待核实」=红（日期本身可疑，必须看得见）；
 * 否则比要求晚=红、比要求早=绿、与要求一致=不着色。
 */
function leadDaysCellClass(row: TrackingOrder) {
  if (leadDaysAlarm(row)) return 'is-danger'
  const tone = leadDaysDeltaTone(row)
  return tone === 'late' ? 'lead-delta-late' : tone === 'early' ? 'lead-delta-early' : ''
}
/**
 * 工期列悬停提示里的**原因**部分；返回空串＝没有需要解释的事。
 * 三真身起算日不同（要求从申请日、车间/实际从任务单提交日），起算口径不在这里复述——
 * 由明细行自带起止日期说清，避免同一件事讲两遍。
 */
function leadDaysTooltip(row: TrackingOrder) {
  const notes: string[] = []
  if (negativeLeadDays(row)) notes.push(negativeLeadReason(row))
  if (invalidApplicationDate(row)) notes.push('申请日期早于任务单提交日期，要求工期仍按申请日起算，天数偏长')
  if (invalidRequiredDate(row)) notes.push('业务员要求日期早于申请/提交日期，要求工期无法计算')
  else {
    const delta = leadDaysDeltaText(row)
    if (delta && delta !== '与要求一致') notes.push(delta)
  }
  return notes.join('；')
}
/**
 * 工期列的悬停明细（10-15 LQ 提：列里只显示一个数，「分不清到底是哪个工期」）。
 * 先列出这条工单实际具备的几个真身（**各带起算日与终点日**——三个真身起算日不同，
 * 不写出来就会被当成同一个起算日去比大小），当前取用的那个打「当前」，再挂原因说明。
 *
 * ⚠️ 用 el-tooltip 的 `raw-content` 才能换行 —— 内容全部由本页用日期与数字拼出，
 * 不含任何用户输入，不做转义是安全的；不要往里塞备注类的自由文本。
 */
function leadDaysTooltipHtml(row: TrackingOrder) {
  const lines = leadDaysBreakdown(row).map(item => {
    const range = item.start && item.date ? `（${shortDate(item.start)} → ${shortDate(item.date)}）` : ''
    const delay = item.delayed ? '，含延期' : ''
    return `${item.current ? '<b>当前</b> ' : ''}${item.label} ${item.text}${range}${delay}`
  })
  const note = leadDaysTooltip(row)
  if (note) lines.push(note)
  return lines.join('<br/>')
}
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
async function load(refreshOverview = true) {
  const requestId = ++latestListRequest
  loading.value = true
  try {
    const result = await fetchTracking({ ...trackingFilters(), page: page.value, size: size.value })
    if (requestId !== latestListRequest) return
    selectedTrackingRows.value = []
    items.value = result.items
    total.value = result.total
    visibility.value = result.visibility || 'ALL'
    if (refreshOverview) {
      if (visibility.value === 'ALL') await loadWorkbookSummary()
      else workbookActionable.value = 0
    }
    await loadFilterOptions(refreshOverview)
  } catch (error) { if (requestId === latestListRequest) ElMessage.error(error instanceof Error ? error.message : '加载跟单任务失败') }
  finally { if (requestId === latestListRequest) loading.value = false }
}
function search() { page.value = 1; void load(false) }
function refreshTracking() { void load(true) }
/** 切换「条/页」后回到第一页，避免停留在越界页码出现空白列表。 */
function onSizeChange() { page.value = 1; void load(false) }
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
.baseline-hint { margin: 0 0 12px; line-height: 1.6; }
.baseline-body { min-height: 120px; }
.baseline-group { margin-bottom: 16px; }
.baseline-group:last-of-type { margin-bottom: 0; }
.baseline-group h4 { margin: 0 0 8px; font-size: 13px; font-weight: 600; }
.baseline-days { width: 130px; }
.baseline-tag { margin-left: 6px; }
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
.tracking-table small.lead-delta-late,
.metric small.lead-delta-late { color: #b42318; font-weight: 600; }
.tracking-table small.lead-delta-early,
.metric small.lead-delta-early { color: #067647; font-weight: 600; }
/* 工期列主数值直接着色（小字已撤，颜色+悬停提示，2026-10-15 LQ 定）。 */
.tracking-table span.lead-delta-late { color: #b42318; font-weight: 600; }
.tracking-table span.lead-delta-early { color: #067647; font-weight: 600; }
/* 表格内异常日期 / 待核实数值的红（申请时间、要求完成、工期、倒计时逾期共用）。 */
.tracking-table .is-danger { color: #d92d20; font-weight: 600; }
/* 工期列两行：主数值 + 真身徽章 / 其余真身紧凑小字（LQ 提「只显示一个数分不清是哪个工期」）。
   ⚠️ 徽章与小字都要带前缀提权，否则被 `.tracking-table small` 刷成统一灰。 */
.tracking-table .lead-cell { display: flex; flex-direction: column; gap: 2px; line-height: 1.3; }
.tracking-table .lead-cell > span { display: flex; align-items: baseline; gap: 4px; }
.tracking-table small.lead-kind,
.metric small.lead-kind { color: #475467; background: #f2f4f7; border-radius: 4px; padding: 0 4px; font-size: 11px; font-weight: 600; }
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
.detail-content { display: grid; gap: 18px; padding-bottom: 8px; }
.detail-content section { border-top: 1px solid #e5e7eb; padding-top: 14px; }
.detail-content h3 { margin: 0; font-size: 15px; }
.muted { color: #667085; font-size: 13px; }
.section-head { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.stage-quick-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pending-change-count { color: #b54708; font-weight: 600; }
.summary-block { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; border-top: none !important; padding-top: 0 !important; }
.summary-title { display: flex; align-items: center; gap: 8px; }
.summary-title strong { font-size: 17px; }
.summary-spec { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 6px 0 8px; }
.summary-tags { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.summary-progress { display: grid; justify-items: center; gap: 4px; }
.progress-num { font-size: 13px; font-weight: 700; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.metric { background: #f9fafb; border: 1px solid #eef0f3; border-radius: 8px; padding: 9px 12px; display: grid; gap: 3px; min-width: 0; }
.metric span { color: #667085; font-size: 12px; }
.metric-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.metric-heading .el-button { margin: -5px -6px -5px 0; }
.metric-delay { color: #b54708; font-weight: 600; overflow-wrap: anywhere; }
.metric strong { font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.metric strong.is-danger { color: #d92d20; }
.metric-link { width: 100%; text-align: left; font: inherit; cursor: pointer; }
.metric-link:hover, .metric-link:focus-visible { border-color: #409eff; background: #f0f7ff; }
.metric-link:focus-visible { outline: 2px solid #409eff; outline-offset: 2px; }
.metric-link:disabled { cursor: wait; }
.metric-link .metric-link-hint { color: #1677c8; }
.metric small { font-size: 12px; line-height: 1.5; }
.metric .el-button { justify-self: start; margin-left: -4px; }
.detail-stopped .metric .el-button { display: none; }
.metric.metric-exception { border-color: #fda29b; background: #fff6f5; }
.metric.metric-exception strong, .metric-exception-text { color: #b42318; }
.metric-reviewed { color: #667085; }
.time-signal-list { display: flex; gap: 4px; flex-wrap: wrap; }
.delay-dialog-note { margin: 0 0 16px; color: #667085; line-height: 1.6; }
/* 节点进度：表格式行内编辑——节点 | 阶段状态 | 完成时间 | 节点备注 | 节点记录。 */
.stage-list {
  --stage-cols: minmax(140px, 1fr) 112px 108px 132px minmax(200px, 1.6fr) auto;
  display: grid; gap: 8px;
}
.stage-head { display: grid; grid-template-columns: var(--stage-cols); gap: 12px; align-items: center; padding: 0 14px; font-size: 12px; font-weight: 600; color: #667085; }
.stage-item { display: grid; gap: 6px; background: #fff; border: 1px solid #e5e7eb; border-radius: 9px; padding: 8px 14px; }
.stage-item.current { box-shadow: 0 0 0 2px rgba(15, 118, 110, .18); border-color: var(--color-primary, #0f766e); }
.stage-grid { display: grid; grid-template-columns: var(--stage-cols); gap: 12px; align-items: center; }
.cell-node { display: flex; align-items: center; gap: 8px; min-width: 0; }
.row-dot { width: 10px; height: 10px; border-radius: 50%; background: #d0d5dd; flex: none; }
.row-dot.in_progress { background: #f59e0b; }
.row-dot.done { background: #12b76a; }
.row-name { font-size: 13px; font-weight: 600; color: #101828; overflow-wrap: anywhere; }
.cell-status { width: 112px; }
.cell-date { width: 132px; }
/* 参考完成：给跟单员的「一般情况该何时完成」参考；超参考未完成标红，保存时后端会要求备注说明原因。 */
.cell-reference { font-size: 12px; color: #475467; display: flex; flex-direction: column; line-height: 1.35; }
.cell-reference small { color: #d92d20; font-weight: 600; }
.cell-reference.is-overdue { color: #d92d20; font-weight: 600; }
.cell-history { justify-self: end; }
/* 按需工序（氧化/喷特氟龙）本单不适用：虚线灰边，明确「不是没做完」。 */
.stage-item.na { border-style: dashed; background: #fcfcfd; }
.stage-item.na .row-dot { background: transparent; box-shadow: inset 0 0 0 1px #d0d5dd; }
.stage-item.na .row-name { color: #98a2b3; }
/* 节点变更记录：点「历史」展开，贴在节点行下方，左竖线对齐。 */
.row-history { margin-left: 5px; padding-left: 14px; border-left: 2px solid #e5e7eb; display: grid; gap: 3px; }
.row-history p { margin: 0; font-size: 12px; color: #475467; line-height: 1.5; overflow-wrap: anywhere; }
.row-history .muted { font-size: 12px; margin-right: 6px; }
/* 本次编辑会话尚未保存的节点修改：实时预览，保存后转为正式历史。 */
.row-history .pending { color: #b54708; background: #fffaeb; border-radius: 6px; padding: 4px 8px; }
.optional-mark { font-style: normal; margin-left: 6px; padding: 1px 5px; border-radius: 4px; background: #f2f4f7; color: #667085; font-size: 11px; font-weight: 600; }
.reference-panel { border-top: none; }
.more-title { font-size: 13px; font-weight: 600; color: #344054; }
.reference-panel :deep(.el-collapse-item__header) { height: auto; min-height: 40px; line-height: 1.5; font-size: 13px; }
.reference-panel { margin-top: 4px; }
.entry { display: grid; gap: 6px; }
.entry-head { display: flex; align-items: baseline; gap: 10px; }
.entry-head strong { font-size: 13px; }
.entry-content { margin: 0; white-space: pre-wrap; }
.entry-diff { display: flex; flex-wrap: wrap; gap: 6px; }
.diff-chip { background: #f2f4f7; border-radius: 6px; padding: 3px 8px; font-size: 12px; color: #344054; }
.diff-chip i { font-style: normal; color: #98a2b3; text-decoration: line-through; }
.diff-chip b { margin: 0 5px; color: #98a2b3; font-weight: 400; }
.drawer-footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
/* 窄屏时五列放不下：退化为纵向堆叠，隐藏表头。 */
@media (max-width: 720px) {
  .stage-head { display: none; }
  .stage-list { --stage-cols: 1fr; }
  .cell-history { justify-self: start; }
}
@media (max-width: 760px) { .tracking-page { padding: 14px; } .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .summary-block { flex-direction: column; } .summary-progress { justify-items: flex-start; } }
</style>

<style>
/*
 * 详情抽屉 append-to-body，DOM 被传送到组件之外，scoped 样式（含 :deep）够不着标题栏；
 * 因此经 header-class 在标题栏元素上挂了专属 class，用非 scoped 块收紧与内容的间距
 * （Element Plus 默认 margin-bottom 30px，过大）。双类名提高优先级，避免被组件库默认样式覆盖。
 */
.el-drawer__header.tracking-drawer-header { margin-bottom: 14px; }
</style>
