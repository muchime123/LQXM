<template>
  <el-dialog v-model="visible" title="登记履约风险与处理方案" width="min(720px, 94vw)" append-to-body>
    <el-alert title="内部调整仍能满足原订单时，不必变更客户订单。只有客户承诺需要变化时，才由销售协调客户确认。" type="info" :closable="false" />
    <el-form label-position="top" style="margin-top: 16px">
      <el-form-item label="具体问题与发生的变化" required><el-input v-model="form.reason" type="textarea" maxlength="1000" /></el-form-item>
      <el-form-item label="影响范围"><el-radio-group v-model="form.pauseFollowup"><el-radio :value="false">风险提醒，继续办理</el-radio><el-radio :value="true">暂停本明细后续下达及发货</el-radio></el-radio-group></el-form-item>
      <p class="muted">暂停会阻止本明细新建生产／采购单、采购提交／审批及出库过账；已有任务的报工、检验和到货不会自动停止，需要执行人员协调。</p>
      <el-form-item label="处理方案与执行安排" :required="Boolean(form.proposedDeliveryDate)"><el-input v-model="form.proposedAction" type="textarea" maxlength="1000" placeholder="内部解决方案；或需要销售与客户协商的具体事项" /></el-form-item>
      <el-form-item v-if="allowDeliveryProposal" label="执行方确认的可行客户交期（选填）"><el-date-picker v-model="form.proposedDeliveryDate" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
      <template v-if="form.proposedDeliveryDate">
        <el-table :data="context?.downstreamImpact || []" size="small" border><el-table-column prop="documentNo" label="关联单据" /><el-table-column prop="status" label="状态" /><el-table-column prop="reportedQuantity" label="报工量" /><el-table-column prop="executedQuantity" label="入库／到货量" /></el-table>
        <el-checkbox v-model="coordinated">已核对关联执行情况，此日期是执行方确认可行的客户交期</el-checkbox>
      </template>
    </el-form>
    <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">登记风险与方案</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { fetchSalesExceptionContext, reportSalesExecutionException, type SalesChangeImpact } from '@/api/salesOrder'
import { notify } from '@/composables/ui/useToast'
const emit = defineEmits<{ saved: [] }>()
const visible = ref(false), saving = ref(false), coordinated = ref(false), allowDeliveryProposal = ref(false)
const target = ref({ orderId: 0, itemId: 0 })
const context = ref<{ downstreamImpact: SalesChangeImpact[]; impactToken: string } | null>(null)
const form = reactive({ reason: '', proposedAction: '', pauseFollowup: false, proposedDeliveryDate: '' })
async function open(orderId: number, itemId: number, allowProposal: boolean) {
  try {
    context.value = await fetchSalesExceptionContext(orderId, itemId)
    target.value = { orderId, itemId }
    allowDeliveryProposal.value = allowProposal
    Object.assign(form, { reason: '', proposedAction: '', pauseFollowup: false, proposedDeliveryDate: '' })
    coordinated.value = false
    visible.value = true
  } catch (error) { notify.errorWithDetail('读取关联执行情况失败', error) }
}
async function save() {
  if (!form.reason.trim()) { notify.warning('请填写具体问题'); return }
  if (form.proposedDeliveryDate && (!coordinated.value || !form.proposedAction.trim())) { notify.warning('请核对执行情况并填写可行交期的执行安排'); return }
  saving.value = true
  try {
    await reportSalesExecutionException(target.value.orderId, target.value.itemId, form.reason.trim(), form.proposedAction.trim(), undefined,
      { pauseFollowup: form.pauseFollowup, proposedDeliveryDate: form.proposedDeliveryDate || null, impactToken: context.value?.impactToken })
    notify.success(form.pauseFollowup ? '已登记，暂停该明细后续办理' : '已登记风险提醒，继续办理')
    visible.value = false
    emit('saved')
  } catch (error) { notify.errorWithDetail('登记风险与方案失败', error) }
  finally { saving.value = false }
}
defineExpose({ open })
</script>
<style scoped>.muted { color: var(--el-text-color-secondary); font-size: 12px; }</style>
