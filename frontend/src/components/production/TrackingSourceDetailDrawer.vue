<template>
  <el-drawer v-model="visible" :title="kind === 'purchase' ? '采购入库核对' : '销售出库核对'" :size="kind === 'purchase' ? 'min(900px, 94vw)' : 'min(660px, 92vw)'" direction="rtl" append-to-body>
    <div v-loading="loading" class="tracking-source-detail">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template v-else-if="data">
        <p class="tracking-source-note">{{ kind === 'purchase' ? '展示这张入库单的全部产品行及各行关联的生产任务单；当前跟单已标出。' : '仅展示与当前工单产品编号相符的销售出库候选行。' }}价格、金额及往来单位信息已隐藏。</p>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ kind === 'purchase' ? data.row.sourceNo : data.row.outboundNo }}</el-descriptions-item>
          <el-descriptions-item label="日期">{{ kind === 'purchase' ? data.row.purchaseDate : data.row.shipDate || data.row.businessDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ kind === 'purchase' ? data.row.warehouseName || '—' : data.row.warehouse || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ kind === 'purchase' ? data.row.dataStatus || '—' : data.row.status || '—' }}</el-descriptions-item>
        </el-descriptions>
        <h4>关联产品明细</h4>
        <el-table :data="displayRows" border size="small" max-height="420">
          <el-table-column v-if="kind === 'purchase'" label="生产任务单编号" min-width="185">
            <template #default="{ row }">
              <strong v-if="row.productionOrderNo && row.productionOrderNo === data?.row.trackingOrderNo">{{ row.productionOrderNo }} <el-tag size="small" type="success">当前跟单</el-tag></strong>
              <span v-else>{{ row.productionOrderNo || '未关联' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="productCode" label="产品编号" min-width="115" />
          <el-table-column prop="productName" label="产品名称" min-width="190" show-overflow-tooltip />
          <el-table-column :prop="kind === 'purchase' ? 'spec' : 'specification'" label="规格" min-width="120" show-overflow-tooltip />
          <el-table-column prop="quantity" label="数量" width="85" />
          <el-table-column prop="unit" label="单位" width="65" />
        </el-table>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { LedgerDetailResult } from '@/types/h3Import'

const props = defineProps<{ modelValue: boolean; kind: 'purchase' | 'outbound'; loading: boolean; data: LedgerDetailResult | null; error: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const displayRows = computed(() => {
  const rows = [...(props.data?.rows || [])]
  if (props.kind !== 'purchase') return rows
  const currentOrderNo = props.data?.row.trackingOrderNo
  return rows.sort((a, b) => Number(b.productionOrderNo === currentOrderNo) - Number(a.productionOrderNo === currentOrderNo))
})
</script>

<style scoped>
.tracking-source-detail { min-height: 140px; }
.tracking-source-note { color: #667085; font-size: 12px; line-height: 1.5; margin: 0 0 14px; }
h4 { margin: 20px 0 10px; }
</style>
