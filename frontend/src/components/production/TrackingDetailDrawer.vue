<template>
  <div class="tracking-detail-host">
    <el-drawer v-model="drawer" :title="detail?.order.orderNo ? `生产跟单 · ${detail.order.orderNo}` : '生产跟单'" size="min(1120px, 98vw)" header-class="tracking-drawer-header" append-to-body destroy-on-close>
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
              <span>{{ shortWorkshop(detail.order.workshopType) || '车间未提供' }}</span>
              <span>跟单 {{ detail.order.assigneeName || '未指定' }}</span>
              <span>客户 {{ detail.order.customerNameSnapshot || '未提供' }}</span>
              <span v-if="detail.order.riskCategory">{{ riskText(detail.order.riskCategory) }}</span>
              <span class="summary-last-followup">最近跟进 {{ formatTime(detail.order.lastFollowupAt) || '暂无' }}</span>
            </div>
          </div>
          <div class="summary-progress">
            <el-progress type="circle" :percentage="stagePercent" :width="72" :stroke-width="7">
              <template #default><span class="progress-num">{{ doneStages }}/{{ requiredTotal }}</span></template>
            </el-progress>
            <span class="muted">节点已完成</span>
          </div>
        </section>

        <div v-if="!detail.order.stoppedAt && (isSourceUrgent(detail.order.sourceOrderRemark) || detail.order.trackingAction)" class="attention-list">
          <div v-if="isSourceUrgent(detail.order.sourceOrderRemark)" class="attention-item attention-item--urgent"><strong>加急任务</strong><span>任务单备注已标注加急，请优先核实当前生产进度。</span></div>
          <div v-if="detail.order.trackingAction" class="attention-item"><strong>{{ actionLabel(detail.order.trackingAction) }}</strong><span>{{ actionHint(detail.order) }}</span></div>
        </div>

        <el-alert v-if="detail.order.stoppedAt" type="info" :closable="false" show-icon title="已停止生产跟单"
          :description="`${detail.order.stoppedByName || '跟单员'} · ${formatTime(detail.order.stoppedAt)} · 原因：${detail.order.stoppedReason || '见跟单历史'}。仅本地跟单停止，氚云任务单保持原状。`" />

        <el-alert v-if="!detail.order.stoppedAt && detail.order.closedAt" type="success" :closable="false" show-icon title="跟单已结案（人工确认）"
          :description="`${detail.order.closedByName || '跟单员'} · ${formatTime(detail.order.closedAt)} · 说明：${detail.order.closedNote || '见跟单历史'}。状态按「结案优先」展示为「跟单已完成」，节点未补齐无需补充。`" />

        <section class="key-dates">
          <header class="section-head"><h3>交期与开工</h3><el-button link type="primary" size="small" @click="showSecondaryMetrics = !showSecondaryMetrics">{{ showSecondaryMetrics ? '收起时间明细' : '展开时间明细与核实' }}</el-button></header>
          <div class="key-dates-grid">
            <div><span>业务员要求</span><strong>{{ detail.order.dueDate || '未定' }}</strong></div>
            <div><span>排产开工</span><strong>{{ detail.order.scheduleTime ? formatMinute(detail.order.scheduleTime) : '未排产' }}</strong><small v-if="!detail.order.scheduleTime && detail.schedule.latestStartDate">建议最晚 {{ detail.schedule.latestStartDate }}</small></div>
            <div><span>当前车间交期</span><strong>{{ detail.schedule.deadline || '未定' }}</strong><el-button v-if="canFollowup && !detail.order.stoppedAt" link type="primary" size="small" @click="openDelayDialog">{{ detail.order.forecastFinishDate ? '修改延期' : '登记延期' }}</el-button></div>
          </div>
        </section>
        <div v-if="showSecondaryMetrics" class="metric-grid show-secondary">
          <div :class="['metric', { 'metric-exception': invalidApplicationDate(detail.order) }]"><span>生产工单申请时间</span><strong>{{ detail.order.applicationDate || '未填写' }}</strong><small v-if="detail.order.sourceCreatedAt" class="muted">任务单提交 {{ formatMinute(detail.order.sourceCreatedAt) }}</small><small v-if="invalidApplicationDate(detail.order)" class="metric-exception-text">申请日期早于任务单提交日期，疑似填写错误</small><el-button v-if="invalidApplicationDate(detail.order) && needsTimeReason(detail.order, 'SOURCE_DATE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('SOURCE_DATE')">记录日期核实</el-button><span v-else-if="invalidApplicationDate(detail.order)" class="metric-reviewed">已记录核实：{{ reviewOf(detail.order, 'SOURCE_DATE')?.reason }}<el-button v-if="reviewOf(detail.order, 'SOURCE_DATE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('SOURCE_DATE', reviewOf(detail.order, 'SOURCE_DATE'))">修改</el-button></span></div>
          <div :class="['metric', { 'metric-exception': invalidRequiredDate(detail.order) }]"><span>订单要求完成（业务员）</span><strong :class="{ 'is-danger': invalidRequiredDate(detail.order) }">{{ detail.order.dueDate || '未定' }}</strong><small v-if="invalidRequiredDate(detail.order)" class="metric-exception-text">早于申请日期或任务单提交日期，疑似填写错误；请核对源工单日期</small><el-button v-if="invalidRequiredDate(detail.order) && !invalidApplicationDate(detail.order) && needsTimeReason(detail.order, 'SOURCE_DATE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('SOURCE_DATE')">记录日期核实</el-button><span v-else-if="invalidRequiredDate(detail.order) && !invalidApplicationDate(detail.order)" class="metric-reviewed">已记录核实：{{ reviewOf(detail.order, 'SOURCE_DATE')?.reason }}<el-button v-if="reviewOf(detail.order, 'SOURCE_DATE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('SOURCE_DATE', reviewOf(detail.order, 'SOURCE_DATE'))">修改</el-button></span></div>
          <div class="metric"><el-tooltip placement="top" raw-content :content="leadDaysTooltipHtml(detail.order)"><span class="lead-detail-heading">工期（当前） ⓘ</span></el-tooltip><strong :class="{ 'is-danger': leadDaysAlarm(detail.order) }">{{ effectiveLeadDaysText(detail.order) }}<small class="lead-kind">{{ leadDaysKindText(detail.order) }}</small></strong><small v-if="otherLeadDaysText(detail.order)" class="muted">{{ otherLeadDaysText(detail.order) }}</small><small v-if="leadDaysDeltaText(detail.order)" :class="leadDeltaClass(detail.order)">{{ leadDaysDeltaText(detail.order) }}</small><small v-if="negativeLeadDays(detail.order)" class="metric-exception-text">{{ negativeLeadReason(detail.order) }}</small></div>
          <div :class="['metric', { 'metric-exception': timeException(detail.order, 'ACCEPTANCE') }]"><span>排产时间（确认是否开工）</span><strong>{{ formatMinute(detail.order.scheduleTime) || '未排产' }}</strong><small v-if="scheduleLagDays(detail.order) != null" class="muted">提交后 {{ scheduleLagDays(detail.order) }} 天排产</small><small class="muted">计划/实际工期自排产日起算</small><small v-if="!detail.order.scheduleTime && detail.schedule.latestStartDate" :class="detail.schedule.risk === 'SCHEDULE_LATE' ? 'metric-exception-text' : 'muted'">建议最晚排产：{{ detail.schedule.latestStartDate }}{{ detail.schedule.risk === 'SCHEDULE_LATE' ? ' · 已滞后' : detail.schedule.risk === 'SCHEDULE_DUE' ? ' · 请尽快排产' : '' }}</small><small v-else-if="!detail.order.scheduleTime" class="muted">产品节点工期或车间交期不完整，暂无法倒排</small><small v-if="timeException(detail.order, 'ACCEPTANCE')" class="metric-exception-text">{{ timeException(detail.order, 'ACCEPTANCE') }}</small><el-button v-if="needsTimeReason(detail.order, 'ACCEPTANCE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('ACCEPTANCE')">注明接单原因</el-button><span v-else-if="timeException(detail.order, 'ACCEPTANCE')" class="metric-reviewed">已记录原因：{{ reviewOf(detail.order, 'ACCEPTANCE')?.reason }}<el-button v-if="reviewOf(detail.order, 'ACCEPTANCE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('ACCEPTANCE', reviewOf(detail.order, 'ACCEPTANCE'))">修改</el-button></span></div>
          <div :class="['metric', { 'metric-exception': timeException(detail.order, 'COMMITMENT') }]"><div class="metric-heading"><span>车间预计完成</span><el-button link type="primary" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openDelayDialog">{{ detail.order.forecastFinishDate ? '修改延期' : '延期' }}</el-button></div><strong>{{ currentCompletionDate(detail.order) || '未定' }}</strong><small class="muted">{{ detail.order.forecastFinishDate ? '原车间预计：' : '' }}{{ expectedCompareText(detail.order) }}</small><small v-if="detail.order.forecastFinishDate" class="metric-delay">{{ forecastAdjustText(detail.order) }}<template v-if="deadlineOf(detail.order)"> · 原预计 {{ deadlineOf(detail.order) }}</template><template v-if="detail.order.delayReason"> · {{ detail.order.delayReason }}</template></small><small v-else-if="detail.order.delayReason" class="muted">原有延期 / 风险说明：{{ detail.order.delayReason }}</small><small v-if="timeException(detail.order, 'COMMITMENT')" class="metric-exception-text">{{ timeException(detail.order, 'COMMITMENT') }}</small><el-button v-if="needsTimeReason(detail.order, 'COMMITMENT')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('COMMITMENT')">注明协商原因</el-button><span v-else-if="timeException(detail.order, 'COMMITMENT')" class="metric-reviewed">已记录原因：{{ reviewOf(detail.order, 'COMMITMENT')?.reason }}<el-button v-if="reviewOf(detail.order, 'COMMITMENT')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('COMMITMENT', reviewOf(detail.order, 'COMMITMENT'))">修改</el-button></span></div>
          <div :class="['metric', { 'metric-exception': timeException(detail.order, 'OVERDUE') }]"><span>生产交付结果</span><strong :class="{ 'is-danger': detail.order.deliveryOutcome === 'OVERDUE' }">{{ deliveryOutcomeText(detail.order) }}</strong><small v-if="detail.order.deliveryBaseline" class="muted">基准：车间预计完成 {{ detail.order.deliveryBaseline }}</small><el-button v-if="needsTimeReason(detail.order, 'OVERDUE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('OVERDUE')">记录逾期原因</el-button><span v-else-if="timeException(detail.order, 'OVERDUE')" class="metric-reviewed">已记录原因：{{ reviewOf(detail.order, 'OVERDUE')?.reason }}<el-button v-if="reviewOf(detail.order, 'OVERDUE')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('OVERDUE', reviewOf(detail.order, 'OVERDUE'))">修改</el-button></span></div>          <div :class="['metric', { 'metric-exception': timeException(detail.order, 'REPORTING') }]"><span>氚云报工完工（仅供对照）</span><strong>{{ detail.order.actualFinishDate || '源工单未填' }}</strong><small v-if="!detail.order.expectedFinishDate" class="muted">车间预计未填，暂无法比较报工日期</small><small v-else-if="!timeException(detail.order, 'REPORTING') && detail.order.actualFinishDate" class="muted">{{ reportCompareText(detail.order) }}</small><small v-if="timeException(detail.order, 'REPORTING')" class="metric-exception-text">{{ timeException(detail.order, 'REPORTING') }}</small><el-button v-if="needsTimeReason(detail.order, 'REPORTING')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('REPORTING')">核实并注明原因</el-button><span v-else-if="timeException(detail.order, 'REPORTING')" class="metric-reviewed">已记录原因：{{ reviewOf(detail.order, 'REPORTING')?.reason }}<el-button v-if="reviewOf(detail.order, 'REPORTING')" link type="danger" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('REPORTING', reviewOf(detail.order, 'REPORTING'))">修改</el-button></span></div>
          <button v-if="detail.order.inboundCount" type="button" :class="['metric', 'metric-link', { 'metric-exception': inboundBeforeReporting(detail.order) }]" :disabled="purchaseLinksLoading" @click="openPurchaseReceipts">
            <span>采购入库时间（仅供对照）</span>
            <strong>{{ detail.order.inboundDate || '尚无采购入库' }}</strong>
            <span v-if="inboundHint(detail.order)" class="muted">{{ inboundHint(detail.order) }}</span>
            <span v-else-if="inboundGapText(detail.order)" class="muted">{{ inboundGapText(detail.order) }}</span>
            <span v-if="inboundBeforeReporting(detail.order)" class="metric-exception-text">{{ inboundReportingHint(detail.order) }}</span>
            <span class="metric-link-hint">{{ purchaseLinksLoading ? '正在查询入库单…' : '点击查看采购入库详情 →' }}</span>
          </button>
          <div v-else class="metric"><span>采购入库时间（仅供对照）</span><strong>尚无采购入库</strong><small v-if="detail.order.actualFinishDate" class="muted">报工后尚未入库，不据此判定未完工</small><el-button v-if="detail.order.actualFinishDate" link type="info" size="small" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" @click="openTimeReview('WAREHOUSING', reviewOf(detail.order, 'WAREHOUSING'))">{{ reviewOf(detail.order, 'WAREHOUSING') ? '修改暂缓入库安排' : '记录暂缓入库安排（可选）' }}</el-button></div>
        </div>
        <el-collapse v-if="detail.order.sourceOptionalConfiguration?.trim() || detail.order.sourceOrderRemark?.trim()" class="source-order-remark">
          <el-collapse-item title="任务单配置与原始备注" name="source">
            <p v-if="detail.order.sourceOptionalConfiguration?.trim()"><strong>可选配置：</strong>{{ detail.order.sourceOptionalConfiguration }}</p>
            <p v-if="detail.order.sourceOrderRemark?.trim()"><strong>备注：</strong>{{ detail.order.sourceOrderRemark }}</p>
          </el-collapse-item>
        </el-collapse>
        <section>
          <header class="section-head">
            <h3>节点进度 <el-tooltip placement="top" content="目标完成是按当前车间交期，扣除后续节点参考工期倒排出的建议最晚日期。仅供跟单协调，需与车间确认，不会修改节点人工预计或车间交期。"><span class="target-heading" tabindex="0" aria-label="目标完成日期说明">ⓘ</span></el-tooltip></h3>
            <div class="stage-quick-actions">
              <span class="muted">{{ detail.order.stoppedAt ? '已停止跟单，节点记录只读' : '快捷操作只填写当前节点，核对后点底部“保存跟单”' }}</span>
              <el-button v-if="canFollowup && !detail.order.stoppedAt && currentStage && currentStage.status === 'PENDING'" size="small" @click="quickStartCurrentStage">开始{{ currentStage.label }}</el-button>
              <el-button v-if="canFollowup && !detail.order.stoppedAt && currentStage" size="small" type="primary" plain @click="quickCompleteCurrentStage">今天完成{{ currentStage.label }}</el-button>
            </div>
          </header>
          <div v-if="referencePressure && !detail.order.stoppedAt && !detail.order.closedAt && !detail.order.deliveredDate" class="reference-pressure" role="status">
            <strong>参考进度晚于车间交期 {{ referencePressure.days }} 天</strong>
            <span>{{ referencePressure.stageLabel }}节点参考完成 {{ referencePressure.referenceDate }}，当前车间交期 {{ detail.schedule.deadline }}。请与车间核实能否提前关键节点；目标完成日期仅供协调。</span>
          </div>
          <el-alert v-if="!detail.order.stoppedAt && stageGapLabels.length" type="warning" :closable="false" show-icon
            :title="`后续节点已完成，前面的 ${stageGapLabels.join('、')} 尚未完成或未核实顺序。`"
            description="确实完成的节点请补完成日期；未按常规顺序经过的节点可逐项记录原因并保存，保存后不再重复提醒。" />
          <div class="stage-list">
            <div class="stage-head">
              <span>节点</span><span>阶段状态</span><span>参考完成</span><span>目标完成</span><span>完成时间</span><span>节点备注</span><span>节点记录</span>
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
                <span v-else :class="['cell-reference', { 'is-overdue': stageOverdue(stage), 'is-late-target': referenceTargetDelay(stage) > 0 }]">
                  <span class="reference-main">
                    <strong v-if="detail.order.scheduleTime && (stage.manualReferenceDate || stage.referenceDate)" class="reference-date">{{ stage.manualReferenceDate || stage.referenceDate }}</strong>
                    <span v-else-if="!canEditManualReference(stage)" class="reference-empty">未设置</span>
                  <el-popover v-if="canEditManualReference(stage)" placement="bottom" :width="310" trigger="click">
                    <template #reference>
                      <el-button v-if="stage.manualReferenceDate || stage.referenceDate" class="reference-edit" circle size="small" aria-label="修改节点预计日期" title="修改节点预计日期"><el-icon><EditPen /></el-icon></el-button>
                      <el-button v-else class="reference-set" size="small" type="primary" plain>设置预计日期</el-button>
                    </template>
                    <p class="manual-hint">仅本任务单的节点预计日期；不修改产品工期或整单交付日期。</p>
                    <el-date-picker v-model="stage.manualReferenceDate" type="date" value-format="YYYY-MM-DD" clearable placeholder="节点预计完成日期" class="manual-date" @change="onManualDateChange(stage)" />
                    <el-input v-if="stage.manualReferenceDate" v-model="stage.manualReferenceReason" type="textarea" :rows="2" maxlength="300" show-word-limit placeholder="填写预计依据或调整原因（必填）" class="manual-reason" />
                    <small class="manual-hint">修改后点击底部“保存跟单”；清空日期可恢复系统参考。</small>
                  </el-popover>
                  </span>
                  <small v-if="stage.manualReferenceDate && stage.manualReferenceDate !== stage.referenceDate" class="reference-source draft">待保存 · 人工预计</small>
                  <small v-else-if="stage.referenceSource === 'MANUAL' && !stage.manualReferenceDate" class="reference-source draft">恢复系统参考待保存</small>
                  <small v-else-if="stage.referenceDate" class="reference-source">{{ stage.referenceSource === 'MANUAL' ? '人工预计' : '系统参考' }}</small>
                  <small v-if="stage.manualReferenceDate && stage.systemReferenceDate && stage.manualReferenceDate !== stage.systemReferenceDate" class="reference-source">系统原参考 {{ stage.systemReferenceDate }}</small>
                  <small v-if="referenceTargetDelay(stage) > 0" class="target-delay-note">晚于目标 {{ referenceTargetDelay(stage) }} 天</small>
                  <small v-if="stageOverdue(stage)" class="overdue-note">超 {{ stage.referenceOverdueDays }} 天</small>
                </span>
                <span class="cell-target">{{ stage.status === 'DONE' ? '已完成' : stage.targetDate || '—' }}</span>
                <el-date-picker v-model="stage.completedDate" type="date" value-format="YYYY-MM-DD" size="small" clearable placeholder="完成日期" :disabled="!canFollowup || Boolean(detail.order.stoppedAt) || stage.status !== 'DONE'" class="cell-date" />
                <el-input v-model="stage.note" size="small" maxlength="500" :disabled="!canFollowup || Boolean(detail.order.stoppedAt)" :placeholder="stageOverdue(stage) ? '超参考日期，建议记录原因（可选）' : '节点备注（可选）'" class="cell-note" />
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

        <section v-if="timeHistoryItems.length">
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
    <LqDialog v-model="dispositionVisible" :title="dispositionStopped ? '标记不再生产' : '恢复生产跟单'" width="min(500px, 94vw)" mode="detail" :show-footer="true">
      <p class="muted">{{ dispositionStopped ? '仅停止本项目的生产跟单，不修改氚云生产任务单。已有节点和记录会保留，并可日后恢复。' : '恢复后重新进入待跟单或跟单中，原有节点和历史继续保留。' }}</p>
      <el-alert v-if="dispositionStopped && detail?.order.actualFinishDate" type="warning" :closable="false" title="氚云已有报工完工日期，请核实是否属于误报或补录，并在原因中说明。" />
      <el-input v-model="dispositionReason" type="textarea" :rows="4" maxlength="500" show-word-limit :placeholder="dispositionStopped ? '请写明不再生产的具体原因及核实情况（必填）' : '请写明恢复生产的原因（必填）'" />
      <template #footer>
        <button type="button" :disabled="dispositionSaving" @click="dispositionVisible = false">取消</button>
        <button
          type="button"
          :class="dispositionStopped ? 'danger' : 'primary'"
          :disabled="dispositionSaving || !dispositionReason.trim()"
          @click="saveDisposition"
        >
          {{ dispositionStopped ? '确认停止跟单' : '确认恢复跟单' }}
        </button>
      </template>
    </LqDialog>
    <LqDialog v-model="closureVisible" :title="closureClosing ? '确认跟单结案' : '撤销跟单结案'" width="min(520px, 94vw)" mode="detail" :show-footer="true">
      <p class="muted">{{ closureClosing ? '跟单员主动确认结束，状态按「结案优先」展示为「跟单已完成」，节点是否齐全不再强制要求；后续可以撤销结案恢复按节点进度派生。仅本地跟单结案，氚云任务单与节点记录保持原状。' : '撤销后状态按节点进度重新派生：节点齐全仍为「跟单已完成」，缺节点则回退为「跟单中」或「待跟单」。' }}</p>
      <el-alert v-if="closureClosing && !detail?.order.deliveredDate" type="warning" :closable="false" title="该任务尚未登记交付（入库），请在结案说明中写明实际情况与核实结果（不少于 6 字）。" />
      <el-input v-model="closureNote" type="textarea" :rows="4" maxlength="500" show-word-limit :placeholder="closureClosing ? '请写明结案说明，例如：配件任务无需走全部工序；交付已核实；本任务不适用某些节点…（必填）' : '请写明撤销结案的原因（必填）'" />
      <template #footer>
        <button type="button" :disabled="closureSaving" @click="closureVisible = false">取消</button>
        <button
          type="button"
          :class="closureClosing ? 'success' : 'primary'"
          :disabled="closureSaving || !closureNote.trim() || (closureClosing && !detail?.order.deliveredDate && closureNote.trim().length < 6)"
          @click="saveClosure"
        >
          {{ closureClosing ? '确认结案' : '确认撤销' }}
        </button>
      </template>
    </LqDialog>
    <LqDialog
      v-model="delayDialog"
      title="登记延期完成日期"
      width="min(520px, 94vw)"
      mode="detail"
      :show-footer="true"
      confirm-text="保存延期"
      :loading="delaySaving"
      @confirm="saveDelay"
    >
      <p class="delay-dialog-note">车间预计完成实现不了时，登记与车间/业务员协商后的延期完成日期。</p>
      <el-form label-position="top">
        <el-form-item label="车间预计完成"><strong>{{ deadlineDate || '未定' }}</strong></el-form-item>
        <el-form-item label="延期至" required><el-date-picker v-model="delayDraftDate" type="date" value-format="YYYY-MM-DD" placeholder="选择协商后的完成日期" style="width: 100%" /></el-form-item>
        <el-form-item label="延期原因与协商结果" required><el-input v-model="delayDraftReason" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="记录无法按原预计完成的原因，以及与车间/业务员协商的结果" /></el-form-item>
      </el-form>
      <p v-if="delayDraftDate && deadlineDate && delayDraftDate > deadlineDate" class="muted">比车间预计完成晚 {{ daysBetween(deadlineDate, delayDraftDate) }} 天</p>
    </LqDialog>
    <LqDialog
      v-model="timeReviewVisible"
      :title="`${timeReviewEditingId ? '修改' : '记录'}${reviewKindLabel(timeReviewForm.kind)}`"
      width="min(580px, 94vw)"
      mode="detail"
      :show-footer="true"
      :confirm-text="timeReviewEditingId ? '保存修改' : '保存核实记录'"
      :loading="timeReviewSaving"
      @confirm="saveTimeReview"
    >
      <el-alert v-if="timeReviewEditingId" type="info" :closable="false" class="review-edit-hint" title="正在修改这条核实记录（不会新增一条历史）；改动前后的原文会留在审计里。" />
      <el-form label-position="top">
        <el-form-item label="核实结果"><el-select v-model="timeReviewForm.status" style="width: 100%"><template v-if="timeReviewForm.kind === 'COMMITMENT'"><el-option label="车间确认可按业务员要求完成" value="ON_TIME" /><el-option label="协商后确定新交期" value="NEGOTIATED" /></template><template v-else><el-option label="已了解原因，仍需跟进" value="OPEN" /><el-option label="已处理 / 已核实完成" value="RESOLVED" /></template></el-select></el-form-item>
        <el-form-item v-if="timeReviewForm.status === 'NEGOTIATED'" label="协商后的交期"><el-date-picker v-model="timeReviewForm.agreedDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="原因及处理记录"><el-input v-model="timeReviewForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="写明实际情况、核实结果或暂缓入库原因" /></el-form-item>
        <el-form-item label="核实对象"><el-input v-model="timeReviewForm.contactName" maxlength="100" placeholder="例如车间负责人、业务员（可选）" /></el-form-item>
        <el-form-item label="下次跟进"><el-date-picker v-model="timeReviewForm.nextFollowupDate" type="date" value-format="YYYY-MM-DD" clearable style="width: 100%" /></el-form-item>
      </el-form>
    </LqDialog>
    <LqDialog v-model="purchaseChooserVisible" title="选择采购入库单" width="min(620px, 94vw)" mode="detail">
      <p class="muted">该生产工单关联 {{ purchaseReceipts.length }} 张采购入库单，选择一张查看台账详情。</p>
      <el-table :data="purchaseReceipts" max-height="360" @row-click="openPurchaseDetail">
        <el-table-column prop="sourceNo" label="采购入库单号" min-width="155" />
        <el-table-column prop="purchaseDate" label="入库日期" width="120" />
        <el-table-column prop="warehouseName" label="仓库" min-width="110" />
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" @click.stop="openPurchaseDetail(row)">详情</el-button></template></el-table-column>
      </el-table>
    </LqDialog>
    <!-- 通用节点参考工期（原「基准设置」）已于 2026-10-22 迁到「生产产品与工期管理」页右上角，
         本页只负责按推算结果显示参考完成日期，不再承担配置入口。 -->
    <TrackingSourceDetailDrawer v-model="purchaseDetailVisible" kind="purchase" :loading="purchaseDetailLoading" :data="purchaseDetailData" :error="purchaseDetailError" />
  </div>
