<template>
  <div class="sales-orders-page">
    <header class="page-head">
      <div><h2>销售订单</h2><p>先记录客户需求，自产／外采履约评审通过后再审批确认。</p></div>
      <el-button v-if="canManage" type="primary" @click="openCreate">新建订单</el-button>
    </header>
    <el-alert v-if="status === 'DRAFT'" title="需求登记使用销售订单草稿：先补齐客户、产品配置和交付约定，履约评审通过后再提交审批。草稿不形成生产、采购或出库事实。" type="info" :closable="false" />
    <div class="filters">
      <el-input v-model="keyword" placeholder="订单号、客户或产品" clearable @keyup.enter="load(1)" />
      <el-select v-model="status" placeholder="全部状态" clearable @change="load(1)">
        <el-option label="草稿" value="DRAFT" /><el-option label="待审批" value="PENDING_APPROVAL" /><el-option label="已确认" value="CONFIRMED" />
      </el-select>
      <el-button @click="load(1)">查询</el-button>
    </div>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="orderNo" label="订单号" min-width="145" />
      <el-table-column prop="customerName" label="客户" min-width="180" />
      <el-table-column prop="salespersonName" label="业务员" width="110" />
      <el-table-column prop="orderDate" label="订单日期" width="130" />
      <el-table-column prop="requestedDeliveryDate" label="期望交期" width="130" />
      <el-table-column prop="itemCount" label="项目数" width="85" />
      <el-table-column label="金额" width="125"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="statusTag(row.status)">{{ SALES_ORDER_STATUS_TEXT[row.status as SalesOrderStatus] }}</el-tag></template></el-table-column>
      <!-- 驳回后状态回到「草稿」，只看状态看不出被驳回；审批列把它显出来（业务员要按意见改）。 -->
      <el-table-column label="审批" width="90"><template #default="{ row }"><el-tag :type="approvalTag(row.approvalStatus)" size="small">{{ approvalStatusText(row.approvalStatus) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="180" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/sales-orders/${row.id}`)">查看订单</el-button><el-button v-if="canManage" link type="primary" @click="openDetail(row.id)">办理</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="pager" background layout="total, prev, pager, next" :total="total" :page-size="20" :current-page="page" @current-change="load" />

    <el-drawer v-model="drawer" :title="editingId ? `销售订单 ${orderNo}` : '新建销售订单'" size="min(1100px, 96vw)" @opened="onDrawerOpened">
      <div class="order-form">
        <el-alert v-if="pending" title="履约评审已完成，等待销售订单审批；审批前不能修改，也不能生成出库。" type="warning" :closable="false" />
        <el-alert v-else-if="confirmed" :title="`订单已确认（第 ${revisionNo} 版）；执行问题先登记风险与处理方案，按影响选择是否暂停。只在客户承诺变化时办理订单变更；普通交期调整由销售登记客户确认。`" type="success" :closable="false" />
        <el-alert v-else-if="approvalStatus === 'REJECTED'" title="上次提交被驳回，请按审批意见修改后重新提交。" type="error" :closable="false" />
        <div v-if="approvalStatus !== 'NONE'" class="approval-meta">
          <el-tag :type="approvalTag(approvalStatus)" size="small">{{ APPROVAL_STATUS_TEXT[approvalStatus] }}</el-tag>
          <span v-if="submittedAt" class="tip">提交 {{ formatTime(submittedAt) }}</span>
          <span v-if="decidedAt" class="tip">决定 {{ formatTime(decidedAt) }}</span>
        </div>
        <div v-if="approvals.length" class="approval-block">
          <h3>审批记录</h3>
          <el-table :data="approvals" size="small" border>
            <el-table-column label="动作" width="100"><template #default="{ row }">
              <el-tag :type="row.action === 'APPROVE' ? 'success' : row.action === 'REJECT' ? 'danger' : 'info'" size="small">
                {{ approvalActionText(row.action) }}
              </el-tag>
            </template></el-table-column>
            <el-table-column prop="actorName" label="操作人" width="110" />
            <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="意见" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ row.comment || '—' }}</template></el-table-column>
          </el-table>
        </div>
        <el-form label-position="top" class="order-head-form">
          <!-- 客户必须锚定客户档案：订单是唯一需求源，客户身份靠 customerId 往下游传（出库/提成/客户档案）。 -->
          <el-form-item label="客户名称" required>
            <div class="customer-picker">
              <el-select v-model="customerPick" filterable remote clearable :remote-method="searchCustomers"
                :loading="customersLoading" :disabled="locked" placeholder="从客户档案搜索（名称/简称/编号/联系人）"
                @change="onCustomerPicked">
                <el-option v-for="c in customerChoices" :key="c.id"
                  :label="c.customerCode ? `${c.customerName} · ${c.customerCode}` : c.customerName" :value="c.id" />
                <template #empty><span class="tip">客户档案里没有这个客户 —— 点右侧「新增客户」建档</span></template>
              </el-select>
              <el-button v-if="!locked && canManage" @click="openCustomerCreate">新增客户</el-button>
            </div>
            <p v-if="!locked && !orderCustomerId && form.customerName" class="tip warn">
              该订单客户「{{ form.customerName }}」未关联客户档案；保存前请从档案中选择，或点「新增客户」建档。
            </p>
          </el-form-item>
          <el-form-item label="订单日期"><el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" :disabled="locked" /></el-form-item>
          <el-form-item label="期望交期"><el-date-picker v-model="form.requestedDeliveryDate" type="date" value-format="YYYY-MM-DD" clearable :disabled="locked" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.remark" :disabled="locked" maxlength="1000" /></el-form-item>
        </el-form>
        <div class="line-heading"><h3>订单明细</h3><el-button v-if="!locked" @click="addLine">添加项目</el-button></div>
        <el-table :data="form.lines" border>
          <el-table-column label="产品及配置" min-width="280"><template #default="{ row, $index }">
            <el-input v-model="row.productName" :disabled="locked || row.supplyMode === 'MAKE'"
              :placeholder="row.supplyMode === 'MAKE' ? '点击下方配置自产产品' : '产品名称 / 暂定名称'" />
            <el-input v-model="row.specification" :disabled="locked" placeholder="规格配置要求" class="line-second" />
            <div v-if="row.supplyMode === 'MAKE'" class="line-config">
              <el-button v-if="!locked" link type="primary" @click="openProductionConfig($index)">
                {{ row.productionConfig ? '修改自产配置' : '配置自产产品' }}
              </el-button>
              <span v-if="row.productionConfig" class="tip">{{ productionSummary(row.productionConfig) }}</span>
              <span v-if="row.productionConfig" class="tip">制程品类：{{ row.processCategory || row.productionConfig.productName }}</span>
              <el-button v-if="row.manufacturingOrderId && canReadTasks" link type="primary"
                @click="router.push({ path: '/local-manufacturing-execution', query: { taskId: String(row.manufacturingOrderId), sourceOrderId: String(editingId) } })">
                查看 MO{{ String(row.manufacturingOrderId).padStart(8, '0') }}
              </el-button>
              <span v-else-if="locked" class="tip">待下达生产任务</span>
            </div>
            <div v-if="row.supplyMode === 'BUY'" class="line-config">
              <el-button v-if="!locked || row.purchaseConfig" link type="primary" @click="openPurchaseConfig($index)">{{ locked ? '查看外采需求' : row.purchaseConfig ? '修改外采需求' : '配置外采需求' }}</el-button>
              <span v-if="row.purchaseConfig" class="tip">{{ [row.purchaseConfig.model, row.purchaseConfig.brand].filter(Boolean).join(' · ') || '已记录技术/验收要求' }}</span>
            </div>
            <!-- 现货：选不到货 / 数量超过可售库存就是缺口，当场改自产并建生产任务单（09-24 LQ 定） -->
            <div v-if="row.supplyMode === 'STOCK'" class="line-config">
              <el-button v-if="!locked && (!row.salesProductId || stockShortageOf(row) > 0)"
                link type="primary" @click="switchToMake($index)">没有现货？改为自产并配置产品</el-button>
              <span v-if="stockShortageOf(row) > 0" class="tip warn">
                可售库存只有 {{ stockAvailableOf(row) }} {{ row.unit }}，缺口 {{ stockShortageOf(row) }}
              </span>
            </div>
          </template></el-table-column>
          <el-table-column label="供货方式" width="145"><template #default="{ row }"><el-select v-model="row.supplyMode" :disabled="locked" @change="onSupplyModeChange(row)"><el-option label="现货" value="STOCK" /><el-option label="自产" value="MAKE" /><el-option label="外采" value="BUY" /><el-option label="待确定" value="UNDECIDED" /></el-select></template></el-table-column>
          <el-table-column label="产品档案" min-width="250"><template #default="{ row }">
            <!-- 现货只能从「可售库存」里选：有货才选得到。选不到就是「该下生产任务单」的业务信号。 -->
            <el-select v-if="row.supplyMode === 'STOCK'" v-model="row.salesProductId" filterable remote clearable
              :remote-method="searchStock" :loading="stockLoading" :disabled="locked"
              placeholder="从可售库存选（按产品合计可用量）" @change="onStockPicked(row)">
              <el-option v-for="s in stockOptionsFor(row)" :key="s.id" :value="s.id"
                :label="`${s.productName} · ${s.specification || '无规格'} · 可用 ${s.available}${s.unit || ''}`" />
              <template #empty><span class="tip">可售库存里没有匹配的产品 —— 没有现货就该下生产任务单</span></template>
            </el-select>
            <el-select v-else v-model="row.salesProductId" filterable remote clearable :remote-method="searchProducts" :loading="productsLoading" :disabled="locked || row.supplyMode === 'MAKE'" :placeholder="row.supplyMode === 'MAKE' ? '自产配置确定产品' : '可先不选'">
              <el-option v-for="p in productOptionsFor(row)" :key="p.id" :label="`${p.productName} · ${p.productCode || p.id}`" :value="p.id" />
            </el-select>
          </template></el-table-column>
          <el-table-column label="数量" width="110"><template #default="{ row }"><el-input-number v-model="row.quantity" :disabled="locked" :min="0.000001" :precision="3" :controls="false" /></template></el-table-column>
          <el-table-column label="单位" width="90"><template #default="{ row }"><el-input v-model="row.unit" :disabled="locked" /></template></el-table-column>
          <el-table-column label="单价" width="115"><template #default="{ row }"><el-input-number v-model="row.unitPrice" :disabled="locked" :min="0" :precision="2" :controls="false" /></template></el-table-column>
          <el-table-column v-if="confirmed" label="发货进度" min-width="170"><template #default="{ row }">已发 {{ Number(row.shippedQuantity || 0) }}<div class="tip">草稿待发 {{ Number(row.allocatedQuantity || 0) - Number(row.shippedQuantity || 0) }} · 未生成 {{ Math.max(0, Number(row.quantity) - Number(row.allocatedQuantity || 0)) }}</div></template></el-table-column>
          <el-table-column label="操作" width="170"><template #default="{ row, $index }">
            <el-button v-if="!locked" link type="danger" @click="form.lines.splice($index, 1)">删除</el-button>
            <el-button v-if="confirmed && row.id && canManage && !isLineHeld(row.id) && Number(row.quantity) > Number(row.allocatedQuantity || 0)" link type="primary" @click="openOutbound(row)">办理发货</el-button>
            <el-button v-if="confirmed && row.supplyMode === 'BUY' && row.id && canManagePurchase && !isLineHeld(row.id)" link type="primary" @click="router.push({path:'/purchase-orders',query:{demandItemId:String(row.id),sourceOrderId:String(editingId)}})">办理采购</el-button>
            <el-button v-if="confirmed && row.id && canManage && !executionExceptions.some(item => item.itemId === row.id && item.status === 'OPEN')" link type="warning" @click="reportException(row)">登记风险／问题</el-button>
            <el-tag v-if="row.id && isLineHeld(row.id)" type="danger" size="small">暂停办理</el-tag>
          </template></el-table-column>
        </el-table>
        <template v-if="confirmed && shipments.length">
          <h3>关联出库记录</h3>
          <el-table :data="shipments" border size="small">
            <el-table-column label="出库单" width="150"><template #default="{ row }"><el-button link type="primary" @click="router.push({path:'/sales-outbounds',query:{outboundId:String(row.outboundId),sourceOrderId:String(editingId)}})">{{ row.outboundNo }}</el-button></template></el-table-column>
            <el-table-column label="状态" width="95"><template #default="{ row }">{{ ({ DRAFT:'草稿待发', POSTED:'已过账', CANCELLED:'已取消', REVERSED:'已冲销' } as Record<string,string>)[row.status] || row.status }}</template></el-table-column>
            <el-table-column prop="itemId" label="原明细 ID" width="100" />
            <el-table-column prop="productName" label="实际发货产品" min-width="180" />
            <el-table-column prop="quantity" label="数量" width="90" />
            <el-table-column prop="warehouseName" label="仓库" width="130" />
            <el-table-column label="实物来源" min-width="150"><template #default="{ row }">{{ row.sourceLotNo || row.sourcePurchaseNo || '库存／代发' }}</template></el-table-column>
          </el-table>
        </template>
        <div v-if="editingId && form.lines.some(line => line.supplyMode === 'MAKE' || line.supplyMode === 'BUY')" class="approval-block">
          <h3>确认前履约评审</h3>
          <p class="tip">车间评审自产、采购评审外采。评审通过后才可提交销售审批；修改配置、数量或交期会使原意见失效。</p>
          <el-alert v-if="hasInvalidFulfillmentReview" type="error" :closable="false" show-icon
            title="有履约评审意见已失效"
            description="下方标红的评审意见已与当前订单配置不一致（content_hash 不匹配）。必须点底部「保存并送履约评审」重新送评，否则「提交审批」会被就绪度拦下。" />
          <el-table :data="fulfillmentReviews" size="small" border :row-class-name="fulfillmentRowClass">
            <el-table-column label="项目" min-width="180"><template #default="{ row }">{{ row.lineNo }}. {{ row.productName }}</template></el-table-column>
            <el-table-column label="评审岗位" width="100"><template #default="{ row }">{{ row.supplyMode === 'MAKE' ? '车间' : '采购' }}</template></el-table-column>
            <el-table-column label="结论" width="160"><template #default="{ row }"><el-tag :type="row.reviewDecision && !row.reviewCurrent ? 'danger' : row.reviewDecision === 'ACCEPTED' ? 'success' : row.reviewDecision === 'CHANGE_REQUIRED' ? 'danger' : 'info'">{{ row.reviewDecision && !row.reviewCurrent ? '已失效 · 必须重评' : row.reviewDecision === 'ACCEPTED' ? '通过' : row.reviewDecision === 'CHANGE_REQUIRED' ? '需要调整' : '待评审' }}</el-tag></template></el-table-column>
            <el-table-column prop="reviewerName" label="评审人" width="110" />
            <el-table-column label="意见" min-width="220"><template #default="{ row }">{{ row.reviewComment || '—' }}<span v-if="row.proposedDeliveryDate">；建议交期 {{ row.proposedDeliveryDate }}</span></template></el-table-column>
          </el-table>
          <p v-if="fulfillmentReviewRequestedAt" class="tip">最近送评：{{ formatTime(fulfillmentReviewRequestedAt) }}</p>
          <el-collapse v-if="fulfillmentReviewHistory.length">
            <el-collapse-item title="查看历次履约评审意见" name="history">
              <el-table :data="fulfillmentReviewHistory" size="small" border>
                <el-table-column label="明细 ID" prop="itemId" width="95" />
                <el-table-column label="岗位" width="80"><template #default="{ row }">{{ row.supplyMode === 'MAKE' ? '车间' : '采购' }}</template></el-table-column>
                <el-table-column label="结论" width="110"><template #default="{ row }">{{ row.decision === 'ACCEPTED' ? '通过' : '需要调整' }}</template></el-table-column>
                <el-table-column prop="reviewerName" label="评审人" width="110" />
                <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatTime(row.reviewedAt) }}</template></el-table-column>
                <el-table-column label="意见" min-width="220"><template #default="{ row }">{{ row.comment || '—' }}</template></el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </div>
        <div ref="collaborationSection" v-if="confirmed && (executionExceptions.length || orderChanges.length)" class="approval-block">
          <h3>执行异常与订单变更</h3>
          <el-alert type="warning" :closable="false" show-icon
            title="变更发起能力的统一入口在订单协作页（/sales-orders/{id} →「协作事项与客户变更」→ 处理客户方案与变更）"
            description="下方表格为本订单已登记的异常与变更记录。订单协作页提供只读概览并跳转本抽屉办理；本抽屉保留发起按钮是为已在此办理的用户便利，避免跨页跳转中断流程。" />
          <el-alert title="风险提醒允许继续办理；只有明确选择暂停的记录才阻止本明细后续下达及发货。内部解决不改变客户订单；客户承诺变化才办理变更。" type="info" :closable="false" />
          <el-table v-if="executionExceptions.length" :data="executionExceptions" size="small" border style="margin-top: 12px">
            <el-table-column prop="itemId" label="明细 ID" width="95" />
            <el-table-column label="状态" width="95"><template #default="{ row }"><el-tag :type="row.status === 'OPEN' ? 'danger' : 'success'">{{ row.status === 'OPEN' ? (row.pauseFollowup === false || row.pauseFollowup === 0 ? '风险提醒' : '后续暂停') : '已解决' }}</el-tag></template></el-table-column>
            <el-table-column prop="reason" label="异常原因" min-width="190" />
            <el-table-column prop="proposedAction" label="处理方案" min-width="150" /><el-table-column prop="proposedDeliveryDate" label="执行方可行交期" width="130" />
            <el-table-column prop="reportedByName" label="登记人" width="100" />
            <el-table-column label="操作" width="180"><template #default="{ row }">
              <el-button v-if="row.status === 'OPEN' && canManage" link type="primary" @click="openChangeProposal(row)">协商其他数量／交期</el-button>
              <el-button v-if="row.status === 'OPEN' && row.proposedDeliveryDate && canManage" link type="success" @click="acceptExecutionDate(row)">客户接受原交期方案</el-button>
              <el-button v-if="row.status === 'OPEN' && (canManage || canApprove)" link type="success" @click="resolveException(row)">原约定不变，解决问题</el-button>
            </template></el-table-column>
          </el-table>
          <el-table v-if="orderChanges.length" :data="orderChanges" size="small" border style="margin-top: 12px">
            <el-table-column prop="id" label="变更号" width="90" />
            <el-table-column prop="itemId" label="明细 ID" width="90" />
            <el-table-column label="数量" width="120"><template #default="{ row }">{{ row.originalQuantity }} → {{ row.proposedQuantity }}</template></el-table-column>
            <el-table-column label="交期" min-width="190"><template #default="{ row }">{{ row.originalDeliveryDate || form.requestedDeliveryDate || '未填' }} → {{ row.proposedDeliveryDate || form.requestedDeliveryDate || '未填' }}</template></el-table-column>
            <el-table-column prop="customerConfirmationRef" label="客户确认依据" min-width="160" />
            <el-table-column label="受影响单据" min-width="210"><template #default="{ row }"><div v-for="document in row.downstreamImpact || []" :key="`${document.documentType}-${document.id}`">{{ document.documentNo }} · {{ document.status }}</div><span v-if="!row.downstreamImpact?.length">尚无下游单据</span></template></el-table-column>
            <el-table-column label="执行可行性确认" min-width="200"><template #default="{ row }">{{ row.reviewedByName || '—' }}<div>{{ row.reviewComment || '暂无意见' }}</div><el-popover v-if="row.reviewedDownstreamImpact?.length" trigger="click" :width="620"><template #reference><el-button link type="primary">查看复评时单据快照</el-button></template><el-table :data="row.reviewedDownstreamImpact" size="small"><el-table-column prop="documentNo" label="单据" /><el-table-column prop="status" label="当时状态" /><el-table-column prop="executedQuantity" label="当时入库／收货量" /><el-table-column prop="reportedQuantity" label="当时报工量合计" /><el-table-column prop="deliveryDate" label="当时交期" /></el-table></el-popover><el-tag v-if="['PENDING_APPROVAL','PENDING_CUSTOMER'].includes(row.status) && row.reviewedByName && !row.reviewImpactCurrent" type="warning">执行情况已变化，需重新复评</el-tag></template></el-table-column>
            <el-table-column label="状态" width="110"><template #default="{ row }">{{ changeStatusText(row.status) }}</template></el-table-column>
            <el-table-column label="操作" width="240"><template #default="{ row }">
              <template v-if="['PENDING_APPROVAL','PENDING_CUSTOMER'].includes(row.status)">
                <el-button v-if="canManage && ['MAKE', 'BUY'].includes(form.lines.find(item => item.id === row.itemId)?.supplyMode || '')" link type="primary" @click="resendChangeReview(row)">重新送复评</el-button>
                <el-button v-if="canManage && Number(row.originalQuantity) === Number(row.proposedQuantity)" link type="success" @click="confirmCustomerDate(row)">登记客户接受并生效</el-button>
                <template v-if="canApprove && row.status === 'PENDING_APPROVAL' && Number(row.originalQuantity) !== Number(row.proposedQuantity)"><el-button link type="success" @click="decideChange(row, true)">批准商业变更</el-button><el-button link type="danger" @click="decideChange(row, false)">驳回</el-button></template>
              </template>
              <el-button v-if="canManage && ['PENDING_REVIEW','PENDING_APPROVAL','PENDING_CUSTOMER'].includes(row.status)" link type="warning" @click="withdrawChange(row)">客户未接受／撤回</el-button>
            </template></el-table-column>
          </el-table>
        </div>
        <p class="tip">自产、外采和定制需求可以先不关联最终库存产品；确认订单后保留每个项目的独立明细 ID。</p>

        <!-- 就绪度：常驻显示「还差什么才能送评 / 提交审批」。判据与后端提交校验同源（见组件注释）。 -->
        <SalesOrderReadinessPanel v-if="editingId && orderStatus === 'DRAFT'" :order-id="editingId"
          :refresh-key="readinessRefresh"
          @update:review-ready="readinessReviewReady = $event"
          @update:submit-ready="readinessSubmitReady = $event" />
      </div>
      <template #footer>
        <el-button @click="drawer = false">关闭</el-button>
        <el-button v-if="canManage && orderStatus === 'DRAFT'" :loading="saving" @click="save">保存草稿</el-button>
        <!-- 未就绪时置灰 + tooltip 说明为什么；就绪度由后端同一判据给出，不会出现「提示说可以、提交却被打回」 -->
        <el-tooltip :disabled="readinessReviewReady" content="还有未完成的配置项，见上方就绪度提示" placement="top">
          <span>
            <el-button v-if="canManage && editingId && orderStatus === 'DRAFT' && form.lines.some(line => line.supplyMode === 'MAKE' || line.supplyMode === 'BUY')"
              :loading="saving" :disabled="!readinessReviewReady" @click="requestReview">保存并送履约评审</el-button>
          </span>
        </el-tooltip>
        <el-tooltip :disabled="readinessSubmitReady" content="还差履约评审或其他配置项，见上方就绪度提示" placement="top">
          <span>
            <el-button v-if="canManage && editingId && orderStatus === 'DRAFT'" type="primary" :loading="saving"
              :disabled="!readinessSubmitReady" @click="submit">提交审批</el-button>
          </span>
        </el-tooltip>
        <el-button v-if="canApprove && pending" type="success" :loading="saving" @click="approve">审批通过</el-button>
        <el-button v-if="canApprove && pending" type="danger" :loading="saving" @click="reject">驳回</el-button>
      </template>
    </el-drawer>

    <ProductionTaskOrderDrawer v-model="productionDialog" mode="configure" :initial="configuringLine?.productionConfig" @configured="applyProductionConfig" />

    <!-- 就地建档：档案里没有这个客户时不用先跑去客户档案页，建完直接选中 -->
    <el-dialog v-model="customerDialog" title="新增客户档案" width="min(460px, 94vw)">
      <el-alert title="只填客户名称即可建档（其余资料之后在客户档案补全）。若档案里已有同名客户，会直接选中那一条，不会重复建档。" type="info" :closable="false" />
      <el-form label-position="top" style="margin-top: 12px">
        <el-form-item label="客户名称" required>
          <el-input v-model="newCustomerName" maxlength="200" show-word-limit placeholder="客户全称"
            @keyup.enter="createCustomerAndPick" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="customerDialog = false">取消</el-button>
        <el-button type="primary" :loading="creatingCustomer" @click="createCustomerAndPick">建档并选中</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="purchaseDialog" title="配置外采需求" width="min(650px, 94vw)">
      <el-alert title="销售在这里确定客户要什么；供应商、采购价格和到货/直发方式由采购环节确定。订单确认后这些需求不能直接改动。" type="info" :closable="false" />
      <el-form label-position="top" :disabled="locked" style="margin-top: 12px">
        <el-form-item label="产品名称" required><el-input v-model="purchaseForm.productName" maxlength="500" /></el-form-item>
        <el-form-item label="规格/型号要求"><el-input v-model="purchaseForm.specification" maxlength="1000" /></el-form-item>
        <el-form-item label="型号"><el-input v-model="purchaseForm.model" maxlength="200" /></el-form-item>
        <el-form-item label="品牌要求"><el-input v-model="purchaseForm.brand" maxlength="200" /></el-form-item>
        <el-form-item label="技术要求"><el-input v-model="purchaseForm.technicalRequirements" type="textarea" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="验收要求"><el-input v-model="purchaseForm.acceptanceCriteria" type="textarea" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="包装要求"><el-input v-model="purchaseForm.packagingRequirements" maxlength="500" /></el-form-item>
        <el-form-item label="交付要求"><el-input v-model="purchaseForm.deliveryRequirements" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="purchaseDialog=false">关闭</el-button><el-button v-if="!locked" type="primary" @click="applyPurchaseConfig">保存到订单明细</el-button></template>
    </el-dialog>

    <SalesExecutionExceptionDialog ref="exceptionDialog" @saved="onExceptionSaved" />
    <el-dialog v-model="changeDialog" title="申请已确认订单变更" width="min(560px, 94vw)">
      <el-alert title="如客户接受执行方原交期方案，请直接使用该方案的确认入口。客户提出不同方案时才重新确认执行可行性；交期调整不再重复商业审批。数量变更保留商业审批。配置、价格、取消及已有下游单据的数量变更尚不支持。" type="warning" :closable="false" />
      <el-form label-position="top" style="margin-top: 14px">
        <el-form-item label="新数量" required><el-input-number v-model="changeForm.quantity" :min="0.000001" :precision="3" /></el-form-item>
        <el-form-item label="新交期"><el-date-picker v-model="changeForm.deliveryDate" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
        <el-form-item label="客户沟通记录" required><el-input v-model="changeForm.customerConfirmationRef" type="textarea" maxlength="500" placeholder="例如：补充协议编号、客户确认日期与记录位置" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="changeDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitChangeProposal">提交变更申请</el-button></template>
    </el-dialog>

    <OrderDeliveryDialog v-model="outboundDialog" :order-id="editingId || 0" :line="outboundItem" @created="onOutboundCreated" />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { notify } from '@/composables/ui/useToast'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { fetchAvailableStock, newRequestKey } from '@/api/salesOutbound'
