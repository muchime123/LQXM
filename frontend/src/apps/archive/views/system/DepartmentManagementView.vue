<template>
  <ListPageLayout
    title="部门主数据"
    description="公司主体与工作部门分开维护；车间所属公司无法确认时保留待映射，不自动猜测。"
    :busy="loading"
    content-label="部门主数据列表"
  >
    <template #eyebrow>系统管理 · ORGANIZATION</template>
    <template #toolbar>
      <div class="department-toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索部门名称 / 编码" style="max-width: 360px" />
        <el-button type="primary" @click="activeTab === 'departments' ? openCreate() : openCompanyCreate()">
          {{ activeTab === 'departments' ? '新增工作部门' : '新增公司主体' }}
        </el-button>
      </div>
    </template>

    <div class="quality-grid">
      <div v-for="card in qualityCards" :key="card.label" class="quality-card">
        <span>{{ card.label }}</span><strong>{{ card.value }}</strong><small>{{ card.hint }}</small>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="organization-tabs">
      <el-tab-pane label="工作部门" name="departments" />
      <el-tab-pane label="公司主体" name="companies" />
    </el-tabs>

    <DataState
      v-if="activeTab === 'departments'"
      :state="dataState"
      :skeleton="dataState === 'loading'"
      skeleton-variant="table"
      :skeleton-rows="8"
      error-title="部门主数据加载失败"
      :error-message="errorMessage"
      empty-text="暂无部门主数据"
      empty-hint="新增部门或同步员工名册后会自动建立。"
      @retry="loadData"
    >
      <LqTable :data="filteredRows" row-key="id">
        <el-table-column prop="departmentCode" label="部门编码" width="140">
          <template #default="{ row }">{{ row.departmentCode || '—' }}</template>
        </el-table-column>
        <el-table-column prop="name" label="部门名称" min-width="180" />
        <el-table-column prop="parentName" label="上级部门" min-width="150">
          <template #default="{ row }">{{ row.parentName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="companyName" label="所属公司" min-width="190">
          <template #default="{ row }">
            <span v-if="row.companyName">{{ row.companyName }}</span>
            <el-tag v-else type="warning" effect="plain" size="small">待人工映射</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ row.unitType === 'WORKSHOP' ? '车间' : row.unitType === 'LEGACY_ORGANIZATION' ? '遗留组织' : '部门' }}</template>
        </el-table-column>
        <el-table-column prop="activeEmployeeCount" label="在职员工" width="110" align="right" />
        <el-table-column prop="expenseClaimCount" label="费用单" width="100" align="right" />
        <el-table-column label="已批费用" width="140" align="right">
          <template #default="{ row }">¥{{ money(row.approvedExpenseAmount) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }"><el-tag effect="plain" size="small">{{ row.sourceSystem }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button></template>
        </el-table-column>
      </LqTable>
    </DataState>

    <LqTable v-else :data="filteredCompanies" row-key="id">
      <el-table-column prop="companyCode" label="公司编码" width="130"><template #default="{ row }">{{ row.companyCode || '—' }}</template></el-table-column>
      <el-table-column prop="name" label="公司名称" min-width="240" />
      <el-table-column prop="parentName" label="上级公司" min-width="220"><template #default="{ row }">{{ row.parentName || '—' }}</template></el-table-column>
      <el-table-column prop="departmentCount" label="工作部门" width="110" align="right" />
      <el-table-column prop="activeEmployeeCount" label="在职员工" width="110" align="right" />
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openCompanyEdit(row)">编辑</el-button></template></el-table-column>
    </LqTable>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑部门' : '新增部门'" width="520px">
      <el-form label-position="top">
        <el-form-item label="部门名称" required><el-input v-model="form.name" maxlength="200" /></el-form-item>
        <el-form-item label="部门编码"><el-input v-model="form.departmentCode" maxlength="50" /></el-form-item>
        <el-form-item label="上级部门">
          <el-select v-model="form.parentId" clearable filterable style="width: 100%">
            <el-option v-for="item in parentOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门类型">
          <el-radio-group v-model="form.unitType"><el-radio value="DEPARTMENT">职能部门</el-radio><el-radio value="WORKSHOP">生产车间</el-radio></el-radio-group>
        </el-form-item>
        <el-form-item label="所属公司">
          <el-select v-model="form.companyId" clearable filterable placeholder="无法确认时保持为空" style="width: 100%">
            <el-option v-for="company in activeCompanies" :key="company.id" :label="company.parentName ? `${company.parentName} / ${company.name}` : company.name" :value="company.id" />
          </el-select>
          <small class="form-hint">留空后进入“所属公司待映射”，不会自动归到总公司。</small>
        </el-form-item>
        <el-form-item label="状态"><el-switch v-model="active" active-text="启用" inactive-text="停用" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="companyDialogVisible" :title="editingCompanyId ? '编辑公司主体' : '新增公司主体'" width="520px">
      <el-form label-position="top">
        <el-form-item label="公司名称" required><el-input v-model="companyForm.name" maxlength="200" /></el-form-item>
        <el-form-item label="公司编码"><el-input v-model="companyForm.companyCode" maxlength="50" /></el-form-item>
        <el-form-item label="上级公司"><el-select v-model="companyForm.parentId" clearable filterable style="width:100%"><el-option v-for="company in companyParentOptions" :key="company.id" :label="company.name" :value="company.id" /></el-select></el-form-item>
        <el-form-item label="状态"><el-switch v-model="companyActive" active-text="启用" inactive-text="停用" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="companyDialogVisible=false">取消</el-button><el-button type="primary" :loading="companySaving" @click="saveCompany">保存</el-button></template>
    </el-dialog>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import DataState, { type DataStateStatus } from '@/components/common/DataState.vue'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import LqTable from '@/components/common/LqTable.vue'
