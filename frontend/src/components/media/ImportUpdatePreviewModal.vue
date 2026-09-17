<template>
  <LqDialog :model-value="modelValue" title="导入更新预览" subtitle="先核对账号匹配，确认后才会写入更新记录" size="lg" mode="workspace"
    :close-on-click-modal="false" :loading="confirming" @update:model-value="handleModelUpdate" @close="handleClose">
    <template #actions>
      <span class="batch-count-pill">{{ summary.totalRows }} 行</span>
    </template>

    <div class="update-import-body">
      <div class="update-import-toolbar">
        <div class="import-filter-tabs">
          <button type="button" :class="['import-tab', { active: filterTab === 'all' }]" @click="filterTab = 'all'">
            全部 <strong>{{ summary.totalRows }}</strong>
          </button>
          <button type="button" :class="['import-tab matched', { active: filterTab === 'matched' }]"
            @click="filterTab = 'matched'">
            已匹配 <strong>{{ summary.matched }}</strong>
          </button>
          <button type="button" :class="['import-tab unmatched', { active: filterTab === 'unmatched' }]"
            @click="filterTab = 'unmatched'">
            未匹配 <strong>{{ summary.unmatched }}</strong>
          </button>
          <button type="button" :class="['import-tab duplicate', { active: filterTab === 'duplicate' }]"
            @click="filterTab = 'duplicate'">
            可能重复 <strong>{{ summary.duplicates }}</strong>
          </button>
          <button type="button" :class="['import-tab enabled', { active: filterTab === 'enabled' }]"
            @click="filterTab = 'enabled'">
            将导入 <strong>{{ enabledCount }}</strong>
          </button>
        </div>
        <div class="import-toolbar-right">
          <input v-model="searchKeyword" class="import-search" type="search" placeholder="搜索账号、标题..." />
        </div>
      </div>

      <div class="update-import-table-wrap">
        <table class="update-import-table">
          <thead>
            <tr>
              <th style="width: 44px">
                <input type="checkbox" :checked="importAllChecked" :indeterminate="importSomeChecked"
                  @change="toggleImportAll" />
              </th>
              <th>源账号</th>
              <th>匹配账号</th>
              <th style="width: 90px">日期</th>
              <th>标题</th>
              <th style="width: 120px">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in filteredImportRows" :key="row.rowNumber"
              :class="['import-row', row.status, { 'row-selected': row.enabled }]">
              <td>
                <input v-model="row.enabled" type="checkbox" :disabled="!row.matchedAccountId" />
              </td>
              <td>
                <div class="import-source-account">
                  <span :class="['platform-badge', platformClass(row.platform)]">{{ row.platform || '-' }}</span>
                  <strong>{{ row.accountName || '-' }}</strong>
                  <em>{{ row.accountId || '-' }}</em>
                </div>
              </td>
              <td>
                <select v-model="row.matchedAccountId" class="import-account-select" @change="onAccountChange(row)">
                  <option :value="null">请选择账号</option>
                  <option v-for="account in accounts" :key="account.id" :value="account.id">
                    {{ account.platform }} / {{ account.accountName
                    }}{{ account.accountId ? ` / ${account.accountId}` : '' }}
                  </option>
                </select>
                <div v-if="row.matchedAccountId" class="import-match-hint">
                  已匹配：{{ accountLabel(row.matchedAccountId) }}
                </div>
              </td>
              <td class="num-cell">{{ row.updateDate || '-' }}</td>
              <td>
                <input v-model="row.title" class="import-title-input" placeholder="无标题" />
                <div v-if="row.contentUrl || row.note" class="import-extra-info">
                  <span v-if="row.contentUrl" class="import-content-url">🔗 {{ row.contentUrl }}</span>
                  <span v-if="row.note" class="import-note">📝 {{ row.note }}</span>
                </div>
              </td>
              <td>
                <span :class="['import-status', row.status]">{{ row.message || '-' }}</span>
              </td>
            </tr>
            <tr v-if="filteredImportRows.length === 0">
              <td colspan="6" class="empty">没有匹配的记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <template #footer>
      <div class="import-footer-left">
        <span class="footer-status">
          共 <strong>{{ summary.totalRows }}</strong> 行， 已选 <strong>{{ enabledCount }}</strong> 条待导入
        </span>
        <span class="import-split">|</span>
        <button type="button" class="btn-link" :disabled="noMatchedAvailable" @click="selectAllMatched">
          全选已匹配
        </button>
        <button type="button" class="btn-link" @click="clearAll">清空选择</button>
      </div>
      <button type="button" @click="handleClose">取消</button>
      <button type="button" class="primary" :disabled="confirming || !hasEnabledRows" @click="handleConfirm">
        {{ confirming ? '导入中...' : '确认导入更新' }}
      </button>
    </template>
  </LqDialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import { platformClass } from '../../utils/media/platformUtils'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  rows: { type: Array, default: () => [] },
  accounts: { type: Array, default: () => [] },
  summary: {
    type: Object,
    default: () => ({ totalRows: 0, matched: 0, unmatched: 0, duplicates: 0 })
  },
  confirming: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'close', 'confirmImport'])

