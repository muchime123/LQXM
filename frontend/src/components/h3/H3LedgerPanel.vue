<template>
  <div class="ledger-panel">
    <div class="ledger-toolbar">
      <el-input
        v-model="keyword"
        :placeholder="meta.placeholder"
        clearable
        style="width: 320px"
        @keyup.enter="reload(1)"
        @clear="reload(1)"
      >
        <template #prefix><el-icon class="ledger-search-icon"><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" plain @click="reload(1)">查询</el-button>
      <span class="ledger-toolbar__hint">{{ meta.hint }}</span>
      <span class="ledger-toolbar__right">
        <span class="ledger-toolbar__count">共 <b>{{ total }}</b> 条</span>
      </span>
    </div>

    <!-- sheet 维度汇总条（2026-09-21）：源文件「每账户/每供应商一个 sheet」的结构，以汇总下拉 + 小计还原。
         选中 = 只看该 sheet 的行（后端精确筛选）；不选 = 全量平铺 + 全局合计。 -->
    <div v-if="ledgerType === 'cashflowJournal'" v-loading="facetLoading" class="ledger-facet">
      <el-select v-model="accountFilter" filterable clearable :placeholder="`全部账户（${accountSummaries.length}）`" class="ledger-facet__select" @change="reload(1)">
        <el-option v-for="a in accountSummaries" :key="a.id" :value="a.accountName" :label="a.accountName">
          <div class="facet-option">
            <span>{{ a.accountName }}</span>
            <span class="facet-option__right">{{ money(a.closingBalance) }} {{ a.currency }}</span>
          </div>
        </el-option>
      </el-select>
      <div class="ledger-facet__stats">
        <template v-if="selectedAccount">
          <span class="facet-chip">期初 <b>{{ money(selectedAccount.openingBalance) }}</b></span>
          <span class="facet-chip facet-chip--in">收入 <b>{{ money(selectedAccount.income) }}</b></span>
          <span class="facet-chip facet-chip--out">支出 <b>{{ money(selectedAccount.expense) }}</b></span>
          <span class="facet-chip">期末 <b>{{ money(selectedAccount.closingBalance) }}</b> {{ selectedAccount.currency }}</span>
          <span class="facet-chip">{{ selectedAccount.entryCount }} 笔</span>
          <span class="facet-chip">最近 {{ selectedAccount.lastEntryDate ? String(selectedAccount.lastEntryDate).slice(0, 10) : '—' }}</span>
        </template>
        <template v-else>
          <span class="facet-chip">{{ accountSummaries.length }} 个账户</span>
          <span class="facet-chip facet-chip--in">收入合计 <b>{{ money(cashflowAgg.income) }}</b></span>
          <span class="facet-chip facet-chip--out">支出合计 <b>{{ money(cashflowAgg.expense) }}</b></span>
          <span v-if="cashflowAgg.foreignCount" class="facet-chip facet-chip--muted">含 {{ cashflowAgg.foreignCount }} 个外币账户，合计为原值混算</span>
        </template>
      </div>
    </div>
    <div v-else-if="ledgerType === 'supplierPayable'" v-loading="facetLoading" class="ledger-facet">
      <el-select v-model="supplierFilter" filterable clearable :placeholder="`全部供应商（${supplierSummaries.length}）`" class="ledger-facet__select" @change="reload(1)">
        <el-option v-for="s in supplierSummaries" :key="s.supplierName" :value="s.supplierName" :label="s.supplierName">
          <div class="facet-option">
            <span>{{ s.supplierName }}</span>
            <span class="facet-option__right">剩 {{ money(s.remainingAmount) }}</span>
          </div>
        </el-option>
      </el-select>
      <div class="ledger-facet__stats">
        <template v-if="selectedSupplier">
          <span v-if="selectedSupplier.resolvedSupplierName" class="facet-chip facet-chip--muted">RK反查：{{ selectedSupplier.resolvedSupplierName }}</span>
          <span class="facet-chip">应付 <b>{{ money(selectedSupplier.payableAmount) }}</b></span>
          <span class="facet-chip">结算 <b>{{ money(selectedSupplier.settledAmount) }}</b></span>
          <span class="facet-chip">折让 <b>{{ money(selectedSupplier.allowance) }}</b></span>
          <span class="facet-chip">付款 <b>{{ money(selectedSupplier.payAmount) }}</b></span>
          <span class="facet-chip">剩余 <b>{{ money(selectedSupplier.remainingAmount) }}</b></span>
          <span class="facet-chip">{{ selectedSupplier.monthCount }} 个月</span>
        </template>
        <template v-else>
          <span class="facet-chip">{{ supplierSummaries.length }} 家供应商</span>
          <span class="facet-chip">应付合计 <b>{{ money(payableAgg.payable) }}</b></span>
          <span class="facet-chip">折让合计 <b>{{ money(payableAgg.allowance) }}</b></span>
          <span class="facet-chip">付款合计 <b>{{ money(payableAgg.pay) }}</b></span>
          <span class="facet-chip">剩余合计 <b>{{ money(payableAgg.remaining) }}</b></span>
        </template>
      </div>
    </div>

    <!-- 销售出库台账 -->
    <template v-if="ledgerType === 'outbound'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="outboundNo" label="出库单号" width="170" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.status)">{{ row.status || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="业务类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="natureTagType(row)" effect="plain">
              {{ natureRowLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="shipDate" label="发货日期" width="110" :formatter="dateCell" />
        <el-table-column prop="businessDate" label="业务日期" width="110" :formatter="dateCell" />
        <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
        <el-table-column prop="dealerName" label="经销商" min-width="130" show-overflow-tooltip />
        <el-table-column prop="salespersonName" label="业务员" width="100" show-overflow-tooltip />
        <el-table-column prop="region" label="地区" width="100" show-overflow-tooltip />
        <el-table-column prop="itemCount" label="产品明细" width="90" align="center" />
        <el-table-column label="项目内关联" width="225">
          <template #default="{ row }">
            <div class="relation-tags">
              <el-tag size="small" :type="row.customerId ? 'success' : 'warning'">客户</el-tag>
              <el-tag size="small" :type="row.employeeId ? 'success' : 'warning'">员工</el-tag>
              <el-tag size="small" :type="Number(row.linkedProductItemCount || 0) === Number(row.productItemCount || 0) ? 'success' : 'warning'">
                产品 {{ row.linkedProductItemCount || 0 }}/{{ row.productItemCount || 0 }}
              </el-tag>
              <el-tag
                size="small"
                :type="row.warehouse && !row.linkedWarehouseId ? 'warning' : 'success'"
                :title="row.warehouse ? `来源仓库：${row.warehouse}${row.linkedWarehouseName ? ` → ${row.linkedWarehouseName}` : '（未关联）'}` : '来源未填写仓库'"
              >
                仓库
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="salesAmount" label="销售金额" width="130" align="right">
          <template #default="{ row }">{{ money(row.salesAmount) }}</template>
        </el-table-column>
        <el-table-column prop="sourceGrossProfit" label="源毛利" width="120" align="right">
          <template #default="{ row }">{{ money(row.sourceGrossProfit) }}</template>
        </el-table-column>
        <el-table-column prop="departmentName" label="部门" min-width="130" show-overflow-tooltip />
        <el-table-column prop="sourceCreatedAt" label="源创建时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 生产任务工单台账 -->
    <template v-else-if="ledgerType === 'production'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="workOrderNo" label="工单号" width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.status)">{{ row.status || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="planType" label="计划类型" width="120" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品" min-width="180" show-overflow-tooltip />
        <el-table-column prop="requiredQuantity" label="需求数量" width="110" align="right">
          <template #default="{ row }">{{ num(row.requiredQuantity) }}</template>
        </el-table-column>
        <el-table-column prop="completedQuantity" label="完成数" width="110" align="right">
          <template #default="{ row }">{{ num(row.completedQuantity) }}</template>
        </el-table-column>
        <el-table-column prop="warehousedQuantity" label="入库数" width="110" align="right">
          <template #default="{ row }">{{ num(row.warehousedQuantity) }}</template>
        </el-table-column>
        <el-table-column prop="productionProgress" label="生产进度" width="110" />
        <el-table-column prop="warehousingProgress" label="入库进度" width="110" />
        <el-table-column prop="applicationDate" label="申请日期" width="120" :formatter="dateCell" />
        <el-table-column prop="requiredCompletionDate" label="要求完成" width="120" :formatter="dateCell" />
        <el-table-column prop="salespersonName" label="业务员" width="100" show-overflow-tooltip />
        <el-table-column prop="componentCount" label="制程明细" width="100" align="center" />
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canExecute" link type="primary" size="small" @click="followUpRouter.push({ path: '/h3-production-execution', query: { sourceId: String(row.id) } })">查看执行</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 收款记录台账（SINGLE_OUTBOUND） -->
    <template v-else-if="ledgerType === 'receipt'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="receiptNo" label="收款单号" width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.status)">{{ row.status || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="receiptDate" label="收款日期" width="120" :formatter="dateCell" />
        <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
        <el-table-column prop="dealerName" label="经销商" min-width="140" show-overflow-tooltip />
        <el-table-column prop="salespersonName" label="业务员" width="110" show-overflow-tooltip />
        <el-table-column prop="paymentMethod" label="支付方式" width="140" show-overflow-tooltip />
        <el-table-column prop="receiptAmount" label="收款金额" width="130" align="right">
          <template #default="{ row }">{{ money(row.receiptAmount) }}</template>
        </el-table-column>
        <el-table-column prop="receivableAmount" label="应收金额" width="130" align="right">
          <template #default="{ row }">{{ money(row.receivableAmount) }}</template>
        </el-table-column>
        <el-table-column label="关联销售出库" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.outboundNo">{{ row.outboundNo }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="departmentName" label="部门" min-width="140" show-overflow-tooltip />
        <el-table-column prop="sourceCreatedAt" label="源创建时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 多订单结清台账（MULTI_OUTBOUND，展开查看覆盖的出库单） -->
    <template v-else-if="ledgerType === 'settlement'">
      <el-table v-loading="loading" :data="items" stripe row-key="id">
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="expand-body">
              <p class="expand-hint">该结清覆盖的出库单（来自导入的「出库单明细.销售出库编号」，V222 起保存；旧数据需重新导入后可见）</p>
              <el-table v-if="row.outboundLinks?.length" :data="row.outboundLinks" size="small" border>
                <el-table-column type="index" label="#" width="45" />
                <el-table-column prop="outboundNo" label="销售出库编号" min-width="150" show-overflow-tooltip />
                <el-table-column prop="customerName" label="客户" min-width="150" show-overflow-tooltip>
                  <template #default="{ row: lk }">{{ lk.customerName || '—' }}</template>
                </el-table-column>
                <el-table-column prop="salesAmount" label="销售额" width="120" align="right">
                  <template #default="{ row: lk }">{{ money(lk.salesAmount) }}</template>
                </el-table-column>
                <el-table-column prop="settledAmount" label="明细结清金额" width="130" align="right">
                  <template #default="{ row: lk }">{{ money(lk.settledAmount) }}</template>
                </el-table-column>
              </el-table>
              <p v-else class="expand-empty">未解析到出库单明细（该结清单未含「出库单明细.销售出库编号」，或导入发生在 V222 上线前）</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="receiptNo" label="结清单号" width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.status)">{{ row.status || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="receiptDate" label="结清日期" width="120" :formatter="dateCell" />
        <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
        <el-table-column prop="dealerName" label="经销商" min-width="140" show-overflow-tooltip />
        <el-table-column prop="salespersonName" label="业务员" width="110" show-overflow-tooltip />
        <el-table-column prop="paymentMethod" label="支付方式" width="140" show-overflow-tooltip />
        <el-table-column prop="receiptAmount" label="结清金额" width="130" align="right">
          <template #default="{ row }">{{ money(row.receiptAmount) }}</template>
        </el-table-column>
        <el-table-column prop="receivableAmount" label="应收金额" width="130" align="right">
          <template #default="{ row }">{{ money(row.receivableAmount) }}</template>
        </el-table-column>
        <el-table-column prop="departmentName" label="部门" min-width="140" show-overflow-tooltip />
        <el-table-column prop="sourceCreatedAt" label="源创建时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 产品信息台账（氚云产品信息 Excel 按批次增量导入 h3_product_catalog，不覆盖） -->

    <!-- 产品信息台账（氚云产品信息 Excel 按批次增量导入 h3_product_catalog，不覆盖） -->
    <template v-else-if="ledgerType === 'product'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="productCode" label="产品编号" width="130" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="category" label="类目" width="120" show-overflow-tooltip />
        <el-table-column prop="specification" label="规格" min-width="140" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column prop="purchasePrice" label="采购价" width="110" align="right">
          <template #default="{ row }">{{ money(row.purchasePrice) }}</template>
        </el-table-column>
        <el-table-column prop="batchId" label="导入批次" width="170" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="导入时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 仓库明细台账（氚云仓库明细 Excel 按批次增量导入 h3_warehouse_stock，不覆盖） -->
    <template v-else-if="ledgerType === 'stock'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="warehouse" label="仓库" width="110" show-overflow-tooltip />
        <el-table-column prop="productCode" label="产品编号" width="130" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品名称" min-width="170" show-overflow-tooltip />
        <el-table-column prop="category" label="类目" width="110" show-overflow-tooltip />
        <el-table-column prop="specification" label="规格" min-width="130" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column prop="quantity" label="库存数量" width="110" align="right">
          <template #default="{ row }">{{ num(row.quantity) }}</template>
        </el-table-column>
        <el-table-column prop="purchasePrice" label="采购价" width="110" align="right">
          <template #default="{ row }">{{ money(row.purchasePrice) }}</template>
        </el-table-column>
        <el-table-column prop="batchId" label="导入批次" width="170" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="导入时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 经销商信息台账（氚云经销商信息 Excel 按批次增量导入 h3_dealer_catalog，不覆盖） -->
    <template v-else-if="ledgerType === 'dealer'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="dealerCode" label="经销商编号" width="140" show-overflow-tooltip />
        <el-table-column prop="dealerName" label="经销商名称" min-width="170" show-overflow-tooltip />
        <el-table-column prop="contact" label="联系方式" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.contact || '—' }}</template>
        </el-table-column>
        <el-table-column label="归属业务员" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ownerSalesperson || '—' }}</template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.address || '—' }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="数据状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.dataStatus)">{{ row.dataStatus || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="batchId" label="导入批次" width="170" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="导入时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 采购入库台账（V305 事实层；供应商解析失败标黄待人工核对，草稿单不进正式口径） -->
    <template v-else-if="ledgerType === 'purchase'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="sourceNo" label="采购入库编号" width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.dataStatus)">{{ row.dataStatus || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="purchaseDate" label="采购日期" width="110" :formatter="dateCell" />
        <el-table-column label="供应商" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.supplierName">{{ row.supplierName }}</span>
            <el-tag v-else size="small" type="danger" effect="plain" title="供应商解析失败，未静默新建主档">
              未解析：{{ row.supplierNameRaw || '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerCompany" label="采购主体" min-width="140" show-overflow-tooltip />
        <el-table-column prop="warehouseName" label="仓库" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.warehouseName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="itemCount" label="明细行" width="80" align="center" />
        <el-table-column prop="totalQuantity" label="合计数量" width="110" align="right">
          <template #default="{ row }">{{ num(row.totalQuantity) }}</template>
        </el-table-column>
        <el-table-column prop="totalAmount" label="合计金额" width="130" align="right">
          <template #default="{ row }">{{ money(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column prop="unpaidAmount" label="未付款金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.unpaidAmount) }}</template>
        </el-table-column>
        <el-table-column prop="paymentStatus" label="付款状态" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.paymentStatus || '—' }}</template>
        </el-table-column>
        <el-table-column prop="keeper" label="仓管员" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.keeper || '—' }}</template>
        </el-table-column>
        <el-table-column prop="sourceCreatedAt" label="源创建时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 供应商主数据台账（V305；名称即业务语义键，is_internal/is_dirty_value 为导入时标记） -->
    <template v-else-if="ledgerType === 'supplier'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="name" label="供应商名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="supplierStatus" label="合作状态" width="100">
          <template #default="{ row }">{{ row.supplierStatus || '—' }}</template>
        </el-table-column>
        <el-table-column label="标记" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.isInternal" size="small" type="warning" effect="plain">内部车间/产线</el-tag>
            <el-tag v-if="row.isDirtyValue" size="small" type="danger" effect="plain">串名脏值</el-tag>
            <span v-if="!row.isInternal && !row.isDirtyValue" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="contactName" label="联系人" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.contactName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="contactPhone" label="联系电话" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.contactPhone || '—' }}</template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.address || '—' }}</template>
        </el-table-column>
        <el-table-column prop="aliasCount" label="别名引用" width="90" align="center" />
        <el-table-column prop="dataStatus" label="数据状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.dataStatus)">{{ row.dataStatus || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="departmentName" label="所属部门" min-width="130" show-overflow-tooltip />
        <el-table-column prop="sourceCreatedAt" label="源创建时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 其他入库台账（V306 direction=IN；与其他出库同表单复制，明细前缀同名） -->
    <template v-else-if="ledgerType === 'otherIn'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="sourceNo" label="入库单号" width="190" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.dataStatus)">{{ row.dataStatus || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="movementDate" label="入库日期" width="110" :formatter="dateCell" />
        <el-table-column prop="warehouseName" label="入库仓库" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.warehouseName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="keeper" label="仓管员" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.keeper || '—' }}</template>
        </el-table-column>
        <el-table-column prop="itemCount" label="明细行" width="80" align="center" />
        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column prop="departmentName" label="所属部门" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.departmentName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="sourceCreatedAt" label="源创建时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 其他出库台账（V306 direction=OUT；合计数量/金额为主表原值） -->
    <template v-else-if="ledgerType === 'otherOut'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="sourceNo" label="出库单号" width="190" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.dataStatus)">{{ row.dataStatus || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="movementDate" label="出库日期" width="110" :formatter="dateCell" />
        <el-table-column prop="warehouseName" label="出库仓库" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.warehouseName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="keeper" label="仓管员" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.keeper || '—' }}</template>
        </el-table-column>
        <el-table-column prop="salesman" label="业务员" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.salesman || '—' }}</template>
        </el-table-column>
        <el-table-column prop="itemCount" label="明细行" width="80" align="center" />
        <el-table-column prop="totalQuantity" label="合计数量" width="110" align="right">
          <template #default="{ row }">{{ num(row.totalQuantity) }}</template>
        </el-table-column>
        <el-table-column prop="totalAmount" label="合计金额" width="130" align="right">
          <template #default="{ row }">{{ money(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column prop="sourceCreatedAt" label="源创建时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 库存调拨台账（V307；双仓库 + 双仓管员，纯数量无金额） -->
    <template v-else-if="ledgerType === 'transfer'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="sourceNo" label="调拨单号" width="190" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.dataStatus)">{{ row.dataStatus || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="transferDate" label="调拨日期" width="110" :formatter="dateCell" />
        <el-table-column prop="outWarehouseName" label="调出仓库" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.outWarehouseName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="inWarehouseName" label="调入仓库" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.inWarehouseName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="outKeeper" label="调出仓管员" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.outKeeper || '—' }}</template>
        </el-table-column>
        <el-table-column prop="inKeeper" label="调入仓管员" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.inKeeper || '—' }}</template>
        </el-table-column>
        <el-table-column prop="itemCount" label="明细行" width="80" align="center" />
        <el-table-column prop="totalQuantity" label="调拨数量" width="110" align="right">
          <template #default="{ row }">{{ num(row.totalQuantity) }}</template>
        </el-table-column>
        <el-table-column prop="departmentName" label="所属部门" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.departmentName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="sourceCreatedAt" label="源创建时间" width="170" :formatter="dateTimeCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else-if="ledgerType === 'invoiceProfile'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="customerName" label="户名" min-width="200" show-overflow-tooltip />
        <el-table-column prop="taxNo" label="税号" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.taxNo || '—' }}</template>
        </el-table-column>
        <el-table-column prop="bankName" label="开户行" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.bankName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="bankAccount" label="银行账号" width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.bankAccount || '—' }}</template>
        </el-table-column>
        <el-table-column label="客户关联" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.customerId">{{ row.linkedCustomerName || `客户 #${row.customerId}` }}</span>
            <el-tag v-else size="small" type="info" effect="plain">未关联</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.dataStatus)">{{ row.dataStatus || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="sourceUpdatedAt" label="源更新时间" width="150" :formatter="dateCell" />
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else-if="ledgerType === 'invoiceRequest'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="sourceNo" label="流水号" width="110" show-overflow-tooltip />
        <el-table-column prop="invoiceType" label="发票类型" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.invoiceType || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.dataStatus)">{{ row.dataStatus || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="requestDate" label="开票日期" width="110" :formatter="dateCell" />
        <el-table-column prop="customerName" label="户名" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="requestAmount" label="申请金额" width="110" align="right">
          <template #default="{ row }">{{ money(row.requestAmount) }}</template>
        </el-table-column>
        <el-table-column prop="receivedAmount" label="已收款" width="110" align="right">
          <template #default="{ row }">{{ money(row.receivedAmount) }}</template>
        </el-table-column>
        <el-table-column prop="unreceivedAmount" label="未收款" width="110" align="right">
          <template #default="{ row }">{{ money(row.unreceivedAmount) }}</template>
        </el-table-column>
        <el-table-column prop="itemCount" label="内容行" width="80" align="center" />
        <el-table-column prop="receiptCount" label="收款行" width="80" align="center" />
        <el-table-column prop="salesperson" label="业务员" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.salesperson || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else-if="ledgerType === 'productionReport'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="reportTime" label="报工时间" width="140" :formatter="dateCell" />
        <el-table-column prop="workOrderNo" label="生产工单编号" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.workOrderNo || '—' }}</template>
        </el-table-column>
        <el-table-column prop="componentName" label="工序（零部件）" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.componentName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="reportQty" label="报工数量" width="90" align="right" />
        <el-table-column prop="reporter" label="报工人" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reporter || '—' }}</template>
        </el-table-column>
        <el-table-column prop="reporterDept" label="所属部门" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reporterDept || '—' }}</template>
        </el-table-column>
        <el-table-column prop="productionLine" label="产线" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.productionLine || '—' }}</template>
        </el-table-column>
        <el-table-column prop="stockInClerk" label="入库员" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.stockInClerk || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="tagType(row.dataStatus)">{{ row.dataStatus || '—' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else-if="ledgerType === 'invoiceInput'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="sourceNo" label="数电发票号码" width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sourceNo || '—' }}</template>
        </el-table-column>
        <el-table-column prop="invoiceDate" label="开票日期" width="150" :formatter="dateCell" />
        <el-table-column prop="sellerName" label="销方名称" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sellerName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="sellerTaxNo" label="销方识别号" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sellerTaxNo || '—' }}</template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="110" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="taxAmount" label="税额" width="100" align="right">
          <template #default="{ row }">{{ money(row.taxAmount) }}</template>
        </el-table-column>
        <el-table-column prop="totalAmount" label="价税合计" width="110" align="right">
          <template #default="{ row }">{{ money(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column prop="invoiceKind" label="票种" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.invoiceKind || '—' }}</template>
        </el-table-column>
        <el-table-column prop="invoiceStatus" label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.invoiceStatus === '正常' ? 'success' : 'warning'">{{ row.invoiceStatus || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="drawer" label="开票人" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.drawer || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else-if="ledgerType === 'invoiceSales'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="sourceNo" label="数电发票号码" width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sourceNo || '—' }}</template>
        </el-table-column>
        <el-table-column prop="invoiceDate" label="开票日期" width="150" :formatter="dateCell" />
        <el-table-column prop="buyerName" label="购买方名称" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.buyerName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="totalAmount" label="价税合计" width="110" align="right">
          <template #default="{ row }">{{ money(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column prop="receiptDate" label="收款日期" width="110" :formatter="dateCell" />
        <el-table-column prop="paymentMethod" label="付款方式" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.paymentMethod || '—' }}</template>
        </el-table-column>
        <el-table-column prop="receiptAmount" label="收款金额" width="110" align="right">
          <template #default="{ row }">{{ money(row.receiptAmount) }}</template>
        </el-table-column>
        <el-table-column prop="unreceivedAmount" label="未收款" width="110" align="right">
          <template #default="{ row }">{{ money(row.unreceivedAmount) }}</template>
        </el-table-column>
        <el-table-column prop="salesman" label="业务员" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.salesman || '—' }}</template>
        </el-table-column>
        <el-table-column prop="receiptCount" label="收款行" width="80" align="center" />
        <el-table-column prop="invoiceStatus" label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.invoiceStatus === '正常' ? 'success' : 'warning'">{{ row.invoiceStatus || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else-if="ledgerType === 'supplierPayable'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="supplierName" label="供应商" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="monthNo" label="月份" width="80" align="center">
          <template #default="{ row }">{{ row.monthNo ? row.monthNo + '月' : (row.monthRaw || '—') }}</template>
        </el-table-column>
        <el-table-column prop="payableAmount" label="应付货款" width="120" align="right">
          <template #default="{ row }">{{ money(row.payableAmount) }}</template>
        </el-table-column>
        <el-table-column prop="settledAmount" label="结算货款" width="120" align="right">
          <template #default="{ row }">{{ money(row.settledAmount) }}</template>
        </el-table-column>
        <el-table-column prop="allowance" label="折让" width="110" align="right">
          <template #default="{ row }">{{ money(row.allowance) }}</template>
        </el-table-column>
        <el-table-column prop="payDate" label="付款日期" width="110" :formatter="dateCell" />
        <el-table-column prop="payMethod" label="付款方式" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.payMethod || '—' }}</template>
        </el-table-column>
        <el-table-column prop="payAmount" label="付款金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.payAmount) }}</template>
        </el-table-column>
        <el-table-column prop="remainingAmount" label="剩余货款" width="120" align="right">
          <template #default="{ row }">{{ money(row.remainingAmount) }}</template>
        </el-table-column>
        <el-table-column prop="resolvedSupplierName" label="RK反查身份" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.resolvedSupplierName || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else-if="ledgerType === 'cashflowJournal'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="accountName" label="账户" min-width="170" show-overflow-tooltip fixed="left">
          <template #default="{ row }">{{ row.accountName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="entryDate" label="日期" width="110" :formatter="dateCell" />
        <el-table-column prop="monthLabel" label="月份" width="70" align="center">
          <template #default="{ row }">{{ row.monthLabel || '—' }}</template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.summary || '—' }}</template>
        </el-table-column>
        <el-table-column prop="supplierRaw" label="供应商" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierRaw || '—' }}</template>
        </el-table-column>
        <el-table-column prop="counterpartyRaw" label="客户 供应商" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.counterpartyRaw || '—' }}</template>
        </el-table-column>
        <el-table-column prop="reimbursedBy" label="报销人" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reimbursedBy || '—' }}</template>
        </el-table-column>
        <el-table-column prop="invoiced" label="是否开票" width="90" align="center">
          <template #default="{ row }">{{ row.invoiced || '—' }}</template>
        </el-table-column>
        <el-table-column prop="income" label="收入" width="120" align="right">
          <template #default="{ row }">{{ money(row.income) }}</template>
        </el-table-column>
        <el-table-column prop="expense" label="支出" width="120" align="right">
          <template #default="{ row }">{{ money(row.expense) }}</template>
        </el-table-column>
        <el-table-column prop="balance" label="结存" width="120" align="right">
          <template #default="{ row }">{{ money(row.balance) }}</template>
        </el-table-column>
        <el-table-column v-if="fxMode" prop="fxRate" label="汇率" width="100" align="right">
          <template #default="{ row }">{{ row.fxRate ?? '—' }}</template>
        </el-table-column>
        <el-table-column v-if="fxMode" prop="fxGainLoss" label="汇兑损益" width="110" align="right">
          <template #default="{ row }">{{ row.fxGainLoss == null ? '—' : money(row.fxGainLoss) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else-if="ledgerType === 'cashierLog'">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column prop="txTime" label="交易完成时间" width="165" :formatter="dateTimeCell" />
        <el-table-column prop="status" label="状态" width="80" align="center">
          <template #default="{ row }">{{ row.status || '—' }}</template>
        </el-table-column>
        <el-table-column prop="amount" label="交易金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="fee" label="订单总扣费" width="120" align="right">
          <template #default="{ row }">{{ money(row.fee) }}</template>
        </el-table-column>
        <el-table-column prop="payerOpenid" label="付款人识别号" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.payerOpenid || '—' }}</template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.summary || '—' }}</template>
        </el-table-column>
        <el-table-column prop="salesperson" label="业务员" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.salesperson || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <div class="ledger-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="reload()"
        @size-change="reload(1)"
      />
    </div>

    <!-- 台账详情抽屉：主记录全字段 + 子表明细（列表行点击「详情」打开） -->
    <H3LedgerDetailDrawer
      v-model="detailVisible"
      :ledger-type="ledgerType"
      :loading="detailLoading"
      :data="detailData"
      :error="detailError"
      @updated="reloadDetail"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { Search } from '@element-plus/icons-vue'
