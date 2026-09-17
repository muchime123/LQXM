<template>
  <LqDialog
    :model-value="modelValue"
    :title="isEdit ? '编辑账户' : '新增账户'"
    size="md"
    :close-on-click-modal="false"
    :loading="saving"
    mode="form"
    confirm-text="保存"
    cancel-text="取消"
    @update:model-value="handleVisibleUpdate"
    @confirm="onConfirm"
    @close="onClose"
  >
    <FormSection title="基础信息" description="账户识别信息：名称、类型、银行和收款户名，用于归档和对外说明。" :required-count="2">
      <div class="form-grid">
        <div class="form-field">
          <label class="required">账户名称</label>
          <input v-model="accountForm.accountName" placeholder="如：对公开票账户" />
        </div>
        <div class="form-field">
          <label class="required">账户类型</label>
          <select v-model="accountForm.accountType">
            <option value="对公账户">对公账户</option>
            <option value="对私账户">对私账户</option>
            <option value="退税收款账户">退税收款账户</option>
            <option value="国际收款账户">国际收款账户</option>
            <option value="支付宝">支付宝</option>
            <option value="微信支付">微信支付</option>
            <option value="银行收款码">银行收款码</option>
            <option value="其他">其他</option>
          </select>
        </div>
        <div class="form-field">
          <label>开户银行</label>
          <input v-model="accountForm.bankName" placeholder="银行名称" />
        </div>
        <div class="form-field">
          <label>银行账号</label>
          <input v-model="accountForm.accountNumber" placeholder="账号" />
        </div>
        <div class="form-field">
          <label>户名</label>
          <input v-model="accountForm.accountHolder" placeholder="账户持有人" />
        </div>
        <div class="form-field">
          <label>货币</label>
          <select v-model="accountForm.currency">
            <option value="CNY">CNY (人民币)</option>
            <option value="USD">USD (美元)</option>
            <option value="EUR">EUR (欧元)</option>
            <option value="GBP">GBP (英镑)</option>
            <option value="HKD">HKD (港币)</option>
            <option value="JPY">JPY (日元)</option>
            <option value="AUD">AUD (澳大利亚元)</option>
            <option value="CAD">CAD (加拿大元)</option>
            <option value="IDR">IDR (印尼盾)</option>
            <option value="AED">AED (阿联酋迪拉姆)</option>
            <option value="MXN">MXN (墨西哥比索)</option>
            <option value="NZD">NZD (新西兰元)</option>
            <option value="SGD">SGD (新加坡元)</option>
            <option value="CHF">CHF (瑞士法郎)</option>
            <option value="SEK">SEK (瑞典克朗)</option>
            <option value="NOK">NOK (挪威克朗)</option>
            <option value="DKK">DKK (丹麦克朗)</option>
            <option value="ZAR">ZAR (南非兰特)</option>
            <option value="CNH">CNH (离岸人民币)</option>
          </select>
        </div>
      </div>
    </FormSection>

    <FormSection title="跨境转账信息" description="用于国际汇款的 SWIFT/IBAN 等银行代码，非国际账户可留空。">
      <div class="form-grid">
        <div class="form-field">
          <label>SWIFT Code</label>
          <input v-model="accountForm.swiftCode" placeholder="如：BKCHCNBJ92B" />
        </div>
        <div class="form-field">
          <label>IBAN</label>
          <input v-model="accountForm.iban" placeholder="国际银行账号" />
        </div>
        <div class="form-field">
          <label>Routing Number</label>
          <input v-model="accountForm.routingNumber" placeholder="北美地区银行代码" />
        </div>
        <div class="form-field">
          <label>国内银行代码</label>
          <input v-model="accountForm.domesticBankCode" placeholder="Sort Code 等" />
        </div>
        <div class="form-field">
          <label>银行地区</label>
          <input v-model="accountForm.bankLocation" placeholder="如：China / US / Hong Kong" />
        </div>
        <div class="form-field full-field">
          <label>银行地址</label>
          <input v-model="accountForm.bankAddress" placeholder="银行详细地址" />
        </div>
      </div>
    </FormSection>

    <FormSection title="其他设置" description="主账户标记、显示排序、收款码上传与备注信息。">
      <div class="form-grid">
        <div class="form-field">
          <label>
            <input v-model="accountForm.isPrimary" type="checkbox" style="width: auto; margin-right: 6px" />
            设为主账户
          </label>
        </div>
        <div class="form-field">
          <label>排序</label>
          <input v-model.number="accountForm.sortOrder" type="number" />
        </div>
        <div class="form-field">
          <label>收款码文件</label>
          <div class="upload-row" @dragover.prevent @drop.prevent="handleQrDrop">
            <input
v-model="accountForm.qrCodeFile"
placeholder="文件名"
readonly
style="flex: 1" />
            <button type="button" class="btn-upload-sm" @click="triggerQrUpload">上传</button>
          </div>
          <div v-if="accountForm.qrCodeFile" class="qr-preview-small">
            <img :src="resolvedFileUrl" :alt="accountForm.accountName" />
          </div>
        </div>
        <div class="form-field full-field">
          <label>备注</label>
          <textarea v-model="accountForm.notes" rows="2" placeholder="选填"></textarea>
        </div>
      </div>
    </FormSection>
  </LqDialog>

  <input