const localRows = ref([])
const filterTab = ref('all')
const searchKeyword = ref('')

watch(
  () => props.modelValue,
  val => {
    if (val) {
      localRows.value = props.rows.map(row => ({ ...row }))
      filterTab.value = 'all'
      searchKeyword.value = ''
    }
  }
)

const filteredImportRows = computed(() => {
  let rows = localRows.value
  if (filterTab.value === 'matched') {
    rows = rows.filter(r => r.matchedAccountId && !r.duplicate)
  } else if (filterTab.value === 'unmatched') {
    rows = rows.filter(r => !r.matchedAccountId)
  } else if (filterTab.value === 'duplicate') {
    rows = rows.filter(r => r.duplicate)
  } else if (filterTab.value === 'enabled') {
    rows = rows.filter(r => r.enabled && r.matchedAccountId)
  }
  if (searchKeyword.value.trim()) {
    const kw = searchKeyword.value.trim().toLowerCase()
    rows = rows.filter(r => {
      const sourceMatch =
        (r.platform || '').toLowerCase().includes(kw) ||
        (r.accountName || '').toLowerCase().includes(kw) ||
        (r.accountId || '').toLowerCase().includes(kw)
      const titleMatch = (r.title || '').toLowerCase().includes(kw)
      const matchedName = accountLabel(r.matchedAccountId).toLowerCase()
      const matchedMatch = r.matchedAccountId && matchedName.includes(kw)
      return sourceMatch || titleMatch || matchedMatch
    })
  }
  return rows
})

const importAllChecked = computed(() => {
  const visible = filteredImportRows.value.filter(r => r.matchedAccountId)
  return visible.length > 0 && visible.every(r => r.enabled)
})

const importSomeChecked = computed(() => {
  const visible = filteredImportRows.value.filter(r => r.matchedAccountId)
  return visible.some(r => r.enabled) && !importAllChecked.value
})

const enabledCount = computed(() => localRows.value.filter(row => row.enabled && row.matchedAccountId).length)

const hasEnabledRows = computed(() => localRows.value.some(row => row.enabled && row.matchedAccountId))

const noMatchedAvailable = computed(() => localRows.value.filter(r => r.matchedAccountId && !r.duplicate).length === 0)

function accountLabel(accountId) {
  const account = props.accounts.find(item => item.id === Number(accountId))
  if (!account) return ''
  return `${account.platform || '-'} / ${account.accountName || '-'}${account.accountId ? ` / ${account.accountId}` : ''}`
}

function toggleImportAll() {
  const checked = !importAllChecked.value
  filteredImportRows.value.forEach(r => {
    if (r.matchedAccountId) r.enabled = checked
  })
}

function onAccountChange(row) {
  const account = props.accounts.find(item => item.id === Number(row.matchedAccountId))
  if (!account) return
  row.matchedAccountName = account.accountName
  row.matchedPlatform = account.platform
  row.duplicate = false
  row.status = 'matched'
  row.message = '手动调整匹配'
  row.enabled = true
}

function selectAllMatched() {
  localRows.value.forEach(r => {
    if (r.matchedAccountId && !r.duplicate) r.enabled = true
  })
}

function clearAll() {
  localRows.value.forEach(r => (r.enabled = false))
}

function handleModelUpdate(val) {
  emit('update:modelValue', val)
  if (!val) emit('close')
}

function handleClose() {
  emit('update:modelValue', false)
  emit('close')
}

function handleConfirm() {
  const rows = localRows.value.map(row => ({
    enabled: !!row.enabled,
    rowNumber: row.rowNumber,
    accountId: row.matchedAccountId ? Number(row.matchedAccountId) : null,
    updateDate: row.updateDate,
    title: row.title,
    contentUrl: row.contentUrl,
    note: row.note,
    operator: row.operator
  }))
  emit('confirmImport', rows)
}
</script>

<style scoped>
@import '../../styles/media-account-view.css';

.update-import-body {
  display: grid;
  gap: 14px;
  padding: 0;
}

.update-import-toolbar {
  align-items: center;
  display: flex;
  gap: var(--space-control-gap-wide);
  justify-content: space-between;
  flex-wrap: wrap;
}

.import-filter-tabs {
  display: flex;
  gap: var(--space-control-gap-tight);
  flex-wrap: wrap;
}