</template>

<script setup lang="ts">
/**
 * 生产跟单详情抽屉（唯一实现）。
 *
 * 2026-10-22 从 ProductionTrackingView.vue 抽出：生产跟单页与「生产产品与工期管理」页
 * 共用同一个抽屉——产品页从历史任务 / 样本明细下钻时**在原地打开**，不再跳转到生产跟单页。
 * 两边各留一份模板必然漂移，故只留这一份。
 *
 * 打开方式：`<TrackingDetailDrawer v-model="visible" :order-id="id" :items="rows" @saved="reload" />`
 * `order-id` 变化或打开时都会重新拉详情；`items` 只服务「保存并下一单」（列表顺序），
 * 产品页没有列表顺序，不传即可。
 */
import { computed, ref, watch } from 'vue'
import { EditPen } from '@element-plus/icons-vue'
import type { TrackingMilestone, TrackingOrder } from '@/api/productionTracking'
import TrackingSourceDetailDrawer from '@/components/production/TrackingSourceDetailDrawer.vue'
import LqDialog from '@/components/common/LqDialog.vue'
import ProductionSalesTracePanel from '@/components/production/ProductionSalesTracePanel.vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { isSourceUrgent } from '@/utils/trackingUrgency'
import { trackingActionHint as actionHint, trackingActionLabel as actionLabel } from '@/utils/trackingAction'
import {
  daysBetween, effectiveLeadDaysText, invalidApplicationDate, invalidRequiredDate, leadDaysAlarm,
  leadDaysDeltaText, leadDaysKindText, negativeLeadDays, negativeLeadReason, otherLeadDaysText
} from '@/utils/trackingDateGuard'
import {
  currentCompletionDate, deadlineOf, deliveryOutcomeText, expectedCompareText, forecastAdjustText,
  formatMinute, formatTime, inboundBeforeReporting, inboundGapText, inboundHint, inboundReportingHint,
  needsTimeReason, reportCompareText, reviewKindLabel, reviewOf, riskText, scheduleLagDays, shortWorkshop,
  stageOverdue, timeException, trackingStatusText, trackingStatusTone
} from '@/utils/tracking/rules'
// 工期配色与悬停明细：与生产跟单列表同一份实现（列表也 import 这里）。
import { leadDeltaClass, leadDaysTooltipHtml } from '@/utils/tracking/leadDaysDisplay'
import { useTrackingDetail } from '@/composables/tracking/useTrackingDetail'

