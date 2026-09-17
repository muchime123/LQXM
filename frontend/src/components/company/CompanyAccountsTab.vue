<template>
  <LoadingState v-if="loading" />
  <div v-else class="accounts-root">
    <!-- 操作栏 -->
    <div class="toolbar">
      <h3 class="toolbar-title">财务账户管理 ({{ accounts.length }})</h3>
      <button class="btn-add" @click="openAddAccount">＋ 新增账户</button>
    </div>

    <!-- 国内账户 -->
    <div v-if="domesticAccounts.length" class="section">
      <h4 class="section-heading">国内账户</h4>
      <div class="account-grid">
        <div v-for="acc in domesticAccounts" :key="acc.id" class="account-card">
          <div class="card-top">
            <div class="card-badges">
              <span v-if="acc.isPrimary" class="badge badge-primary">主账户</span>
              <span class="badge badge-currency">{{ acc.currency }}</span>
              <span class="badge badge-type">{{ acc.accountType }}</span>
            </div>
            <div class="card-actions">
              <button class="action-btn" title="编辑" @click="openEditAccount(acc)">✏</button>
              <button class="action-btn danger" title="删除" @click="confirmDeleteAccount(acc)">🗑</button>
            </div>
          </div>
          <div class="card-title">{{ acc.accountName }}</div>
          <div class="card-fields">
            <div class="field">
              <label>开户银行</label>
              <span>{{ acc.bankName || '-' }}</span>
            </div>
            <div class="field">
              <label>银行账号</label>
              <div class="account-no-row">
                <span v-if="revealedIds.has(acc.id)" class="mono">{{ acc.accountNumber || '-' }}</span>
                <span
v-else
class="masked"
                  >**** **** **** {{ acc.accountNumber ? acc.accountNumber.slice(-4) : '----' }}</span
                >
                <button class="reveal-btn" @click="toggleReveal(acc.id)">
                  <svg
                    v-if="revealedIds.has(acc.id)"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    width="14"
                    height="14"
                  >
                    <path
                      d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"
                    />
                    <line
