<template>
  <div class="s-page monthly-commission-page">
    <header class="page-head">
      <div class="page-title">
        <div class="eyebrow">经营核算 · 版本化核算</div>
        <div class="title-row">
          <h1>提成核算</h1>
          <el-tooltip
            placement="bottom"
            effect="light"
            :show-after="200"
            :offset="6"
          >
            <template #content>
              <div style="max-width: 520px; line-height: 1.7;">
                「核算结账」为财务手动按所选日期范围生成核算版本：期间正常提成 + 已生效出库单修正变化（导入时单据级自动跟踪）；版本间可逐业务员对比差额；「月度参考」为实时汇总视图（范围可任意，默认本月）。
              </div>
            </template>
            <el-button
              link
              type="info"
              size="small"
              :icon="InfoIcon"
              class="title-help"
              aria-label="页面说明"
            />
          </el-tooltip>
        </div>
      </div>
      <div class="filters">
        <div class="filter-row filter-row--period">
          <el-radio-group
            v-model="quickRangeKey"
            size="small"
            class="filter-quick-range"
            @change="onQuickRange"
          >
            <el-radio-button value="thisMonth">本月</el-radio-button>
            <el-radio-button value="lastMonth">上月</el-radio-button>
            <el-radio-button value="thisQuarter">本季度</el-radio-button>
          </el-radio-group>
          <el-date-picker
            v-model="range"
            class="filter-date"
            type="daterange"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            range-separator="~"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            @change="onPeriodChange()"
          />
          <el-select
            v-if="canViewOthers && activeTab === 'monthly'"
            v-model="quickEmployeeId"
            class="filter-employee"
            placeholder="快捷查看业务员"
            clearable
            @change="onQuickEmployee"
          >
            <el-option
              v-for="row in items"
              :key="row.employeeId"
              :label="row.employeeName"
              :value="row.employeeId" />
          </el-select>
        </div>
        <div class="filter-row filter-row--freight">
          <el-select
            v-model="freightMode"
            title="运杂费扣减窗口"
            class="filter-freight"
            @change="onFreightWindowChange"
          >
            <el-option label="运费：上一自然月" value="PREVIOUS_CALENDAR_MONTH" />
            <el-option label="运费：前等长周期" value="PREVIOUS_EQUAL_PERIOD" />
            <el-option label="运费：自定义" value="CUSTOM" />
          </el-select>
          <template v-if="freightMode === 'CUSTOM'">
            <el-date-picker
              v-model="freightCustomRange"
              type="daterange"
              class="filter-freight-date"
              format="YYYY-MM-DD"
              value-format="YYYY-MM-DD"
              start-placeholder="运费起"
              end-placeholder="运费止"
              @change="onFreightWindowChange"
            />
            <el-input
              v-model="freightReason"
              class="filter-freight-reason"
              placeholder="自定义运费范围原因（必填）"
              clearable
              @change="onFreightWindowChange"
            />
          </template>
          <div class="filter-actions">
            <el-button v-if="canManage" type="primary" @click="openSettle">核算结账</el-button>
            <el-button v-if="canManage" @click="openRates">比例配置</el-button>
            <el-button :icon="RefreshIcon" @click="reloadAll()">刷新</el-button>
          </div>
        </div>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="commission-tabs" @tab-change="onTabChange">
      <el-tab-pane label="月度参考" name="monthly">

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="数据口径"
      class="caliber-alert"
    >
      <template #default>
        核算期间 = 上方选择的<strong>日期范围 [开始, 结束]</strong>（默认本月，可任意选择，如按账期 7/16~8/15）。
        期间销售额：出库单销售金额按<strong>发货日期</strong>归属期间（CONFIRMED，供参考）。
        期间实际回款：销售出库「收款详细」子表按<strong>收款日期</strong>归属期间（CONFIRMED）。<strong>未收款</strong>和回款进度直接汇总期间销售出库主单中的「未收款金额、已收款金额」，不在本系统二次分配；多订单结清后的结果以氚云同步回销售出库的数据为准。
        上期运杂费：出库运杂费按<strong>发货日期</strong>归属运费窗口（顶部可切换：<strong>默认「上一自然月」</strong>= 结束日所在月的前一个整月，财务口径运杂费按整月，核算 2026-08-01~08-31 取 2026-07 整月；亦可选「前等长周期」= 核算范围前一个等长周期，或「自定义」（须填原因，审计留痕））。
        经销商 = 出库单「选择经销商」非空，<strong>或毛利率低于 15%</strong>（漏填经销商时按低利润订单兜底，毛利率 = 氚云毛利 ÷ 销售额）。
        提成金额 = (经销商回款 − 上期经销商运杂费) × 经销商比例 + (零售回款 − 上期零售运杂费) × 零售比例。
        比例在「比例配置」中维护，按来源发生日生效。下方“实时试算”反映当前氚云数据和来源发生时的比例；“已核算累计”按有效版本中的冻结来源累计，比例调整不会回算历史版本。
      </template>
    </el-alert>

    <el-alert
      v-if="summary?.settled"
      type="info"
      :closable="false"
      show-icon
      title="该日期范围已有核算批次"
      class="issue-alert"
    >
      <template #default>
        该范围最近一次核算于 <strong>{{ formatDateTime(summary.settledAt) }}</strong>（版本 <strong>{{ summary.settledVersionNo }}</strong>）。
        当前页同时展示<strong>氚云实时参考</strong>和<strong>有效版本冻结累计</strong>，两种金额不再相互覆盖。
        再次选择相同或重叠范围时，只会核算尚未被有效批次占用的收款、运杂费及待处理修正。
      </template>
    </el-alert>

    <el-alert
      v-for="issue in issues"
      :key="issue.type"
      type="warning"
      :closable="false"
      show-icon
      :title="issue.message"
      class="issue-alert"
    />

    <!-- 顶部指标 -->
    <section v-if="summary" class="kpi-grid">
      <div class="kpi kpi-sales">
        <span class="kpi-label">期间销售额</span>
        <div class="kpi-main">{{ money(summary.currentMonthSalesAmount) }}</div>
        <div class="kpi-sub">经销 {{ money(summary.dealerSalesAmount) }} / 零售 {{ money(summary.retailSalesAmount) }}</div>
      </div>
      <div class="kpi kpi-receipt">
        <span class="kpi-label">期间实际回款</span>
        <div class="kpi-main">{{ money(summary.currentMonthReceiptAmount) }}</div>
        <div class="kpi-sub">收款日期 ∈ 期间 · 经销 {{ money(summary.dealerReceiptAmount) }} / 零售 {{ money(summary.retailReceiptAmount) }}</div>
      </div>
      <div class="kpi kpi-uncollected">
        <span class="kpi-label">未收款（期间发货未回）</span>
        <div class="kpi-main danger">{{ money(summary.totalUncollectedAmount) }}</div>
        <div class="kpi-sub">氚云出库已收 {{ money(summary.totalCollectedAmount) }} · 未收占比 {{ ratePct(1 - summary.collectionRate) }}</div>
      </div>
      <div class="kpi kpi-progress">
        <span class="kpi-label">期间回款进度</span>
        <div class="kpi-progress-bar">
          <el-progress
            :percentage="Math.round(summary.collectionRate * 100)"
            :stroke-width="10"
            :color="progressColor(summary.collectionRate)"
            :text-inside="true"
            :format="() => ratePct(summary?.collectionRate)"
          />
        </div>
        <div class="kpi-sub">氚云出库已收 ÷（已收 + 未收）· 未收 {{ ratePct(1 - summary.collectionRate) }}</div>
      </div>
      <div class="kpi kpi-freight">
        <span class="kpi-label">上期运杂费</span>
        <div class="kpi-main warn">{{ money(summary.previousMonthFreightAmount) }}</div>
        <div class="kpi-sub">经销 {{ money(summary.dealerFreightAmount) }} / 零售 {{ money(summary.retailFreightAmount) }}</div>
      </div>
      <div class="kpi kpi-base">
        <span class="kpi-label">提成核算基数</span>
        <div class="kpi-main">{{ money(summary.commissionBase) }}</div>
        <div class="kpi-sub">{{ summary.period }} 回款 − {{ summary.previousPeriod }} 运杂费（供参考）</div>
      </div>
      <div class="kpi kpi-amount">
        <span class="kpi-label">当前实时试算</span>
        <div class="kpi-main primary">{{ money(summary.commissionAmount) }}</div>
        <div class="kpi-sub">当前氚云回款、运杂费按逐笔历史比例计算，仅供核对</div>
      </div>
      <div class="kpi kpi-amount">
        <span class="kpi-label">已核算累计</span>
        <div class="kpi-main primary">{{ money(summary.settlement.settledCommission) }}</div>
        <div class="kpi-sub">{{ summary.settlement.versionCount }} 个有效版本 · {{ summary.settlement.sourceLineCount }} 笔冻结来源</div>
      </div>
      <div class="kpi kpi-amount">
        <span class="kpi-label">已核算未发放</span>
        <div class="kpi-main" :class="summary.settlement.unpaidCommission < 0 ? 'danger' : 'warn'">{{ money(summary.settlement.unpaidCommission) }}</div>
        <div class="kpi-sub">已发放 {{ money(summary.settlement.paidCommission) }} · 作废版本不计入</div>
      </div>
      <div v-if="canManage && settlePreview" class="kpi kpi-amount">
        <span class="kpi-label">本次待核算合计</span>
        <div class="kpi-main primary">{{ money(settlePreview.pendingCommission) }}</div>
        <div class="kpi-sub">基础 {{ money(settlePreview.pendingBaseCommission) }} · 修正 {{ signedMoney(settlePreview.pendingAdjustmentCommission) }}</div>
      </div>
      <div v-if="canManage && settlePreview" class="kpi kpi-amount">
        <span class="kpi-label">核算后预计累计</span>
        <div class="kpi-main primary">{{ money(summary.settlement.settledCommission + settlePreview.pendingCommission) }}</div>
        <div class="kpi-sub">已核算累计 + 本次待核算基础及修正</div>
      </div>
      <div class="kpi kpi-emp">
        <span class="kpi-label">参与核算业务员</span>
        <div class="kpi-main">{{ summary.employeeCount }}</div>
        <div class="kpi-sub">期间有销售额、回款或运杂费扣减</div>
      </div>
    </section>

    <!-- 业务员汇总表 -->
    <section class="panel">
      <div class="panel-head">
        <h2>业务员汇总</h2>
        <el-tag size="small" type="info" effect="plain">点击行查看「怎么算出来的」</el-tag>
      </div>
      <el-table
v-loading="loading"
:data="items"
stripe
row-key="employeeId"
@row-click="openDetail">
        <el-table-column
prop="employeeName"
label="业务员"
width="120"
show-overflow-tooltip />
        <el-table-column
prop="currentMonthSalesAmount"
label="期间销售额"
min-width="150"
align="right">
          <template #default="{ row }">
            <div class="cell-main">{{ money(row.currentMonthSalesAmount) }}</div>
            <div class="cell-sub">经销 {{ money(row.dealerSalesAmount) }} / 零售 {{ money(row.retailSalesAmount) }}</div>
          </template>
        </el-table-column>
        <el-table-column
