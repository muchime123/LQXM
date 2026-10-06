<template>
  <div class="inventory-page">
    <!-- 统计卡片：口径为当前筛选条件下的全量库存（后端聚合），与分页无关 -->
    <section class="stats-grid" v-if="!loading || items.length">
      <div class="stat-card">
        <span class="stat-card__label">记录条数</span>
        <span class="stat-card__value">{{ summary.total }}</span>
        <span class="stat-card__hint" v-if="summary.zeroCount > 0">含零库存 {{ summary.zeroCount }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">产品种类</span>
        <span class="stat-card__value">{{ summary.productCount }}</span>
      </div>
      <div class="stat-card stat-card--accent">
        <span class="stat-card__label">库存总金额</span>
        <span class="stat-card__value">¥{{ totalAmountText }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">有货仓库数</span>
        <span class="stat-card__value">{{ summary.warehouseCount }}</span>
      </div>
    </section>

    <!-- 主体面板 -->
    <section class="panel">
      <!-- 搜索栏 -->
      <div class="toolbar">
        <div class="toolbar__left">
          <el-select v-model="warehouseId" clearable placeholder="全部仓库" style="width:140px" @change="load(1)">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.warehouseName" />
          </el-select>
          <el-select v-model="categoryFilter" clearable placeholder="全部类目" style="width:150px" @change="load(1)">
            <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
          </el-select>
          <!-- 生命周期（V304）：产品永不物理删除，下架是标注不是删除 -->
          <el-select v-model="shelfFilter" style="width:110px" @change="load(1)">
            <el-option value="ALL" label="全部状态" />
            <el-option value="ON_SHELF" label="在用" />
            <el-option value="OFF_SHELF" label="下架" />
          </el-select>
          <el-input v-model="keyword" clearable placeholder="编号 / 名称 / 规格" style="width:220px"
            @keyup.enter="load(1)" @clear="load(1)">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="primary" plain @click="load(1)">查询</el-button>
        </div>
        <div class="toolbar__right">
          <el-button type="warning" plain @click="configuredReviewOpen = true">配置产品审核</el-button>
          <el-checkbox v-model="hideZero" @change="load(1)">仅看有库存</el-checkbox>
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="category">按类目</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <!-- 口径说明：本页是「氚云库存快照」（产品信息台账 × 仓库明细的组合视图），不是实时库存账 -->
      <div class="scope-note">
        <span><b>氚云库存快照</b> = 仓库明细提供「仓库 × 数量」，产品信息台账提供名称 / 规格 / 单位 / <b>采购价（唯一价格来源）</b></span>
        <span class="scope-note__sep">·</span>
        <span>清单 = 两张台账并集（{{ fmtNum(summary.total) }} 行 / {{ fmtNum(summary.productCount) }} 个产品）</span>
        <span class="scope-note__sep">·</span>
        <span>仓库「未记录」= 只在产品信息台账有、仓库明细无该产品</span>
        <span class="scope-note__sep">·</span>
        <span><el-icon class="warn-icon"><WarningFilled /></el-icon> = 编号在两张台账里指向不同产品，采购价待核对</span>
        <template v-if="snap?.catalogBatch?.id && snap?.stockBatch?.id">
          <span class="scope-note__sep">·</span>
          <span :class="{ 'snap-gap-warn': gapWarn }">
            来源批次：产品信息 #{{ snap.catalogBatch.id }} / 仓库明细 #{{ snap.stockBatch.id }}
            <template v-if="snap.gapSeconds != null">（导出时间差 {{ gapText }}<template v-if="gapWarn">，两份文件非同一时点导出，金额口径可能偏</template>）</template>
          </span>
        </template>
        <span class="scope-note__sep">·</span>
        <span v-if="snap && snap.affectedCount > 0">
          <el-button link type="warning" size="small" @click="openIssues()">
            待处理异常 {{ snap.affectedCount }} 行会影响库存金额（缺产品有量 {{ snap.missingProductInStockCount }} / 缺价 {{ snap.missingPriceInStockCount }}）→
          </el-button>
          <span v-if="snap.pendingCount > snap.affectedCount" class="scope-note__quiet">
            （另有 {{ snap.pendingCount - snap.affectedCount }} 行零库存残留，金额本就是 0）
          </span>
        </span>
        <span v-else-if="snap && snap.pendingCount > 0" class="scope-note__quiet">
          影响金额的异常 0 行；另有 {{ snap.pendingCount }} 行零库存残留
          <el-button link type="primary" size="small" @click="openIssues()">查看 →</el-button>
        </span>
        <span v-else-if="snap" class="scope-note__ok">缺价 / 缺产品异常 0 行</span>
        <template v-if="snap && snap.disposedCount > 0">
          <span class="scope-note__sep">·</span>
          <span class="scope-note__quiet">已处置 {{ snap.disposedCount }} 行</span>
        </template>
        <template v-if="snap && snap.staleCatalogCount + snap.staleStockCount > 0">
          <span class="scope-note__sep">·</span>
          <span class="scope-note__quiet">
            覆盖报告 {{ snap.staleCatalogCount + snap.staleStockCount }} 行超 {{ snap.staleDays }} 天未被导入覆盖
            <el-button link type="warning" size="small" @click="openIssues()">查看 →</el-button>
          </span>
        </template>
      </div>

      <!-- 表格视图 -->
      <el-table v-if="viewMode === 'table'" :data="items" v-loading="loading" stripe style="width:100%"
        :max-height="tableMaxHeight">
        <el-table-column label="仓库" width="90">
          <template #default="{ row }">
            <span v-if="row.warehouseName">{{ row.warehouseName }}</span>
            <span v-else class="cell-muted">未记录</span>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="类目" width="130" show-overflow-tooltip />
        <el-table-column label="状态" width="72">
          <template #default="{ row }">
            <el-popconfirm v-if="row.shelfStatus"
              :title="row.shelfStatus === 'OFF_SHELF' ? '恢复为「在用」？' : '标记为「下架」？（是标注不是删除，库存金额照算）'"
              width="240" @confirm="toggleShelf(row)">
              <template #reference>
                <el-tag size="small" effect="plain" style="cursor:pointer"
                  :type="row.shelfStatus === 'OFF_SHELF' ? 'info' : 'success'">
                  {{ SHELF_STATUS_TEXT[row.shelfStatus] || row.shelfStatus }}
                </el-tag>
              </template>
            </el-popconfirm>
            <span v-else class="cell-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="编号" width="135" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.productCode }}</span>
            <el-tooltip v-if="row.codeConflict" placement="top"
              content="该编号在产品信息台账与仓库明细里指向不同产品（氚云编号漂移），本行采购价可能取自别的产品，请人工核对">
              <el-icon class="warn-icon"><WarningFilled /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="产品名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.productName }}</span>
            <el-tag v-if="row.isCustomized" size="small" type="warning" effect="plain" style="margin-left:6px">非标</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="specification" label="规格" width="110" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="75" />
        <el-table-column prop="quantity" label="数量" width="75" align="right">
          <template #default="{ row }">
            <span :class="{ 'qty-zero': !Number(row.quantity), 'qty-negative': Number(row.quantity) < 0 }">{{ fmtNum(row.quantity) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="purchasePrice" label="采购价" width="120" align="right"
          :formatter="(_r: any, _c: any, v: any) => v != null ? '¥' + Number(v).toFixed(2) : '—'" />
        <el-table-column label="库存金额" width="130" align="right">
          <template #default="{ row }">
            <span :class="{ 'amt-highlight': row.stockAmount > 0 }">
              {{ row.stockAmount != null ? '¥' + Number(row.stockAmount).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 按类目分组视图 -->
      <div v-else class="category-view" v-loading="loading" :style="{ maxHeight: tableMaxHeight + 'px' }">
        <div v-for="grp in groupedItems" :key="grp.category" class="category-group">
          <div class="category-group__head" @click="grp.collapsed = !grp.collapsed">
            <el-icon class="collapse-icon" :class="{ 'is-collapsed': grp.collapsed }"><ArrowDown /></el-icon>
            <span class="category-group__name">{{ grp.category || '未分类' }}</span>
            <span class="category-group__meta">{{ grp.items.length }} 种 · ¥{{ fmtMoney(grp.amount) }}</span>
          </div>
          <el-table v-show="!grp.collapsed" :data="grp.items" stripe size="small">
            <el-table-column label="仓库" width="85">
              <template #default="{ row }">
                <span v-if="row.warehouseName">{{ row.warehouseName }}</span>
                <span v-else class="cell-muted">未记录</span>
              </template>
            </el-table-column>
            <el-table-column label="编号" width="135" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.productCode }}</span>
                <el-tooltip v-if="row.shelfStatus === 'OFF_SHELF'" placement="top" content="该产品已标记下架（本地生命周期状态，不影响库存金额）">
                  <el-tag size="small" type="info" effect="plain" style="margin-left:4px">下架</el-tag>
                </el-tooltip>
                <el-tooltip v-if="row.codeConflict" placement="top"
                  content="该编号在产品信息台账与仓库明细里指向不同产品（氚云编号漂移），本行采购价可能取自别的产品">
                  <el-icon class="warn-icon"><WarningFilled /></el-icon>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="产品名称" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.productName }}</span>
                <el-tag v-if="row.isCustomized" size="small" type="warning" effect="plain" style="margin-left:6px">非标</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="specification" label="规格" width="110" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="50" />
            <el-table-column prop="quantity" label="数量" width="80" align="right">
              <template #default="{ row }">
                <span :class="{ 'qty-zero': !Number(row.quantity), 'qty-negative': Number(row.quantity) < 0 }">{{ fmtNum(row.quantity) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="purchasePrice" label="采购价" width="90" align="right"
              :formatter="(_r: any, _c: any, v: any) => v != null ? '¥' + Number(v).toFixed(2) : '—'" />
            <el-table-column label="库存金额" width="115" align="right">
              <template #default="{ row }">
                <span :class="{ 'amt-highlight': row.stockAmount > 0 }">
                  {{ row.stockAmount != null ? '¥' + fmtMoney(row.stockAmount) : '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-empty v-if="groupedItems.length === 0" description="暂无库存数据" />
      </div>

      <!-- 分页 -->
      <div class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
          :page-sizes="[20, 50, 100]" layout="total,sizes,prev,pager,next"
          @current-change="load()" @size-change="load(1)" />
      </div>
    </section>

    <!-- 快照待处理异常抽屉：口径与清单一致 + 按是否影响金额分层 + 可标记处置结论 -->
    <el-drawer v-model="issuesOpen" title="库存快照待处理异常" size="min(980px,94vw)">
      <template v-if="snap">
        <div class="issue-summary">
          <div class="issue-summary__counts">
            <span>影响库存金额 <b class="issue-summary__strong">{{ snap.affectedCount }}</b> 行</span>
            <span class="issue-summary__sep">·</span>
            <span>待处理合计 {{ snap.pendingCount }} 行<template v-if="snap.pendingCount > snap.affectedCount">（含零库存残留 {{ snap.pendingCount - snap.affectedCount }} 行）</template></span>
            <span class="issue-summary__sep">·</span>
            <span>已处置 {{ snap.disposedCount }} 行</span>
          </div>
          <el-button size="small" :loading="exporting" @click="exportIssues">导出全量明细 CSV</el-button>
        </div>
        <p class="issue-summary__note">
          口径已与页面清单对齐：仓库映射不上的行、以及「数量」列里装的是金额的费用项（运费 / 优惠 / 加急费等）都不计入本清单<template v-if="snap.feeExcludedCount > 0">，本次自动排除 {{ snap.feeExcludedCount }} 行费用项</template>。数量为 0 的行金额本就是 0，属于台账残留，不影响库存总额。
        </p>

        <div class="issue-block">
          <div class="issue-block__head">
            <span class="issue-block__title">缺产品信息</span>
            <span class="issue-block__meta">待处理 {{ snap.missingProductCount }} 行 / {{ snap.missingProductCodes }} 个编号 · 其中影响金额 {{ snap.missingProductInStockCount }} 行</span>
          </div>
          <p class="issue-hint">
            仓库明细有该编号、产品信息台账没有：数量有效，但采购价缺失，库存金额算不出。
            在氚云产品信息表补建产品（含采购价）后<strong>重新导入产品信息台账</strong>即可补齐——台账按编号增量写入，不需要清库，也不必重传仓库明细。
          </p>
          <el-table :data="snap.missingProductRows" stripe size="small" max-height="320">
            <el-table-column prop="productCode" label="编号" width="115" show-overflow-tooltip />
            <el-table-column prop="productName" label="产品名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="warehouseName" label="仓库" width="85" show-overflow-tooltip />
            <el-table-column prop="specification" label="规格" width="95" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="55" />
            <el-table-column prop="quantity" label="数量" width="75" align="right" :formatter="(_r: any, _c: any, v: any) => fmtNum(v)" />
            <el-table-column label="操作" width="86" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDispose(row)">标记处置</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="无待处理缺产品行" :image-size="60" /></template>
          </el-table>
        </div>

        <div class="issue-block">
          <div class="issue-block__head">
            <span class="issue-block__title">缺采购价</span>
            <span class="issue-block__meta">待处理 {{ snap.missingPriceCount }} 行 / {{ snap.missingPriceCodes }} 个编号</span>
          </div>
          <p class="issue-hint">
            编号在两张台账都能对上、数量非 0，但产品信息台账采购价为空或 0，该行库存金额为空。
            在氚云补价后重新导入产品信息台账；若属设备 / 配件 / 模具类本就不按库存金额计价，可标记「无需计价」。
          </p>
          <el-table :data="snap.missingPriceRows" stripe size="small" max-height="320">
            <el-table-column prop="productCode" label="编号" width="115" show-overflow-tooltip />
            <el-table-column prop="productName" label="产品名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="warehouseName" label="仓库" width="85" show-overflow-tooltip />
            <el-table-column prop="specification" label="规格" width="95" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="55" />
            <el-table-column prop="quantity" label="数量" width="75" align="right" :formatter="(_r: any, _c: any, v: any) => fmtNum(v)" />
            <el-table-column label="操作" width="86" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDispose(row)">标记处置</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="无待处理缺价行" :image-size="60" /></template>
          </el-table>
        </div>

        <div v-if="snap.disposedRows && snap.disposedRows.length" class="issue-block">
          <div class="issue-block__head">
            <span class="issue-block__title">已处置</span>
            <span class="issue-block__meta">共 {{ snap.disposedCount }} 行 · 下表最多显示 200 行</span>
          </div>
          <p class="issue-hint">已给出结论的行不再计入待处理。标记按「仓库 + 编号 + 单位」持久化，重导台账后依然生效；判错了可以撤销。</p>
          <el-table :data="snap.disposedRows" stripe size="small" max-height="300">
            <el-table-column prop="productCode" label="编号" width="115" show-overflow-tooltip />
            <el-table-column prop="productName" label="产品名称" min-width="150" show-overflow-tooltip />
            <el-table-column prop="warehouseName" label="仓库" width="80" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="55" />
            <el-table-column label="异常类型" width="92">
              <template #default="{ row }">{{ issueTypeText(row.issueType) }}</template>
            </el-table-column>
            <el-table-column label="结论" width="92">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="row.dispositionStatus === 'PENDING_H3' ? 'warning' : 'info'">
                  {{ statusText(row.dispositionStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="dispositionRemark" label="备注" min-width="100" show-overflow-tooltip />
            <el-table-column prop="dispositionBy" label="处置人" width="78" show-overflow-tooltip />
            <el-table-column label="操作" width="64" fixed="right">
              <template #default="{ row }">
                <el-button link type="danger" size="small" @click="undoDispose(row)">撤销</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <!-- 导入覆盖报告（V304）：长期未被任何导入文件覆盖 = 氚云导出里可能已没有这行。软提示，绝不自动判定为删除 -->
        <div v-if="snap.staleCatalogCount > 0 || snap.staleStockCount > 0" class="issue-block">
          <div class="issue-block__head">
            <span class="issue-block__title">导入覆盖报告</span>
            <span class="issue-block__meta">超过 {{ snap.staleDays }} 天未被任何导入文件覆盖 · 产品信息 {{ snap.staleCatalogCount }} 行 / 仓库明细 {{ snap.staleStockCount }} 行</span>
          </div>
          <p class="issue-hint">
            这些行最近 {{ snap.staleDays }} 天没有出现在任何一次导入文件里——可能是氚云已停用 / 删除，也可能只是导出文件没覆盖到这一段。
            <strong>系统不会自动判定为删除</strong>：确认氚云确实不再维护的，标记「下架」（下架是标注不是删除，库存金额照算，之后不再提示）；
            其余建议下次导出全量文件核对。仓库明细行可在清单里用「异常处置」记录结论。
          </p>
          <el-table v-if="snap.staleCatalogRows.length" :data="snap.staleCatalogRows" stripe size="small" max-height="260">
            <el-table-column prop="productCode" label="编号" width="115" show-overflow-tooltip />
            <el-table-column prop="productName" label="产品名称" min-width="150" show-overflow-tooltip />
            <el-table-column prop="category" label="类目" width="110" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="55" />
            <el-table-column prop="purchasePrice" label="采购价" width="90" align="right"
              :formatter="(_r: any, _c: any, v: any) => v != null ? '¥' + Number(v).toFixed(2) : '—'" />
            <el-table-column label="最后覆盖" width="100">
              <template #default="{ row }">{{ shortDate(row.lastSeenAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="warning" size="small" @click="markStaleOffShelf(row)">标记下架</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="产品信息台账无未覆盖行" :image-size="60" /></template>
          </el-table>
          <el-table v-if="snap.staleStockRows.length" :data="snap.staleStockRows" stripe size="small" max-height="220" style="margin-top:10px">
            <el-table-column prop="productCode" label="编号" width="115" show-overflow-tooltip />
            <el-table-column prop="productName" label="产品名称" min-width="150" show-overflow-tooltip />
            <el-table-column prop="warehouse" label="仓库" width="90" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="55" />
            <el-table-column prop="quantity" label="数量" width="75" align="right" :formatter="(_r: any, _c: any, v: any) => fmtNum(v)" />
            <el-table-column label="最后覆盖" width="100">
              <template #default="{ row }">{{ shortDate(row.lastSeenAt) }}</template>
            </el-table-column>
            <template #empty><el-empty description="仓库明细无未覆盖行" :image-size="60" /></template>
          </el-table>
          <p v-if="snap.staleCatalogCount > snap.staleCatalogRows.length || snap.staleStockCount > snap.staleStockRows.length" class="issue-hint">
            每类仅显示前 200 行；其余在下次导出全量文件后自然收敛。
          </p>
        </div>
      </template>
      <el-empty v-else description="快照信息加载中…" />
    </el-drawer>

    <!-- 标记处置结论：只记录人的判断，不改台账数据（采购价唯一来源仍是氚云） -->
    <el-dialog v-model="disposeOpen" title="标记异常处置结论" width="min(560px,92vw)" append-to-body>
      <div class="dispose-target">
        <div><span class="dispose-target__label">编号</span>{{ disposeForm.productCode }}</div>
        <div><span class="dispose-target__label">仓库 / 单位</span>{{ disposeForm.warehouseName }} / {{ disposeForm.unit || '—' }}</div>
        <div><span class="dispose-target__label">异常类型</span>{{ issueTypeText(disposeForm.issueType) }}</div>
      </div>
      <el-radio-group v-model="disposeForm.status" class="dispose-options">
        <el-radio value="PENDING_H3">
          氚云待补
          <span class="dispose-options__hint">已确认是真缺，回氚云补产品 / 补采购价后重导</span>
        </el-radio>
        <el-radio value="DATA_RESIDUE">
          历史残留
          <span class="dispose-options__hint">氚云产品信息表已无此产品（打样 / 定做 / 设备类用完即移除），无需处理</span>
        </el-radio>
        <el-radio value="NO_PRICE_NEEDED">
          无需计价
          <span class="dispose-options__hint">设备 / 配件 / 模具类不按库存金额计价，接受价格为空</span>
        </el-radio>
      </el-radio-group>
      <el-input v-model="disposeForm.remark" type="textarea" :rows="2" maxlength="200" show-word-limit
        placeholder="备注（可选），例如「已反馈采购确认价格」" />
      <template #footer>
        <el-button @click="disposeOpen = false">取消</el-button>
        <el-button type="primary" :loading="disposing" @click="submitDispose">确认标记</el-button>
      </template>
    </el-dialog>

    <!-- 库存单行详情：本行快照 + 两份来源台账行 + 跨仓库分布 + 更新明细 -->
    <ProductInventoryDetailDrawer v-model="detailOpen" :row="detailRow" />
    <ConfiguredProductReviewDialog v-model="configuredReviewOpen" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive, onUnmounted } from 'vue'
import { Search, ArrowDown, WarningFilled } from '@element-plus/icons-vue'
import { fetchProductInventory, fetchProductInventorySnapshotInfo, fetchInventoryIssues, disposeInventoryIssue, undoInventoryIssueDisposition, setProductShelfStatus, SHELF_STATUS_TEXT, INVENTORY_ISSUE_TYPE_TEXT, INVENTORY_ISSUE_STATUS_TEXT, type ProductInventoryBalance, type ProductInventorySummary, type ProductInventorySnapshotInfo, type SnapshotIssueRow, type StaleCatalogRow, type InventoryIssueType, type InventoryIssueStatus } from '@/api/productInventory'
import { notify } from '@/composables/ui/useToast'
import { downloadBlob } from '@/utils/ui/downloadBlob'
import ProductInventoryDetailDrawer from '@/components/production/ProductInventoryDetailDrawer.vue'
import ConfiguredProductReviewDialog from '@/components/production/ConfiguredProductReviewDialog.vue'

const keyword = ref('')
const warehouseId = ref<number>()
const categoryFilter = ref<string>()
const page = ref(1)
const size = ref(50)
const total = ref(0)
const items = ref<ProductInventoryBalance[]>([])
const warehouses = ref<Array<{ id: number; warehouseCode: string; warehouseName: string }>>([])
const loading = ref(false)
// 默认展示完整清单（含零库存产品）；勾选后退化为「仅有库存」口径
const hideZero = ref(false)
// 生命周期筛选（V304）：产品永不物理删除，下架是标注不是删除
const shelfFilter = ref<'ALL' | 'ON_SHELF' | 'OFF_SHELF'>('ALL')
const viewMode = ref<'table' | 'category'>('table')

/** 生命周期切换：同一编号在多仓库多行，状态是产品级的，一起变 */
async function toggleShelf(row: ProductInventoryBalance) {
  if (!row.shelfStatus) return
  const next = row.shelfStatus === 'OFF_SHELF' ? 'ON_SHELF' : 'OFF_SHELF'
  try {
    await setProductShelfStatus([row.productCode], next)
    notify.success(`已${next === 'OFF_SHELF' ? '下架' : '恢复在用'}：${row.productCode}`)
    await Promise.all([load(), loadSnap()])
  } catch (e) {
    notify.error((e as Error).message || '操作失败')
  }
}

/** 覆盖报告里的一键下架（产品信息台账行） */
async function markStaleOffShelf(row: StaleCatalogRow) {
  try {
    await setProductShelfStatus([row.productCode], 'OFF_SHELF')
    notify.success(`已下架：${row.productCode}（后续覆盖报告不再提示该行）`)
    await loadSnap()
  } catch (e) {
    notify.error((e as Error).message || '操作失败')
  }
}

const shortDate = (s?: string | null) => (s ? String(s).slice(0, 10) : '—')

// 单行详情抽屉（2026-09-16）：定位键 = 仓库 + 编号 + 单位（V295 后同仓同编号可存在多单位行）
const detailOpen = ref(false)
const configuredReviewOpen = ref(false)
const detailRow = ref<ProductInventoryBalance | null>(null)
function openDetail(row: ProductInventoryBalance) {
  detailRow.value = row
  detailOpen.value = true
}

// ===== 快照元信息与待处理异常（非阻断：加载失败只影响口径条与异常入口，不影响清单本身） =====
const snap = ref<ProductInventorySnapshotInfo | null>(null)
const issuesOpen = ref(false)
/** 两份来源批次导出时间差超过 24h 视为非同一时点，黄标提示 */
const gapWarn = computed(() => snap.value?.gapSeconds != null && snap.value.gapSeconds > 24 * 3600)
const gapText = computed(() => {
  const s = snap.value?.gapSeconds
  if (s == null) return ''
  if (s < 60) return `${s} 秒`
  if (s < 3600) return `${Math.round(s / 60)} 分钟`
  if (s < 86400) return `${(s / 3600).toFixed(1)} 小时`
  return `${(s / 86400).toFixed(1)} 天`
})
async function loadSnap() {
  try { snap.value = await fetchProductInventorySnapshotInfo() } catch { /* 快照信息失败不阻断清单 */ }
}

// ===== 异常处置（只写结论，不改台账；采购价唯一来源仍是氚云） =====
const issueTypeText = (t: string) => INVENTORY_ISSUE_TYPE_TEXT[t as InventoryIssueType] || t
const statusText = (s: string) => INVENTORY_ISSUE_STATUS_TEXT[s as InventoryIssueStatus] || s

const disposeOpen = ref(false)
const disposing = ref(false)
const exporting = ref(false)
const disposeForm = reactive({
  warehouseName: '',
  productCode: '',
  unit: '',
  issueType: 'MISSING_PRODUCT' as InventoryIssueType,
  status: 'PENDING_H3' as InventoryIssueStatus,
  remark: ''
})

/** 打开抽屉前重拉快照：计数与明细都是处置后的最新状态 */
function openIssues() {
  issuesOpen.value = true
  loadSnap()
}

function openDispose(row: SnapshotIssueRow) {
  disposeForm.warehouseName = row.warehouseName || ''
  disposeForm.productCode = row.productCode
  disposeForm.unit = row.unit || ''
  disposeForm.issueType = row.issueType
  disposeForm.status = 'PENDING_H3'
  disposeForm.remark = ''
  disposeOpen.value = true
}

async function submitDispose() {
  disposing.value = true
  try {
    await disposeInventoryIssue({
      warehouseName: disposeForm.warehouseName,
      productCode: disposeForm.productCode,
      unit: disposeForm.unit,
      issueType: disposeForm.issueType,
      status: disposeForm.status,
      remark: disposeForm.remark || undefined
    })
    notify.success(`已标记「${statusText(disposeForm.status)}」`)
    disposeOpen.value = false
    await loadSnap()
  } catch (e) {
    notify.error((e as Error).message || '标记失败')
  } finally {
    disposing.value = false
  }
}

async function undoDispose(row: SnapshotIssueRow) {
  try {
    await undoInventoryIssueDisposition({
      warehouseName: row.warehouseName,
      productCode: row.productCode,
      unit: row.unit || ''
    })
    notify.success('已撤销处置结论')
    await loadSnap()
  } catch (e) {
    notify.error((e as Error).message || '撤销失败')
  }
}

/** CSV 单元格转义：含逗号/引号/换行时加引号并双写引号 */
const csvCell = (v: string) => (/[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)

function dateStamp() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`
}

/**
 * 导出全量待处理异常（未处置）。
 *
 * 抽屉每类只回显前 200 行，几百行人工核不完；导出走全量端点，带回处理建议列，
 * 可直接拿去氚云逐条补数。
 */
async function exportIssues() {
  exporting.value = true
  try {
    const rows = await fetchInventoryIssues()
    if (!rows.length) {
      notify.warning('当前没有待处理异常行')
      return
    }
    const head = ['异常类型', '仓库', '产品编号', '产品名称', '规格', '单位', '数量', '处理建议']
    const body = rows.map((r) => [
      issueTypeText(r.issueType),
      r.warehouseName || '',
      r.productCode,
      r.productName || '',
      r.specification || '',
      r.unit || '',
      r.quantity == null ? '' : String(r.quantity),
      r.issueType === 'MISSING_PRODUCT'
        ? '氚云产品信息表补建该产品（含采购价）后重导产品信息台账'
        : '氚云产品信息表补采购价后重导产品信息台账；设备/配件类可标记「无需计价」'
    ])
    const csv = [head, ...body].map((line) => line.map(csvCell).join(',')).join('\r\n')
    // 前置 BOM，Excel 才会按 UTF-8 识别中文
    downloadBlob(
      new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }),
      `库存待处理异常_${dateStamp()}.csv`
    )
  } catch (e) {
    notify.error((e as Error).message || '导出失败')
  } finally {
    exporting.value = false
  }
}

// 全量聚合统计（后端在满足筛选条件的所有记录上计算，与当前分页无关）
const summary = ref<ProductInventorySummary>({ total: 0, productCount: 0, warehouseCount: 0, zeroCount: 0, totalAmount: 0 })
// 类目下拉候选（后端返回，与所选类目条件解耦，保证可随时切换）
const categories = ref<string[]>([])

// 列表可视高度随视口伸缩：既避免矮屏时表格把页面挤出可视区，也避免高屏大量留白
const tableMaxHeight = ref(520)
function updateTableMaxHeight() {
  tableMaxHeight.value = Math.max(360, window.innerHeight - 330)
}
updateTableMaxHeight()
window.addEventListener('resize', updateTableMaxHeight)
onUnmounted(() => window.removeEventListener('resize', updateTableMaxHeight))

const totalAmountText = computed(() => fmtMoney(Number(summary.value.totalAmount) || 0))

// 按类目分组（数据为后端按筛选返回的当前页，组内金额为该页组小计）
const groupedItems = computed(() => {
  const map = new Map<string, { category: string; items: ProductInventoryBalance[]; amount: number; collapsed: boolean }>()
  for (const item of items.value) {
    const cat = item.category || '未分类'
    if (!map.has(cat)) map.set(cat, reactive({ category: cat, items: [], amount: 0, collapsed: false }))
    const g = map.get(cat)!
    g.items.push(item)
    g.amount += Number(item.stockAmount) || 0
  }
  return [...map.values()].sort((a, b) => b.amount - a.amount)
})

const fmtNum = (v: unknown) => Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
const fmtMoney = (v: number) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function load(p?: number) {
  if (p) page.value = p
  loading.value = true
  try {
    const r = await fetchProductInventory({
      keyword: keyword.value || undefined,
      warehouseId: warehouseId.value,
      category: categoryFilter.value || undefined,
      onlyInStock: hideZero.value,
      shelfStatus: shelfFilter.value,
      page: page.value,
      size: size.value
    })
    items.value = r.items
    total.value = r.total
    summary.value = r.summary
    categories.value = r.categories || []
    warehouses.value = r.warehouses
  } catch (e) {
    notify.error((e as Error).message || '库存加载失败')
  } finally {
    loading.value = false
  }
}

await load(1)
loadSnap()
</script>

<style scoped>
.inventory-page { display: grid; gap: 16px; padding: 0 24px 24px; min-width: 0; }
.inventory-page > * { min-width: 0; }

/* 统计卡片 */
.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.stat-card {
  display: flex; flex-direction: column; gap: 6px; padding: 16px 18px;
  background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0);
  border-radius: 12px; box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,.04));
}
.stat-card--accent { background: linear-gradient(135deg, #0f766e 0%, #14b8a6 100%); border-color: transparent; }
.stat-card--accent .stat-card__label { color: rgba(255,255,255,.8); }
.stat-card--accent .stat-card__value { color: #fff; }
.stat-card__label { font-size: 12px; color: var(--color-text-muted, #64748b); font-weight: 500; }
.stat-card__value { font-size: 24px; font-weight: 700; color: var(--color-text, #1f2937); }
.stat-card__hint { font-size: 12px; color: var(--color-text-muted, #64748b); }

/* 零库存行弱化：完整清单口径下与真实库存区分 */
.qty-zero { color: var(--color-text-muted, #94a3b8); }
/* 台账负库存（退货/冲销）用跌色提示，与零库存的灰化区分开 */
.qty-negative { color: var(--color-danger, #dc2626); font-weight: 600; }
.cell-muted { color: var(--color-text-muted, #94a3b8); }
.warn-icon { color: var(--color-warning, #d97706); margin-left: 4px; vertical-align: -2px; }
.scope-note {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
  padding: 8px 12px; margin-bottom: 10px;
  font-size: 12px; color: var(--color-text-muted, #64748b);
  background: var(--color-surface-alt, #f8fafc);
  border: 1px solid var(--color-border, #e2e8f0); border-radius: 8px;
}
.scope-note__sep { color: var(--color-border, #cbd5e1); }
.scope-note__ok { color: var(--color-success, #16a34a); }
/* 零库存残留这类「无害但需说明」的信息：弱化，避免与需要处理的异常抢注意力 */
.scope-note__quiet { color: var(--color-text-muted, #94a3b8); }
.snap-gap-warn { color: var(--color-warning, #d97706); font-weight: 600; }

/* 快照异常抽屉 */
.issue-summary {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
  padding: 10px 12px; margin-bottom: 8px;
  background: var(--color-surface-alt, #f8fafc);
  border: 1px solid var(--color-border, #e2e8f0); border-radius: 8px;
}
.issue-summary__counts { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; color: var(--color-text, #1f2937); }
.issue-summary__strong { font-size: 16px; font-weight: 700; color: var(--color-warning, #d97706); }
.issue-summary__sep { color: var(--color-border, #cbd5e1); }
.issue-summary__note { font-size: 12px; color: var(--color-text-muted, #64748b); margin: 0 0 16px; line-height: 1.6; }
.issue-block { margin-bottom: 22px; }
.issue-block__head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 4px; flex-wrap: wrap; }
.issue-block__title { font-size: 15px; font-weight: 600; color: var(--color-text, #1f2937); }
.issue-block__meta { font-size: 12px; color: var(--color-warning, #d97706); }
.issue-hint { font-size: 12px; color: var(--color-text-muted, #64748b); margin: 0 0 10px; line-height: 1.6; }

/* 处置结论弹窗 */
.dispose-target {
  display: grid; gap: 4px; margin-bottom: 14px; padding: 10px 12px;
  font-size: 13px; color: var(--color-text, #1f2937);
  background: var(--color-surface-alt, #f8fafc);
  border: 1px solid var(--color-border, #e2e8f0); border-radius: 8px;
}
.dispose-target__label { display: inline-block; width: 76px; color: var(--color-text-muted, #64748b); }
.dispose-options { display: grid; gap: 10px; margin-bottom: 14px; }
.dispose-options__hint { margin-left: 6px; font-size: 12px; color: var(--color-text-muted, #94a3b8); }

/* 面板 */
.panel {
  background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0);
  border-radius: 12px; padding: 16px; min-width: 0;
}

/* 搜索栏 */
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
.toolbar__left { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; min-width: 0; }
.toolbar__right { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }

/* 金额高亮 */
.amt-highlight { font-weight: 600; color: var(--color-primary, #0f766e); }
.text-success { color: var(--color-success, #16a34a); font-weight: 600; }
.text-danger { color: var(--color-danger, #dc2626); font-weight: 600; }

/* 类目分组视图：maxHeight 由 JS 按视口动态给定，窄屏下纵向滚动查看全部分组 */
.category-view { overflow-y: auto; min-width: 0; }
.category-group { margin-bottom: 12px; border: 1px solid var(--color-border, #e2e8f0); border-radius: 10px; overflow: hidden; }
.category-group__head {
  display: flex; align-items: center; gap: 8px; padding: 10px 14px; cursor: pointer;
  background: var(--color-fill, #f8fafc); user-select: none; font-size: 14px;
}
.category-group__head:hover { background: var(--color-fill-hover, #f1f5f9); }
.collapse-icon { transition: transform .2s; }
.collapse-icon.is-collapsed { transform: rotate(-90deg); }
.category-group__name { font-weight: 600; }
.category-group__meta { color: var(--color-text-muted, #64748b); font-size: 12px; margin-left: auto; }

/* 分页 */
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }

@media (max-width: 768px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .toolbar { flex-direction: column; align-items: stretch; }
}
</style>
