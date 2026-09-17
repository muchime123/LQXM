<template>
  <MasterLayout
    title="员工名册"
    description="统一维护员工、组织归属、业务员身份、角色权限与手机号登录账号。"
    :is-workbench="true"
    page-class="employee-roster-page"
  >
    <template #toolbar>
      <div class="emp-toolbar">
        <SearchToolbar
          v-model:keyword="filters.keyword"
          search-placeholder="搜索姓名 / 工号 / 手机 / 邮箱 / 岗位"
          search-mode="debounce"
          :fields="toolbarFields"
          :field-values="filters"
          :batch-enabled="false"
          @search="applyFilters"
          @filter-change="handleToolbarFilterChange"
          @clear="handleResetFilters"
        >
          <template #actions>
            <ReturnToSourceButton label="返回订单并复查" />
            <el-button
              title="上传氚云/钉钉通讯录导出文件，批量建立外部身份与本地员工的精确映射。"
              @click="externalImportVisible = true"
              >导入员工资料</el-button
            >
            <el-button type="primary" @click="openCreate">新建员工</el-button>
          </template>
        </SearchToolbar>
        <ActiveFilterChips :filters="activeFilterChips" @remove="removeFilterChip" @clear="handleResetFilters" />
      </div>
    </template>

    <template #list>
      <MasterListPanel
        :models="employees"
        :pagination="pagination"
        :loading="loading"
        :selected-id="selectedEmployeeId"
        layout="workbench"
        :list-sort="`${sortField}-${sortDirection}`"
        :sort-options="masterSortOptions"
        :row="masterRowConfig"
        :empty-title="employees.length ? '当前筛选没有员工' : '暂无员工档案'"
        :empty-hint="employees.length ? '调整筛选条件' : '点击「新建员工」开始建立员工档案。'"
        loading-text="正在加载员工..."
        @select="onSelectEmployee"
        @sort-change="handleMasterSortChange"
        @pagination-change="loadList"
      />
    </template>

    <template #detail>
      <EmployeeDetailPanel
        :key="`${selectedEmployeeId ?? 'none'}-${detailRefreshKey}`"
        :employee-id="selectedEmployeeId"
        :filter-options="filterOptions"
        @edit="openEditFromPanel"
        @delete="confirmDelete"
      />
    </template>

    <template #empty>
      <EmptyState
        v-if="!selectedEmployeeId"
        :text="employees.length ? '选择员工查看详情' : '暂无员工数据'"
        :hint="
          employees.length
            ? '从左侧列表选择一个员工，维护档案、身份映射、角色与登录账号。'
            : '点击「新建员工」开始建立员工档案。'
        "
      />
    </template>

    <template #modals>
      <!-- ========== 新建 / 编辑员工 Dialog ========== -->
      <LqDialog
        v-model="editorVisible"
        :title="editingId ? '编辑员工' : '新建员工'"
        size="md"
        mode="form"
        :loading="saving"
        @confirm="saveEmployee"
      >
        <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
          <div class="employee-form-grid">
            <el-form-item label="工号">
              <el-input v-model="form.employeeNo" placeholder="留空自动生成 LQ 序号" />
            </el-form-item>
            <el-form-item label="姓名" prop="name"><el-input v-model="form.name" /></el-form-item>
            <el-form-item label="性别">
              <el-select v-model="form.gender" clearable placeholder="请选择">
                <el-option label="男" value="男" />
                <el-option label="女" value="女" />
                <el-option label="其他" value="其他" />
              </el-select>
            </el-form-item>
            <el-form-item label="所属公司">
              <el-select v-model="form.companyId" filterable clearable placeholder="可暂不映射">
                <el-option
                  v-for="company in filterOptions?.companies || []"
                  :key="company.id"
                  :label="company.parentName ? `${company.parentName} / ${company.name}` : company.name"
                  :value="company.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="工作部门">
              <el-select
                v-model="form.departmentId"
                filterable
                clearable
                placeholder="请选择实际工作部门"
                @change="syncDepartmentName"
              >
                <el-option
                  v-for="department in organizationDepartments"
                  :key="department.id"
                  :label="department.name"
                  :value="department.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="岗位"><el-input v-model="form.position" /></el-form-item>
            <el-form-item label="手机" prop="phone">
              <el-input v-model="form.phone" placeholder="同时作为员工登录账号" />
            </el-form-item>
            <el-form-item label="身份证号">
              <el-input
                v-model="form.idCardNumber"
                maxlength="18"
                placeholder="18 位身份证号（选填）"
                @change="form.idCardNumber = form.idCardNumber?.trim() || null"
              />
            </el-form-item>
            <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
            <el-form-item label="状态">
              <el-select v-model="form.status">
                <el-option v-for="s in statusOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="入职日期"> <LqDatePicker v-model="form.entryDate" /> </el-form-item>
            <el-form-item label="离职日期"> <LqDatePicker v-model="form.leaveDate" /> </el-form-item>
            <el-form-item label="业务员身份">
              <el-switch
                v-model="form.isSalesperson"
                active-text="是业务员（纳入提成归属/业务行级权限）"
                inactive-text="非业务员"
              />
            </el-form-item>
          </div>
          <el-form-item label="备注" class="employee-form__remark">
            <el-input v-model="form.remark" type="textarea" :rows="3" />
          </el-form-item>
        </el-form>
      </LqDialog>

      <ExternalIdentityImportDialog v-model="externalImportVisible" @imported="handleExternalImported" />
    </template>
  </MasterLayout>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { createEmployee, deleteEmployee, fetchEmployeeFilters, fetchEmployees, updateEmployee } from '@/api/employees'
