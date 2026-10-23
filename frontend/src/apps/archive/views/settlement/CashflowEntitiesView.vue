<template>
  <div class="page">
    <el-card shadow="never" class="toolbar">
      <div class="toolbar-row">
        <div>
          <h2 class="title">资金主体管理</h2>
          <p class="hint">
            主体=资金归属（企业 / 个人 / 其他），由财务人工认领，程序只提供建议。
            共 {{ totalAccounts }} 个账户，其中 <b>{{ unassignedCount }}</b> 个待认领。
            识别不出的账户明确标「待认领」，不猜测归属。
          </p>
        </div>
        <div class="actions">
          <el-button @click="openSuggest">查看建议归属</el-button>
          <el-button v-if="canManage" type="primary" @click="openCreate">新建主体</el-button>
          <el-button v-if="canManage" @click="openAssign">账户认领</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="entities" v-loading="loading" size="default">
        <el-table-column prop="name" label="主体名称" min-width="180" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ typeLabel(row.entityType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="accountCount" label="账户数" width="90" align="right" />
        <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip />
        <el-table-column v-if="canManage" label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 'ACTIVE' ? 'warning' : 'success'" @click="toggleEntity(row)">
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="row.accountCount === 0" link type="danger" @click="removeEntity(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 账户管理（A-2/A-3）：待认领徽标 + 停用开关；停用只影响未来操作，不影响历史统计 -->
    <el-card shadow="never">
      <template #header>
        <div class="toolbar-row">
          <span>账户列表（{{ accounts.length }} 个）</span>
          <el-checkbox v-model="showInactive">显示已停用账户</el-checkbox>
        </div>
      </template>
      <el-table :data="visibleAccounts" v-loading="accountsLoading" size="small" max-height="520">
        <el-table-column prop="accountName" label="账户" min-width="220" />
        <el-table-column prop="currency" label="币种" width="70" />
        <el-table-column label="归属主体" min-width="160">
          <template #default="{ row }">
            <template v-if="row.entityName">{{ row.entityName }}</template>
            <el-tag v-else type="warning" size="small">待认领</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
              @click="toggleAccount(row)">
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="field-hint" style="margin-top: 8px">
        停用只影响未来操作（新增流水默认不可选）；历史流水查询与历史期间汇总不受影响。
      </p>
    </el-card>

    <!-- 新建/编辑主体 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑主体' : '新建主体'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="主体名称" required>
          <el-input v-model="editForm.name" maxlength="64" placeholder="如：浙江利强 / 于清山（个人）" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="editForm.entityType">
            <el-radio value="COMPANY">企业</el-radio>
            <el-radio value="INDIVIDUAL">个人</el-radio>
            <el-radio value="OTHER">其他</el-radio>
          </el-radio-group>
          <div class="field-hint">主体是资金归属，不一定是企业法人；户名≠法律归属，程序不做推断。</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.note" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEntity">保存</el-button>
      </template>
    </el-dialog>

    <!-- 账户认领 -->
    <el-dialog v-model="assignVisible" title="账户归属认领" width="760px">
      <el-alert type="info" :closable="false" class="assign-alert"
        title="下拉建议来自账户名前缀（仅供预填）。保存前请逐户核对——尤其「利强包装微信零钱」「安徽利强」这类易混户。" />
      <el-table :data="assignRows" v-loading="assignLoading" size="small" max-height="440">
        <el-table-column prop="accountName" label="账户" min-width="220" />
        <el-table-column prop="currency" label="币种" width="70" />
        <el-table-column label="归属主体" min-width="220">
          <template #default="{ row }">
            <el-select v-model="row.entityId" clearable placeholder="待认领" size="small" style="width: 100%">
              <el-option v-for="e in entities" :key="e.id" :value="e.id"
                :label="e.name + (row.suggestedEntityName === e.name ? '（建议）' : '')" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAssign">保存认领</el-button>
      </template>
    </el-dialog>

    <!-- 建议预览 -->
    <el-dialog v-model="suggestVisible" title="归属建议（只读，不落库）" width="640px">
      <el-alert type="warning" :closable="false" class="assign-alert"
        :title="`建议覆盖 ${suggestData?.suggestedCount ?? 0} 户；${suggestData?.unrecognizedCount ?? 0} 户识别不出，需人工认领。`" />
      <el-table :data="suggestData?.items ?? []" size="small" max-height="420">
        <el-table-column prop="accountName" label="账户" min-width="220" />
        <el-table-column label="建议主体" min-width="180">
          <template #default="{ row }">
            <template v-if="row.suggestedEntityName">{{ row.suggestedEntityName }}</template>
            <el-tag v-else type="warning" size="small">待认领</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import {
  fetchCashflowEntities, createCashflowEntity, updateCashflowEntity, deleteCashflowEntity,
  fetchCashflowAccounts, fetchCashflowEntitySuggestions,
  assignCashflowEntities, setCashflowAccountStatus,
  type CashflowEntity, type EntityType, type CashflowAccountLite, type SuggestResponse,
} from '@/api/cashflowEntities'

const auth = useCurrentUser()
const canManage = computed(() => auth.can('cashflow.manage'))

const loading = ref(false)
const saving = ref(false)
const entities = ref<CashflowEntity[]>([])
const unassignedCount = ref(0)
const totalAccounts = ref(0)

const editVisible = ref(false)
const editForm = reactive<{ id: number | null; name: string; entityType: EntityType; note: string }>({
  id: null, name: '', entityType: 'COMPANY', note: '',
})

const assignVisible = ref(false)
const assignLoading = ref(false)
const assignRows = ref<(CashflowAccountLite & { suggestedEntityName: string | null })[]>([])

const suggestVisible = ref(false)
const suggestData = ref<SuggestResponse | null>(null)

const accounts = ref<CashflowAccountLite[]>([])
const accountsLoading = ref(false)
const showInactive = ref(false)
const visibleAccounts = computed(() =>
  showInactive.value ? accounts.value : accounts.value.filter(a => a.status === 'ACTIVE')
)

async function loadAccounts() {
  accountsLoading.value = true
  try {
    accounts.value = await fetchCashflowAccounts()
  } finally {
    accountsLoading.value = false
  }
}

async function toggleAccount(row: CashflowAccountLite) {
  const target = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    await setCashflowAccountStatus(row.id, target)
    row.status = target
    ElMessage.success(target === 'INACTIVE' ? '已停用（历史统计不受影响）' : '已启用')
  } catch (e: unknown) {
    ElMessage.error(extractError(e))
  }
}