import ProductionTaskOrderDrawer from '@/components/production/ProductionTaskOrderDrawer.vue'
import OrderDeliveryDialog from '@/components/sales/OrderDeliveryDialog.vue'
import SalesExecutionExceptionDialog from '@/components/sales/SalesExecutionExceptionDialog.vue'
import { fetchSalesOrders, fetchSalesOrder, createSalesOrder, updateSalesOrder,
  submitSalesOrderApproval, approveSalesOrder, rejectSalesOrder,
  requestSalesFulfillmentReview, fetchSalesFulfillmentReviewStatus, fetchSalesFulfillmentReviewHistory,
  fetchSalesExecutionExceptions, fetchSalesOrderChanges, acceptSalesDeliveryProposal, confirmSalesDeliveryChange, withdrawSalesOrderChange,
  proposeSalesOrderChange, resolveSalesExecutionException, decideSalesOrderChange, requestSalesChangeReReview,
  SALES_ORDER_STATUS_TEXT,
  searchOrderProducts,
  type SalesOrder, type SalesOrderLine, type SalesOrderStatus, type ProductionDemandConfig, type PurchaseDemandConfig,
  type FulfillmentReviewLine, type FulfillmentReviewHistory, type SalesExecutionException,
  type SalesOrderChange,  type SalesOrderShipment } from '@/api/salesOrder'