import ActiveFilterChips, { type ActiveFilter } from '@/components/common/ActiveFilterChips.vue'
import ExternalIdentityImportDialog from '@/components/employee/ExternalIdentityImportDialog.vue'
import EmployeeDetailPanel from '@/components/employee/EmployeeDetailPanel.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import LqDatePicker from '@/components/common/LqDatePicker.vue'
import LqDialog from '@/components/common/LqDialog.vue'
import MasterLayout from '@/components/master/MasterLayout.vue'
import MasterListPanel, { type MasterRowConfig, type SortOption } from '@/components/master/MasterListPanel.vue'
import SearchToolbar, { type ToolbarField } from '@/components/common/SearchToolbar.vue'
import ReturnToSourceButton from '@/components/common/ReturnToSourceButton.vue'
import { useListFilters } from '@/composables/list/useListFilters'
import { useListPagination } from '@/composables/list/useListPagination'
import { fetchDepartments, type DepartmentItem } from '@/api/employees/departments'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { notify } from '@/composables/ui/useToast'
import type { EmployeeDetail, EmployeeFilterOptions, EmployeePayload, EmployeeSummary } from '@/types/employee'

// ========== 筛选 / 分页 ==========
const route = useRoute()
const { filters, resetFilters, getQueryParams } = useListFilters({
  keyword: '',
  department: '',
  status: '',
  accountStatus: '',
  isSalesperson: '' as string | boolean
})
filters.keyword = String(route.query.keyword || '')
const statusOptions = ['在职', '离职', '停薪留职']

const toolbarFields = computed<ToolbarField[]>(() => [
  { key: 'department', label: '部门', options: filterOptions.value?.departments || [], width: 160 },
  { key: 'status', label: '状态', options: filterOptions.value?.statuses || statusOptions, width: 140 },
  {
    key: 'isSalesperson',
    label: '业务员身份',
    options: [
      { label: '仅业务员', value: true },
      { label: '仅非业务员', value: false }
    ],
    width: 150
  },
  {
    key: 'accountStatus',
    label: '登录账号',
    options: [
      { label: '账号正常', value: 'NORMAL' },
      { label: '缺少手机号', value: 'MISSING_PHONE' },
      { label: '未建账号', value: 'MISSING_ACCOUNT' },
      { label: '账号已停用', value: 'DISABLED' }
    ],
    width: 170
  }
])

const pagination = useListPagination()
const { resetPage, getPageParams, updateFromResponse } = pagination