function typeLabel(t: string) {
  return t === 'COMPANY' ? '企业' : t === 'INDIVIDUAL' ? '个人' : '其他'
}

async function load() {
  loading.value = true
  try {
    const data = await fetchCashflowEntities()
    entities.value = data.items
    unassignedCount.value = data.unassignedCount
    totalAccounts.value = data.totalAccounts
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editForm.id = null
  editForm.name = ''
  editForm.entityType = 'COMPANY'
  editForm.note = ''
  editVisible.value = true
}

function openEdit(row: CashflowEntity) {
  editForm.id = row.id
  editForm.name = row.name
  editForm.entityType = row.entityType
  editForm.note = row.note ?? ''
  editVisible.value = true
}

async function saveEntity() {
  if (!editForm.name.trim()) { ElMessage.warning('主体名称不能为空'); return }
  saving.value = true
  try {
    if (editForm.id) {
      await updateCashflowEntity(editForm.id, {
        name: editForm.name.trim(), entityType: editForm.entityType, note: editForm.note,
      })
    } else {
      await createCashflowEntity({ name: editForm.name.trim(), entityType: editForm.entityType, note: editForm.note })
    }
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } catch (e: unknown) {
    ElMessage.error(extractError(e))
  } finally {
    saving.value = false
  }
}

async function toggleEntity(row: CashflowEntity) {
  const target = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  await updateCashflowEntity(row.id, { status: target })
  ElMessage.success(target === 'INACTIVE' ? '主体已停用（不影响已认领账户的历史统计）' : '主体已启用')
  await load()
}

async function openAssign() {
  assignVisible.value = true
  assignLoading.value = true
  try {
    const [accounts, suggestions] = await Promise.all([fetchCashflowAccounts(), fetchCashflowEntitySuggestions()])
    const sugMap = new Map(suggestions.items.map(i => [i.accountName, i.suggestedEntityName]))
    const entMap = new Map(entities.value.map(e => [e.name, e.id]))
    assignRows.value = accounts.map(a => ({
      ...a,
      // 建议仅作预填：账户当前待认领且建议主体已存在时才预填，绝不自动落库
      entityId: a.entityId ?? (a.entityId === null && sugMap.get(a.accountName)
        ? entMap.get(sugMap.get(a.accountName)!) ?? null : null),
      suggestedEntityName: sugMap.get(a.accountName) ?? null,
    }))
  } finally {
    assignLoading.value = false
  }
}

async function saveAssign() {
  saving.value = true
  try {
    const res = await assignCashflowEntities(assignRows.value.map(a => ({ accountId: a.id, entityId: a.entityId })))
    ElMessage.success(`已认领 ${res.assigned} 户，撤销 ${res.cleared} 户`)
    assignVisible.value = false
    await load()
  } catch (e: unknown) {
    ElMessage.error(extractError(e))
  } finally {
    saving.value = false
  }
}

async function removeEntity(row: CashflowEntity) {
  try {
    await deleteCashflowEntity(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e: unknown) {
    ElMessage.error(extractError(e))
  }
}

async function openSuggest() {
  suggestData.value = await fetchCashflowEntitySuggestions()
  suggestVisible.value = true
}

function extractError(e: unknown): string {
  const anyE = e as { response?: { data?: { message?: string } } }
  return anyE?.response?.data?.message ?? '操作失败，请稍后重试'
}

onMounted(() => {
  load()
  loadAccounts()
})
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 12px; }
.toolbar-row { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.title { margin: 0 0 4px; font-size: 16px; }
.hint { margin: 0; color: var(--el-text-color-secondary); font-size: 12px; }
.actions { display: flex; gap: 8px; flex-shrink: 0; }
.assign-alert { margin-bottom: 10px; }
.field-hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; margin-top: 2px; }
</style>