import { APPROVAL_STATUS_TEXT, APPROVAL_ACTION_TEXT, type ApprovalEntry, type ApprovalStatus } from '@/api/approval'
import { searchCustomerOptions, quickCreateCustomer } from '@/api/customer'
import SalesOrderReadinessPanel from '@/components/sales/SalesOrderReadinessPanel.vue'

// 就绪度：判据来自后端 GET /erp-sales-orders/{id}/readiness，与提交时的校验是**同一份实现**，
// 所以面板说「可以提交」就一定能提交。ready* 为 false 时置灰对应按钮，避免「先点、再被打回」。
const readinessReviewReady = ref(true)
const readinessSubmitReady = ref(true)
// 任何会让就绪度变化的动作（保存 / 送评 / 评审裁决）之后 +1 重新拉取；集中在 openDetail 里 bump。
const readinessRefresh = ref(0)

const router = useRouter()
const route = useRoute()
const auth = useCurrentUser()
const canManage = computed(() => auth.can('sales_order.manage'))
const canManagePurchase = computed(() => auth.can('purchase_order.manage'))
/** 审批是**独立权限**（#35）：有管理权 ≠ 有审批权，故按钮各按各的权限显示。 */
const canApprove = computed(() => auth.can('sales_order.approve'))
const canReadTasks = computed(() => ['manufacturing.manage', 'manufacturing.report', 'manufacturing.inspect', 'manufacturing.report.override', 'manufacturing.receive', 'manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all'].some(permission => auth.can(permission)))
const keyword = ref('')
const status = ref(['DRAFT','PENDING_APPROVAL','CONFIRMED'].includes(String(route.query.status)) ? String(route.query.status) : '')
const page = ref(1)
const total = ref(0)
const items = ref<SalesOrder[]>([])
const loading = ref(false)
const drawer = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const orderNo = ref('')
const orderStatus = ref<SalesOrderStatus | ''>('')
const confirmed = computed(() => orderStatus.value === 'CONFIRMED')
const pending = computed(() => orderStatus.value === 'PENDING_APPROVAL')
/** 已提交或已确认都不许改：待审批时改单，等于老板审的不是同一张单。 */
const locked = computed(() => confirmed.value || pending.value)
const approvals = ref<ApprovalEntry[]>([])
const fulfillmentReviews = ref<FulfillmentReviewLine[]>([])
const fulfillmentReviewHistory = ref<FulfillmentReviewHistory[]>([])
const fulfillmentReviewRequestedAt = ref<string | null>(null)
/** 是否存在失效的履约评审意见（改了配置/数量/交期后 hash 不匹配）。用于在抽屉里强提示：必须重新送评才能提交审批。 */
const hasInvalidFulfillmentReview = computed(() => fulfillmentReviews.value.some(r => r.reviewDecision && !r.reviewCurrent))
/** 表格行类：失效行整行红色背景，强化视觉提示。 */
const fulfillmentRowClass = ({ row }: { row: FulfillmentReviewLine }) => (row.reviewDecision && !row.reviewCurrent ? 'review-invalid-row' : '')
const revisionNo = ref(1)
const executionExceptions = ref<SalesExecutionException[]>([])
const orderChanges = ref<SalesOrderChange[]>([])
const exceptionDialog = ref<InstanceType<typeof SalesExecutionExceptionDialog>>()
const changeDialog = ref(false)
const changeException = ref<SalesExecutionException | null>(null)
const changeForm = reactive({ quantity: 1, deliveryDate: '', customerConfirmationRef: '' })
/** 审批态是独立于业务状态的一列（驳回后 status 回到草稿，只有它记得被驳回）。 */
const approvalStatus = ref<ApprovalStatus>('NONE')
const submittedAt = ref<string | null>(null)
const decidedAt = ref<string | null>(null)
const form = reactive<{ customerName: string; orderDate: string; requestedDeliveryDate: string; remark: string; lines: SalesOrderLine[] }>({ customerName: '', orderDate: '', requestedDeliveryDate: defaultDeliveryDate(), remark: '', lines: [] })
const products = ref<Array<{ id: number; productCode: string; productName: string; specification?: string; unit: string }>>([])
const productsLoading = ref(false)
const shipments = ref<SalesOrderShipment[]>([])
const outboundDialog = ref(false)
const outboundItem = ref<SalesOrderLine | null>(null)
const saveKey = ref('')
const productionDialog = ref(false)
const configuringLine = ref<SalesOrderLine | null>(null)
const purchaseDialog = ref(false)
const purchasingLine = ref<SalesOrderLine | null>(null)
const purchaseForm = reactive<PurchaseDemandConfig & {productName:string;specification:string}>({productName:'',specification:'',model:'',brand:'',technicalRequirements:'',acceptanceCriteria:'',packagingRequirements:'',deliveryRequirements:''})
function openPurchaseConfig(index:number) {
  purchasingLine.value=form.lines[index] || null
  if(!purchasingLine.value)return
  Object.assign(purchaseForm,{productName:purchasingLine.value.productName,specification:purchasingLine.value.specification || '',model:'',brand:'',technicalRequirements:'',acceptanceCriteria:'',packagingRequirements:'',deliveryRequirements:'',...purchasingLine.value.purchaseConfig})
  purchaseDialog.value=true
}
function applyPurchaseConfig() {
  if(!purchasingLine.value || !purchaseForm.productName.trim()){notify.warning('请填写外采产品名称');return}
  purchasingLine.value.productName=purchaseForm.productName.trim()
  purchasingLine.value.specification=purchaseForm.specification.trim()
  const {productName:_name,specification:_specification,...configuration}=purchaseForm
  purchasingLine.value.purchaseConfig={...configuration}
  purchaseDialog.value=false
}

