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
        <div v-if="canPreview" class="cf-opening-details">
          <h3>期初更正影响预览</h3>
          <el-alert type="warning" :closable="false" title="这里只测算余额变化，不会保存更正。已确认期初更正后，新金额需要重新财务确认。" />
          <el-input v-model="proposedAmount" placeholder="拟更正金额，最多 4 位小数" class="cf-opening-preview-input" />
          <el-button :loading="previewLoading" @click="previewCorrection">预览影响</el-button>
          <el-descriptions v-if="preview" :column="2" border class="cf-opening-details">
            <el-descriptions-item label="原金额">{{ preview.oldAmount }}</el-descriptions-item>
            <el-descriptions-item label="拟更正金额">{{ preview.newAmount }}</el-descriptions-item>
            <el-descriptions-item label="各期间余额变化">{{ preview.balanceDelta }} {{ preview.currency }}</el-descriptions-item>
            <el-descriptions-item label="影响账年">{{ preview.fiscalYear }}</el-descriptions-item>
            <el-descriptions-item label="确认状态">{{ preview.wouldResetFinanceConfirmation ? '应用更正后须重新确认' : '目前未确认' }}</el-descriptions-item>
            <el-descriptions-item label="执行状态">仅预览，未应用</el-descriptions-item>
          </el-descriptions>
          <el-form v-if="canCorrect && preview && !pendingCorrection" label-width="100px" class="cf-opening-details">
            <el-form-item label="更正原因"><el-input v-model="correctionReason" maxlength="255" type="textarea" /></el-form-item>
            <el-form-item label="依据编号"><el-input v-model="correctionEvidenceRef" maxlength="255" /></el-form-item>
            <el-button type="primary" :loading="correctionSaving" @click="submitCorrection">申请更正</el-button>
          </el-form>
        </div>
        <div v-if="opening.openingId" class="cf-opening-details">
          <h3>更正记录</h3>
          <el-empty v-if="!corrections.length" description="暂无更正记录" :image-size="55" />
          <el-descriptions v-for="item in corrections" :key="item.id" :column="2" border class="cf-opening-details">
            <el-descriptions-item label="状态">{{ item.status === 'APPLIED' ? '已应用' : '待应用' }}</el-descriptions-item>
            <el-descriptions-item label="金额">{{ item.oldAmount }} → {{ item.newAmount }}</el-descriptions-item>
            <el-descriptions-item label="申请人 / 时间">{{ item.requestedByName }} · {{ item.requestedAt }}</el-descriptions-item>
            <el-descriptions-item label="应用人 / 时间">{{ item.appliedByName ?? '—' }} · {{ item.appliedAt ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="更正原因" :span="2">{{ item.reason }}</el-descriptions-item>
            <el-descriptions-item label="依据编号" :span="2">{{ item.evidenceRef }}</el-descriptions-item>
            <el-descriptions-item v-if="item.originalConfirmationId" label="原确认记录 ID" :span="2">{{ item.originalConfirmationId }}（历史记录保留）</el-descriptions-item>
            <el-descriptions-item v-if="item.status === 'PENDING' && canCorrect" label="操作" :span="2">
              <el-button type="danger" :loading="correctionSaving" @click="applyCorrection(item)">核对后应用更正</el-button>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { notify } from '@/composables/ui/useToast'
import { applyOpeningCorrection, confirmCashflowOpening, fetchCashflowOpening, fetchOpeningConfirmations, fetchOpeningCorrectionPreview, fetchOpeningCorrections, requestOpeningCorrection, type CashflowOpening, type OpeningConfirmation, type ConfirmOpeningRequest, type OpeningCorrectionPreview, type OpeningCorrection } from '@/api/cashflowOpening'
import type { CashflowAccount } from '@/api/cashflowJournal'

const props = defineProps<{ account: CashflowAccount | null; year: number }>()
const emit = defineEmits<{ close: []; openingChanged: [] }>()
const visible = computed({ get: () => props.account !== null, set: value => { if (!value) emit('close') } })
const auth = useCurrentUser()
const opening = ref<CashflowOpening | null>(null)
const history = ref<OpeningConfirmation[]>([])
const loading = ref(false)
const saving = ref(false)
const previewLoading = ref(false)
const proposedAmount = ref('')
const preview = ref<OpeningCorrectionPreview | null>(null)
const corrections = ref<OpeningCorrection[]>([])
const correctionReason = ref('')
const correctionEvidenceRef = ref('')
const correctionSaving = ref(false)
let correctionRequestKey: string | null = null
const correctionApplyKeys = new Map<number, string>()
const confirmed = computed(() => opening.value?.financeConfirmed === true || opening.value?.financeConfirmed === 1)
const canConfirm = computed(() => auth.can('cashflow.opening.confirm') && opening.value?.openingStatus === 'ACTIVE' && !confirmed.value)
const canCorrect = computed(() => auth.can('cashflow.opening.correct') && opening.value?.openingStatus === 'ACTIVE')
const pendingCorrection = computed(() => corrections.value.some(item => item.status === 'PENDING'))
const canPreview = computed(() => (auth.can('cashflow.manage') || auth.can('cashflow.opening.correct')) && opening.value?.openingStatus === 'ACTIVE')
const statusLabel = computed(() => ({ ACTIVE: '计算生效', CANDIDATE: '候选', MISSING: '缺失' })[opening.value?.openingStatus ?? 'MISSING'])
const form = reactive<ConfirmOpeningRequest>({ evidenceType: 'BANK_STATEMENT', balanceAsOfDate: '', verifiedBalance: '', evidenceRef: '', note: '' })
let requestKey: string | null = null

async function load(account: CashflowAccount) {
  loading.value = true
  opening.value = null
  history.value = []
  corrections.value = []
  proposedAmount.value = ''
  preview.value = null
  correctionReason.value = ''
  correctionEvidenceRef.value = ''
  correctionRequestKey = null
  correctionApplyKeys.clear()
  requestKey = null
  try {
    opening.value = await fetchCashflowOpening(account.id, props.year)
    if (opening.value.openingId) {
      [history.value, corrections.value] = await Promise.all([
        fetchOpeningConfirmations(opening.value.openingId),
        fetchOpeningCorrections(opening.value.openingId)
      ])
    }
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
watch(proposedAmount, () => { preview.value = null; correctionRequestKey = null })
watch([correctionReason, correctionEvidenceRef], () => { correctionRequestKey = null })

async function submitCorrection() {
  if (!opening.value?.openingId || !preview.value || !props.account) return
  if (!correctionReason.value.trim() || !correctionEvidenceRef.value.trim()) {
    notify.error('请填写更正原因和外部依据编号')
    return
  }
  correctionRequestKey ??= crypto.randomUUID()
  correctionSaving.value = true
  try {
    await requestOpeningCorrection(opening.value.openingId, {
      expectedOldAmount: preview.value.oldAmount,
      newAmount: preview.value.newAmount,
      reason: correctionReason.value.trim(),
      evidenceRef: correctionEvidenceRef.value.trim()
    }, correctionRequestKey)
    notify.success('更正申请已保存，当前报表金额未变化')
    await load(props.account)
  } catch (error) {
    notify.error(error instanceof Error ? error.message : '更正申请失败')
  } finally {
    correctionSaving.value = false
  }
}

async function applyCorrection(item: OpeningCorrection) {
  if (!props.account) return
  try {
    await ElMessageBox.confirm(
      `将 ${item.oldAmount} 更正为 ${item.newAmount}，该账年余额将立即变化。原确认记录保留，新金额需要重新财务确认。`,
      '确认应用期初更正', { type: 'warning', confirmButtonText: '应用更正', cancelButtonText: '取消' }
    )
  } catch { return }
  const key = correctionApplyKeys.get(item.id) ?? crypto.randomUUID()
  correctionApplyKeys.set(item.id, key)
  correctionSaving.value = true
  try {
    await applyOpeningCorrection(item.id, key)
    notify.success('期初更正已应用，请核对报表并重新财务确认')
    await load(props.account)
    emit('openingChanged')
  } catch (error) {
    notify.error(error instanceof Error ? error.message : '应用更正失败')
  } finally {
    correctionSaving.value = false
  }
}

async function previewCorrection() {
  if (!opening.value?.openingId || !proposedAmount.value.trim()) return
  previewLoading.value = true
  try {
    preview.value = await fetchOpeningCorrectionPreview(opening.value.openingId, proposedAmount.value.trim())
  } catch (error) {
    notify.error(error instanceof Error ? error.message : '期初更正预览失败')
  } finally {
    previewLoading.value = false
  }
}

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
.cf-opening-preview-input { width: 230px; margin: 14px 10px 0 0; }
</style>