const props = withDefaults(defineProps<{
  modelValue: boolean
  /** production_orders.id；为 null 时不展示内容。 */
  orderId?: number | null
  /** 当前列表（只用于「保存并下一单」）；产品页不传。 */
  items?: TrackingOrder[]
}>(), { orderId: null, items: () => [] })
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const auth = useCurrentUser()
const canFollowup = computed(() => auth.can('manufacturing.followup'))
const showSecondaryMetrics = ref(false)
function canEditManualReference(stage: TrackingMilestone) {
  return canFollowup.value && Boolean(detail.value?.order.scheduleTime) && !detail.value?.order.stoppedAt
    && stage.status !== 'DONE' && (!stage.optional || stage.applicable)
}
function onManualDateChange(stage: TrackingMilestone) {
  if (!stage.manualReferenceDate) stage.manualReferenceReason = null
}

const {
  drawer, nextTrackingOrder, purchaseLinksLoading, purchaseReceipts, purchaseChooserVisible, purchaseDetailVisible,
  purchaseDetailLoading, purchaseDetailData, purchaseDetailError,
  detail, milestones, requiredTotal, doneStages, showSalesTrace, gapStages, stageGapLabels,
  stagePercent, currentStageIndex, currentStage, referencePanels, expandedEntries,
  timeReviewVisible, timeReviewSaving, timeReviewForm, timeReviewEditingId, deadlineDate, opDone, saving,
  dispositionVisible, dispositionStopped, dispositionReason, dispositionSaving,
  closureVisible, closureClosing, closureNote, closureSaving,
  delayDialog, delaySaving, delayDraftDate, delayDraftReason, unsavedStageCount,
  entryDiff, visibleDiff, toggleEntry, timeHistoryItems, stageNodeHistory,
  expandedStages, toggleStage, pendingStageChanges, stageRecordCount,
  openDetail, openPurchaseReceipts, openPurchaseDetail, quickStartCurrentStage,
  quickCompleteCurrentStage, resetStageDraft, recordStageSequenceReason, openDelayDialog, saveDelay, save, saveAndNext,
  openTimeReview, saveTimeReview, openDisposition, saveDisposition, openClosure, saveClosure, onStageStatusChange
} = useTrackingDetail({ items: () => props.items, reload: async () => { emit('saved') } })

