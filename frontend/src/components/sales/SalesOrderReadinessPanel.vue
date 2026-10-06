<template>
  <!--
    就绪度面板：把「还差什么才能送评 / 提交审批」常驻显示出来。

    改造前这些校验只在**点提交那一刻**跑，用户只能「先点、再被打回」；如果前端另写一套判断，
    又会变成「面板说可以提交、提交却被打回」——那是更难查的第二种口径分裂。
    所以这里的判据完全来自后端 `GET /erp-sales-orders/{id}/readiness`，
    与提交时抛异常的 `requireSubmittable` 是同一份实现。
  -->
  <div v-if="orderId && readiness" class="readiness">
    <el-alert v-if="readiness.submitReady" type="success" :closable="false" show-icon
      title="已就绪，可以提交审批" />
    <el-alert v-else type="warning" :closable="false" show-icon
      :title="`还不能提交审批，还差 ${allBlockers.length} 项`" />

    <ul v-if="allBlockers.length" class="readiness__list">
      <li v-for="(item, index) in allBlockers" :key="`${item.code}-${index}`">
        <el-tag size="small" :type="item.code === 'FULFILLMENT_REVIEW' ? 'info' : 'warning'">
          {{ codeText(item.code) }}
        </el-tag>
        <span>{{ item.message }}</span>
      </li>
    </ul>
    <p v-for="(hint, index) in readiness.hints" :key="`hint-${index}`" class="readiness__hint">{{ hint }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fetchSalesOrderReadiness, type SalesOrderReadiness } from '@/api/salesOrder'

const props = defineProps<{
  /** 订单 id；为 null 时不请求（新建未保存的订单还没有就绪度可言）。 */
  orderId: number | null
  /** 递增即重新拉取：单据保存、送评、评审裁决后由父组件 +1。 */
  refreshKey?: number
}>()

const emit = defineEmits<{
  /** 能否送履约评审（配置类阻塞项为空） */
  (event: 'update:reviewReady', value: boolean): void
  /** 能否提交审批（配置类与评审类都不为空才为 true） */
  (event: 'update:submitReady', value: boolean): void
}>()

const readiness = ref<SalesOrderReadiness | null>(null)

const allBlockers = computed(() => [
  ...(readiness.value?.configBlockers ?? []),
  ...(readiness.value?.reviewBlockers ?? []),
])

/** 阻塞项分类到人能看懂的中文；未登记的分类直接显示原始 code，避免静默吞掉新分类。 */
function codeText(code: string) {
  const table: Record<string, string> = {
    NO_ITEMS: '缺明细',
    SUPPLY_MODE: '待定供货方式',
    PURCHASE_CONFIG: '缺采购配置',
    PRODUCTION_CONFIG: '缺产品配置',
    PRODUCTION_CONFIG_INVALID: '产品配置有误',
    FULFILLMENT_REVIEW: '待履约评审',
  }
  return table[code] ?? code
}

async function load() {
  if (!props.orderId) {
    readiness.value = null
    emit('update:reviewReady', true)
    emit('update:submitReady', true)
    return
  }
  try {
    const data = await fetchSalesOrderReadiness(props.orderId)
    readiness.value = data
    emit('update:reviewReady', data.reviewReady)
    emit('update:submitReady', data.submitReady)
  } catch {
    // 就绪度只是提示，取不到时不能挡住正常操作：交回 true，让后端在提交那一刻给出真正的判据。
    readiness.value = null
    emit('update:reviewReady', true)
    emit('update:submitReady', true)
  }
}

onMounted(load)
watch(() => [props.orderId, props.refreshKey], load)
</script>

<style scoped>
.readiness {
  margin: 12px 0;
}

.readiness__list {
  margin: 8px 0 0;
  padding-left: 18px;
  line-height: 1.9;
}

.readiness__list li {
  display: flex;
  align-items: center;
  gap: 8px;
}

.readiness__hint {
  margin: 6px 0 0;
  font-size: 12px;
  opacity: 0.75;
}
</style>