import H3LedgerDetailDrawer from './H3LedgerDetailDrawer.vue'
import {
  fetchOutboundLedger,
  fetchProductionLedger,
  fetchReceiptLedger,
  fetchSettlementLedger,
  fetchPurchaseLedger,
  fetchSupplierLedger,
  fetchOtherInLedger,
  fetchOtherOutLedger,
  fetchTransferLedger,
  fetchInvoiceProfileLedger,
  fetchInvoiceRequestLedger,
  fetchProductionReportLedger,
  fetchInvoiceInputLedger,
  fetchInvoiceSalesLedger,
  fetchSupplierPayableLedger,
  fetchCashflowJournalLedger,
  fetchCashierLogLedger,
  fetchLedgerDetail,
  fetchCashflowAccounts,
  fetchPayableSuppliers,
  type CashAccountSummary,
  type PayableSupplierSummary
} from '@/api/business/orders'
import { fetchProductCatalogLedger, fetchWarehouseStockLedger, fetchDealerLedger } from '@/api/productInventory'
import { notify } from '@/composables/ui/useToast'
import type { H3LedgerKind, LedgerDetailResult, LedgerRow } from '@/api/business/orders'

const followUpRouter = useRouter()
const { can: canFollowUpPermission } = useCurrentUser()
const canExecute = computed(() => ['manufacturing.report', 'manufacturing.report.override', 'manufacturing.inspect', 'manufacturing.followup', 'manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all'].some(canFollowUpPermission))