function referenceTargetDelay(stage: TrackingMilestone) {
  if (stage.status === 'DONE' || (stage.optional && !stage.applicable) || !detail.value?.order.scheduleTime) return 0
  const difference = daysBetween(stage.targetDate, stage.manualReferenceDate || stage.referenceDate)
  return difference != null && difference > 0 ? difference : 0
}

const referencePressure = computed(() => {
  if (!detail.value?.order.scheduleTime || !detail.value.schedule.deadline) return null
  const stage = milestones.value.find(item => item.code === 'WAREHOUSE')
    || [...milestones.value].reverse().find(item => !item.optional || item.applicable)
  const referenceDate = stage?.manualReferenceDate || stage?.referenceDate
  const days = daysBetween(detail.value.schedule.deadline, referenceDate)
  return stage && stage.status !== 'DONE' && referenceDate && days != null && days > 0
    ? { days, stageLabel: stage.label, referenceDate }
    : null
})

function loadOrder(id: number) {
  if (!Number.isInteger(id) || id <= 0) return
  showSecondaryMetrics.value = false
  void openDetail({ id } as TrackingOrder)
}
/** 打开时拉详情；打开状态下 orderId 变化（例如又点了另一行）也要重新拉。 */
watch(() => [props.modelValue, props.orderId] as const, ([visible, id]) => {
  // 父页主动关（点遮罩不算，遮罩会经 drawer 自己的 v-model 关掉）→ 内部抽屉同步收起
  if (!visible) {
    if (drawer.value) drawer.value = false
    return
  }
  if (id == null) return
  loadOrder(Number(id))
}, { immediate: true })
/** 抽屉自己关（右上角 ×、底部「关闭」、「保存并下一单」）都要回写给父页。 */
watch(drawer, value => { if (!value) emit('update:modelValue', false) })
</script>