function handleToolbarFilterChange(key: string, value: unknown) {
  ;(filters as Record<string, unknown>)[key] = value ?? ''
  applyFilters()
}
function applyFilters() {
  resetPage()
  loadList()
}
function handleResetFilters() {
  resetFilters()
  applyFilters()
}
const activeFilterChips = computed<ActiveFilter[]>(() => {
  const chips: ActiveFilter[] = []
  if (filters.keyword) chips.push({ key: 'keyword', label: '关键词', valueLabel: filters.keyword })
  if (filters.department) chips.push({ key: 'department', label: '部门', valueLabel: filters.department })
  if (filters.status) chips.push({ key: 'status', label: '状态', valueLabel: filters.status })
  if (filters.accountStatus) {
    chips.push({
      key: 'accountStatus',
      label: '登录账号',
      valueLabel: accountStatusLabels[filters.accountStatus] || filters.accountStatus
    })
  }
  if (filters.isSalesperson !== '' && filters.isSalesperson !== undefined && filters.isSalesperson !== null) {
    chips.push({
      key: 'isSalesperson',
      label: '业务员身份',
      valueLabel: filters.isSalesperson === true || filters.isSalesperson === 'true' ? '仅业务员' : '仅非业务员'
    })
  }
  return chips
})

const accountStatusLabels: Record<string, string> = {
  NORMAL: '账号正常',
  MISSING_PHONE: '缺少手机号',
  MISSING_ACCOUNT: '未建账号',
  DISABLED: '账号已停用'
}

function removeFilterChip(key: string) {
  if (key === 'isSalesperson') {
    ;(filters as Record<string, unknown>).isSalesperson = ''
  } else {
    ;(filters as Record<string, unknown>)[key] = ''
  }
  applyFilters()
}

// ========== 数据加载 ==========
const loading = ref(false)
const employees = ref<EmployeeSummary[]>([])
const listError = ref('')
const filterOptions = ref<EmployeeFilterOptions | null>(null)
const organizationDepartments = ref<DepartmentItem[]>([])

const sortField = ref('department')
const sortDirection = ref<'asc' | 'desc'>('asc')

const masterSortOptions: SortOption[] = [
  { value: 'name-asc', label: '姓名 A→Z' },
  { value: 'name-desc', label: '姓名 Z→A' },
  { value: 'employeeNo-asc', label: '工号升序' },
  { value: 'department-asc', label: '部门 A→Z' },
  { value: 'entryDate-desc', label: '最近入职' },
  { value: 'createdAt-desc', label: '最近建档' }
]

const masterRowConfig: MasterRowConfig = {
  thumbnailUrl: () => null,
  thumbnailFallback: e => (e.name || '员').slice(0, 1),
  title: e => e.name || '未命名员工',
  metaLine: e =>
    [
      e.employeeNo || '无工号',
      filterOptions.value?.companies?.find(company => company.id === e.companyId)?.name || '所属公司待映射',
      e.department || '未分部门',
      e.position || null
    ].filter(Boolean) as string[],
  countsLine: () => null,
  badges: e => [
    e.isSalesperson ? '业务员' : null,
    e.status === '在职' ? null : e.status || null,
    e.accountStatus === 'NORMAL' ? null : accountStatusLabels[e.accountStatus]
  ]
}

const selectedEmployeeId = ref<number | null>(null)
const detailRefreshKey = ref(0)

function onSelectEmployee(id: number) {
  selectedEmployeeId.value = id
}

function handleMasterSortChange(sort: string) {
  const [field, direction] = sort.split('-')
  if (!field || !direction) return
  sortField.value = field
  sortDirection.value = direction as 'asc' | 'desc'
  resetPage()
  loadList()
}

async function loadFilters() {
  try {
    filterOptions.value = await fetchEmployeeFilters()
  } catch {
    /* 列表加载失败时 filterOptions 可为空 */
  }
}

async function loadList() {
  loading.value = true
  listError.value = ''
  try {
    const params: Record<string, unknown> = {
      ...getQueryParams(),
      ...getPageParams()
    }
    if (filters.department) params.department = filters.department
    if (filters.status) params.status = filters.status
    if (filters.accountStatus) params.accountStatus = filters.accountStatus
    if (filters.isSalesperson === true || filters.isSalesperson === 'true') params.isSalesperson = true
    else if (filters.isSalesperson === false || filters.isSalesperson === 'false') params.isSalesperson = false
    const sortDirectionParam = sortDirection.value === 'asc' ? 'asc' : 'desc'
    params.sort = sortField.value
    params.direction = sortDirectionParam
    const page = await fetchEmployees(params)
    employees.value = page.content || page.data?.content || page.data || []
    updateFromResponse(page)
    // 当前选中员工不在本页时，自动取消选中（右侧面板回到空态）
    if (selectedEmployeeId.value != null && !employees.value.some(e => e.id === selectedEmployeeId.value)) {
      selectedEmployeeId.value = null
    }
  } catch (e: any) {
    listError.value = e?.message || '加载失败，请稍后重试。'
    notify.error(listError.value)
  } finally {
    loading.value = false
  }
}