x1="1"
y1="1"
x2="23"
y2="23" />
                  </svg>
                  <svg
                    v-else
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    width="14"
                    height="14"
                  >
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                </button>
              </div>
            </div>
            <div v-if="acc.accountHolder" class="field">
              <label>户名</label>
              <span>{{ acc.accountHolder }}</span>
            </div>
            <div v-if="acc.notes" class="field full">
              <label>备注</label>
              <span class="notes-text">{{ acc.notes }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 退税收款账户 -->
    <div v-if="taxAccounts.length" class="section">
      <h4 class="section-heading">退税账户</h4>
      <div class="account-grid">
        <div v-for="acc in taxAccounts" :key="acc.id" class="account-card">
          <div class="card-top">
            <div class="card-badges">
              <span class="badge badge-currency">{{ acc.currency }}</span>
              <span class="badge badge-type">{{ acc.accountType }}</span>
            </div>
            <div class="card-actions">
              <button class="action-btn" title="编辑" @click="openEditAccount(acc)">✏</button>
              <button class="action-btn danger" title="删除" @click="confirmDeleteAccount(acc)">🗑</button>
            </div>
          </div>
          <div class="card-title">{{ acc.accountName }}</div>
          <div class="card-fields">
            <div class="field">
              <label>户名</label>
              <span class="mono-small">{{ acc.accountHolder || '-' }}</span>
            </div>
            <div class="field">
              <label>账号</label>
              <span class="mono">{{ acc.accountNumber }}</span>
            </div>
            <div class="field">
              <label>银行</label>
              <span>{{ acc.bankName || '-' }}</span>
            </div>
            <div class="field">
              <label>SWIFT</label>
              <span class="mono">{{ acc.swiftCode || '-' }}</span>
            </div>
            <div v-if="acc.bankAddress" class="field full">
              <label>银行地址</label>
              <span class="addr-text">{{ acc.bankAddress }}</span>
            </div>
            <div v-if="acc.notes" class="field full">
              <label>备注</label>
              <span class="notes-text">{{ acc.notes }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 国际收款账户 -->
    <div v-if="intlAccounts.length" class="section">
      <h4 class="section-heading">国际收款账户</h4>
      <div class="account-grid">
        <div v-for="acc in intlAccounts" :key="acc.id" class="account-card">
          <div class="card-top">
            <div class="card-badges">
              <span class="badge badge-currency">{{ acc.currency }}</span>
              <span v-if="acc.bankLocation" class="badge badge-location">{{ acc.bankLocation }}</span>
            </div>
            <div class="card-actions">
              <button class="action-btn" title="编辑" @click="openEditAccount(acc)">✏</button>
              <button class="action-btn danger" title="删除" @click="confirmDeleteAccount(acc)">🗑</button>
            </div>
          </div>
          <div class="card-title">{{ acc.accountName }}</div>
          <div class="card-fields">
            <div class="field">
              <label>银行</label>
              <span>{{ acc.bankName }}</span>
            </div>
            <div class="field">
              <label>账号</label>
              <span class="mono">{{ acc.accountNumber }}</span>
            </div>
            <div v-if="acc.accountHolder" class="field">
              <label>户名</label>
              <span class="mono-small">{{ acc.accountHolder }}</span>
            </div>
            <div v-if="acc.swiftCode" class="field">
              <label>SWIFT</label>
              <span class="mono">{{ acc.swiftCode }}</span>
            </div>
            <div v-if="acc.iban" class="field">
              <label>IBAN</label>
              <span class="mono">{{ acc.iban }}</span>
            </div>
            <div v-if="acc.routingNumber" class="field">
              <label>Routing</label>
              <span class="mono">{{ acc.routingNumber }}</span>
            </div>
            <div v-if="acc.domesticBankCode" class="field">
              <label>银行代码</label>
              <span class="mono">{{ acc.domesticBankCode }}</span>
            </div>
            <div v-if="acc.bankAddress" class="field full">
              <label>银行地址</label>
              <span class="addr-text">{{ acc.bankAddress }}</span>
            </div>
            <div v-if="acc.notes" class="field full">
              <label>备注</label>
              <span class="notes-text">{{ acc.notes }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <EmptyState v-if="!accounts.length" text="暂未添加财务账户" hint="点击「新增账户」添加" />

    <!-- ====== 编辑/新增弹窗 ====== -->
    <BankAccountEditorModal
      v-model="accountEditorVisible"
      :edit-data="accountEditorData"
      :file-url-resolver="fileUrlResolver"
      @saved="onAccountSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import LoadingState from '../common/LoadingState.vue'
import EmptyState from '../common/EmptyState.vue'
import BankAccountEditorModal from './BankAccountEditorModal.vue'
import { getAccounts, deleteAccount } from '../../api/company'
import { notify } from '../../composables/ui/useToast'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
import type { CompanyAccount } from '../../types/company'

defineProps<{
  fileUrlResolver: (category: string, fileName: string | null | undefined) => string
}>()

const loading = ref(false)
const accounts = ref<CompanyAccount[]>([])

async function loadAccounts() {
  loading.value = true
  try {
    accounts.value = (await getAccounts()) || []
  } catch (err) {
    notify('加载财务账户失败', 'error')
    console.error(err)
  } finally {
    loading.value = false
  }
}

const revealedIds = ref<Set<number>>(new Set())

function toggleReveal(id: number) {
  const s = new Set(revealedIds.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  revealedIds.value = s
}

const domesticAccounts = computed(() => accounts.value.filter(a => ['对公账户', '对私账户'].includes(a.accountType)))
const taxAccounts = computed(() => accounts.value.filter(a => a.accountType === '退税收款账户'))
const intlAccounts = computed(() => accounts.value.filter(a => a.accountType === '国际收款账户'))

// 账户编辑器状态
const accountEditorVisible = ref(false)
const accountEditorData = ref<CompanyAccount | null>(null)

function openAddAccount() {
  accountEditorData.value = null
  accountEditorVisible.value = true
}

function openEditAccount(acc: CompanyAccount) {
  accountEditorData.value = acc
  accountEditorVisible.value = true
}

function onAccountSaved() {
  loadAccounts()
}

// 删除
async function confirmDeleteAccount(acc: CompanyAccount) {
  const ok = await askConfirm({
    title: '确认删除',
    message: `确定要删除账户「${acc.accountName}」吗？此操作不可撤销。`,
    danger: true
  })
  if (!ok) return

  try {
    await deleteAccount(acc.id)
    notify('账户已删除', 'success')
    loadAccounts()
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

onMounted(loadAccounts)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.toolbar-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-heading);
  margin: 0;
}

.btn-add {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  border: none;
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.btn-add:hover {
  background: var(--color-primary-hover);
}

/* ===== 通用 ===== */
.section {
  margin-bottom: 28px;
}

.section-heading {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-faint);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 0 0 14px;
}

.badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-weight: 600;
  display: inline-flex;
  align-items: center;
}

.badge-primary {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.badge-currency {
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
}

.badge-type {
  background: var(--color-surface-alt);
  color: var(--color-text-faint);
}

.badge-location {
  background: var(--color-info-bg);
  color: var(--color-info-text);
}

.card-badges {
  display: flex;
  gap: 6px;
}

.mono {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  letter-spacing: 0.3px;
}

.mono-small {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 12px;
}

.notes-text {
  color: var(--color-text-faint);
  font-size: 12px;
}

.card-actions {
  display: flex;
  gap: var(--space-control-gap-tight);
  flex-shrink: 0;
}

.action-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  cursor: pointer;
  color: var(--color-text-secondary);
  font-size: 15px;
  line-height: 1;
  transition: all var(--transition-fast);
}

.action-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg);
}

.action-btn.danger:hover {
  color: var(--color-danger);
  border-color: var(--color-danger);
  background: var(--color-danger-bg-tint);
}

/* ===== 账户卡片（统一） ===== */
.account-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}

.account-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  padding: 16px 18px;
  box-shadow: var(--shadow-xs);
  transition: all var(--transition-fast);
  display: flex;
  flex-direction: column;
  gap: var(--space-card-gap);
}

.account-card:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-sm);
  transform: translateY(-1px);
}

.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap);
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-heading);
  line-height: 1.4;
}

.card-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
  padding-top: 12px;
  border-top: 1px dashed var(--color-border-lighter);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.field.full {
  grid-column: 1 / -1;
}

.field label {
  font-size: 11px;
  color: var(--color-text-faint);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.field span {
  font-size: 13px;
  color: var(--color-text-primary);
  word-break: break-all;
  line-height: 1.4;
}

.account-no-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.account-no-row span {
  flex: 1;
  min-width: 0;
}

.masked {
  letter-spacing: 1px;
  color: var(--color-text-secondary);
}

.reveal-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--color-text-faint);
  padding: 2px;
  display: flex;
  transition: color var(--transition-fast);
  flex-shrink: 0;
}

.reveal-btn:hover {
  color: var(--color-primary);
}

.addr-text {
  font-size: 12px;
  color: var(--color-text-secondary);
  line-height: 1.4;
}
</style>