function today() { return new Date().toISOString().slice(0, 10) }
/** 新建订单的期望交期默认值 = 下单日 + 7 天（LQ 2026-09-28 定）；仅作默认，页面上仍可改。
 *  与 today() 同源（含同口径的时区行为），保证「默认交期」和「下单日」不会互相矛盾。 */
function defaultDeliveryDate() {
  const d = new Date(`${today()}T00:00:00`)
  d.setDate(d.getDate() + 7)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function money(n?: number | null) { return n == null ? '待定' : `¥${Number(n).toFixed(2)}` }
function statusTag(s?: string) {
  if (s === 'CONFIRMED') return 'success'
  if (s === 'PENDING_APPROVAL') return 'warning'
  return 'info'
}
function approvalTag(s?: ApprovalStatus) {
  if (s === 'APPROVED') return 'success'
  if (s === 'PENDING') return 'warning'
  if (s === 'REJECTED') return 'danger'
  return 'info'
}
/** 后端回的是 "2026-09-24T18:04:01"，秒就够用（与采购订单视图同口径）。 */
function formatTime(v?: string | null) {
  if (!v) return '—'
  return v.replace('T', ' ').slice(0, 19)
}
// 表格插槽里的 row 是 any，直接索引字面量 Record 会触发 noImplicitAny；统一走查表函数。
function approvalStatusText(s?: string | null) {
  return (APPROVAL_STATUS_TEXT as Record<string, string>)[s || 'NONE'] || '未提交'
}
function approvalActionText(a?: string | null) {
  return (APPROVAL_ACTION_TEXT as Record<string, string>)[a || ''] || a || ''
}
function addLine() { form.lines.push({ productName: '', specification: '', supplyMode: 'UNDECIDED', unit: '台', quantity: 1, unitPrice: null }) }
const orderCustomerId = ref<number | null>(null)

/**
 * 客户下拉：客户**档案**里的客户（复用客户列表接口搜索，不另起一套口径）。
 *
 * 为什么要锚定：订单是唯一需求源，客户身份靠 `customerId` 往下游传（出库、提成、客户档案台账）。
 * 手填名称在档案里挂不上钩，下游就只能靠名字猜——所以这里只允许「选档案」或「当场建档」，
 * 档案里没有这个客户时用 {@link createCustomerAndPick} 就地建档（同样落进客户档案，同一份数据）。
 */
interface CustomerOption { id: number; customerName: string; customerCode: string | null }

const customerOptions = ref<CustomerOption[]>([])
const customersLoading = ref(false)
const customerPick = ref<number | null>(null)
/** 最近一次搜索词：点「新增客户」时预填，省得重敲一遍。 */
const customerTerm = ref('')
const customerDialog = ref(false)
const creatingCustomer = ref(false)
const newCustomerName = ref('')
/** 已选客户不在当前搜索结果里时（打开历史订单、换关键词搜索）也要能显示名称。 */
const customerChoices = computed<CustomerOption[]>(() => {
  if (!customerPick.value || customerOptions.value.some(c => c.id === customerPick.value)) return customerOptions.value
  return [{ id: customerPick.value, customerName: form.customerName, customerCode: null }, ...customerOptions.value]
})

/** 只取前 20 条：选客户是「找到那一个」，不是浏览全表（要浏览去客户档案页）。 */
async function searchCustomers(term: string) {
  customerTerm.value = term.trim()
  customersLoading.value = true
  try {
    const rows = await searchCustomerOptions(term, 20)
    customerOptions.value = rows.map(row => ({ id: row.id, customerName: row.customerName, customerCode: row.customerCode }))
  } catch (e) { notify.errorWithDetail('加载客户档案失败', e) }
  finally { customersLoading.value = false }
}
/** 选中档案客户 → 订单头同时记 id（身份锚点）与名称快照（后端存 customer_name_snapshot）。 */
function onCustomerPicked(id: number | null) {
  if (!id) { customerPick.value = null; orderCustomerId.value = null; form.customerName = ''; return }
  const picked = customerChoices.value.find(item => item.id === id)
  if (!picked) return
  orderCustomerId.value = picked.id
  form.customerName = picked.customerName
}
function openCustomerCreate() {
  newCustomerName.value = customerTerm.value
  customerDialog.value = true
}
/** 就地建档：后端同名幂等（已有同名客户就直接返回那一条，不会建出重复档案）。 */
async function createCustomerAndPick() {
  const name = newCustomerName.value.trim()
  if (!name) { notify.warning('请填写客户名称'); return }
  creatingCustomer.value = true
  try {
    const { customer, created } = await quickCreateCustomer(name)
    if (!customerOptions.value.some(item => item.id === customer.id)) {
      customerOptions.value = [{ id: customer.id, customerName: customer.customerName, customerCode: customer.customerCode },
        ...customerOptions.value]
    }
    customerPick.value = customer.id
    onCustomerPicked(customer.id)
    customerDialog.value = false
    notify.success(created ? `已新建客户档案并选中：${customer.customerName}`
      : `档案里已有同名客户，已直接选中：${customer.customerName}`)
  } catch (e) { notify.errorWithDetail('新增客户档案失败', e) }
  finally { creatingCustomer.value = false }
}
/** 客户必须已锚定档案：保存/提交/送评前统一拦（后端只存快照，锚点丢了是静默的）。 */
function requireCustomer() {
  if (orderCustomerId.value) return true
  notify.warning(form.customerName.trim()
    ? `客户「${form.customerName.trim()}」未关联客户档案：请从客户档案中选择，或点「新增客户」建档`
    : '请从客户档案选择客户（档案里没有就点「新增客户」建档）')
  return false
}

function onSupplyModeChange(line: SalesOrderLine) {
  if (line.supplyMode === 'MAKE') { line.salesProductId = null; line.productName = ''; line.available = undefined }
  // 现货：必须从可售库存重选（产品档案里的产品不代表有货）
  else if (line.supplyMode === 'STOCK') {
    line.salesProductId = null; line.productName = ''; line.available = undefined
    void searchStock('')
  }
  if (line.supplyMode !== 'MAKE') line.productionConfig = null
  if (line.supplyMode !== 'BUY') line.purchaseConfig = null
}

function openProductionConfig(index: number) {
  configuringLine.value = form.lines[index] ?? null
  productionDialog.value = !!configuringLine.value
}
function productionSummary(config: ProductionDemandConfig) {
  return [config.boxShape, config.boxSize, config.moldArrangement, ...config.optionalConfigs].filter(Boolean).join(' · ')
}
function applyProductionConfig(config: ProductionDemandConfig, specification: string, displayName: string) {
  if (!configuringLine.value) return
  configuringLine.value.productionConfig = config
  configuringLine.value.productName = displayName
  configuringLine.value.processCategory = config.productName
  configuringLine.value.specification = specification
}
async function load(p = page.value) {
  page.value = p; loading.value = true
  try { const data = await fetchSalesOrders({ keyword: keyword.value, status: status.value, page: p, size: 20 }); items.value = data.items; total.value = data.total }
  catch (e) { notify.errorWithDetail('加载销售订单失败', e) }
  finally { loading.value = false }
}
function openCreate() {
  editingId.value = null; orderNo.value = ''; orderStatus.value = 'DRAFT'
  approvals.value = []; approvalStatus.value = 'NONE'; submittedAt.value = null; decidedAt.value = null
  fulfillmentReviews.value = []; fulfillmentReviewHistory.value = []; fulfillmentReviewRequestedAt.value = null
  executionExceptions.value = []; orderChanges.value = []; revisionNo.value = 1
  orderCustomerId.value = null
  customerPick.value = null; customerTerm.value = ''; customerOptions.value = []
  saveKey.value = newRequestKey()
  Object.assign(form, { customerName: '', orderDate: today(), requestedDeliveryDate: defaultDeliveryDate(), remark: '', lines: [] })
  addLine(); drawer.value = true
  void searchCustomers('')
}
/** 把订单装载进表单（含明细行 id）：打开详情与建单前共用，避免两处各写一遍。 */
async function loadIntoForm(id: number): Promise<SalesOrderLine[]> {
  const data = await fetchSalesOrder(id)
  shipments.value = data.shipments || []
  editingId.value = id; orderNo.value = data.head.orderNo; orderStatus.value = data.head.status
  approvalStatus.value = data.head.approvalStatus || 'NONE'
  submittedAt.value = data.head.approvalSubmittedAt ?? null
  decidedAt.value = data.head.approvalDecidedAt ?? null
  approvals.value = data.approvals || []
  fulfillmentReviewRequestedAt.value = data.head.fulfillmentReviewRequestedAt ?? null
  revisionNo.value = data.head.revisionNo ?? 1
  fulfillmentReviews.value = await fetchSalesFulfillmentReviewStatus(id)
  fulfillmentReviewHistory.value = await fetchSalesFulfillmentReviewHistory(id)
  executionExceptions.value = data.head.status === 'CONFIRMED' ? await fetchSalesExecutionExceptions(id) : []
  orderChanges.value = data.head.status === 'CONFIRMED' ? await fetchSalesOrderChanges(id) : []
  orderCustomerId.value = data.head.customerId ?? null
  // 客户下拉：以该订单客户名预搜，选中项就在列表里；老单据没有 customerId 时给一批候选让用户重新锚定
  customerPick.value = data.head.customerId ?? null
  customerTerm.value = ''
  customerOptions.value = []
  void searchCustomers(data.head.customerId ? data.head.customerName : '')
  saveKey.value = newRequestKey()
  const lines: SalesOrderLine[] = data.items.map(line => ({ ...line, productionConfig: line.productionConfigJson
    ? JSON.parse(line.productionConfigJson) as ProductionDemandConfig : null,
    purchaseConfig: line.purchaseConfigJson ? JSON.parse(line.purchaseConfigJson) as PurchaseDemandConfig : null }))
  Object.assign(form, { customerName: data.head.customerName, orderDate: String(data.head.orderDate).slice(0, 10),
    requestedDeliveryDate: data.head.requestedDeliveryDate ? String(data.head.requestedDeliveryDate).slice(0, 10) : '', remark: data.head.remark || '', lines })
  // 现货行要给得出「可售库存」才有判定依据；同时把已选产品喂给下拉显示名称
  for (const line of lines) {
    if (line.supplyMode === 'STOCK' && line.salesProductId) {
      stockPicked.value[line.salesProductId] = {
        id: line.salesProductId, productName: line.productName, specification: line.specification || '',
        unit: line.unit, available: Number(line.available ?? 0)
      }
    }
  }
  if (lines.some(line => line.supplyMode === 'STOCK')) void searchStock('')
  return lines
}
const collaborationSection = ref<HTMLElement>()
function onDrawerOpened() {
  if (route.query.section === 'collaboration') collaborationSection.value?.scrollIntoView({ block: 'start' })
}
async function openDetail(id: number) {
  try { await loadIntoForm(id); drawer.value = true; readinessRefresh.value++ }
  catch (e) { notify.errorWithDetail('读取销售订单失败', e) }
}
function isLineHeld(itemId: number) {
  return executionExceptions.value.some(item => item.itemId === itemId && item.status === 'OPEN' && item.pauseFollowup !== false && item.pauseFollowup !== 0)
}
function changeStatusText(status: SalesOrderChange['status']) {
  return ({ PENDING_REVIEW: '待履约复评', PENDING_CUSTOMER: '待客户确认', PENDING_APPROVAL: '待商业审批', APPROVED: '已生效', REJECTED: '已驳回' })[status]
}
async function reportException(line: SalesOrderLine) {
  if (editingId.value && line.id) await exceptionDialog.value?.open(editingId.value, line.id, false)
}
async function onExceptionSaved() { if (editingId.value) await openDetail(editingId.value) }
async function customerEvidence(title: string) {
  try {
    const answer = await ElMessageBox.prompt('请填写客户明确接受本方案的依据（确认日期、记录位置或补充协议）。', title, {
      inputType: 'textarea', inputValidator: value => String(value || '').trim() ? true : '客户确认依据必填',
    })
    return String(answer.value).trim()
  } catch { return null }
}
async function acceptExecutionDate(exception: SalesExecutionException) {
  const evidence = await customerEvidence(`客户接受执行方交期 ${exception.proposedDeliveryDate}`)
  if (!evidence || !editingId.value) return
  saving.value = true
  try { await acceptSalesDeliveryProposal(exception.id, evidence); notify.success('客户确认已记录，订单交期已生效'); await openDetail(editingId.value) }
  catch (error) { notify.errorWithDetail('接受交期方案失败；执行情况变化时请重新送方案确认', error) }
  finally { saving.value = false }
}
async function confirmCustomerDate(change: SalesOrderChange) {
  const evidence = await customerEvidence(`客户接受新交期 ${change.proposedDeliveryDate || form.requestedDeliveryDate}`)
  if (!evidence || !editingId.value) return
  saving.value = true
  try { await confirmSalesDeliveryChange(change.id, evidence); notify.success('客户确认已记录，订单变更已生效'); await openDetail(editingId.value) }
  catch (error) { notify.errorWithDetail('登记客户确认失败', error) }
  finally { saving.value = false }
}
async function withdrawChange(change: SalesOrderChange) {
  if (!editingId.value) return
  let reason = ''
  try {
    const response = await ElMessageBox.prompt('填写客户未接受或撤回原因。原订单保持不变，异常仍需解决。', '撤回未生效方案', {
      inputType: 'textarea', inputValidator: value => String(value || '').trim() ? true : '原因必填',
    })
    reason = String(response.value).trim()
  } catch { return }
  saving.value = true
  try { await withdrawSalesOrderChange(change.id, reason); await openDetail(editingId.value) }
  catch (error) { notify.errorWithDetail('撤回方案失败', error) }
  finally { saving.value = false }
}
function openChangeProposal(exception: SalesExecutionException) {
  const line = form.lines.find(item => item.id === exception.itemId)
  if (!line) return
  changeException.value = exception
  changeForm.quantity = Number(line.quantity)
  changeForm.deliveryDate = String(line.requestedDeliveryDate || '')
  changeForm.customerConfirmationRef = ''
  changeDialog.value = true
}
async function submitChangeProposal() {
  if (!changeException.value || !editingId.value) return
  if (!changeForm.customerConfirmationRef.trim()) { notify.warning('请填写客户确认依据'); return }
  saving.value = true
  try {
    await proposeSalesOrderChange(changeException.value.id, changeForm.quantity,
      changeForm.deliveryDate || null, changeForm.customerConfirmationRef.trim())
    notify.success('变更申请已提交')
    changeDialog.value = false
    await openDetail(editingId.value)
  } catch (error) { notify.errorWithDetail('提交变更申请失败', error) }
  finally { saving.value = false }
}
async function resolveException(exception: SalesExecutionException) {
  if (!editingId.value) return
  let comment = ''
  try {
    const response = await ElMessageBox.prompt('仅当原数量、配置和交期仍可履行时关闭异常。请写明恢复执行的依据。', '按原约定恢复', {
      inputType: 'textarea', inputValidator: value => String(value || '').trim() ? true : '恢复依据必填',
    })
    comment = String(response.value).trim()
  } catch { return }
  saving.value = true
  try {
    await resolveSalesExecutionException(exception.id, comment)
    notify.success('异常已解决，原订单明细恢复办理')
    await openDetail(editingId.value)
  } catch (error) { notify.errorWithDetail('解决异常失败', error) }
  finally { saving.value = false }
}
async function resendChangeReview(change: SalesOrderChange) {
  if (!editingId.value) return
  try {
    await ElMessageBox.confirm('重新送评后，上次复评意见将留档，需车间／采购再次核对方案；随后登记客户确认。', '重新送履约复评')
  } catch { return }
  saving.value = true
  try {
    await requestSalesChangeReReview(change.id)
    notify.success('已重新送履约复评')
    await openDetail(editingId.value)
  } catch (error) { notify.errorWithDetail('重新送复评失败', error) }
  finally { saving.value = false }
}
async function decideChange(change: SalesOrderChange, accept: boolean) {
  if (!editingId.value) return
  let comment = ''
  try {
    if (accept) await ElMessageBox.confirm('确认客户依据及车间／采购复评意见后，批准这次数量／交期变更？', '批准订单变更')
    else {
      const response = await ElMessageBox.prompt('请填写驳回原因。', '驳回订单变更', {
        inputType: 'textarea', inputValidator: value => String(value || '').trim() ? true : '驳回原因必填',
      })
      comment = String(response.value).trim()
    }
  } catch { return }
  saving.value = true
  try {
    await decideSalesOrderChange(change.id, accept, comment)
    notify.success(accept ? '订单变更已生效，异常已解除' : '订单变更已驳回，异常仍暂停')
    await openDetail(editingId.value)
  } catch (error) { notify.errorWithDetail('审批订单变更失败', error) }
  finally { saving.value = false }
}
function productOptionsFor(line: SalesOrderLine) {
  if (!line.salesProductId || products.value.some(p => p.id === line.salesProductId)) return products.value
  return [{ id: line.salesProductId, productCode: '', productName: line.productName, unit: line.unit }, ...products.value]
}
async function searchProducts(term: string) {
  if (term.trim().length < 2) { products.value = []; return }
  productsLoading.value = true
  try { products.value = await searchOrderProducts(term) }
  catch { products.value = [] }
  finally { productsLoading.value = false }
}

/**
 * 现货明细选品 = **从可售库存里选**（09-24 LQ 定：先生产入库，才在订单里作为现货可选）。
 *
 * 口径与出库扣减完全一致（`product_inventory_balances` 的 canonical 口径，数量 &gt; 0）：
 * 只有真能发货的货才选得到。来源含期初合并入库、完工入库、采购到货入库——
 * 所以「直接下生产任务单 → 完工入库 → 回这里选作现货」这条链是通的。
 *
 * 按产品合计可用量：仓库到出库时才选，订单行只认产品。
 */
interface StockOption { id: number; productName: string; specification?: string; unit: string; available: number }

const stockOptions = ref<StockOption[]>([])
const stockLoading = ref(false)
/** 打开历史订单时已选但不在当前列表里的产品，保证下拉能显示名称。 */
const stockPicked = ref<Record<number, StockOption>>({})

async function searchStock(term: string) {
  stockLoading.value = true
  try {
    const rows = await fetchAvailableStock({ keyword: term.trim(), limit: 200 })
    const merged = new Map<number, StockOption>()
    for (const r of rows) {
      const id = r.canonicalProductId
      const hit = merged.get(id)
      if (hit) hit.available += Number(r.available)
      else merged.set(id, {
        id, productName: r.productName, specification: r.specification,
        unit: r.unit, available: Number(r.available)
      })
    }
    stockOptions.value = [...merged.values()]
  } catch (e) { notify.errorWithDetail('加载可售库存失败', e); stockOptions.value = [] }
  finally { stockLoading.value = false }
}
function stockOptionsFor(line: SalesOrderLine) {
  if (!line.salesProductId || stockOptions.value.some(s => s.id === line.salesProductId)) return stockOptions.value
  const picked = stockPicked.value[line.salesProductId]
  return picked ? [picked, ...stockOptions.value] : stockOptions.value
}
/** 该行当前可售库存（`null` = 库存清单还没加载）。 */
function stockAvailableOf(line: SalesOrderLine) {
  const hit = stockOptions.value.find(s => s.id === line.salesProductId)
  return hit ? hit.available : (line.available ?? null)
}
/** 数量超出可售库存的缺口（`0` = 不缺或还没依据）。 */
function stockShortageOf(line: SalesOrderLine) {
  const available = stockAvailableOf(line)
  if (available == null) return 0
  return Math.max(0, Number(line.quantity) - Number(available))
}
function onStockPicked(line: SalesOrderLine) {
  const picked = stockOptions.value.find(s => s.id === line.salesProductId)
  if (!picked) return
  stockPicked.value[picked.id] = picked
  line.available = picked.available
  line.productName = picked.productName
  if (!line.specification) line.specification = picked.specification || ''
  if (picked.unit) line.unit = picked.unit
}
/** 现货选不到货 / 货不够 → 改为自产并在销售订单内配置。 */
function switchToMake(index: number) {
  const line = form.lines[index]
  if (!line || locked.value || line.supplyMode === 'MAKE') return
  line.supplyMode = 'MAKE'
  onSupplyModeChange(line)
  openProductionConfig(index)
}
function payload() {
  return { customerId: orderCustomerId.value, customerName: form.customerName.trim(), orderDate: form.orderDate,
    requestedDeliveryDate: form.requestedDeliveryDate || null, remark: form.remark,
    lines: form.lines.map(l => ({ id: l.id, productName: l.productName.trim(), specification: l.specification || '',
      supplyMode: l.supplyMode, unit: l.unit.trim(), quantity: l.quantity, unitPrice: l.unitPrice ?? null,
      salesProductId: l.salesProductId || null, requestedDeliveryDate: l.requestedDeliveryDate || null,
      remark: l.remark || '', productionConfig: l.supplyMode === 'MAKE' ? l.productionConfig || null : null,
      purchaseConfig: l.supplyMode === 'BUY' ? l.purchaseConfig || null : null })) }
}
async function save() {
  if (saving.value) return
  if (!requireCustomer()) return
  if (!form.lines.length || form.lines.some(l => !l.productName.trim() || !l.unit.trim() || !Number(l.quantity))) {
    notify.warning('请补齐产品、单位和数量'); return
  }
  saving.value = true
  try {
    const id = editingId.value ? await updateSalesOrder(editingId.value, payload(), saveKey.value) : await createSalesOrder(payload(), saveKey.value)
    notify.success('订单草稿已保存'); await load(1); await openDetail(id)
  } catch (e) { notify.errorWithDetail('保存销售订单失败', e) }
  finally { saving.value = false }
}
async function requestReview() {
  if (saving.value || !editingId.value) return
  if (!requireCustomer()) return
  saving.value = true
  try {
    await updateSalesOrder(editingId.value, payload(), newRequestKey())
    await requestSalesFulfillmentReview(editingId.value, newRequestKey())
    notify.success('已送车间／采购履约评审')
    await openDetail(editingId.value)
  } catch (error) { notify.errorWithDetail('送履约评审失败', error) }
  finally { saving.value = false }
}
/**
 * 提交审批（草稿 → 待审批）。
 *
 * ⚠️ 提交 **不等于** 审批通过（决策 #35）：原来这里是 `confirmSalesOrder` 一步跳到「已确认」，
 * 等于业务员自己批自己，审批关口形同虚设。现在只能把单子推给老板。
 */
async function submit() {
  if (saving.value || !editingId.value) return
  if (!requireCustomer()) return
  // 前端先自检，少一次往返报错；后端同样会拦（这里只是体验优化，判据以后端为准）
  if (form.lines.some(line => line.supplyMode === 'UNDECIDED')) { notify.warning('请先确定每项产品的供货方式'); return }
  if (form.lines.some(line => line.supplyMode === 'MAKE' && !line.productionConfig)) { notify.warning('请先配置自产产品'); return }
  if (form.lines.some(line => line.supplyMode === 'BUY' && !line.purchaseConfig)) { notify.warning('请先配置外采需求'); return }
  try { await ElMessageBox.confirm('自产／外采明细必须已有有效履约评审通过意见。提交后订单进入待审批，不能再修改。', '提交审批') }
  catch { return }
  saving.value = true
  try {
    // 先落盘再提交：审批人看到的必须就是当前表单内容
    await updateSalesOrder(editingId.value, payload(), newRequestKey())
    await submitSalesOrderApproval(editingId.value, newRequestKey())
    notify.success('已提交审批，等待老板审批'); await load(); await openDetail(editingId.value)
  } catch (e) { notify.errorWithDetail('提交审批失败', e) }
  finally { saving.value = false }
}

/** 审批通过（待审批 → 已确认）。权限是 `sales_order.approve`，与「提交」是两个独立权限。 */
async function approve() {
  if (saving.value || !editingId.value) return
  try { await ElMessageBox.confirm('审批通过后订单确认，需求锁定，可按明细办理现货出库 / 生产 / 采购。', '审批通过') }
  catch { return }
  saving.value = true
  try {
    await approveSalesOrder(editingId.value, undefined, newRequestKey())
    notify.success('已审批通过'); await load(); await openDetail(editingId.value)
  } catch (e) { notify.errorWithDetail('审批失败', e) }
  finally { saving.value = false }
}

/** 驳回（待审批 → 草稿），原因必填：不给理由，提交人不知道该改什么。 */
async function reject() {
  if (saving.value || !editingId.value) return
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因，提交人会看到。', '驳回销售订单', {
      inputType: 'textarea',
      inputPlaceholder: '例如：单价与报价不符 / 交期无法满足',
      inputValidator: v => (String(v ?? '').trim() ? true : '驳回原因必填'),
    })
    comment = String(value ?? '').trim()
  } catch { return }
  saving.value = true
  try {
    await rejectSalesOrder(editingId.value, comment, newRequestKey())
    notify.success('已驳回，订单退回草稿'); await load(); await openDetail(editingId.value)
  } catch (e) { notify.errorWithDetail('驳回失败', e) }
  finally { saving.value = false }
}
function openOutbound(line: SalesOrderLine) {
  if (!editingId.value || !line.id) return
  outboundItem.value = line
  outboundDialog.value = true
}
function onOutboundCreated(id: number) {
  notify.success(`已生成出库草稿 #${id}，请核对后过账`)
  void router.push({ path: '/sales-outbounds', query: { outboundId: String(id), sourceOrderId: String(editingId.value) } })
}