// ========== 新建 / 编辑 ==========
const editorVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref()
const form = reactive<EmployeePayload>({
  employeeNo: null,
  name: '',
  gender: null,
  department: null,
  departmentId: null,
  companyId: null,
  position: null,
  phone: null,
  idCardNumber: null,
  email: null,
  entryDate: null,
  leaveDate: null,
  status: '在职',
  isSalesperson: false,
  remark: null
})

const rules = {
  name: [{ required: true, message: '姓名不能为空', trigger: 'blur' }],
  phone: [{ required: true, message: '手机号不能为空，将作为员工登录账号', trigger: 'blur' }]
}

function resetForm() {
  Object.assign(form, {
    employeeNo: null,
    name: '',
    gender: null,
    department: null,
    departmentId: null,
    companyId: null,
position: null,
  phone: null,
  idCardNumber: null,
  email: null,
  entryDate: null,
  leaveDate: null,
  status: '在职',
  isSalesperson: false,
  remark: null
})
  editingId.value = null
}

function openCreate() {
  resetForm()
  editorVisible.value = true
}

async function openEditFromPanel(detail: EmployeeDetail) {
  Object.assign(form, {
    employeeNo: detail.employeeNo,
    name: detail.name,
    gender: detail.gender,
    department: detail.department,
    departmentId: detail.departmentId,
    companyId: detail.companyId,
    position: detail.position,
    phone: detail.phone,
    idCardNumber: detail.idCardNumber,
    email: detail.email,
    entryDate: detail.entryDate,
    leaveDate: detail.leaveDate,
    status: detail.status,
    isSalesperson: detail.isSalesperson,
    remark: detail.remark
  })
  editingId.value = detail.id
  editorVisible.value = true
}

async function saveEmployee() {
  await formRef.value?.validate()
  saving.value = true
  try {
    if (editingId.value) {
      await updateEmployee(editingId.value, form)
      notify.success('员工档案已更新')
    } else {
      await createEmployee(form)
      notify.success('员工已创建')
    }
    editorVisible.value = false
    detailRefreshKey.value++
    await loadFilters()
    await loadList()
  } catch (e: any) {
    notify.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function syncDepartmentName(departmentId: number | null) {
  form.department = organizationDepartments.value.find(item => item.id === departmentId)?.name || null
}

// ========== 删除 ==========
async function confirmDelete(row: EmployeeDetail | EmployeeSummary) {
  const ok = await askConfirm({
    title: '删除或停用员工档案？',
    message: `${row.name}（${row.employeeNo || '无工号'}）如已有订单、提成、报销等业务记录，将保留档案并转为离职，同时停用登录账号；仅无业务记录的误建档案会被彻底删除。`,
    confirmText: '确认处理',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  try {
    const result = await deleteEmployee(row.id)
    notify.success(result.message)
    if (result.action === 'DELETED' && selectedEmployeeId.value === row.id) selectedEmployeeId.value = null
    detailRefreshKey.value++
    await loadFilters()
    await loadList()
  } catch (e: any) {
    notify.error(e?.message || '删除失败')
  }
}

// ========== 导入外部身份 ==========
const externalImportVisible = ref(false)
async function handleExternalImported() {
  await loadList()
}

// ========== 生命周期 ==========
;[organizationDepartments.value] = await Promise.all([fetchDepartments(), loadFilters(), loadList()])
</script>

<style scoped lang="css">
.employee-roster-page {
  width: 100%;
}

.emp-toolbar {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.emp-toolbar :deep(.search-toolbar) {
  padding: var(--space-sm) var(--space-control-gap-wide);
}

.emp-toolbar :deep(.active-filter-chips) {
  padding: 0;
}

.employee-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 16px;
}
.employee-form__remark {
  margin-top: 8px;
}
</style>