/** initialKeyword：从外部深链（如出库单详情 → 本页台账）带入的初始搜索词，仅挂载时生效一次 */
const props = defineProps<{ ledgerType: H3LedgerKind; initialKeyword?: string }>()

const FETCHERS: Record<H3LedgerKind, (p: { keyword?: string; account?: string; supplier?: string; page?: number; size?: number }) => Promise<{ items: LedgerRow[]; total: number }>> = {
  outbound: fetchOutboundLedger,
  production: fetchProductionLedger,
  receipt: fetchReceiptLedger,
  settlement: fetchSettlementLedger,
  purchase: fetchPurchaseLedger,
  product: fetchProductCatalogLedger,
  stock: fetchWarehouseStockLedger,
  dealer: fetchDealerLedger,
  supplier: fetchSupplierLedger,
  otherIn: fetchOtherInLedger,
  otherOut: fetchOtherOutLedger,
  transfer: fetchTransferLedger,
  invoiceProfile: fetchInvoiceProfileLedger,
  invoiceRequest: fetchInvoiceRequestLedger,
  productionReport: fetchProductionReportLedger,
  invoiceInput: fetchInvoiceInputLedger,
  invoiceSales: fetchInvoiceSalesLedger,
  supplierPayable: fetchSupplierPayableLedger,
  cashflowJournal: fetchCashflowJournalLedger,
  cashierLog: fetchCashierLogLedger
}
const META: Record<H3LedgerKind, { placeholder: string; hint: string }> = {
  outbound: {
    placeholder: '搜索出库单号 / 客户 / 经销商 / 业务员',
    hint: '氚云销售出库最终事实；客户档案与月度提成都以本台账及「收款详细」子表为唯一来源。退换货疑似单可在详情中人工确认。'
  },
  production: {
    placeholder: '搜索工单号 / 客户 / 产品',
    hint: '保留氚云生产任务工单原始数据；点击“跟单”维护本地进度，重新导入不覆盖跟单记录。'
  },
  receipt: {
    placeholder: '搜索收款单号 / 客户 / 业务员',
    hint: '氚云收款记录（SINGLE_OUTBOUND）原始数据，只读核对，不参与业务编排。'
  },
  settlement: {
    placeholder: '搜索结清单号 / 客户 / 业务员',
    hint: '氚云多订单结清（MULTI_OUTBOUND）原始数据，只读核对；展开行查看覆盖的出库单。'
  },
  product: {
    placeholder: '搜索产品编号 / 名称 / 类目',
    hint: '氚云产品信息台账：按批次增量导入（新增/更新，不覆盖历史行）。作为库存快照的维表，提供名称/规格/单位/采购价。'
  },
  stock: {
    placeholder: '搜索产品编号 / 名称 / 仓库',
    hint: '氚云仓库明细台账：按批次增量导入（新增/更新，不覆盖历史行）。作为库存快照的事实表，提供仓库与库存数量；产品库存页实时合并两张台账，导入完成即生效。'
  },
  dealer: {
    placeholder: '搜索经销商编号 / 名称 / 联系方式 / 归属业务员',
    hint: '氚云经销商信息台账：按批次增量导入（新增/更新，不覆盖历史行）；经销商编号（JXS…）是销售出库 dealer_name 自由文本对应的权威业务键。'
  },
  purchase: {
    placeholder: '搜索单号 / 供应商 / 采购主体 / 仓库',
    hint: '氚云采购入库事实层（2026-09-17 决策：不动库存余额）。明细行的「采购价格×日期×供应商」是采购价权威口径；草稿单仅存档；未解析供应商待人工核对。'
  },
  supplier: {
    placeholder: '搜索名称 / 联系人 / 地址 / 备注',
    hint: '氚云供应商主数据（无编号，名称即业务语义键）。「内部车间/产线」「串名脏值」为导入时的非供应商标记；别名引用 = 历史单据上指向本供应商的原始文本数。'
  },
  otherIn: {
    placeholder: '搜索单号 / 仓库 / 仓管员 / 备注',
    hint: '氚云其他入库事实层（QTRK…，2026-09-17 决策：不动库存余额）。与「其他出库」同表单复制而来、明细前缀同名为「其他出库明细.」，故落库合并为一张表按方向区分；草稿单仅存档不进正式口径。'
  },
  otherOut: {
    placeholder: '搜索单号 / 仓库 / 仓管员 / 备注',
    hint: '氚云其他出库事实层（QTCK…，2026-09-17 决策：不动库存余额）。合计数量/金额为主表原值；明细「采购总价」为源单据列，入库侧无此列。'
  },
  transfer: {
    placeholder: '搜索调拨单号 / 调出仓库 / 调入仓库 / 仓管员',
    hint: '氚云库存调拨事实层（DB…，纯数量、无金额）。调出/调入仓管员为源表两列同名「仓管员」（解析层按列号区分）；data_status 非「已生效」（草稿/已取消）仅标记保留、不进正式口径；备查列「判断（隐）」为氚云自带的超调出库存校验标记。'
  },
  invoiceProfile: {
    placeholder: '搜索户名 / 税号 / 开户行 / 银行账号',
    hint: '氚云开票资料主数据（按数据ID幂等；44 组同名户名是合法事实，绝不能按户名合并）。税号→客户档案 credit_code 等值唯一命中才关联，未命中只标记不进确认队列（外协/一次性户名属正常）；地址/电话/银行 80% 空值率为源数据实情。'
  },
  invoiceRequest: {
    placeholder: '搜索流水号 / 户名 / 税号 / 业务员 / 备注',
    hint: '氚云开票申请（本系统唯一开票口径来源）。收款情况只建引用、不写收款记录（收款权威=销售出库）；「选择出库单匹配开票内容」为自由文本无单号，只存原文不做自动勾稽；18 单流水号为空以数据ID兜底。'
  },
  productionReport: {
    placeholder: '搜索工单号 / 工序 / 报工人 / 部门 / 产线',
    hint: '氚云生产报工（V311，第 15 类）：工序完工报工，粒度=生产工单×零部件制程（零部件流水号全表 10 值=工序模板，非 BOM/材料领料）。生产工单编号可关联生产工单台账（实测 87.8% 工单有报工）；数据状态全「已生效」、氚云无撤销原语，误报需 ERP 侧冲销。'
  },
  invoiceInput: {
    placeholder: '搜索发票号 / 销方名称 / 税号 / 票种 / 状态',
    hint: '进项发票（V312，第 16 类，税务平台全量导出）：实开口径权威源·进项侧（2026-09-18 拍板，氚云开票申请降为申请侧意向）。按数电发票号码幂等；数据只到 2026-08。'
  },
  invoiceSales: {
    placeholder: '搜索发票号 / 购买方 / 付款方式 / 业务员 / 状态',
    hint: '销项发票（V312，第 16 类，税务平台全量导出）：实开口径权威源·销项侧 + 多次收款登记子行。发票侧收款仅用于核对销售出库回款（收款权威=销售出库，不写收款事实）；「备注(平台)」列承载红冲链（被红冲蓝字发票号），负数票=红字发票。'
  },
  supplierPayable: {
    placeholder: '搜索供应商 / 月份 / 付款方式 / 折让',
    hint: '供应商应付（V313，第 17 类，《2026年各供应商明细.xlsx》）：55+ 张供应商 sheet 聚合的人工月度台账（应付/结算/折让/付款/剩余），补库内采购单应付侧残缺（paid_amount 全 0）。折让为独立一等概念；尾部 RK 明细行不入库（库内 purchase_receipts 已覆盖，实测 1064/1064 命中）；汇总 sheet 不导入。'
  },
  cashflowJournal: {
    placeholder: '搜索账户 / 摘要 / 供应商 / 客户 / 报销人',
    hint: '流水日记账（V315，第 18 类，《2026年流水日记账.xlsx》）：35 张账户 sheet 行级聚合 7 588 行分录（日期/收入/支出/结存 + 供应商/客户/报销人/是否开票人工标注），「上期结存」期初行转写账户期初余额（34 张有期初行），逐月空白填充行不入库。本表定位=校验源（收款权威=销售出库，§9.3），只核对不回写；汇总/Sheet2 字典/收银通 sheet 均不导入。'
  },
  cashierLog: {
    placeholder: '搜索摘要 / 业务员 / 付款人识别号 / 状态',
    hint: '收银通收单流水（V316，第 19 类，同文件「利强收银通」sheet）：6 088 行微信收单记录，幂等键=交易完成时间#金额#付款人识别号。401 行缺摘要/业务员（openid 未在人工字典 Sheet2 中）照常入库并告警；「订单总扣费」为手续费（源值带负号，原样存）。本表定位=校验源，只核对不回写。'
  }
}
const meta = computed(() => META[props.ledgerType])