prop="currentMonthReceiptAmount"
label="期间实际回款"
min-width="150"
align="right">
          <template #default="{ row }">
            <div class="cell-main">{{ money(row.currentMonthReceiptAmount) }}</div>
            <div class="cell-sub">经销 {{ money(row.dealerReceiptAmount) }} / 零售 {{ money(row.retailReceiptAmount) }}</div>
          </template>
        </el-table-column>
        <el-table-column
prop="uncollectedAmount"
label="未收款"
min-width="150"
align="right">
          <template #default="{ row }">
            <div class="cell-main danger">{{ money(row.uncollectedAmount) }}</div>
            <div class="cell-sub">氚云出库已收 {{ money(row.collectedAmount) }} · 未收 {{ ratePct(1 - row.collectionRate) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="回款进度" min-width="170">
          <template #default="{ row }">
            <div class="progress-cell">
              <el-progress
                :percentage="Math.round(row.collectionRate * 100)"
                :stroke-width="8"
                :color="progressColor(row.collectionRate)"
                :text-inside="true"
                :format="() => ratePct(row.collectionRate)"
              />
            </div>
            <div class="cell-sub">氚云出库已收 {{ money(row.collectedAmount) }} / 未收 {{ money(row.uncollectedAmount) }}</div>
          </template>
        </el-table-column>
        <el-table-column
prop="previousMonthFreightAmount"
label="上期运杂费"
min-width="150"
align="right">
          <template #default="{ row }">
            <div class="cell-main">{{ money(row.previousMonthFreightAmount) }}</div>
            <div class="cell-sub">经销 {{ money(row.dealerFreightAmount) }} / 零售 {{ money(row.retailFreightAmount) }}</div>
          </template>
        </el-table-column>
        <el-table-column
prop="commissionBase"
label="提成核算基数"
min-width="130"
align="right">
          <template #default="{ row }">
            <span :class="{ 'is-negative': row.commissionBase < 0 }">{{ money(row.commissionBase) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="当前配置比例" width="140" align="right">
          <template #default="{ row }">{{ rateLabel(row) }}</template>
        </el-table-column>
        <el-table-column
prop="commissionAmount"
label="实时试算"
min-width="160"
align="right">
          <template #default="{ row }">
            <div class="cell-main" :class="{ 'is-negative': row.commissionAmount < 0 }">{{ money(row.commissionAmount) }}</div>
            <div class="cell-sub">每笔按发生日的生效比例计算</div>
          </template>
        </el-table-column>
        <el-table-column label="已核算" min-width="125" align="right">
          <template #default="{ row }">
            <div class="cell-main">{{ money(row.settledCommission) }}</div>
            <div class="cell-sub">未发放 {{ money(row.unpaidCommission) }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="canManage" label="待核算" min-width="125" align="right">
          <template #default="{ row }">
            <span :class="{ 'is-negative': row.pendingCommission < 0 }">{{ money(row.pendingCommission) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="已发放" min-width="115" align="right">
          <template #default="{ row }">{{ money(row.paidCommission) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">查看明细</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
      </el-tab-pane>

      <el-tab-pane label="核算版本" name="versions">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="核算口径（单据级变化跟踪 + 手动核算结账）"
          class="caliber-alert"
        >
          <template #default>
            每次导入销售出库后，系统对<strong>已生效（CONFIRMED）出库单</strong>逐单对比「当前收款/运杂费 vs 基线」，有变化则记录<strong>单据级修正明细</strong>（首次生效只建基线、不记修正；业务员换人/清空也会产生「追回 + 补给」修正；取消后重新生效会补记补给，避免提成丢失）。
            财务点击「核算结账」按<strong>上方所选日期范围</strong>生成一个版本：<strong>期间正常提成</strong>（每笔期间回款按收款日比例计提，每笔上期运杂费按归属日比例扣除）+ <strong>修正提成</strong>（变化按对应来源的归属期间比例计算；归属当前期间的未核算修正按业务员汇总：回款按收款日期 ∈ 期间、运杂费按发货日期 ∈ 上期窗口；负数=抵扣）。
            生成后并入的修正明细标记为已核算；归属其他期间的修正保留未核算，待对应期间结账时并入。版本间仅支持同一期间内对比差额；误结账可对<strong>最新版本执行「作废」</strong>回滚重算。
          </template>
        </el-alert>

        <section v-if="pendingChangeCount > 0" class="panel change-warning">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="有待核算修正"
            class="issue-alert"
          >
            <template #default>
              当前存在 <strong>{{ pendingChangeCount }}</strong> 笔已生效出库单修正变化尚未并入核算版本（其中归属当前期间的将在本次结账并入）。请在「修正变化」页签核对后点击「核算结账」结算。
            </template>
          </el-alert>
        </section>

        <section class="panel">
          <div class="panel-head">
            <h2>核算版本（全部期间）</h2>
            <el-tag size="small" type="info" effect="plain">每版本 = 期间正常提成 + 修正提成（当前范围 {{ rangeLabel }}）</el-tag>
          </div>
          <el-table
v-loading="versionsLoading"
:data="versions"
size="small"
stripe
row-key="id"
empty-text="暂无核算版本（点击右上角「核算结账」生成）">
            <el-table-column
prop="versionNo"
label="版本号"
min-width="230"
show-overflow-tooltip />
            <el-table-column label="状态" width="88" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'FINAL'" type="success" size="small" effect="plain">已结账</el-tag>
                <el-tag v-else-if="row.status === 'VOID'" type="info" size="small" effect="plain">已作废</el-tag>
                <el-tag v-else type="warning" size="small" effect="plain">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型 / 运费 / 发放" width="216" align="center">
              <template #default="{ row }">
                <div class="tag-line">
                  <el-tag v-if="row.settlementType === 'SUPPLEMENT'" type="warning" size="small" effect="plain">补充核算</el-tag>
                  <el-tag v-else-if="row.settlementType === 'RECALCULATION'" type="warning" size="small">作废重算</el-tag>
                  <el-tag v-else type="info" size="small" effect="plain">正常核算</el-tag>
                  <el-tag v-if="row.freightPeriodMode === 'PREVIOUS_EQUAL_PERIOD'" type="warning" size="small" effect="plain">等长运费</el-tag>
                  <el-tag v-else-if="row.freightPeriodMode === 'CUSTOM'" type="danger" size="small" effect="plain">自定义运费</el-tag>
                  <el-tag v-if="row.paidFlag === 'PAID'" type="success" size="small">已发放</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="核算期间" min-width="210" show-overflow-tooltip>
              <template #default="{ row }">{{ row.period }}</template>
            </el-table-column>
            <el-table-column label="核算时间" width="165">
              <template #default="{ row }">{{ formatDateTime(row.calculatedAt) }}</template>
            </el-table-column>
            <el-table-column prop="createdByName" label="操作人" width="100">
              <template #default="{ row }">{{ row.createdByName || '—' }}</template>
            </el-table-column>
            <el-table-column
prop="employeeCount"
label="业务员数"
width="90"
align="right" />
            <el-table-column
prop="totalBaseCommission"
label="正常提成"
min-width="130"
align="right">
              <template #default="{ row }">{{ money(row.totalBaseCommission) }}</template>
            </el-table-column>
            <el-table-column
prop="totalAdjCommission"
label="修正提成"
min-width="130"
align="right">
              <template #default="{ row }">
                <span :class="{ 'is-negative': row.totalAdjCommission < 0 }">{{ signedMoney(row.totalAdjCommission) }}</span>
              </template>
            </el-table-column>
            <el-table-column
prop="totalCommission"
label="应发合计"
min-width="130"
align="right">
              <template #default="{ row }">
                <span :class="{ 'is-negative': row.totalCommission < 0 }">{{ money(row.totalCommission) }}</span>
              </template>
            </el-table-column>
            <el-table-column
prop="remark"
label="备注"
min-width="140"
show-overflow-tooltip>
              <template #default="{ row }">{{ row.remark || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="340" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openVersionDetail(row)">查看明细</el-button>
                <el-button link type="primary" @click="openCompare(row)">对比</el-button>
                <el-dropdown
                  trigger="click"
                  class="ops-export-dropdown"
                  @command="(kind: 'items' | 'lines' | 'adjustments') => exportVersionCsv(row, kind)"
                >
                  <el-button link type="primary">
                    导出<el-icon class="el-icon--right"><ArrowDownIcon /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="items">提成发放汇总</el-dropdown-item>
                      <el-dropdown-item command="lines">核算来源明细</el-dropdown-item>
                      <el-dropdown-item command="adjustments">修正调整明细</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button
                  v-if="row.status === 'FINAL' && row.paidFlag !== 'PAID' && canManage"
                  link
                  type="success"
                  @click="markPaid(row)">标记发放</el-button>
                <el-button v-if="row.voidable && canManage" link type="danger" @click="voidVersion(row)">作废</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section v-if="compare" class="panel compare-panel">
          <div class="panel-head">
            <h2>
              版本对比：
              <el-tag size="small" type="primary" effect="plain">{{ compare.newerVersion.versionNo }}</el-tag>
              <span class="compare-sub">（新）</span>
              vs
              <el-tag size="small" type="info" effect="plain">{{ compare.olderVersion.versionNo }}</el-tag>
              <span class="compare-sub">（旧）diff = 新版 − 旧版，逐业务员</span>
            </h2>
            <el-button link @click="compare = null">收起</el-button>
          </div>
          <div class="compare-total">
            应发合计差额：<strong :class="compare.totalCommissionDiff < 0 ? 'is-negative' : 'is-positive'">{{ signedMoney(compare.totalCommissionDiff) }}</strong>
            <span class="compare-sub">其中正常提成 {{ signedMoney(compare.totalBaseCommissionDiff) }}，修正提成 {{ signedMoney(compare.totalAdjCommissionDiff) }}</span>
          </div>
          <el-table
:data="compare.items"
size="small"
stripe
row-key="employeeId">
            <el-table-column
prop="employeeName"
label="业务员"
width="130"
show-overflow-tooltip />
            <el-table-column label="正常提成" min-width="150" align="right">
              <template #default="{ row }">
                <div class="cell-main">{{ money(row.baseCommissionOld) }} → {{ money(row.baseCommissionNew) }}</div>
                <div class="cell-sub" :class="{ 'is-negative': row.baseCommissionDiff < 0 }">{{ signedMoney(row.baseCommissionDiff) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="修正提成" min-width="150" align="right">
              <template #default="{ row }">
                <div class="cell-main">{{ money(row.adjCommissionOld) }} → {{ money(row.adjCommissionNew) }}</div>
                <div class="cell-sub" :class="{ 'is-negative': row.adjCommissionDiff < 0 }">{{ signedMoney(row.adjCommissionDiff) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="Δ修正回款" min-width="130" align="right">
              <template #default="{ row }">
                <span :class="{ 'is-negative': row.adjReceiptDiff < 0 }">{{ signedMoney(row.adjReceiptDiff) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="Δ修正运杂费" min-width="130" align="right">
              <template #default="{ row }">
                <span :class="{ 'is-negative': row.adjFreightDiff < 0 }">{{ signedMoney(row.adjFreightDiff) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="应发合计" min-width="150" align="right">
              <template #default="{ row }">
                <div class="cell-main">{{ money(row.totalCommissionOld) }} → {{ money(row.totalCommissionNew) }}</div>
                <div class="cell-sub" :class="row.totalCommissionDiff < 0 ? 'is-negative' : 'is-positive'">{{ signedMoney(row.totalCommissionDiff) }}</div>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="修正变化" name="changes">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="已生效出库单修正明细（单据级）"
          class="caliber-alert"
        >
          <template #default>
            每次导入销售出库后自动识别：已生效（CONFIRMED）出库单的收款/运杂费<strong>相比基线</strong>发生变化的单据（首次生效不在此列）；业务员<strong>换人/清空</strong>也会产生「追回 + 补给」两条修正。
            每条变化可精确到「哪张单被修正、从多少变到多少」，并标注<strong>归属期间</strong>（回款按收款日期、运杂费按发货日期），财务可下钻核对。
            核算结账仅并入<strong>归属当前期间</strong>的未核算（PENDING）明细；历史无期间标注的修正会并入下次结账。
          </template>
        </el-alert>

        <section class="panel">
          <div class="panel-head">
            <h2>修正明细</h2>
            <el-radio-group v-model="changeStatus" size="small" @change="loadChanges">
              <el-radio-button value="PENDING">未核算</el-radio-button>
              <el-radio-button value="PROCESSED">已核算</el-radio-button>
              <el-radio-button value="">全部</el-radio-button>
            </el-radio-group>
          </div>
          <el-alert
            v-if="changesTruncated > 0"
            type="info"
            :closable="false"
            show-icon
            title="明细较多，仅展示最近一批"
            class="issue-alert"
          >
            <template #default>
              当前状态下共 <strong>{{ changesTruncated }}</strong> 条修正变化，列表仅展示最近 {{ CHANGES_PAGE_LIMIT }} 条（按记录时间倒序）；「未核算」总条数以核算版本页签待核算提示为准。
            </template>
          </el-alert>
          <el-table
v-loading="changesLoading"
:data="changes"
size="small"
stripe
row-key="id"
empty-text="该状态下暂无修正变化">
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'PENDING' ? 'warning' : 'success'" effect="plain">
                  {{ row.status === 'PENDING' ? '未核算' : '已核算' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="recordedAt" label="识别时间" width="165">
              <template #default="{ row }">{{ formatDateTime(row.recordedAt) }}</template>
            </el-table-column>
            <el-table-column label="出库单" min-width="150">
              <template #default="{ row }">
                <el-link type="primary" :underline="false" @click="openLedgerDetail(row.outboundId)">{{ row.outboundNo }}</el-link>
              </template>
            </el-table-column>
            <el-table-column
prop="customerName"
label="客户"
min-width="130"
show-overflow-tooltip>
              <template #default="{ row }">{{ row.customerName || '—' }}</template>
            </el-table-column>
            <el-table-column label="业务员" width="130" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.employeeName }}</span>
                <el-tag
                  v-if="row.prevEmployeeName"
                  size="small"
                  type="warning"
                  effect="plain"
                  style="margin-left: 4px"
                >换人</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="原业务员" width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ row.prevEmployeeName || '—' }}</template>
            </el-table-column>
            <el-table-column label="归属期间" min-width="160">
              <template #default="{ row }">
                <div>回款 {{ row.receiptDate ? String(row.receiptDate).slice(0, 10) : '—' }}</div>
                <div class="cell-sub">运杂费(发货) {{ row.shipDate ? String(row.shipDate).slice(0, 10) : '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="变化前回款" min-width="130" align="right">
              <template #default="{ row }">
                <div class="cell-main">{{ money(receiptOf(row.beforeReceiptDealer) + receiptOf(row.beforeReceiptRetail)) }}</div>
                <div class="cell-sub">经销 {{ money(row.beforeReceiptDealer) }} / 零售 {{ money(row.beforeReceiptRetail) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="变化后回款" min-width="130" align="right">
              <template #default="{ row }">
                <div class="cell-main">{{ money(receiptOf(row.afterReceiptDealer) + receiptOf(row.afterReceiptRetail)) }}</div>
                <div class="cell-sub">经销 {{ money(row.afterReceiptDealer) }} / 零售 {{ money(row.afterReceiptRetail) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="Δ运杂费" min-width="110" align="right">
              <template #default="{ row }">
                <span :class="{ 'is-negative': freightDelta(row) < 0 }">{{ signedMoney(freightDelta(row)) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="Δ提成" min-width="120" align="right">
              <template #default="{ row }">
                <span :class="row.changeCommission < 0 ? 'is-negative' : 'is-positive'">{{ signedMoney(row.changeCommission) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="versionId" label="并入版本" width="100">
              <template #default="{ row }">{{ row.versionId != null ? '#' + row.versionId : '—' }}</template>
            </el-table-column>
          </el-table>
          <p class="detail-note">
            变化提成与正常提成同口径：(Δ经销回款 − Δ经销运杂费) × 经销比例 + (Δ零售回款 − Δ零售运杂费) × 零售比例；负数 = 历史数据修正，下次核算抵扣。
            比例按单据归属期间追溯（原期间比例），历史修正不因比例调整漂移。业务员变更产生「追回（原业务员，负数）+ 补给（新业务员，正数）」两条修正；「原业务员」列为变更前归属。
            重复导入同批次数据幂等：基线已刷新，不会重复记录。
          </p>
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>

  <!-- 业务员核算明细下钻 -->
  <el-drawer
    v-model="drawerVisible"
    size="50%"
    :title="detailTitle"
    destroy-on-close
  >
    <div v-loading="detailLoading" class="detail-body">
      <template v-if="detail">
        <el-alert
          v-if="detail.settled"
          type="info"
          :closable="false"
          show-icon
          title="该期间已核算结账"
          class="issue-alert"
        >
          <template #default>
            该期间已核算结账（版本 {{ detail.settledVersionNo }}，核算于 {{ formatDateTime(detail.settledAt) }}）。
            已结账金额请以核算版本为准（含修正提成）；下方单笔明细和试算金额使用当前氚云数据及各笔发生日的比例，仅供核对。
          </template>
        </el-alert>

        <!-- 四段式公式（经销商/零售各扣各的运杂费） -->
        <section class="formula-card">
          <div class="formula-card-head">
            <span class="formula-card-title">核算公式</span>
            <span class="formula-card-sub">{{ detail.period }} 经销商 / 零售分列扣减</span>
          </div>
          <div class="formula-body">
            <div class="formula-row">
              <span class="formula-label">{{ detail.period }} 期间销售额（发货口径）</span>
              <span class="formula-value primary">{{ money(detail.currentMonthSalesAmount) }}</span>
            </div>
            <div class="formula-row sub">
              <span class="formula-label"><span class="dot dot-dealer" />经销 {{ money(detail.dealerSalesAmount) }}<span class="dot dot-retail" />零售 {{ money(detail.retailSalesAmount) }}</span>
              <span></span>
            </div>
            <div class="formula-row sub">
              <span class="formula-label">期间销售出库未收款 {{ money(detail.uncollectedAmount) }}（氚云出库已收 {{ money(detail.collectedAmount) }}，已收占比 {{ ratePct(detail.collectionRate) }}）</span>
              <span></span>
            </div>
            <div class="formula-divider" />

            <div class="formula-row group-dealer">
              <span class="formula-label">{{ detail.period }} 经销商回款（逐笔按收款日比例）</span>
              <span class="formula-value primary">{{ money(detail.dealerReceiptAmount) }}</span>
            </div>
            <div class="formula-row sub">
              <span class="formula-label">减：{{ detail.previousPeriod }} 经销商运杂费</span>
              <span class="formula-value warn">−{{ money(detail.dealerFreightAmount) }}</span>
            </div>
            <div class="formula-row calc">
              <span class="formula-label">→ 经销商提成</span>
              <span class="formula-value">{{ money(detail.dealerCommission) }}</span>
            </div>

            <div class="formula-divider" />

            <div class="formula-row group-retail">
              <span class="formula-label">{{ detail.period }} 零售回款（逐笔按收款日比例）</span>
              <span class="formula-value primary">{{ money(detail.retailReceiptAmount) }}</span>
            </div>
            <div class="formula-row sub">
              <span class="formula-label">减：{{ detail.previousPeriod }} 零售运杂费</span>
              <span class="formula-value warn">−{{ money(detail.retailFreightAmount) }}</span>
            </div>
            <div class="formula-row calc">
              <span class="formula-label">→ 零售提成</span>
              <span class="formula-value">{{ money(detail.retailCommission) }}</span>
            </div>

            <div class="formula-divider strong" />
            <div class="formula-row total">
              <span class="formula-label">期间提成金额</span>
              <span class="formula-value" :class="detail.commissionAmount < 0 ? 'danger' : 'primary'">
                {{ money(detail.commissionAmount) }}
              </span>
            </div>
            <div class="formula-row sub">
              <span class="formula-label">核算基数：{{ detail.period }} 回款 − {{ detail.previousPeriod }} 运杂费（供参考）</span>
              <span class="formula-value">{{ money(detail.commissionBase) }}</span>
            </div>
          </div>
        </section>

        <!-- 本月实际回款明细 -->
        <section class="detail-section">
          <div class="section-head">
            <h3>{{ detail.period }} 期间实际回款明细</h3>
            <span class="section-total">{{ money(detail.currentMonthReceiptAmount) }}</span>
          </div>
          <el-table
:data="detail.receipts"
size="small"
max-height="360"
empty-text="期间无收款明细">
            <el-table-column
              prop="outboundNo"
              label="销售出库"
              min-width="130"
              show-overflow-tooltip>
              <template #default="{ row }">
                <el-link v-if="row.outboundId" type="primary" :underline="false" @click.stop="openLedgerDetail(row.outboundId)">
                  {{ row.outboundNo }}
                </el-link>
                <span v-else>{{ row.outboundNo }}</span>
              </template>
            </el-table-column>
            <el-table-column
prop="customerName"
label="客户"
min-width="130"
show-overflow-tooltip>
              <template #default="{ row }">{{ row.customerName || '—' }}</template>
            </el-table-column>
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.customerType === 'DEALER' ? 'warning' : 'primary'" effect="plain">
                  {{ row.customerType === 'DEALER' ? '经销商' : '零售' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="receiptDate" label="收款日期" width="105">
              <template #default="{ row }">{{ row.receiptDate ? String(row.receiptDate).slice(0, 10) : '—' }}</template>
            </el-table-column>
            <el-table-column
prop="amount"
label="本次回款"
min-width="110"
align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="比例" width="70" align="right">
              <template #default="{ row }">{{ pct(row.rate) }}</template>
            </el-table-column>
            <el-table-column
prop="commission"
label="本笔提成"
min-width="105"
align="right">
              <template #default="{ row }">{{ money(row.commission) }}</template>
            </el-table-column>
          </el-table>
        </section>

        <!-- 上月运杂费明细 -->
        <section class="detail-section">
          <div class="section-head">
            <h3>{{ detail.previousPeriod }} 运杂费明细</h3>
            <span class="section-total warn">{{ money(detail.previousMonthFreightAmount) }}</span>
          </div>
          <el-table
:data="detail.freights"
size="small"
max-height="360"
empty-text="上期无运杂费">
            <el-table-column
              prop="outboundNo"
              label="销售出库"
              min-width="140"
              show-overflow-tooltip>
              <template #default="{ row }">
                <el-link v-if="row.outboundId" type="primary" :underline="false" @click.stop="openLedgerDetail(row.outboundId)">
                  {{ row.outboundNo }}
                </el-link>
                <span v-else>{{ row.outboundNo }}</span>
              </template>
            </el-table-column>
            <el-table-column
prop="customerName"
label="客户"
min-width="140"
show-overflow-tooltip>
              <template #default="{ row }">{{ row.customerName || '—' }}</template>
            </el-table-column>
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.customerType === 'DEALER' ? 'warning' : 'primary'" effect="plain">
                  {{ row.customerType === 'DEALER' ? '经销商' : '零售' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="shipDate" label="发货日期" width="110">
              <template #default="{ row }">{{ row.shipDate ? String(row.shipDate).slice(0, 10) : '—' }}</template>
            </el-table-column>
            <el-table-column
prop="freight"
label="运杂费"
min-width="120"
align="right">
              <template #default="{ row }">{{ money(row.freight) }}</template>
            </el-table-column>
          </el-table>
        </section>

        <p class="detail-note">以上均实时取自销售出库事实数据，单笔按发生日的历史比例计算，不保存核算结果；若与财务手算不一致，请核对收款子表、出库运杂费与比例历史。</p>
      </template>
    </div>
  </el-drawer>

  <!-- 核算版本明细 -->
  <el-dialog
v-model="versionDetailVisible"
:title="versionDetailTitle"
width="1040px"
destroy-on-close>
    <div v-loading="versionDetailLoading" class="version-detail-body">
      <template v-if="versionDetail">
        <div class="version-detail-meta">
          <el-tag size="small" type="info" effect="plain">{{ versionDetail.versionNo }}</el-tag>
          <el-tag v-if="versionDetail.settlementType === 'SUPPLEMENT'" size="small" type="warning" effect="plain">补充核算</el-tag>
          <el-tag v-else-if="versionDetail.settlementType === 'RECALCULATION'" size="small" type="warning">作废重算</el-tag>
          <el-tag v-if="versionDetail.paidFlag === 'PAID'" size="small" type="success">已发放</el-tag>
          <el-tag v-if="versionDetail.status === 'VOID'" size="small" type="info" effect="dark">已作废</el-tag>
          <span>{{ versionDetail.period }} · {{ formatDateTime(versionDetail.calculatedAt) }} · 操作人 {{ versionDetail.createdByName || '—' }}</span>
        </div>
        <p class="detail-note">
          运费扣减窗口：{{ freightModeLabel(versionDetail.freightPeriodMode) }}
          （{{ versionDetail.freightStartDate }} ~ {{ versionDetail.freightEndDate }}）<template v-if="anyMultiRate"> · 含跨比例段业务员，逐段比例见来源明细</template>
        </p>
        <el-table
:data="versionDetail.items"
size="small"
stripe
row-key="employeeId">
          <el-table-column
prop="employeeName"
label="业务员"
width="110"
show-overflow-tooltip />
          <el-table-column label="比例" width="150" align="right">
            <template #default="{ row }">
              <span>{{ rateLabel2(row.dealerRate, row.retailRate) }}</span>
              <el-tooltip v-if="row.multiRateSegments" placement="top" :content="rateSegmentsText(row)">
                <span class="multi-rate-badge">多段</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="未收款考核快照" min-width="190" align="right">
            <template #default="{ row }">
              <div class="cell-main danger">未收 {{ money(row.outstandingAmountSnapshot) }}</div>
              <div class="cell-sub">销售 {{ money(row.salesAmountSnapshot) }} / 已收 {{ money(row.receivedAmountSnapshot) }}</div>
              <div class="cell-sub">回款率 {{ ratePct(row.collectionRateSnapshot) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="正常提成" min-width="180" align="right">
            <template #default="{ row }">
              <div class="cell-main">{{ money(row.baseCommission) }}</div>
              <div class="cell-sub">回款 经销 {{ money(row.baseReceiptDealer) }} / 零售 {{ money(row.baseReceiptRetail) }}</div>
              <div class="cell-sub">运杂费 经销 {{ money(row.baseFreightDealer) }} / 零售 {{ money(row.baseFreightRetail) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="修正提成" min-width="150" align="right">
            <template #default="{ row }">
              <div class="cell-main" :class="{ 'is-negative': row.adjCommission < 0 }">{{ signedMoney(row.adjCommission) }}</div>
              <div class="cell-sub">Δ回款 {{ signedMoney(receiptOf(row.adjReceiptDealer) + receiptOf(row.adjReceiptRetail)) }}</div>
              <div class="cell-sub">Δ运杂费 {{ signedMoney(receiptOf(row.adjFreightDealer) + receiptOf(row.adjFreightRetail)) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="应发合计" min-width="130" align="right">
            <template #default="{ row }">
              <span :class="{ 'is-negative': row.totalCommission < 0 }">{{ money(row.totalCommission) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="version-detail-total">
          本版本应发合计：<strong :class="versionDetail.totalCommission < 0 ? 'is-negative' : 'is-positive'">{{ money(versionDetail.totalCommission) }}</strong>
          <span>（正常提成 {{ money(versionDetail.totalBaseCommission) }} + 修正提成 {{ signedMoney(versionDetail.totalAdjCommission) }}）</span>
        </div>
        <div class="section-head">
          <h3>逐张销售出库未收款快照</h3>
          <span class="section-total">{{ versionDetail.assessmentLineCount }} 张 · 结账时冻结</span>
        </div>
        <el-table :data="versionDetail.assessmentLines" size="small" stripe max-height="360" empty-text="该版本暂无逐单考核快照">
          <el-table-column prop="employeeName" label="业务员" width="100" show-overflow-tooltip />
          <el-table-column prop="outboundNo" label="销售出库" min-width="145" show-overflow-tooltip>
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click.stop="openLedgerDetail(row.outboundId)">{{ row.outboundNo || row.outboundId }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="customerName" label="客户" min-width="120" show-overflow-tooltip />
          <el-table-column prop="attributionDate" label="发货日期" width="105" />
          <el-table-column label="销售额" width="115" align="right"><template #default="{ row }">{{ money(row.salesAmount) }}</template></el-table-column>
          <el-table-column label="已收款" width="115" align="right"><template #default="{ row }">{{ money(row.receivedAmount) }}</template></el-table-column>
          <el-table-column label="未收款" width="115" align="right"><template #default="{ row }">{{ money(row.outstandingAmount) }}</template></el-table-column>
        </el-table>
        <div class="section-head">
          <h3>核算来源明细</h3>
          <span class="section-total">{{ versionDetail.sourceLineCount }} 笔</span>
        </div>
        <el-table :data="versionDetail.sourceLines" size="small" stripe max-height="360" empty-text="旧版本暂无逐笔来源快照">
          <el-table-column prop="employeeName" label="业务员" width="100" show-overflow-tooltip />
          <el-table-column label="类型" width="78">
            <template #default="{ row }">{{ row.lineType === 'RECEIPT' ? '收款' : row.lineType === 'FREIGHT' ? '运杂费' : '调整' }}</template>
          </el-table-column>
          <el-table-column prop="outboundNo" label="销售出库" min-width="135" show-overflow-tooltip>
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click.stop="openLedgerDetail(row.outboundId)">{{ row.outboundNo || row.outboundId }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="customerName" label="客户" min-width="130" show-overflow-tooltip />
          <el-table-column prop="occurrenceDate" label="归属日期" width="105" />
          <el-table-column label="渠道" width="132">
            <template #default="{ row }">
              <span>{{ row.channelType === 'DEALER' ? '经销' : '零售' }}</span>
              <div v-if="row.channelSource" class="cell-sub">
                {{ row.channelSource === 'DEALER_FIELD' ? '填经销商' : row.channelSource === 'MARGIN_FALLBACK' ? '毛利兜底' : '默认零售' }}
              </div>
              <div v-else-if="row.lineType === 'ADJUSTMENT'" class="cell-sub">修正并入</div>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="105" align="right">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="比例" width="72" align="right">
            <template #default="{ row }">{{ pct(row.appliedRate) }}</template>
          </el-table-column>
          <el-table-column label="提成贡献" width="110" align="right">
            <template #default="{ row }"><span :class="{ 'is-negative': row.commissionContribution < 0 }">{{ signedMoney(row.commissionContribution) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="75">
            <template #default="{ row }"><el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '有效' : '已释放' }}</el-tag></template>
          </el-table-column>
        </el-table>
      </template>
    </div>
    <template #footer>
      <div class="version-detail-footer">
        <span class="footer-note">CSV 导出（Excel 可直接打开，含 BOM）：</span>
        <el-button size="small" :disabled="!versionDetail" @click="exportVersionCsv(versionDetail, 'items')">提成发放汇总</el-button>
        <el-button size="small" :disabled="!versionDetail" @click="exportVersionCsv(versionDetail, 'lines')">核算来源明细</el-button>
        <el-button size="small" :disabled="!versionDetail" @click="exportVersionCsv(versionDetail, 'adjustments')">修正调整明细</el-button>
        <el-button size="small" @click="versionDetailVisible = false">关闭</el-button>
      </div>
    </template>
  </el-dialog>

  <!-- 版本对比选择 -->
  <el-dialog
v-model="compareDialogVisible"
title="版本对比（diff = 新版 − 旧版）"
width="520px"
destroy-on-close>
    <el-form label-width="80px">
      <el-form-item label="新版">
        <el-select v-model="compareNewerId" placeholder="选择新版" style="width: 100%">
          <el-option
v-for="v in versions"
:key="v.id"
:label="`${v.versionNo}（${v.period}）`"
:value="v.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="旧版">
        <el-select v-model="compareOlderId" placeholder="选择旧版" style="width: 100%">
          <el-option
v-for="v in versions"
:key="v.id"
:label="`${v.versionNo}（${v.period}）`"
:value="v.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="compareDialogVisible = false">取消</el-button>
      <el-button
type="primary"
:loading="compareLoading"
:disabled="compareNewerId == null || compareOlderId == null || compareNewerId === compareOlderId"
@click="runCompare">
        开始对比
      </el-button>
    </template>
  </el-dialog>

  <!-- 核算结账 -->
  <el-dialog
v-model="settleDialogVisible"
title="核算结账"
width="640px"
destroy-on-close>
    <el-alert
      v-if="settleExistingVersions > 0"
      type="warning"
      :closable="false"
      show-icon
      title="该期间已核算结账"
      class="issue-alert"
    >
      <template #default>
        当前期间（{{ rangeLabel }}）已有 <strong>{{ settleExistingVersions }}</strong> 个核算版本。再次核算属于补充核算，只会占用新增的未核算来源和待处理修正。
      </template>
    </el-alert>
    <el-alert
      v-for="issue in settleIssues"
      :key="issue.type"
      type="warning"
      :closable="false"
      show-icon
      :title="issue.message"
      class="issue-alert"
    />
    <div class="settle-tip">
      本次只核算尚未被有效版本占用的来源明细；日期范围可以与历史批次重叠，同一笔收款或运杂费不会重复进入提成。
    </div>
    <div v-if="settlePreview" class="settle-tip">
      <strong>回款范围 {{ settlePreview.startDate }} ~ {{ settlePreview.endDate }}</strong><br>
      共 {{ settlePreview.totalReceiptLines }} 笔，已核算 {{ settlePreview.settledReceiptLines }} 笔，
      本次待核算 <strong>{{ settlePreview.pendingReceiptLines }}</strong> 笔 / {{ money(settlePreview.pendingReceiptAmount) }}。<br>
      <strong>运杂费范围（{{ freightModeLabel(settlePreview.freightPeriodMode) }}）{{ settlePreview.freightStartDate }} ~ {{ settlePreview.freightEndDate }}</strong><br>
      <template v-if="settlePreview.freightReason">运费范围原因：{{ settlePreview.freightReason }}<br /></template>
      共 {{ settlePreview.totalFreightLines }} 笔，已核算 {{ settlePreview.settledFreightLines }} 笔，
      本次待扣 <strong>{{ settlePreview.pendingFreightLines }}</strong> 笔 / {{ money(settlePreview.pendingFreightAmount) }}。<br>
      待核算基础提成约 <strong>{{ money(settlePreview.pendingBaseCommission) }}</strong>，涉及 {{ settlePreview.employeeCount }} 名业务员；
      另有待核算修正 {{ pendingChangeCount }} 笔，将按归属日期筛选后并入。
      <br><strong>未收款考核快照：</strong>期间销售 {{ money(settlePreview.salesAmountSnapshot) }}，
      氚云已收 {{ money(settlePreview.receivedAmountSnapshot) }}，
      氚云未收 <strong>{{ money(settlePreview.outstandingAmountSnapshot) }}</strong>。
    </div>
    <!-- P3 结账前检查：阻断必须清零才可确认结账；警告可展开查看（不阻断） -->
    <div v-if="settlePreview && (settlePreview.blocking?.length || settlePreview.warnings?.length)" class="settle-checks">
      <div v-if="settlePreview.blocking?.length" class="settle-check-group settle-check-group-block">
        <div class="settle-check-title">结账前检查未通过：{{ settlePreview.blocking.length }} 项阻断，需处理后才能结账</div>
        <div v-for="(b, bi) in settlePreview.blocking" :key="'blk' + bi" class="settle-check-item">
          <span class="check-count">{{ b.count }} 笔</span>
          <span class="check-text">{{ b.message }}</span>
          <span v-if="b.amount" class="check-amt">{{ money(b.amount) }}</span>
        </div>
      </div>
      <div v-if="settlePreview.warnings?.length" class="settle-check-group settle-check-group-warn">
        <div class="settle-check-title settle-check-title-warn" @click="settleWarningsExpanded = !settleWarningsExpanded">
          警告 {{ settlePreview.warnings.length }} 项（不阻断核算）
          <span class="check-expand">{{ settleWarningsExpanded ? '收起' : '展开' }}</span>
        </div>
        <template v-if="settleWarningsExpanded">
          <div v-for="(w, wi) in settlePreview.warnings" :key="'warn' + wi" class="settle-check-item">
            <span class="check-count">{{ w.count }} 笔</span>
            <span class="check-text">{{ w.message }}</span>
            <span v-if="w.amount" class="check-amt">{{ money(w.amount) }}</span>
          </div>
        </template>
      </div>
    </div>
    <el-form label-width="80px">
      <el-form-item label="备注">
        <el-input
v-model="settleRemark"
type="textarea"
:rows="3"
placeholder="选填，记录本次核算说明"
maxlength="500"
show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="settleDialogVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="settleSaving"
        :disabled="settleBlockingCount > 0"
        @click="confirmSettle"
      >确认结账</el-button>
    </template>
  </el-dialog>

  <!-- 比例配置 -->
  <el-dialog
v-model="ratesVisible"
title="提成比例配置"
width="780px"
destroy-on-close>
    <div class="rates-tip">
      默认规则作用于未单独配置的业务员（经销商/零售均 4%）。每笔收款和运杂费按发生日的生效比例计算；修改配置不回算既有来源，也不改变已结账版本的冻结金额。
    </div>
    <el-table
v-loading="ratesLoading"
:data="rates"
size="small"
max-height="460">
      <el-table-column label="业务员" min-width="150">
        <template #default="{ row }">
          <span :class="{ 'default-rate-name': row.employeeId == null }">{{ row.employeeId == null ? '默认规则' : row.employeeName }}</span>
          <el-tag
v-if="row.employeeId == null"
size="small"
type="info"
effect="plain">未单独配置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="经销商比例" width="105" align="right">
        <template #default="{ row }">{{ pct(row.dealerRate) }}</template>
      </el-table-column>
      <el-table-column label="零售比例" width="105" align="right">
        <template #default="{ row }">{{ pct(row.retailRate) }}</template>
      </el-table-column>
      <el-table-column
prop="remark"
label="说明"
min-width="160"
show-overflow-tooltip>
        <template #default="{ row }">{{ row.remark || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEditRate(row)">编辑</el-button>
          <el-button
v-if="row.employeeId != null"
link
type="danger"
@click="removeRate(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="ratesVisible = false">关闭</el-button>
      <el-button type="primary" @click="openCreateRate">新增业务员配置</el-button>
    </template>
  </el-dialog>

  <!-- 编辑/新增比例 -->
  <el-dialog
v-model="rateFormVisible"
:title="editingRate ? '编辑比例配置' : '新增业务员比例配置'"
width="500px"
destroy-on-close>
    <el-form :model="rateForm" label-width="100px">
      <el-form-item v-if="!editingRate" label="业务员" required>
        <el-select
v-model="rateForm.employeeId"
filterable
placeholder="选择业务员（已配置的置灰）"
style="width: 100%">
          <el-option
            v-for="emp in salespersons"
            :key="emp.id"
            :label="emp.name"
            :value="emp.id"
            :disabled="configuredEmpIds.has(emp.id)"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="经销商比例" required>
        <el-input-number
v-model="rateForm.dealerRate"
:min="0"
:max="100"
:step="0.5"
:precision="2"
style="width: 180px" />
        <span class="form-suffix">%（经销商：出库单「选择经销商」非空）</span>
      </el-form-item>
      <el-form-item label="零售比例" required>
        <el-input-number
v-model="rateForm.retailRate"
:min="0"
:max="100"
:step="0.5"
:precision="2"
style="width: 180px" />
        <span class="form-suffix">%（其余订单）</span>
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="rateForm.remark" placeholder="选填，记录配置依据" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="rateFormVisible = false">取消</el-button>
      <el-button type="primary" :loading="rateSaving" @click="saveRate">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown as ArrowDownIcon, Refresh as RefreshIcon, InfoFilled as InfoIcon } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import {
  calculateCommissionVersion,
  compareCommissionVersions,
  createMonthlyCommissionRate,
  deleteMonthlyCommissionRate,
  exportCommissionVersionCsv,
  fetchCommissionCalculationVersionDetail,
  fetchCommissionCalculationVersions,
  fetchCommissionSettlementPreview,
  fetchCommissionOutboundChangeCount,
  fetchCommissionOutboundChanges,
  fetchMonthlyCommissionDetail,
  fetchMonthlyCommissionRates,
  fetchMonthlyCommissionSummary,
  markCommissionVersionPaid,
  updateMonthlyCommissionRate,
  voidCommissionCalculationVersion,
  type CommissionCalculationVersion,
  type CommissionCalculationVersionDetail,
  type CommissionSettlementPreview,
  type CommissionOutboundChange,
  type CommissionVersionCompare,
  type FreightRangeParams,
  type MonthlyCommissionDetail,
  type MonthlyCommissionIssue,
  type MonthlyCommissionRate,
  type MonthlyCommissionRow,
  type MonthlyCommissionSummary,
} from '@/api/monthlyCommission'
import { fetchEmployees } from '@/api/employees'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { notify } from '@/composables/ui/useToast'
import { downloadBlob } from '@/utils/ui/downloadBlob'

const auth = useCurrentUser()
const route = useRoute()
const router = useRouter()

/** 打开销售出库台账详情抽屉（全局宿主 ?orderId=，orderId = sales_outbounds.id）。 */
function openLedgerDetail(outboundId: number) {
  router.push({ query: { ...route.query, orderId: String(outboundId) } })
}

const today = new Date()
/** 核算日期范围（YYYY-MM-DD，含两端），默认本月 1 日 ~ 本月最后一天。 */
const range = ref<string[]>([
  `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-01`,
  `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate()).padStart(2, '0')}`,
])
/** 当前范围显示标签（如 2026-08-01~2026-08-31）。 */
const rangeLabel = computed(() =>
  range.value[0] && range.value[1] ? `${range.value[0]}~${range.value[1]}` : '未选择'
)
/** 运杂费扣减窗口（G4，P2）：默认上一自然月；自定义需起止日期 + 原因（结账/预览强制，审计留痕）。 */
const freightMode = ref<'PREVIOUS_CALENDAR_MONTH' | 'PREVIOUS_EQUAL_PERIOD' | 'CUSTOM'>('PREVIOUS_CALENDAR_MONTH')
const freightCustomRange = ref<string[] | null>(null)
const freightReason = ref('')

/** 组装运费范围请求参数：默认模式无需传参（后端保持旧口径）；等长/自定义才传。 */
function freightParams(): FreightRangeParams | null {
  if (freightMode.value === 'PREVIOUS_CALENDAR_MONTH') return null
  if (freightMode.value === 'CUSTOM') {
    // 自定义模式未填完整（缺起止或原因）时按默认处理，避免选择过程中打断参考视图/预览
    if (freightCustomRange.value?.length !== 2 || !freightReason.value.trim()) return null
    return {
      periodMode: 'CUSTOM',
      startDate: freightCustomRange.value[0],
      endDate: freightCustomRange.value[1],
      reason: freightReason.value.trim(),
    }
  }
  return { periodMode: freightMode.value }
}

/** 运费窗口切换/自定义输入后，月度参考视图按新窗口刷新（结账对话框在打开时实时取值）。 */
function onFreightWindowChange() {
  if (activeTab.value === 'monthly') void reload()
}
const loading = ref(false)
const summary = ref<MonthlyCommissionSummary | null>(null)
/** 视图切换：monthly=月度参考（实时汇总） / versions=核算版本 / changes=修正变化。 */
const activeTab = ref<'monthly' | 'versions' | 'changes'>('monthly')

const items = computed(() => {
  const settled = new Map((summary.value?.settlement.items ?? []).map(row => [Number(row.employeeId), row]))
  const pending = new Map((settlePreview.value?.items ?? []).map(row => [Number(row.employeeId), row]))
  return (summary.value?.items ?? []).map(row => {
    const frozen = settled.get(Number(row.employeeId))
    const waiting = pending.get(Number(row.employeeId))
    return {
      ...row,
      settledCommission: frozen?.settledCommission ?? 0,
      unpaidCommission: frozen?.unpaidCommission ?? 0,
      paidCommission: frozen?.paidCommission ?? 0,
      pendingCommission: waiting?.commissionAmount ?? 0,
    }
  })
})
const issues = computed(() => summary.value?.issues ?? [])

/** 仅可查看多人（财务/老板/销售经理）时显示业务员快捷筛选；业务员本人无筛选，只看到自己。 */
const canViewOthers = computed(() => auth.can(['monthly_commission.read.all', 'monthly_commission.read.department']))
/** 核算结账 / 比例配置写权限（ADMIN / BOSS / FINANCE）。 */
const canManage = computed(() => auth.can('monthly_commission.manage'))

const quickEmployeeId = ref<number | null>(null)

async function reload() {
  if (!range.value[0] || !range.value[1]) {
    notify.warning('请选择核算日期范围')
    return
  }
  loading.value = true
  try {
    summary.value = await fetchMonthlyCommissionSummary(range.value[0], range.value[1], freightParams())
    if (canManage.value) {
      try {
        settlePreview.value = await fetchCommissionSettlementPreview(range.value[0], range.value[1], freightParams())
      } catch {
        settlePreview.value = null
      }
    }
  } catch (e: any) {
    notify.error((e as Error).message || '月度提成核算加载失败')
  } finally {
    loading.value = false
  }
}

// ==================== 核算版本（2026-08-31 财务口径：单据级变化跟踪 + 手动核算结账） ====================
const versions = ref<CommissionCalculationVersion[]>([])
const versionsLoading = ref(false)
const pendingChangeCount = ref(0)

const versionDetailVisible = ref(false)
const versionDetailLoading = ref(false)
const versionDetail = ref<CommissionCalculationVersionDetail | null>(null)
const versionDetailTitle = computed(() => {
  if (!versionDetail.value) return '核算版本明细'
  return `核算版本 ${versionDetail.value.versionNo} · ${versionDetail.value.period}`
})

const compareDialogVisible = ref(false)
const compareLoading = ref(false)
const compare = ref<CommissionVersionCompare | null>(null)
const compareNewerId = ref<number | null>(null)
const compareOlderId = ref<number | null>(null)

const settleDialogVisible = ref(false)
const settleSaving = ref(false)
const settleRemark = ref('')
/** 结账弹窗：该期间已生成的版本数（>0 提示防重复结账）。 */
const settleExistingVersions = ref(0)
/** 结账弹窗：当前期间的数据异常提示（无业务员回款/运杂费、缺收款日期等）。 */
const settleIssues = ref<MonthlyCommissionIssue[]>([])
const settlePreview = ref<CommissionSettlementPreview | null>(null)
/** P3 结账前检查：警告明细是否展开（阻断清单默认直接展示）。 */
const settleWarningsExpanded = ref(false)
/** P3 结账前检查：阻断数（>0 时禁用确认结账；后端在核算入口再次校验）。 */
const settleBlockingCount = computed(() => settlePreview.value?.blocking?.length ?? 0)

async function loadVersions() {
  versionsLoading.value = true
  try {
    versions.value = await fetchCommissionCalculationVersions()
    pendingChangeCount.value = await fetchCommissionOutboundChangeCount('PENDING')
    if (compareNewerId.value != null && !versions.value.some(v => v.id === compareNewerId.value)) compareNewerId.value = null
    if (compareOlderId.value != null && !versions.value.some(v => v.id === compareOlderId.value)) compareOlderId.value = null
  } catch (e: any) {
    notify.error((e as Error).message || '核算版本加载失败')
  } finally {
    versionsLoading.value = false
  }
}

async function openVersionDetail(row: CommissionCalculationVersion) {
  versionDetailVisible.value = true
  versionDetailLoading.value = true
  versionDetail.value = null
  try {
    versionDetail.value = await fetchCommissionCalculationVersionDetail(row.id)
  } catch (e: any) {
    notify.error((e as Error).message || '版本明细加载失败')
  } finally {
    versionDetailLoading.value = false
  }
}

/** 打开版本对比：默认以当前版本为旧版，优先选同期间的最新版为新版（跨期间对比无业务可比性）。 */
function openCompare(row: CommissionCalculationVersion) {
  compareDialogVisible.value = true
  compareOlderId.value = row.id
  const sameRange = versions.value.filter(v => v.id !== row.id && v.startDate === row.startDate && v.endDate === row.endDate)
  compareNewerId.value = sameRange.length > 0 ? sameRange[0].id : null
}

async function runCompare() {
  if (compareNewerId.value == null || compareOlderId.value == null || compareNewerId.value === compareOlderId.value) {
    notify.warning('请选择两个不同的版本')
    return
  }
  const newer = versions.value.find(v => v.id === compareNewerId.value)
  const older = versions.value.find(v => v.id === compareOlderId.value)
  if (newer && older && (newer.startDate !== older.startDate || newer.endDate !== older.endDate)) {
    notify.warning('仅支持同一核算期间内的版本对比（不同期间版本无业务可比性）')
    return
  }
  compareLoading.value = true
  try {
    compare.value = await compareCommissionVersions(compareNewerId.value, compareOlderId.value)
    compareDialogVisible.value = false
  } catch (e: any) {
    notify.error((e as Error).message || '版本对比失败')
  } finally {
    compareLoading.value = false
  }
}

/** 打开核算结账：加载来源占用预览和异常，日期重叠不等于明细重复。 */
async function openSettle() {
  if (!range.value[0] || !range.value[1]) {
    notify.warning('请选择核算日期范围')
    return
  }
  if (freightMode.value === 'CUSTOM') {
    if (!freightCustomRange.value || freightCustomRange.value.length !== 2) {
      notify.warning('自定义运费范围请先选择运费起止日期')
      return
    }
    if (!freightReason.value.trim()) {
      notify.warning('自定义运费范围必须填写原因（审计留痕）')
      return
    }
  }
  settleRemark.value = ''
  settleIssues.value = []
  settlePreview.value = null
  settleWarningsExpanded.value = false
  const sameRange = versions.value.filter(v => v.startDate === range.value[0] && v.endDate === range.value[1])
  // 仅统计仍生效（FINAL）的版本；作废过的版本提示"作废重算"而非"补充核算"
  settleExistingVersions.value = sameRange.filter(v => v.status === 'FINAL').length
  try {
    const fp = freightParams()
    const [s, preview] = await Promise.all([
      fetchMonthlyCommissionSummary(range.value[0], range.value[1], fp),
      fetchCommissionSettlementPreview(range.value[0], range.value[1], fp),
    ])
    settleIssues.value = s.issues ?? []
    settlePreview.value = preview
  } catch (e: any) {
    notify.error((e as Error).message || '结账预检失败')
    return
  }
  const finalsInRange = sameRange.filter(v => v.status === 'FINAL')
  if (finalsInRange.length > 0) {
    try {
      await ElMessageBox.confirm(
        `当前期间（${rangeLabel.value}）已有 ${finalsInRange.length} 个生效核算版本（最新：${finalsInRange[0].versionNo}）。本次只核算尚未占用的来源明细和待处理修正。`,
        '补充核算',
        { type: 'warning', confirmButtonText: '查看补充核算', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  settleDialogVisible.value = true
}

async function confirmSettle() {
  if (!range.value[0] || !range.value[1]) {
    notify.warning('请选择核算日期范围')
    return
  }
  // P3 门禁：阻断项未清零则拒绝（后端核算入口会再次校验，双保险）
  if (settleBlockingCount.value > 0) {
    notify.error(`结账前检查未通过（阻断 ${settleBlockingCount.value} 项），请先处理弹窗中的阻断项后再结账`)
    return
  }
  settleSaving.value = true
  try {
    const sameRange = versions.value.filter(v => v.startDate === range.value[0] && v.endDate === range.value[1])
    const intent = sameRange.some(v => v.status === 'VOID') && !sameRange.some(v => v.status === 'FINAL') ? 'RECALCULATION' : undefined
    const result = await calculateCommissionVersion(
      range.value[0], range.value[1], settleRemark.value.trim() || null, intent, freightParams()
    )
    settleDialogVisible.value = false
    const typeLabel =
      result.settlementType === 'SUPPLEMENT' ? '（补充核算）'
        : result.settlementType === 'RECALCULATION' ? '（作废后重算）' : ''
    notify.success(
      `核算结账完成：${result.versionNo}${typeLabel}，本次核算来源 ${result.settledSourceLines} 笔` +
        `（收款 ${result.settledReceiptLines}、运杂费 ${result.settledFreightLines}、调整 ${result.settledAdjustmentLines ?? 0}），应发合计 ${money(result.totalCommission)}`
    )
    await loadVersions()
  } catch (e: any) {
    notify.error((e as Error).message || '核算结账失败')
  } finally {
    settleSaving.value = false
  }
}

/** 作废核算版本：原因必填（审计留痕）；仅 FINAL 且未发放版本可作废；作废后来源占用释放、修正回滚待核算。 */
async function voidVersion(row: CommissionCalculationVersion) {
  let reason: string
  try {
    const prompt = await ElMessageBox.prompt(
      `作废版本 ${row.versionNo}（${row.period}）后，该版本占用的收款/运杂费/调整明细将释放，修正变化将回滚为「待核算」。已作废版本仍保留审计记录。\n作废原因将写入审计留痕，请如实填写。`,
      '作废核算版本',
      {
        type: 'warning',
        confirmButtonText: '确认作废',
        cancelButtonText: '取消',
        inputPlaceholder: '必填：作废原因（如：回款录入有误，需修正后重新核算）',
        inputValidator: (v: string) => (v && v.trim() ? true : '请填写作废原因'),
      }
    )
    reason = (prompt.value ?? '').trim()
  } catch {
    return
  }
  try {
    const r = await voidCommissionCalculationVersion(row.id, reason)
    notify.success(`版本 ${r.versionNo} 已作废，释放 ${r.releasedSourceLines} 笔来源，回滚 ${r.rolledBackChanges} 笔修正`)
    await loadVersions()
  } catch (e: any) {
    notify.error((e as Error).message || '版本作废失败')
  }
}

/** 标记核算版本提成已发放：发放后该版本不可作废（审计留痕）。 */
async function markPaid(row: CommissionCalculationVersion) {
  try {
    await ElMessageBox.confirm(
      `确认标记版本 ${row.versionNo}（${row.period}）的提成为「已发放」？发放后该版本将不可作废。`,
      '标记提成已发放',
      { type: 'warning', confirmButtonText: '标记已发放', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const r = await markCommissionVersionPaid(row.id)
    notify.success(`版本 ${r.versionNo} 已标记发放`)
    await loadVersions()
  } catch (e: any) {
    notify.error((e as Error).message || '标记发放失败')
  }
}

/** P4 G8：核算版本 CSV 导出下载（版本列表行或详情弹窗触发；row 可空以兼容「详情未加载」状态）。 */
async function exportVersionCsv(row: { id: number; versionNo: string } | null, kind: 'items' | 'lines' | 'adjustments') {
  if (!row) {
    notify.warning('核算版本详情尚未加载，无法导出')
    return
  }
  const label = kind === 'items' ? '提成发放汇总' : kind === 'lines' ? '核算来源明细' : '修正调整明细'
  try {
    const blob = await exportCommissionVersionCsv(row.id, kind)
    downloadBlob(blob, `提成核算_${row.versionNo}_${label}.csv`)
    notify.success(`已导出：${label}`)
  } catch (e: any) {
    notify.error((e as Error).message || '导出失败，请稍后重试')
  }
}

// ==================== 修正变化（已生效出库单，单据级） ====================
const changes = ref<CommissionOutboundChange[]>([])
const changesLoading = ref(false)
const changeStatus = ref<'PENDING' | 'PROCESSED' | ''>('PENDING')
/** 列表仅加载最近一批（按 recorded_at 倒序）；若总条数超过该批，提示用 count 徽标了解全量规模。 */
const CHANGES_PAGE_LIMIT = 2000
const changesTruncated = ref(0)

async function loadChanges() {
  changesLoading.value = true
  try {
    changes.value = await fetchCommissionOutboundChanges(changeStatus.value || undefined, undefined, CHANGES_PAGE_LIMIT)
    const total = await fetchCommissionOutboundChangeCount(changeStatus.value || undefined)
    changesTruncated.value = total > CHANGES_PAGE_LIMIT ? total : 0
  } catch (e: any) {
    notify.error((e as Error).message || '修正变化加载失败')
  } finally {
    changesLoading.value = false
  }
}

/** P4 G8：核算范围快捷选择（本月/上月/本季度），点击即生效并刷新当前视图。 */
const quickRangeKey = ref<'thisMonth' | 'lastMonth' | 'thisQuarter'>('thisMonth')
function onQuickRange() {
  const now = new Date()
  const pad = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  let first: Date
  let last: Date
  if (quickRangeKey.value === 'lastMonth') {
    first = new Date(now.getFullYear(), now.getMonth() - 1, 1)
    last = new Date(now.getFullYear(), now.getMonth(), 0)
  } else if (quickRangeKey.value === 'thisQuarter') {
    const q = Math.floor(now.getMonth() / 3)
    first = new Date(now.getFullYear(), q * 3, 1)
    last = new Date(now.getFullYear(), q * 3 + 3, 0)
  } else {
    first = new Date(now.getFullYear(), now.getMonth(), 1)
    last = new Date(now.getFullYear(), now.getMonth() + 1, 0)
  }
  range.value = [pad(first), pad(last)]
  onPeriodChange()
}

/** 日期范围切换：按当前视图加载对应数据。 */
function onPeriodChange() {
  if (activeTab.value === 'monthly') void reload()
  else if (activeTab.value === 'versions') void loadVersions()
  else void loadChanges()
}

/** Tab 切换。 */
function onTabChange() {
  if (activeTab.value === 'monthly') void reload()
  else if (activeTab.value === 'versions') void loadVersions()
  else void loadChanges()
}

/** 顶部刷新按钮：按当前视图加载。 */
function reloadAll() {
  if (activeTab.value === 'monthly') return reload()
  if (activeTab.value === 'versions') return loadVersions()
  return loadChanges()
}

// ==================== 明细下钻 ====================
const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<MonthlyCommissionDetail | null>(null)

const detailTitle = computed(() => {
  if (!detail.value) return '业务员明细'
  return `${detail.value.employeeName} · ${detail.value.period} 提成核算`
})

async function openDetail(row: MonthlyCommissionRow) {
  await loadDetail(row.employeeId)
}

async function onQuickEmployee(v: number | string | null) {
  if (v == null || v === '') return
  await loadDetail(Number(v))
}

async function loadDetail(employeeId: number) {
  if (!range.value[0] || !range.value[1]) {
    notify.warning('请选择核算日期范围')
    return
  }
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await fetchMonthlyCommissionDetail(employeeId, range.value[0], range.value[1], freightParams())
  } catch (e: any) {
    notify.error((e as Error).message || '业务员明细加载失败')
  } finally {
    detailLoading.value = false
  }
}

// ==================== 比例配置 ====================
const ratesVisible = ref(false)
const ratesLoading = ref(false)
const rates = ref<MonthlyCommissionRate[]>([])
const salespersons = ref<{ id: number; name: string }[]>([])

const configuredEmpIds = computed(() => new Set(rates.value.filter(r => r.employeeId != null).map(r => r.employeeId as number)))

async function openRates() {
  ratesVisible.value = true
  ratesLoading.value = true
  try {
    rates.value = await fetchMonthlyCommissionRates()
    const page = await fetchEmployees({ isSalesperson: true, status: 'ACTIVE', page: 0, size: 200 })
    salespersons.value = ((page as any)?.content ?? []).map((e: any) => ({ id: e.id, name: e.name }))
  } catch (e: any) {
    notify.error((e as Error).message || '比例配置加载失败')
  } finally {
    ratesLoading.value = false
  }
}

async function reloadRates() {
  try {
    rates.value = await fetchMonthlyCommissionRates()
  } catch (e: any) {
    notify.error((e as Error).message || '比例配置刷新失败')
  }
}

const rateFormVisible = ref(false)
const rateSaving = ref(false)
const editingRate = ref<MonthlyCommissionRate | null>(null)
const rateForm = ref({ employeeId: undefined as number | undefined, dealerRate: 4, retailRate: 4, remark: '' })

function openCreateRate() {
  editingRate.value = null
  rateForm.value = { employeeId: undefined, dealerRate: 4, retailRate: 4, remark: '' }
  rateFormVisible.value = true
}

function openEditRate(row: MonthlyCommissionRate) {
  editingRate.value = row
  rateForm.value = {
    employeeId: row.employeeId ?? undefined,
    dealerRate: Number(row.dealerRate) * 100,
    retailRate: Number(row.retailRate) * 100,
    remark: row.remark ?? '',
  }
  rateFormVisible.value = true
}

async function saveRate() {
  if (rateForm.value.dealerRate == null || rateForm.value.retailRate == null) {
    notify.warning('请填写经销商与零售比例')
    return
  }
  rateSaving.value = true
  try {
    if (editingRate.value) {
      await updateMonthlyCommissionRate(editingRate.value.id, {
        dealerRate: rateForm.value.dealerRate / 100,
        retailRate: rateForm.value.retailRate / 100,
        remark: rateForm.value.remark.trim() || null,
      })
      notify.success('比例配置已更新，保存即生效（已结账期间不回算）')
    } else {
      if (rateForm.value.employeeId == null) {
        notify.warning('请选择业务员')
        return
      }
      await createMonthlyCommissionRate({
        employeeId: rateForm.value.employeeId,
        dealerRate: rateForm.value.dealerRate / 100,
        retailRate: rateForm.value.retailRate / 100,
        remark: rateForm.value.remark.trim() || null,
      })
      notify.success('已新增比例配置')
    }
    rateFormVisible.value = false
    await reloadRates()
    await reload()
  } catch (e: any) {
    notify.error((e as Error).message || '保存失败')
  } finally {
    rateSaving.value = false
  }
}

async function removeRate(row: MonthlyCommissionRate) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.employeeName}」的提成比例配置？删除后该业务员按默认规则（4%）计算。`,
      '确认删除',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteMonthlyCommissionRate(row.id)
    notify.success('已删除')
    await reloadRates()
    await reload()
  } catch (e: any) {
    notify.error((e as Error).message || '删除失败')
  }
}

// ==================== 工具 ====================
const money = (v: number | undefined | null) =>
  v === undefined || v === null || isNaN(Number(v)) ? '¥0.00'
    : '¥' + Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** 小数比例 → 百分比文案（0.04 → 4%）。 */
const pct = (v: number | undefined | null) => {
  if (v == null || isNaN(Number(v))) return '—'
  return Number(v) * 100 + '%'
}

/** 收款占比（0~1）→ 百分比文案（0.616 → 61.6%）。 */
const ratePct = (v: number | undefined | null) => {
  if (v == null || isNaN(Number(v))) return '0%'
  const p = Number(v) * 100
  return String(Math.round(p * 10) / 10) + '%'
}

/** 收款进度条颜色：≥80% 绿 / ≥50% 橙 / 低于 50% 红。 */
const progressColor = (r: number | undefined | null) => {
  const rate = r == null || isNaN(Number(r)) ? 0 : Number(r)
  if (rate >= 0.8) return '#67c23a'
  if (rate >= 0.5) return '#e6a23c'
  return '#f56c6c'
}

/** 汇总行比例列：相同只显示一个，不同显示「经销 x% / 零售 y%」。 */
const rateLabel = (row: MonthlyCommissionRow) => {
  if (row.dealerRate === row.retailRate) return pct(row.dealerRate)
  return `经销 ${pct(row.dealerRate)} / 零售 ${pct(row.retailRate)}`
}

/** 比例列（数值入参版本）。 */
const rateLabel2 = (dealer: number | undefined | null, retail: number | undefined | null) => {
  if (dealer == null || retail == null || isNaN(Number(dealer)) || isNaN(Number(retail))) return '—'
  if (dealer === retail) return pct(dealer)
  return `经销 ${pct(dealer)} / 零售 ${pct(retail)}`
}

/** 运费扣减窗口模式文案（G4）。 */
const freightModeLabel = (mode?: string | null) => {
  if (mode === 'PREVIOUS_EQUAL_PERIOD') return '前一个等长周期'
  if (mode === 'CUSTOM') return '自定义范围'
  return '上一自然月'
}

/** 版本内任一业务员存在跨比例段（多段比例提示）。 */
const anyMultiRate = computed(() => {
  const items = versionDetail.value?.items
  return Array.isArray(items) && items.some((i) => (i as Record<string, unknown>).multiRateSegments === true)
})

/** rateSegments 摘要文案（多段比例 tooltip：按生效日逐段的比例与行数）。 */
const rateSegmentsText = (row: Record<string, unknown>): string => {
  const segs = (row.rateSegments as Array<Record<string, unknown>>) || []
  if (!segs.length) return ''
  return segs
    .map((s) => {
      const d = s.dealerRate as number | null
      const r = s.retailRate as number | null
      const dText = d == null || isNaN(Number(d)) ? '—' : Number(d) * 100 + '%'
      const rText = r == null || isNaN(Number(r)) ? '—' : Number(r) * 100 + '%'
      return `${s.effectiveFrom} 起：经销 ${dText} / 零售 ${rText}（${s.sourceLineCount} 行）`
    })
    .join('；')
}

/** 带符号金额：+¥1,234.56 / −¥567.89（负数表示修正需抵扣）。 */
const signedMoney = (v: number | undefined | null) => {
  if (v == null || isNaN(Number(v))) return '¥0.00'
  const n = Number(v)
  const sign = n < 0 ? '−' : n > 0 ? '+' : ''
  return sign + '¥' + Math.abs(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** 金额取值（null/NaN 视为 0），用于加法。 */
const receiptOf = (v: number | undefined | null) => {
  if (v == null || isNaN(Number(v))) return 0
  return Number(v)
}

/** 修正明细的 Δ运杂费 = (after − before) 经销/零售合计。 */
function freightDelta(row: CommissionOutboundChange) {
  return receiptOf(row.afterFreightDealer) - receiptOf(row.beforeFreightDealer) + receiptOf(row.afterFreightRetail) - receiptOf(row.beforeFreightRetail)
}

/** 日期时间展示：ISO → 空格分隔、截取到秒。 */
const formatDateTime = (v: string | null | undefined) => {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 19)
}

await reload()
</script>

<style scoped>
.monthly-commission-page { padding: 0 24px 24px; }
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 16px;
  padding: 18px 20px;
  background: linear-gradient(135deg, var(--color-surface, #fff) 0%, var(--color-surface-alt, #f8fafc) 100%);
  border: 1px solid var(--color-border-light, #e2e8f0);
  border-radius: var(--radius-xl, 12px);
  box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,0.04));
}
.page-title {
  display: grid;
  gap: 4px;
  min-width: 0;
}
.page-head h1 {
  margin: 0;
  font-size: 26px;
  line-height: 1.2;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.title-help {
  margin-left: 2px;
}
.page-head p { margin: 0; color: var(--color-text-muted, #64748b); }
.eyebrow { font-size: 11px; letter-spacing: 0.15em; color: var(--color-primary, #409eff) !important; font-weight: 700; margin: 0; }
.filters {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
  flex-shrink: 0;
}
.filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.filter-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 12px;
}
.filters .filter-date { width: 280px; min-width: 0; }
.filters .filter-employee { width: 180px; min-width: 0; }

@media (max-width: 1100px) {
  .page-head { flex-direction: column; align-items: stretch; gap: 14px; }
  .page-head h1 { font-size: 22px; }
  .filters { align-items: flex-start; }
  .filter-actions { margin-left: 0; margin-top: 4px; }
  .filters .filter-date { width: 100%; max-width: 340px; }
  .filters .filter-employee { width: 160px; }
}

.caliber-alert { margin-bottom: 12px; }
.issue-alert { margin-bottom: 10px; }

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(175px, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}
.kpi {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 12px;
  padding: 16px 18px;
}
.kpi-label { font-size: 12px; color: var(--color-text-muted, #64748b); }
.kpi-main { font-size: 24px; font-weight: 700; margin: 6px 0 4px; font-variant-numeric: tabular-nums; }
.kpi-main.primary { color: var(--color-primary, #409eff); }
.kpi-main.warn { color: var(--color-warning, #e6a23c); }
.kpi-main.danger { color: var(--color-danger, #f56c6c); }
.kpi-sub { font-size: 12px; color: var(--color-text-muted, #64748b); }
.kpi-progress-bar { margin: 8px 0 4px; }

.cell-main.danger { color: var(--color-danger, #f56c6c); }
.progress-cell { margin-bottom: 4px; }

.panel {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 14px;
}
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.panel-head h2 { margin: 0; font-size: 16px; }
.panel-total { font-size: 13px; color: var(--color-text-muted, #64748b); }
.panel-total strong { font-size: 16px; font-variant-numeric: tabular-nums; }

.change-warning { padding: 12px 16px 4px; }
.compare-panel { border-color: var(--color-primary, #409eff); }
.compare-sub { font-size: 12px; color: var(--color-text-muted, #64748b); font-weight: 400; }
.compare-total { margin-bottom: 12px; font-size: 13px; color: var(--color-text-muted, #64748b); }
.compare-total strong { font-size: 16px; margin: 0 4px; font-variant-numeric: tabular-nums; }

.cell-main { font-variant-numeric: tabular-nums; }
.cell-sub { font-size: 12px; color: var(--color-text-muted, #64748b); }
.is-negative { color: var(--color-danger, #f56c6c); }
.is-positive { color: var(--color-success, #67c23a); }
.commission-tabs { margin-bottom: 16px; }

.version-detail-body { padding: 4px 2px 8px; }
.version-detail-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; font-size: 13px; color: var(--color-text-muted, #64748b); }
.version-detail-total { margin-top: 14px; padding: 12px 14px; background: var(--color-surface-subtle, #f1f5f9); border-radius: 10px; font-size: 14px; color: var(--color-text-muted, #64748b); }
.version-detail-total strong { font-size: 18px; margin: 0 4px; font-variant-numeric: tabular-nums; }

.settle-tip { margin-bottom: 16px; padding: 12px 14px; background: var(--color-surface-subtle, #f8fafc); border-radius: 8px; font-size: 13px; color: var(--color-text, #334155); line-height: 1.7; }

/* P3 结账前检查：阻断（红，强制清零）/ 警告（黄，可展开） */
.settle-checks { margin-bottom: 14px; }
.settle-check-group { border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; font-size: 12px; line-height: 1.6; }
.settle-check-group-block { border: 1px solid rgba(245, 108, 108, 0.45); background: rgba(245, 108, 108, 0.08); color: #b54848; }
.settle-check-group-warn { border: 1px solid rgba(230, 162, 60, 0.4); background: rgba(230, 162, 60, 0.08); color: #8a6a2f; }
.settle-check-title { font-weight: 700; margin-bottom: 6px; }
.settle-check-title-warn { display: flex; justify-content: space-between; align-items: center; cursor: pointer; user-select: none; }
.settle-check-item { display: flex; gap: 8px; align-items: baseline; padding: 3px 0; }
.check-count { flex: none; padding: 0 7px; border-radius: 6px; font-weight: 700; background: rgba(245, 108, 108, 0.14); }
.settle-check-group-warn .check-count { background: rgba(230, 162, 60, 0.16); }
.check-text { flex: 1; }
.check-amt { flex: none; white-space: nowrap; font-variant-numeric: tabular-nums; }
.check-expand { font-weight: 400; color: var(--color-text-muted, #64748b); }

.detail-body { padding: 8px 6px 20px; }
.formula-card {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 12px;
  overflow: hidden;
  margin-bottom: 20px;
}
.formula-card-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 12px 18px;
  background: linear-gradient(90deg, var(--color-primary, #409eff), rgba(64, 158, 255, 0.55));
  color: #fff;
}
.formula-card-title { font-size: 15px; font-weight: 700; letter-spacing: 0.02em; }
.formula-card-sub { font-size: 12px; opacity: 0.9; }
.formula-body { padding: 14px 18px 16px; }
.formula-row { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; }
.formula-label { color: var(--color-text, #334155); }
.formula-row.sub { padding: 2px 0 4px 18px; }
.formula-row.sub .formula-label { font-size: 12px; color: var(--color-text-muted, #64748b); }
.formula-row.calc .formula-label { font-size: 12px; }
.formula-row.calc .formula-value { color: var(--color-text, #1e293b); }
.formula-row.group-dealer .formula-label,
.formula-row.group-retail .formula-label { font-weight: 600; }
.formula-row.group-dealer { border-left: 3px solid var(--color-warning, #e6a23c); padding-left: 10px; }
.formula-row.group-retail { border-left: 3px solid var(--color-primary, #409eff); padding-left: 10px; }
.formula-value { font-weight: 700; font-variant-numeric: tabular-nums; }
.formula-value.primary { color: var(--color-primary, #409eff); }
.formula-value.warn { color: var(--color-warning, #e6a23c); }
.formula-value.danger { color: var(--color-danger, #f56c6c); }
.formula-divider { border-top: 1px dashed var(--color-border, #e5e7eb); margin: 8px 0; }
.formula-divider.strong { border-top: 1px solid var(--color-border-strong, #cbd5e1); }
.formula-row.total {
  margin: 10px 0 4px;
  padding: 12px 14px;
  background: var(--color-surface-subtle, #f1f5f9);
  border-radius: 10px;
}
.formula-row.total .formula-label { font-weight: 700; color: var(--color-text, #1e293b); }
.formula-row.total .formula-value { font-size: 20px; }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin: 0 5px 1px 2px; vertical-align: middle; }
.dot-dealer { background: var(--color-warning, #e6a23c); }
.dot-retail { background: var(--color-primary, #409eff); }

.detail-section { margin-bottom: 20px; }
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
.section-head h3 { margin: 0; font-size: 14px; font-weight: 700; color: var(--color-text, #1e293b); }
.section-total { font-weight: 700; color: var(--color-primary, #409eff); font-variant-numeric: tabular-nums; }
.section-total.warn { color: var(--color-warning, #e6a23c); }
.detail-note { margin: 4px 0 0; padding: 10px 12px; font-size: 12px; color: var(--color-text-muted, #64748b); line-height: 1.6; background: var(--color-surface-subtle, #f8fafc); border-radius: 8px; }

.rates-tip { margin-bottom: 12px; font-size: 12px; color: var(--color-text-muted, #64748b); line-height: 1.6; }
.default-rate-name { font-weight: 700; }
.form-suffix { margin-left: 8px; font-size: 12px; color: var(--color-text-muted, #64748b); }

/* 抽屉整体样式 */
::deep(.el-drawer__header) {
  margin-bottom: 12px;
  padding: 16px 20px 12px;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
::deep(.el-drawer__title) {
  font-size: 17px;
  font-weight: 700;
  color: var(--color-text, #1e293b);
}
::deep(.el-drawer__body) {
  padding: 0 24px 24px;
}

/* P2：运费范围选择控件（顶部工具栏） */
.filter-freight { width: 150px; }
.filter-freight-date { width: 252px; }
.filter-freight-reason { width: 230px; }

/* P4 G8：快捷范围 + 版本导出入口 */
.filter-quick-range { margin-right: 12px; }
.ops-export-dropdown { margin: 0 8px; }
.version-detail-footer { display: flex; align-items: center; gap: 8px; }
.version-detail-footer .footer-note { margin-right: auto; font-size: 12px; color: var(--color-text-muted, #64748b); }

/* P2：版本列表多标签行与多段比例提示 */
.tag-line { display: flex; flex-wrap: wrap; justify-content: center; gap: 3px; }
.multi-rate-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 10px;
  font-size: 11px;
  line-height: 18px;
  color: #fff;
  background: var(--color-warning, #e6a23c);
  cursor: help;
}
.version-detail-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--color-text-muted, #64748b);
}
</style>
