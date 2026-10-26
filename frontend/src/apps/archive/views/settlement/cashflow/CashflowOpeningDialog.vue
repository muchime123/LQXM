<template>
  <el-dialog v-model="visible" :title="`${account?.accountName ?? ''} · ${year} 年度期初`" width="620px" destroy-on-close>
    <div v-loading="loading">
      <el-alert type="info" :closable="false" title="这里只确认年度期初金额与财务保管的外部凭据一致；不代表逐笔流水对账或年度结账。" />
      <template v-if="opening">
        <el-descriptions :column="2" border class="cf-opening-details">
          <el-descriptions-item label="状态">{{ statusLabel }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ opening.amount ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ opening.source ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="财务确认">{{ confirmed ? '已确认' : '未确认' }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="opening.openingId">
          <h3>确认记录</h3>
          <el-empty v-if="!history.length" description="暂无确认记录" :image-size="55" />
          <el-descriptions v-for="item in history" :key="item.id" :column="2" border class="cf-opening-details">
            <el-descriptions-item label="确认人">{{ item.confirmedByName }}</el-descriptions-item>
            <el-descriptions-item label="确认时间">{{ item.confirmedAt }}</el-descriptions-item>
            <el-descriptions-item label="金额快照">{{ item.openingAmountSnapshot }}</el-descriptions-item>
            <el-descriptions-item label="余额截至">{{ item.balanceAsOfDate }}</el-descriptions-item>
            <el-descriptions-item label="凭据编号" :span="2">{{ item.evidenceRef }}</el-descriptions-item>
            <el-descriptions-item label="核对说明" :span="2">{{ item.note }}</el-descriptions-item>
          </el-descriptions>
        </template>
        <el-form v-if="canConfirm" label-width="110px" class="cf-opening-details">
          <h3>财务确认</h3>
          <el-form-item label="凭据类型">
            <el-select v-model="form.evidenceType"><el-option label="银行对账单" value="BANK_STATEMENT" /><el-option label="现金盘点" value="CASH_COUNT" /><el-option label="其他" value="OTHER" /></el-select>
          </el-form-item>
          <el-form-item label="余额截至日"><el-date-picker v-model="form.balanceAsOfDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="核对余额"><el-input v-model="form.verifiedBalance" placeholder="最多 4 位小数" /></el-form-item>
          <el-form-item label="外部凭据编号"><el-input v-model="form.evidenceRef" maxlength="255" /></el-form-item>
          <el-form-item label="核对说明"><el-input v-model="form.note" maxlength="255" type="textarea" /></el-form-item>
          <el-button type="primary" :loading="saving" @click="submit">确认年度期初</el-button>
        </el-form>
      </template>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { notify } from '@/composables/ui/useToast'
import { confirmCashflowOpening, fetchCashflowOpening, fetchOpeningConfirmations, type CashflowOpening, type OpeningConfirmation, type ConfirmOpeningRequest } from '@/api/cashflowOpening'
import type { CashflowAccount } from '@/api/cashflowJournal'

const props = defineProps<{ account: CashflowAccount | null; year: number }>()
const emit = defineEmits<{ close: [] }>()
const visible = computed({ get: () => props.account !== null, set: value => { if (!value) emit('close') } })
const auth = useCurrentUser()
const opening = ref<CashflowOpening | null>(null)
const history = ref<OpeningConfirmation[]>([])
const loading = ref(false)
const saving = ref(false)
const confirmed = computed(() => opening.value?.financeConfirmed === true || opening.value?.financeConfirmed === 1)
const canConfirm = computed(() => auth.can('cashflow.opening.confirm') && opening.value?.openingStatus === 'ACTIVE' && !confirmed.value)
const statusLabel = computed(() => ({ ACTIVE: '计算生效', CANDIDATE: '候选', MISSING: '缺失' })[opening.value?.openingStatus ?? 'MISSING'])
const form = reactive<ConfirmOpeningRequest>({ evidenceType: 'BANK_STATEMENT', balanceAsOfDate: '', verifiedBalance: '', evidenceRef: '', note: '' })
let requestKey: string | null = null

async function load(account: CashflowAccount) {
  loading.value = true
  opening.value = null
  history.value = []
  requestKey = null
  try {
    opening.value = await fetchCashflowOpening(account.id, props.year)
    if (opening.value.openingId) history.value = await fetchOpeningConfirmations(opening.value.openingId)
    form.balanceAsOfDate = `${props.year - 1}-12-31`
    form.verifiedBalance = opening.value.amount == null ? '' : String(opening.value.amount)
    form.evidenceRef = ''
    form.note = ''
  } catch (error) {
    notify.error(error instanceof Error ? error.message : '年度期初加载失败')
  } finally {
    loading.value = false
  }
}

watch(() => props.account, account => { if (account) void load(account) })
watch(() => [form.evidenceType, form.balanceAsOfDate, form.verifiedBalance, form.evidenceRef, form.note],
  () => { requestKey = null })

async function submit() {
  if (!opening.value?.openingId || !props.account) return
  if (!form.balanceAsOfDate || !form.verifiedBalance || !form.evidenceRef.trim() || !form.note.trim()) {
    notify.error('请填写余额截至日、核对余额、外部凭据编号和核对说明')
    return
  }
  requestKey ??= crypto.randomUUID()
  saving.value = true
  try {
    await confirmCashflowOpening(opening.value.openingId, { ...form }, requestKey)
    notify.success('年度期初已完成财务确认')
    await load(props.account)
  } catch (error) {
    notify.error(error instanceof Error ? error.message : '财务确认失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.cf-opening-details { margin-top: 16px; }
</style>