import { notify } from '@/composables/ui/useToast'
import {
  createCompany, createDepartment, fetchCompanies, fetchDepartmentQuality, fetchDepartments, updateCompany, updateDepartment,
  type CompanyItem, type DepartmentItem, type DepartmentQuality
} from '@/api/employees/departments'

const rows = ref<DepartmentItem[]>([])
const quality = ref<DepartmentQuality | null>(null)
const companies = ref<CompanyItem[]>([])
const activeTab = ref<'departments' | 'companies'>('departments')
const keyword = ref('')
const loading = ref(false)
const errorMessage = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const active = ref(true)
const form = reactive({ name: '', departmentCode: '', parentId: null as number | null, companyId: null as number | null, unitType: 'DEPARTMENT' as 'DEPARTMENT' | 'WORKSHOP' })
const companyDialogVisible = ref(false)
const companySaving = ref(false)
const editingCompanyId = ref<number | null>(null)
const companyActive = ref(true)
const companyForm = reactive({ name: '', companyCode: '', parentId: null as number | null })

const filteredRows = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return key ? rows.value.filter(row => `${row.name} ${row.departmentCode || ''}`.toLowerCase().includes(key)) : rows.value
})
const filteredCompanies = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return key ? companies.value.filter(row => `${row.name} ${row.companyCode || ''}`.toLowerCase().includes(key)) : companies.value
})
const dataState = computed<DataStateStatus>(() => loading.value && !rows.value.length ? 'loading' : errorMessage.value ? 'error' : rows.value.length ? 'ready' : 'empty')
const qualityCards = computed(() => [
  { label: '有效部门', value: quality.value?.departmentCount ?? 0, hint: '统一部门主数据' },
  { label: '员工待归属', value: quality.value?.employeesWithoutDepartment ?? 0, hint: '在职员工 department_id 为空' },
  { label: '费用待归属', value: quality.value?.claimsWithoutDepartment ?? 0, hint: '历史费用 department_id 为空' },
  { label: '部门待映射公司', value: quality.value?.departmentsWithoutCompany ?? 0, hint: '允许后续人工确认所属子公司' }
])
const parentOptions = computed(() => rows.value.filter(row => row.status === 'ACTIVE' && row.id !== editingId.value))
const activeCompanies = computed(() => companies.value.filter(row => row.status === 'ACTIVE'))
const companyParentOptions = computed(() => activeCompanies.value.filter(row => row.id !== editingCompanyId.value))