<style scoped>
.manual-date { width: 100%; }
.manual-reason { margin-top: 8px; }
.manual-hint { display: block; margin: 0 0 8px; color: var(--color-text-secondary, #64748b); font-size: 12px; line-height: 1.5; }
.tracking-detail-host { display: contents; }
.muted { color: #667085; font-size: 13px; }
.detail-content { display: grid; gap: 14px; padding-bottom: 8px; }
.detail-content section { padding-top: 0; }
.detail-content h3 { margin: 0; font-size: 15px; }
.section-head { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.stage-quick-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pending-change-count { color: #b54708; font-weight: 600; }
.attention-list { display: flex; gap: 8px; flex-wrap: wrap; }
.attention-item { display: flex; align-items: baseline; gap: 6px; padding: 6px 9px; border-radius: 6px; background: #fffaeb; font-size: 12px; color: #78551d; }
.attention-item strong { flex: none; color: #b54708; }
.attention-item--urgent { background: #fff6f5; color: #912018; }
.attention-item--urgent strong { color: #b42318; }
.source-order-remark { border: none; }
.source-order-remark p { margin: 0 0 8px; padding: 8px 12px; background: #f8fafc; border-radius: 6px; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; }
.source-order-remark :deep(.el-collapse-item__header) { height: 36px; border-bottom: none; font-size: 12px; color: #667085; }
.source-order-remark :deep(.el-collapse-item__wrap) { border-bottom: none; }
.summary-block { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.summary-title { display: flex; align-items: center; gap: 8px; }
.summary-title strong { font-size: 17px; }
.summary-spec { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 6px 0 8px; }
.summary-tags { display: flex; align-items: center; gap: 4px 0; flex-wrap: wrap; color: #667085; font-size: 12px; }
.summary-tags > span + span::before { content: '·'; color: #c0c4cc; padding: 0 9px; }
.summary-last-followup { color: #98a2b3; }
.summary-progress { display: grid; justify-items: center; gap: 4px; }
.progress-num { font-size: 13px; font-weight: 700; }
.key-dates { padding-top: 0; }
.key-dates .section-head { margin-bottom: 8px; align-items: center; }
.key-dates-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border: 1px solid #e4e7ec; border-radius: 9px; overflow: hidden; background: #fff; }
.key-dates-grid > div { display: flex; align-items: baseline; gap: 3px 8px; flex-wrap: wrap; min-width: 0; padding: 10px 13px; border-right: 1px solid #e4e7ec; }
.key-dates-grid > div:last-child { border-right: none; }
.key-dates-grid span { flex-basis: 100%; color: #667085; font-size: 11px; }
.key-dates-grid strong { color: #101828; font-size: 14px; white-space: nowrap; }
.key-dates-grid small { color: #667085; font-size: 11px; }
.key-dates-grid .el-button { margin: -5px 0; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.secondary-toggle { justify-self: start; margin: -8px 0 0; }
.metric { background: #f9fafb; border: 1px solid #eef0f3; border-radius: 8px; padding: 9px 12px; display: grid; gap: 3px; min-width: 0; }
.metric span { color: #667085; font-size: 12px; }
.metric-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.metric-heading .el-button { margin: -5px -6px -5px 0; }
.metric-delay { color: #b54708; font-weight: 600; overflow-wrap: anywhere; }
.metric strong { font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.metric strong.is-danger { color: #d92d20; }
.metric-link { width: 100%; text-align: left; font: inherit; cursor: pointer; }
/* 原为 Element Plus 默认蓝 #409eff / #f0f7ff 硬编码，与项目主色（--color-primary #0f766e）不一致，
   已改为设计令牌，保证与全站交互色一致 */
.metric-link:hover, .metric-link:focus-visible { border-color: var(--color-primary); background: var(--color-primary-bg); }
.metric-link:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.metric-link:disabled { cursor: wait; }
.metric-link .metric-link-hint { color: var(--color-primary); }
.metric small { font-size: 12px; line-height: 1.5; }
.metric .el-button { justify-self: start; margin-left: -4px; }
.detail-stopped .metric .el-button { display: none; }
.metric.metric-exception { border-color: #fda29b; background: #fff6f5; }
.metric.metric-exception strong, .metric-exception-text { color: #b42318; }
.metric-reviewed { color: #667085; }
.metric-reviewed .el-button { margin-left: 6px; vertical-align: baseline; font-size: 12px; }
.review-edit-hint { margin-bottom: 12px; }
.time-signal-list { display: flex; gap: 4px; flex-wrap: wrap; }
.delay-dialog-note { margin: 0 0 16px; color: #667085; line-height: 1.6; }
.lead-detail-heading { cursor: help; }
.metric small.lead-kind { color: #475467; background: #f2f4f7; border-radius: 4px; padding: 0 4px; font-size: 11px; font-weight: 600; }
.metric small.lead-delta-late { color: #b42318; font-weight: 600; }
.metric small.lead-delta-early { color: #067647; font-weight: 600; }
.stage-list {
  --stage-cols: minmax(130px, 1fr) 112px 150px 90px 132px minmax(180px, 1.5fr) auto;
  display: grid; gap: 8px;
}
.stage-head { display: grid; grid-template-columns: var(--stage-cols); gap: 12px; align-items: center; padding: 0 14px; font-size: 12px; font-weight: 600; color: #667085; }
.target-heading { cursor: help; white-space: nowrap; }
.target-heading:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; border-radius: 2px; }
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
.cell-reference { font-size: 12px; color: #475467; display: flex; flex-direction: column; gap: 2px; line-height: 1.35; min-width: 0; }
.reference-pressure { display: flex; align-items: baseline; gap: 8px 12px; flex-wrap: wrap; margin-bottom: 10px; padding: 8px 11px; border: 1px solid #fecdca; border-radius: 7px; background: #fff6f5; color: #912018; font-size: 12px; }
.reference-pressure strong { color: #b42318; }
.cell-reference.is-late-target .reference-date, .cell-reference .target-delay-note { color: #b42318; }
.cell-reference .target-delay-note { font-weight: 600; font-size: 11px; }
.cell-target { font-size: 12px; color: #475467; white-space: nowrap; }
.reference-main { display: flex; align-items: center; gap: 6px; min-height: 25px; }
.reference-date { font-size: 12px; font-weight: 600; white-space: nowrap; }
.reference-empty { color: #98a2b3; }
.reference-edit { width: 24px; height: 24px; flex: none; color: var(--color-primary, #0f766e); }
.reference-set { margin: 0; }
.cell-reference .reference-source { font-size: 11px; color: #98a2b3; }
.cell-reference .reference-source.draft { color: #b54708; }
.cell-reference small { color: var(--color-text-secondary, #64748b); }
.cell-reference .overdue-note { color: #d92d20; font-weight: 600; }
.cell-reference.is-overdue { color: #d92d20; font-weight: 600; }
.cell-history { justify-self: end; }
.stage-item.na { border-style: dashed; background: #fcfcfd; }
.stage-item.na .row-dot { background: transparent; box-shadow: inset 0 0 0 1px #d0d5dd; }
.stage-item.na .row-name { color: #98a2b3; }
.row-history { margin-left: 5px; padding-left: 14px; border-left: 2px solid #e5e7eb; display: grid; gap: 3px; }
.row-history p { margin: 0; font-size: 12px; color: #475467; line-height: 1.5; overflow-wrap: anywhere; }
.row-history .muted { font-size: 12px; margin-right: 6px; }
.row-history .pending { color: #b54708; background: #fffaeb; border-radius: 6px; padding: 4px 8px; }
.optional-mark { font-style: normal; margin-left: 6px; padding: 1px 5px; border-radius: 4px; background: #f2f4f7; color: #667085; font-size: 11px; font-weight: 600; }
.reference-panel { border: none; margin-top: 4px; }
.reference-panel :deep(.el-collapse-item__header), .reference-panel :deep(.el-collapse-item__wrap) { border-bottom: none; }
.more-title { font-size: 13px; font-weight: 600; color: #344054; }
.reference-panel :deep(.el-collapse-item__header) { height: auto; min-height: 40px; line-height: 1.5; font-size: 13px; }
.entry { display: grid; gap: 6px; }
.entry-head { display: flex; align-items: baseline; gap: 10px; }
.entry-head strong { font-size: 13px; }
.entry-content { margin: 0; white-space: pre-wrap; }
.entry-diff { display: flex; flex-wrap: wrap; gap: 6px; }
.diff-chip { background: #f2f4f7; border-radius: 6px; padding: 3px 8px; font-size: 12px; color: #344054; }
.diff-chip i { font-style: normal; color: #98a2b3; text-decoration: line-through; }
.diff-chip b { margin: 0 5px; color: #98a2b3; font-weight: 400; }
.drawer-footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
@media (max-width: 720px) {
  .stage-head { display: none; }
  .stage-list { --stage-cols: 1fr; }
  .cell-history { justify-self: start; }
}
@media (max-width: 760px) { .metric-grid, .key-dates-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .key-dates-grid > div:nth-child(2) { border-right: none; } .key-dates-grid > div:nth-child(-n+2) { border-bottom: 1px solid #e4e7ec; } .key-dates-grid > div:last-child { grid-column: 1 / -1; } .summary-block { flex-direction: column; } .summary-progress { justify-items: flex-start; } }
</style>