ref="qrFileInput"
type="file"
accept="image/*"
style="display: none"
@change="handleQrUpload" />
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import FormSection from '../common/FormSection.vue'
import { notify } from '../../composables/ui/useToast'
import { useFileUploadWorkflow } from '../../composables/upload/useFileUploadWorkflow'
import { createAccount, updateAccount } from '../../api/company'

interface BankAccount {
  id?: number
  accountName: string
  accountType: string
  bankName: string
  accountNumber: string
  accountHolder: string
  currency: string
  swiftCode: string
  iban: string
  routingNumber: string
  domesticBankCode: string
  bankLocation: string
  bankAddress: string
  isPrimary: boolean
  sortOrder: number
  qrCodeFile: string
  notes: string
  [key: string]: any
}

const props = defineProps<{
  modelValue: boolean
  editData: Partial<BankAccount> | null
  fileUrlResolver: (category: string, fileName: string | null | undefined) => string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
  (e: 'close'): void
}>()

const saving = ref(false)
const editId = ref<number | null>(null)

const defaultAccountForm = (): BankAccount => ({
  accountName: '',
  accountType: '对公账户',
  bankName: '',
  accountNumber: '',
  accountHolder: '',
  currency: 'CNY',
  swiftCode: '',
  iban: '',
  routingNumber: '',
  domesticBankCode: '',
  bankLocation: '',
  bankAddress: '',
  isPrimary: false,
  sortOrder: 0,
  qrCodeFile: '',
  notes: ''
})

const accountForm = reactive<BankAccount>(defaultAccountForm())

const isEdit = computed(() => editId.value !== null)

const resolvedFileUrl = computed(() => {
  if (!accountForm.qrCodeFile) return ''
  return props.fileUrlResolver('company_finance', accountForm.qrCodeFile)
})

const qrFileInput = ref<HTMLInputElement | null>(null)

const qrUploadWorkflow = useFileUploadWorkflow({
  notify,
  category: 'company_finance',
  accept: 'image/*',
  multiple: false,
  onUploaded: ({ fileName }: any) => {
    accountForm.qrCodeFile = fileName
  },
  onBatchComplete: (results: any) => {
    if (results.length) notify('收款码已上传', 'success')
  }
})

function triggerQrUpload() {
  qrFileInput.value?.click()
}

function handleQrUpload(e: Event) {
  return qrUploadWorkflow.handleInputChange(e)
}

function handleQrDrop(e: DragEvent) {
  return qrUploadWorkflow.onDrop(e)
}

function initForm() {
  if (props.editData) {
    editId.value = props.editData.id ?? null
    const defaults = defaultAccountForm()
    Object.keys(defaults).forEach(key => {
      ;(accountForm as any)[key] = props.editData![key] != null ? props.editData![key] : (defaults as any)[key]
    })
  } else {
    editId.value = null
    Object.assign(accountForm, defaultAccountForm())
  }
}

watch(
  () => props.modelValue,
  val => {
    if (val) {
      initForm()
    }
  }
)

function handleVisibleUpdate(value: boolean) {
  emit('update:modelValue', value)
}

function onClose() {
  emit('update:modelValue', false)
  emit('close')
}

function onConfirm() {
  saveAccountForm()
}

async function saveAccountForm() {
  if (!accountForm.accountName || !accountForm.accountType) {
    notify('请填写账户名称和类型', 'warning')
    return
  }
  if (saving.value) return
  saving.value = true
  try {
    const data = { ...accountForm }
    if (editId.value) {
      await updateAccount(editId.value, data)
      notify('账户已更新', 'success')
    } else {
      await createAccount(data)
      notify('账户已添加', 'success')
    }
    emit('update:modelValue', false)
    emit('saved')
  } catch (err: any) {
    notify('保存失败' + (err?.message ? '：' + err.message : ''), 'error')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px 18px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.form-field.full-field {
  grid-column: 1 / -1;
}

.form-field label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  display: inline-flex;
  align-items: baseline;
}

.form-field > label.required::before {
  content: '*';
  color: var(--color-danger);
  margin-right: 3px;
  font-weight: 700;
}

.form-field input,
.form-field textarea,
.form-field select {
  padding: 8px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: 14px;
  color: var(--color-text-primary);
  background: var(--color-surface);
  font-family: inherit;
  transition: border-color var(--transition-fast);
}

.form-field input:focus,
.form-field textarea:focus,
.form-field select:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring);
}

.form-field textarea {
  resize: vertical;
  min-height: 60px;
}

.upload-row {
  display: flex;
  gap: var(--space-control-gap);
  align-items: center;
}

.btn-upload-sm {
  padding: 7px 12px;
  border: 1px dashed var(--color-primary);
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--transition-fast);
  flex-shrink: 0;
}

.btn-upload-sm:hover {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
}

.qr-preview-small {
  margin-top: 8px;
  width: 80px;
  height: 80px;
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-surface-alt);
}

.qr-preview-small img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
</style>