function money(value: number) { return Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function openCreate() { editingId.value = null; Object.assign(form, { name: '', departmentCode: '', parentId: null, companyId: null, unitType: 'DEPARTMENT' }); active.value = true; dialogVisible.value = true }
function openEdit(row: DepartmentItem) { editingId.value = row.id; Object.assign(form, { name: row.name, departmentCode: row.departmentCode || '', parentId: row.parentId, companyId: row.companyId, unitType: row.unitType === 'WORKSHOP' ? 'WORKSHOP' : 'DEPARTMENT' }); active.value = row.status === 'ACTIVE'; dialogVisible.value = true }
function openCompanyCreate() { editingCompanyId.value=null; Object.assign(companyForm,{name:'',companyCode:'',parentId:null}); companyActive.value=true; companyDialogVisible.value=true }
function openCompanyEdit(row: CompanyItem) { editingCompanyId.value=row.id; Object.assign(companyForm,{name:row.name,companyCode:row.companyCode || '',parentId:row.parentId}); companyActive.value=row.status==='ACTIVE'; companyDialogVisible.value=true }

async function loadData() {
  loading.value = true; errorMessage.value = ''
  try { [rows.value, quality.value, companies.value] = await Promise.all([fetchDepartments(), fetchDepartmentQuality(), fetchCompanies()]) }
  catch (error: any) { errorMessage.value = error?.response?.data?.message || error?.message || '加载失败' }
  finally { loading.value = false }
}
async function save() {
  if (!form.name.trim()) return notify.warning('请输入部门名称')
  saving.value = true
  try {
    const payload = { name: form.name.trim(), departmentCode: form.departmentCode.trim() || null, parentId: form.parentId, companyId: form.companyId, ownershipStatus: form.companyId ? 'CONFIRMED' as const : 'UNMAPPED' as const, unitType: form.unitType, status: active.value ? 'ACTIVE' as const : 'INACTIVE' as const }
    if (editingId.value) await updateDepartment(editingId.value, payload)
    else await createDepartment(payload)
    notify.success('部门主数据已保存'); dialogVisible.value = false; await loadData()
  } catch (error: any) { notify.error(error?.response?.data?.message || error?.message || '保存失败') }
  finally { saving.value = false }
}
async function saveCompany() {
  if (!companyForm.name.trim()) return notify.warning('请输入公司名称')
  companySaving.value=true
  try {
    const payload={name:companyForm.name.trim(),companyCode:companyForm.companyCode.trim() || null,parentId:companyForm.parentId,status:companyActive.value ? 'ACTIVE' as const : 'INACTIVE' as const}
    if (editingCompanyId.value) await updateCompany(editingCompanyId.value,payload); else await createCompany(payload)
    notify.success('公司主体已保存'); companyDialogVisible.value=false; await loadData()
  } catch(error:any) { notify.error(error?.response?.data?.message || error?.message || '保存失败') }
  finally { companySaving.value=false }
}

await loadData()
</script>

<style scoped>
.department-toolbar { display: flex; justify-content: space-between; gap: var(--space-3); }
.quality-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-3); margin-bottom: var(--space-4); }
.quality-card { display: grid; gap: var(--space-1); padding: var(--space-4); border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-bg-card); }
.quality-card span,.quality-card small { color: var(--color-text-secondary); }
.quality-card strong { font-size: 26px; }
.organization-tabs { margin-bottom: var(--space-3); }
.form-hint { display:block; margin-top:var(--space-1); color:var(--color-text-secondary); }
@media (max-width: 900px) { .quality-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