watch(() => route.query.status, value => { status.value = ['DRAFT','PENDING_APPROVAL','CONFIRMED'].includes(String(value)) ? String(value) : ''; void load(1) })
await load()
watch(() => [route.query.editOrderId ?? route.query.orderId, route.query.section], value => {
  const id = Number(value[0])
  if (Number.isSafeInteger(id) && id > 0) void openDetail(id)
}, { immediate: true })
</script>

<style scoped>
.sales-orders-page { padding: 22px; }
.page-head, .filters, .line-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-head { margin-bottom: 18px; }
.page-head h2, .line-heading h3 { margin: 0 0 6px; }
.page-head p, .tip { color: var(--el-text-color-secondary); margin: 0; }
.filters { justify-content: flex-start; margin-bottom: 16px; }
.filters .el-input { max-width: 300px; }
.filters .el-select { width: 150px; }
.pager { justify-content: flex-end; margin-top: 16px; }
.order-form { padding: 0 10px; }
.approval-meta { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.approval-meta .tip { margin-top: 0; }
.approval-block { margin-top: 16px; }
.approval-block h3 { margin: 0 0 8px; }
.order-head-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; margin-top: 15px; }
.customer-picker { display: flex; gap: 8px; width: 100%; }
.customer-picker .el-select { flex: 1; }
.line-heading { margin: 18px 0 10px; }
.line-second { margin-top: 6px; }
.tip { margin-top: 12px; font-size: 13px; }
.tip.warn { color: var(--el-color-warning); }
/* 履约评审失效行整行红色背景：与 el-tag danger 配合，强提示用户必须重评才能提交审批。 */
:deep(.review-invalid-row td) { background: var(--el-color-danger-light-9) !important; }
</style>