.import-tab {
  align-items: center;
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  color: var(--color-text-muted);
  cursor: pointer;
  display: inline-flex;
  font-size: 12px;
  gap: var(--space-control-gap-tight);
  padding: 6px 12px;
  transition: all 0.15s ease;
}

.import-tab:hover {
  border-color: var(--color-border-input);
  color: var(--color-text-primary);
}

.import-tab strong {
  color: var(--color-text-primary);
  font-weight: 700;
}

.import-tab.active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-text-on-primary);
}

.import-tab.active strong {
  color: var(--color-text-on-primary);
}

.import-tab.matched.active {
  background: var(--color-success);
  border-color: var(--color-success);
}

.import-tab.unmatched.active {
  background: var(--color-warning);
  border-color: var(--color-warning);
}

.import-tab.duplicate.active {
  background: var(--color-warning);
  border-color: var(--color-warning);
}

.import-tab.enabled.active {
  background: var(--color-primary);
  border-color: var(--color-primary);
}

.import-search {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  font-size: 12px;
  height: 32px;
  padding: 0 12px;
  width: 220px;
}

.import-search:focus {
  border-color: var(--color-primary);
  outline: none;
}

.update-import-table-wrap {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-2xl);
  max-height: min(62vh, 620px);
  overflow: auto;
}

.update-import-table {
  border-collapse: collapse;
  min-width: 1060px;
  width: 100%;
}

.update-import-table th {
  background: var(--color-surface-alt);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 700;
  padding: 8px 10px;
  position: sticky;
  text-align: left;
  top: 0;
  z-index: 1;
}

.update-import-table td {
  border-bottom: 1px solid var(--color-border-lighter);
  color: var(--color-text-primary);
  font-size: 12px;
  padding: 8px 10px;
  vertical-align: top;
}

.update-import-table tr:last-child td {
  border-bottom: 0;
}

.update-import-table .empty {
  color: var(--color-text-muted);
  padding: 32px;
  text-align: center;
}

.num-cell {
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.import-row.row-selected td {
  background: var(--color-primary-bg);
}

.import-row.unmatched td {
  background: var(--color-warning-bg-soft);
}

.import-row.duplicate td {
  background: var(--color-warning-bg-soft);
}

.import-source-account {
  align-items: center;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  min-width: 180px;
}

.platform-badge {
  border-radius: var(--radius-md);
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  text-transform: uppercase;
}

.import-source-account em,
.import-match-hint {
  color: var(--color-text-muted);
  font-size: 11px;
  font-style: normal;
}

.import-match-hint {
  margin-top: 4px;
}

.import-account-select,
.import-title-input {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  font-size: 12px;
  height: 32px;
  padding: 0 10px;
  width: 100%;
}

.import-account-select:focus,
.import-title-input:focus {
  border-color: var(--color-primary);
  outline: none;
}

.import-account-select {
  min-width: 280px;
}

.import-title-input {
  min-width: 220px;
}

.import-extra-info {
  display: grid;
  gap: 2px;
  margin-top: 4px;
}

.import-content-url,
.import-note {
  color: var(--color-text-muted);
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-status {
  border-radius: var(--radius-pill);
  display: inline-flex;
  font-size: 11px;
  font-weight: 700;
  padding: 4px 10px;
  white-space: nowrap;
}

.import-status.matched {
  background: var(--color-success-bg);
  color: var(--color-success-text);
}

.import-status.unmatched {
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
}

.import-status.duplicate {
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
}

.import-footer-left {
  align-items: center;
  display: flex;
  gap: var(--space-control-gap-wide);
  margin-right: auto;
}

/* footer 内文本按钮用统一的 .btn-link 类，避免与 utilities.css 中同名实心按钮冲突 */
.lq-dialog__foot .btn-link {
  min-height: auto;
  padding: 4px 6px;
  font-size: 12px;
  font-weight: var(--fw-medium);
}

.lq-dialog__foot .btn-link:hover:not(:disabled) {
  text-decoration: none;
}

.lq-dialog__foot .btn-link:disabled {
  background: none;
  color: var(--color-text-muted);
  cursor: not-allowed;
}

.import-split {
  color: var(--color-border-input);
}

.batch-count-pill {
  align-items: center;
  background: var(--color-primary-bg);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-pill);
  color: var(--color-success-text);
  display: inline-flex;
  font-size: var(--fs-xs);
  font-weight: var(--fw-extrabold);
  padding: 5px 10px;
  white-space: nowrap;
}

.form-dialog-footer {
  background: var(--color-surface-alt);
  justify-content: flex-end;
  margin: 0;
  padding: 10px 18px;
}
</style>