const keyword = ref(props.initialKeyword ?? '')
const items = ref<LedgerRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

// ---- sheet 维度汇总条（仅 cashflowJournal / supplierPayable）：账户/供应商下拉 + 小计 ----
const facetLoading = ref(false)
const accountSummaries = ref<CashAccountSummary[]>([])
const accountFilter = ref('')
const supplierSummaries = ref<PayableSupplierSummary[]>([])
const supplierFilter = ref('')
const selectedAccount = computed(() => accountSummaries.value.find((a) => a.accountName === accountFilter.value) || null)
const selectedSupplier = computed(() => supplierSummaries.value.find((s) => s.supplierName === supplierFilter.value) || null)
/** 全账户合计：收入/支出为各账户原值直接相加（外币账户存在时以提示标注，不折算） */
const cashflowAgg = computed(() => ({
  income: accountSummaries.value.reduce((s, a) => s + Number(a.income || 0), 0),
  expense: accountSummaries.value.reduce((s, a) => s + Number(a.expense || 0), 0),
  foreignCount: accountSummaries.value.filter((a) => a.currency && a.currency !== 'CNY').length
}))
const payableAgg = computed(() => ({
  payable: supplierSummaries.value.reduce((s, x) => s + Number(x.payableAmount || 0), 0),
  allowance: supplierSummaries.value.reduce((s, x) => s + Number(x.allowance || 0), 0),
  pay: supplierSummaries.value.reduce((s, x) => s + Number(x.payAmount || 0), 0),
  remaining: supplierSummaries.value.reduce((s, x) => s + Number(x.remainingAmount || 0), 0)
}))
/** 汇总条数据独立于列表加载；失败只降级为无下拉，不阻断台账本身 */
async function loadFacets() {
  if (props.ledgerType !== 'cashflowJournal' && props.ledgerType !== 'supplierPayable') return
  facetLoading.value = true
  try {
    if (props.ledgerType === 'cashflowJournal') accountSummaries.value = (await fetchCashflowAccounts()).items
    else supplierSummaries.value = (await fetchPayableSuppliers()).items
  } catch {
    /* 汇总条非关键路径，静默降级 */
  } finally {
    facetLoading.value = false
  }
}

