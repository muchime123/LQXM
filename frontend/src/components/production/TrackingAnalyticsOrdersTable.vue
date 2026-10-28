<template>
  <section class="orders-table-panel">
    <div class="orders-table-head">
      <div>
        <h2>{{ title }}</h2>
        <p>点击工单可打开生产跟单详情。</p>
      </div>
      <el-button @click="emit('close')">收起</el-button>
    </div>
    <!-- 面板内工具条：如节点分析的状态筛选（生产 / 周复盘不用）。 -->
    <slot name="toolbar" />
    <el-table v-loading="loading" :data="items" stripe size="small" :empty-text="emptyText" @row-click="openOrder">
      <!-- 基础列：三个分析抽屉（生产分析 / 周复盘 / 节点分析）下钻列表都有的「工单基本信息」 -->
      <el-table-column label="任务单" min-width="80">
        <template #default="{ row }">
          <button class="orders-table-link" type="button" :title="row.orderNo" @click.stop="openOrder(row)">{{ shortOrderNo(row.orderNo) }} </button>
        </template>
      </el-table-column>
      <el-table-column prop="productName" label="产品" min-width="130" show-overflow-tooltip />
      <el-table-column label="业务员" width="80">
        <template #default="{ row }"><TrackingAnalyticsSalespersonCell :salesperson="row.salesperson" /></template>
      </el-table-column>
      <el-table-column label="产品规格 / 模具出数" min-width="180" show-overflow-tooltip>
        <template #default="{ row }"><TrackingAnalyticsSpecCell :specification="row.specification" :mold-layout="row.moldLayout" /></template>
      </el-table-column>
      <el-table-column prop="workshop" label="车间" min-width="100" show-overflow-tooltip />
      <!-- 附加列：各抽屉自己的业务列（提交/排产/等待/当前预计/整单完工/状态/逾期/超期…） -->
      <slot name="extra" />
    </el-table>
    <el-pagination v-if="total > size" :current-page="page" :page-size="size" :total="total"
      layout="prev, pager, next, total" @current-change="(p: number) => emit('update:page', p)" />
  </section>
</template>

<script setup lang="ts">
/**
 * 分析 / 复盘抽屉的**下钻工单列表**——三个抽屉（生产分析 / 周复盘 / 节点分析）共用。
 *
 * 【为什么单独一个组件】三个抽屉的下钻表此前各自写列，其中「生产任务单 / 产品 / 业务员 /
 * 产品规格·模具出数 / 车间」这 5 列「工单基本信息」三处都有、且渲染完全一致（仅类名不同）；
 * 而其余列（提交 / 排产 / 等待 / 当前预计 / 整单完工日 / 状态 / 逾期 / 超期 / 该节点）各抽屉不同。
 * 这里把**基础 5 列固定渲染**，附加列通过 `#extra` 插槽由各抽屉自己放——既收敛了共用的部分，
 * 又不强迫三抽屉的业务列统一。
 *
 * 【边界】本组件负责「基础列 + 面板外壳（标题/收起/分页/空态文案）+ 点击打开详情」；
 * 右栏的整体布局与空态占位仍由调用方控制。附加列里若有需要 row 的列，用 `<template #default="{ row }">`。
 */
import type { TrackingAnalyticsOrder } from '@/api/productionTracking'
import TrackingAnalyticsSalespersonCell from './TrackingAnalyticsSalespersonCell.vue'
import TrackingAnalyticsSpecCell from './TrackingAnalyticsSpecCell.vue'
// 与生产跟单主列表同一个缩短规则：任务号只显示数字结尾的后四位，完整号作悬浮提示。
import { shortOrderNo } from '@/utils/tracking/rules'

/**
 * 基础列行类型：三个抽屉（生产分析 / 周复盘 / 节点分析）都有的「工单基本信息」字段必填，
 * 业务员 / 规格·模具出数为可选（节点分析的下钻行目前不含这两项，渲染时缺失显示「—」）。
 */
type OrdersTableRow = Pick<TrackingAnalyticsOrder, 'id' | 'orderNo' | 'productName' | 'workshop'> &
  Partial<Pick<TrackingAnalyticsOrder, 'salesperson' | 'specification' | 'moldLayout'>>

withDefaults(
  defineProps<{
    items: OrdersTableRow[]
    total: number
    page: number
    size: number
    /** 面板标题（形如「应完成任务 · 对应工单 N 单」）。 */
    title: string
    loading?: boolean
    emptyText?: string
  }>(),
  { loading: false, emptyText: '没有符合条件的工单' }
)

const emit = defineEmits<{ 'update:page': [page: number]; 'open-order': [id: number]; close: [] }>()

function openOrder(row: OrdersTableRow) {
  emit('open-order', row.id)
}
</script>

<style scoped>
.orders-table-panel {
  border: 1px solid #80c7bf;
  background: #fbfefd;
  border-radius: 12px;
  padding: 18px;
  scroll-margin-top: 8px;
}
.orders-table-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.orders-table-head h2 {
  margin: 0;
  font-size: 18px;
  color: #263448;
}
.orders-table-head p {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 13px;
}
.orders-table-link {
  border: 0;
  background: none;
  padding: 0;
  color: #087c72;
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.orders-table-link:hover {
  text-decoration: underline;
}
:deep(.el-table__row) {
  cursor: pointer;
}
:deep(.el-pagination) {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