async function reload(p?: number | undefined) {
  if (p !== undefined) page.value = p
  loading.value = true
  try {
    const r = await FETCHERS[props.ledgerType]({
      keyword: keyword.value || undefined,
      account: accountFilter.value || undefined,
      supplier: supplierFilter.value || undefined,
      page: page.value,
      size: size.value
    })
    items.value = r.items
    total.value = r.total
  } catch (e: any) {
    notify.error((e as Error).message || '历史台账加载失败')
  } finally {
    loading.value = false
  }
}

// ---- 台账详情：主记录全字段 + 子表明细（GET /api/h3-imports/ledger/{kind}/{id}） ----
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailData = ref<LedgerDetailResult | null>(null)
const detailError = ref('')
/** 打开某行详情：携带当前台账类型拉取完整主记录与子表，供抽屉展示核对。 */
async function openDetail(row: LedgerRow) {
  const id = Number(row?.id)
  if (!id || Number.isNaN(id)) {
    notify.error('该行缺少有效 ID，无法查看详情')
    return
  }
  detailData.value = null
  detailError.value = ''
  detailVisible.value = true
  detailLoading.value = true
  try {
    detailData.value = await fetchLedgerDetail(props.ledgerType, id)
  } catch (e: any) {
    detailError.value = (e as Error).message || '详情加载失败'
    notify.error(detailError.value)
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  const id = Number(detailData.value?.row?.id)
  if (!Number.isSafeInteger(id) || id <= 0) return
  detailData.value = await fetchLedgerDetail(props.ledgerType, id)
  await reload()
}

/** 状态标签配色：结清/完成/已生效=绿；进行/部分=蓝/橙；其余中性。 */
function tagType(v?: string): 'success' | 'warning' | 'primary' | 'info' {
  if (!v) return 'info'
  return /完成|结清|收讫|成功|已生效|生效/i.test(v) ? 'success' : /部分|进行中|生产中|进行/i.test(v) ? 'warning' : 'primary'
}
function natureRowLabel(row: LedgerRow) {
  const value = row.businessTypeConfirmed ? row.confirmedBusinessType : row.inferredBusinessType
  const labels: Record<string, string> = {
    SALE: '正常销售', RETURN: '退货', EXCHANGE: '换货', RETURN_OR_EXCHANGE: '待确认退换货'
  }
  return labels[String(value || '')] || '待识别'
}
function natureTagType(row: LedgerRow): 'success' | 'warning' | 'danger' | 'info' {
  const value = row.businessTypeConfirmed ? row.confirmedBusinessType : row.inferredBusinessType
  if (value === 'SALE') return 'success'
  return row.businessTypeConfirmed ? 'warning' : 'danger'
}
function money(v: any) {
  return v == null ? '—' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function num(v: any) {
  return v == null ? '—' : Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
function dateCell(_r: any, _c: any, v: any) {
  return v ? String(v).slice(0, 10) : '—'
}
function dateTimeCell(_r: any, _c: any, v: any) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '—'
}
/** 流水日记账外币列（汇率/汇兑损益）仅在本页出现外币行时展示（人民币账户 35 张中 32 张无此列）。 */
const fxMode = computed(() => items.value.some((r) => r.fxRate != null || r.fxGainLoss != null))

defineExpose({ reload, keyword, items, total, page, size, loading })

onMounted(() => {
  reload(1)
  loadFacets()
})
</script>

<style scoped>
.ledger-panel { width: 100%; }
.ledger-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; }
.ledger-search-icon { color: var(--color-text-faint, #94a3b8); }
.ledger-toolbar__hint {
  color: var(--color-text-muted, #94a3b8);
  font-size: 12px;
  margin-left: 6px;
}
.ledger-toolbar__right {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.ledger-toolbar__count {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 14px;
  border-radius: var(--radius-pill, 999px);
  background: var(--color-surface-alt, #f6f8fa);
  border: 1px solid var(--color-border-lighter, #eef2f6);
  color: var(--color-text-muted, #64748b);
  font-size: 12px;
  white-space: nowrap;
}
.ledger-toolbar__count b {
  color: var(--color-text-heading, #0f172a);
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.ledger-pager { display: flex; justify-content: flex-end; margin-top: 14px; }
/* sheet 维度汇总条：下拉 + 小计片（2026-09-21） */
.ledger-facet {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.ledger-facet__select { width: 280px; }
.ledger-facet__stats { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.facet-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border-radius: var(--radius-pill, 999px);
  background: var(--color-surface-alt, #f6f8fa);
  border: 1px solid var(--color-border-lighter, #eef2f6);
  color: var(--color-text-muted, #64748b);
  font-size: 12px;
  white-space: nowrap;
}
.facet-chip b {
  color: var(--color-text-heading, #0f172a);
  font-variant-numeric: tabular-nums;
}
.facet-chip--in b { color: var(--color-danger, #c0392b); }
.facet-chip--out b { color: var(--color-success, #1e8e5a); }
.facet-chip--muted { border-style: dashed; }
.facet-option { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.facet-option__right { color: var(--color-text-muted, #94a3b8); font-variant-numeric: tabular-nums; }
.expand-body { padding: 4px 8px 12px; }
.expand-hint { margin: 0 0 8px; font-size: 12px; color: var(--color-text-muted, #64748b); }
.expand-empty { margin: 4px 0; font-size: 12px; color: var(--color-text-muted, #64748b); }
.muted { color: var(--color-text-muted, #94a3b8); }

</style>
