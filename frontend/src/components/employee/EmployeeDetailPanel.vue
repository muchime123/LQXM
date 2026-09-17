<template>
  <section class="employee-detail-panel pinned" :class="{ 'is-loading': loading }">
    <MasterDetailHeader v-if="detail" :tabs="detailTabs" :active="activeTab" @update:active="activeTab = $event">
      <template #actions>
        <el-button v-if="canManage" size="small" @click="emit('edit', detail)">编辑档案</el-button>
        <el-button v-if="canManage" size="small" type="danger" plain @click="emit('delete', detail)"
          >删除 / 离职</el-button
        >
      </template>
    </MasterDetailHeader>

    <div class="employee-detail-body">
      <div v-if="!detail" class="employee-detail-empty">
        <el-skeleton v-if="loading" :rows="8" animated />
        <template v-else>
          <strong>未选择员工</strong>
          <span>从左侧列表选择员工后，在此维护档案、身份映射、角色与登录账号。</span>
        </template>
      </div>

      <div v-else class="employee-detail-content">
        <div class="emp-meta">
          <StatusTag :type="statusTagType(detail.status)" :label="detail.status || '-'" size="sm" />
          <el-tag v-if="detail.isSalesperson" type="success" effect="light" size="small" round>业务员</el-tag>
          <span v-if="detail.employeeNo" class="emp-meta__pill mono">{{ detail.employeeNo }}</span>
          <span v-if="detail.department" class="emp-meta__pill">{{ detail.department }}</span>
          <span v-if="detail.position" class="emp-meta__pill">{{ detail.position }}</span>
        </div>

        <!-- 基本信息 -->
        <section v-if="activeTab === 'basic'" class="employee-tab-panel">
          <LqDescriptions :items="basicDescriptions" :column="2" border />
          <div v-if="detail.remark" class="remark-block">
            <h4>备注</h4>
            <p>{{ detail.remark }}</p>
          </div>
        </section>

        <!-- 身份映射 -->
        <section v-else-if="activeTab === 'mappings'" class="employee-tab-panel">
          <div class="mappings-toolbar">
            <p class="mappings-hint">
              将同一员工在
              <strong>氚云 / 钉钉 / 系统登录账号</strong>
              的外部身份绑定到内部员工档案，后续导入报销、查询业务、权限过滤均通过此映射稳定定位员工。
            </p>
            <el-button v-if="canManage" type="primary" size="small" @click="openAddMapping">新增映射</el-button>
          </div>
          <el-table v-if="detail.mappings.length" :data="detail.mappings" row-key="id" class="mappings-table" stripe>
            <el-table-column label="系统" width="110">
              <template #default="{ row }">
                <StatusTag :type="sourceTagType(row.sourceSystem)" :label="row.sourceSystem" size="sm" />
              </template>
            </el-table-column>
            <el-table-column prop="externalUserId" label="外部用户 ID" min-width="200" show-overflow-tooltip>
              <template #default="{ row }"
                ><code>{{ row.externalUserId }}</code></template
              >
            </el-table-column>
            <el-table-column prop="externalName" label="外部显示名" width="150">
              <template #default="{ row }">{{ row.externalName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="externalDepartment" label="外部部门" width="150">
              <template #default="{ row }">{{ row.externalDepartment || '—' }}</template>
            </el-table-column>
            <el-table-column prop="externalStatus" label="来源状态" width="110">
              <template #default="{ row }">{{ row.externalStatus || '—' }}</template>
            </el-table-column>
            <el-table-column prop="externalPosition" label="来源职位" width="130">
              <template #default="{ row }">{{ row.externalPosition || '—' }}</template>
            </el-table-column>
            <el-table-column prop="externalRoles" label="氚云角色" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ row.externalRoles || '—' }}</template>
            </el-table-column>
            <el-table-column label="主映射" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.isPrimary" size="small" type="primary" effect="plain">主</el-tag>
                <span v-else class="dim">副</span>
              </template>
            </el-table-column>
            <el-table-column prop="mappedAt" label="绑定时间" width="170">
              <template #default="{ row }">{{ formatTime(row.mappedAt) }}</template>
            </el-table-column>
            <el-table-column v-if="canManage" label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEditMapping(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="confirmRemoveMapping(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="尚未绑定任何外部系统身份。" />

          <LqDialog
            v-model="mappingEditorVisible"
            :title="editingMappingId ? '编辑身份映射' : '新增身份映射'"
            size="sm"
            mode="form"
            :loading="mappingSaving"
            @confirm="saveMapping"
          >
            <el-form ref="mappingFormRef" :model="mappingForm" :rules="mappingRules" label-width="100px">
              <el-form-item label="来源系统" prop="sourceSystem">
                <el-select v-model="mappingForm.sourceSystem">
                  <el-option
                    v-for="s in filterOptions?.sourceSystems || sourceSystemsFallback"
                    :key="s"
                    :label="s"
                    :value="s"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="外部用户 ID" prop="externalUserId">
                <el-input v-model="mappingForm.externalUserId" placeholder="OwnerId / userId / 登录账号" />
              </el-form-item>
              <el-form-item label="外部显示名">
                <el-input v-model="mappingForm.externalName" placeholder="可选，外部系统本名，用于兜底匹配" />
              </el-form-item>
              <el-form-item label="外部部门">
                <el-input v-model="mappingForm.externalDepartment" placeholder="可选" />
              </el-form-item>
              <el-form-item label="主映射">
                <el-switch
                  v-model="mappingForm.isPrimary"
                  active-text="该系统下这个账号是主要的"
                  inactive-text="非主"
                />
              </el-form-item>
              <el-form-item label="备注">
                <el-input v-model="mappingForm.remark" type="textarea" :rows="2" />
              </el-form-item>
            </el-form>
          </LqDialog>
        </section>

        <!-- 角色权限 -->
        <section v-else-if="activeTab === 'roles'" class="employee-tab-panel">
          <el-alert
            title="角色决定菜单、接口操作权限和可见数据范围；业务员档案会强制保留“业务员”角色。"
            type="info"
            :closable="false"
            show-icon
          />
          <div v-loading="roleLoading" class="role-grid">
            <label
              v-for="role in systemRoles"
              :key="role.role_code"
              class="role-card"
              :class="{ 'is-selected': selectedRoleCodes.includes(role.role_code) }"
            >
              <el-checkbox
                v-model="selectedRoleCodes"
                :value="role.role_code"
                :disabled="role.role_code === 'SALESPERSON' && !!detail?.isSalesperson"
              >
                <strong>{{ role.role_name }}</strong>
              </el-checkbox>
              <el-tag size="small" effect="plain">{{ scopeLabel(role.data_scope) }}</el-tag>
              <p>
                {{
                  (role.featureGrants?.length ?? 0) > 0
                    ? `已授权 ${role.featureGrants!.length} 项功能（含查看/操作与数据范围）`
                    : '未单独授权功能，仅保留菜单可见性'
                }}
              </p>
            </label>
          </div>
          <div v-if="canManage" class="role-actions">
            <el-button type="primary" :loading="roleSaving" @click="saveEmployeeRoles">保存角色配置</el-button>
          </div>
        </section>

        <!-- 登录账号 -->
        <section v-else-if="activeTab === 'loginAccount'" class="employee-tab-panel">
          <el-alert
            title="员工默认使用手机号登录，初始密码为 123456；管理员可在此修改账号状态或重置密码。"
            type="info"
            :closable="false"
            show-icon
          />
          <div v-if="detailAccount" v-loading="accountLoading" class="login-account-box">
            <LqDescriptions :items="loginAccountDescriptions" :column="1" border />
            <div v-if="canManage" class="login-account-actions">
              <el-button size="small" @click="toggleAccountEnabled">
                {{ detailAccount.enabled ? '停用账号' : '启用账号' }}
              </el-button>
              <el-button size="small" type="warning" plain @click="resetAccountPassword">重置为 123456</el-button>
            </div>
          </div>
          <div v-else v-loading="accountLoading" class="login-account-empty">
            <el-empty description="该员工尚未生成登录账号，请确认手机号后重新保存员工档案。" :image-size="80" />
          </div>
        </section>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { addEmployeeMapping, fetchEmployee, updateEmployeeMapping, removeEmployeeMapping } from '@/api/employees'
import { fetchEmployeeRoles, fetchSystemRoles, updateEmployeeRoles, type SystemRole } from '@/api/accessControl'
import { fetchEmployeeLoginAccount, resetLoginPassword, setLoginAccountEnabled, type LoginAccount } from '@/api/auth'
import LqDescriptions from '@/components/common/LqDescriptions.vue'
import LqDialog from '@/components/common/LqDialog.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import MasterDetailHeader, { type MasterDetailTab } from '@/components/master/MasterDetailHeader.vue'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import type {
  EmployeeDetail,
  EmployeeFilterOptions,
  EmployeeMappingSummary,
  EmployeeMappingPayload
} from '@/types/employee'
import { formatDateTime } from '@/utils/common/dateUtils'

const props = withDefaults(
  defineProps<{
    employeeId: number | null
    /** 是否可维护员工档案（employee.manage 权限） */
    canManage?: boolean
    /** 身份映射来源系统选项 */
    filterOptions?: EmployeeFilterOptions | null
  }>(),
  { canManage: true, filterOptions: null }
)

const emit = defineEmits<{
  edit: [detail: EmployeeDetail]
  delete: [detail: EmployeeDetail]
}>()

const formatTime = (val: string | null | undefined) => (val ? formatDateTime(val) : '—')
const sourceSystemsFallback = ['氚云', '钉钉', '系统账号']

const detail = ref<EmployeeDetail | null>(null)
const activeTab = ref('basic')
const loading = ref(false)

const systemRoles = ref<SystemRole[]>([])
const selectedRoleCodes = ref<string[]>([])
const roleLoading = ref(false)
const roleSaving = ref(false)

const detailAccount = ref<LoginAccount | null>(null)
const accountLoading = ref(false)

const detailTabs = computed<MasterDetailTab[]>(() => [
  { key: 'basic', label: '基本信息' },
  { key: 'mappings', label: '身份映射', badge: detail.value?.mappings.length ?? 0 },
  { key: 'roles', label: '角色权限' },
  { key: 'loginAccount', label: '登录账号' }
])

function statusTagType(status: string): string {
  const map: Record<string, string> = { 在职: 'success', 离职: 'danger', 停薪留职: 'warning' }
  return map[status] || 'neutral'
}
function sourceTagType(sys: string): string {
  const map: Record<string, string> = { 氚云: 'info', 钉钉: 'primary', 系统账号: 'neutral' }
  return map[sys] || 'neutral'
}
function scopeLabel(scope: SystemRole['data_scope']) {
  return { SELF: '本人数据', DEPARTMENT: '本部门数据', ALL: '全部数据' }[scope]
}

const basicDescriptions = computed(() =>
  detail.value
    ? [
        { label: '工号', value: detail.value.employeeNo || '—' },
        { label: '姓名', value: detail.value.name },
        { label: '性别', value: detail.value.gender || '—' },
        {
          label: '所属公司',
          value:
            props.filterOptions?.companies?.find(company => company.id === detail.value?.companyId)?.name || '待映射'
        },
        { label: '工作部门', value: detail.value.department || '—' },
        { label: '岗位', value: detail.value.position || '—' },
        { label: '手机', value: detail.value.phone || '—' },
        { label: '身份证号', value: detail.value.idCardNumber || '—' },
        { label: '邮箱', value: detail.value.email || '—' },
        { label: '状态', value: detail.value.status },
        { label: '业务员身份', value: detail.value.isSalesperson ? '业务员' : '非业务员' },
        { label: '入职日期', value: detail.value.entryDate || '—' },
        { label: '离职日期', value: detail.value.leaveDate || '—' },
        { label: '映射条目', value: `${detail.value.mappings.length} 条` },
        { label: '创建人', value: detail.value.createdBy || '—' },
        { label: '最后更新人', value: detail.value.updatedBy || '—' },
        { label: '建档时间', value: formatTime(detail.value.createdAt) },
        { label: '最近更新', value: formatTime(detail.value.updatedAt) }
      ]
    : []
)

const loginAccountDescriptions = computed(() =>
  detailAccount.value
    ? [
        { label: '登录用户名', value: detailAccount.value.username },
        { label: '状态', value: detailAccount.value.enabled ? '启用' : '停用' },
        { label: '最近登录', value: formatTime(detailAccount.value.lastLoginAt) },
        { label: '建档时间', value: formatTime(detailAccount.value.createdAt) }
      ]
    : []
)

async function loadRoleConfig(employeeId: number) {
  roleLoading.value = true
  try {
    const [roles, assigned] = await Promise.all([
      systemRoles.value.length ? Promise.resolve(systemRoles.value) : fetchSystemRoles(),
      fetchEmployeeRoles(employeeId)
    ])
    systemRoles.value = roles
    selectedRoleCodes.value = [...assigned.roleCodes]
  } catch (e: any) {
    notify.error(e?.message || '加载角色权限失败')
  } finally {
    roleLoading.value = false
  }
}

async function saveEmployeeRoles() {
  if (!detail.value || !props.canManage) return
  roleSaving.value = true
  try {
    const result = await updateEmployeeRoles(detail.value.id, selectedRoleCodes.value)
    selectedRoleCodes.value = [...result.roleCodes]
    notify.success('角色权限已更新')
  } catch (e: any) {
    notify.error(e?.message || '保存角色权限失败')
  } finally {
    roleSaving.value = false
  }
}

async function loadDetailAccount(employeeId: number) {
  accountLoading.value = true
  try {
    detailAccount.value = await fetchEmployeeLoginAccount(employeeId)
  } catch (e: any) {
    detailAccount.value = null
    notify.error(e?.message || '加载登录账号失败')
  } finally {
    accountLoading.value = false
  }
}

// ===== 身份映射 CRUD =====

const mappingEditorVisible = ref(false)
const editingMappingId = ref<number | null>(null)
const mappingSaving = ref(false)
const mappingFormRef = ref()
const mappingForm = reactive<EmployeeMappingPayload>({
  sourceSystem: undefined,
  externalUserId: undefined,
  externalName: null,
  externalDepartment: null,
  isPrimary: false,
  remark: null
})
const mappingRules = {
  sourceSystem: [{ required: true, message: '请选择来源系统', trigger: 'change' }],
  externalUserId: [{ required: true, message: '请填写外部用户 ID', trigger: 'blur' }]
}

function resetMappingForm() {
  Object.assign(mappingForm, {
    sourceSystem: undefined,
    externalUserId: undefined,
    externalName: null,
    externalDepartment: null,
    isPrimary: false,
    remark: null
  })
  editingMappingId.value = null
}

function openAddMapping() {
  if (!detail.value) return
  resetMappingForm()
  mappingEditorVisible.value = true
}

function openEditMapping(row: EmployeeMappingSummary) {
  Object.assign(mappingForm, {
    sourceSystem: row.sourceSystem,
    externalUserId: row.externalUserId,
    externalName: row.externalName,
    externalDepartment: row.externalDepartment,
    isPrimary: row.isPrimary,
    remark: row.remark
  })
  editingMappingId.value = row.id
  mappingEditorVisible.value = true
}

async function saveMapping() {
  if (!detail.value || !props.canManage) return
  await mappingFormRef.value?.validate()
  mappingSaving.value = true
  try {
    if (editingMappingId.value) {
      await updateEmployeeMapping(detail.value.id, editingMappingId.value, mappingForm)
      notify.success('身份映射已更新')
    } else {
      await addEmployeeMapping(detail.value.id, mappingForm)
      notify.success('身份映射已新增')
    }
    mappingEditorVisible.value = false
    await reload()
  } catch (e: any) {
    notify.error(e?.message || '保存映射失败')
  } finally {
    mappingSaving.value = false
  }
}

async function confirmRemoveMapping(row: EmployeeMappingSummary) {
  if (!detail.value || !props.canManage) return
  const ok = await askConfirm({
    title: '确认删除身份映射？',
    message: `${row.sourceSystem} / ${row.externalUserId}，删除后该外部账号将无法再定位到本员工。`,
    confirmText: '删除',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  try {
    await removeEmployeeMapping(detail.value.id, row.id)
    notify.success('身份映射已删除')
    await reload()
  } catch (e: any) {
    notify.error(e?.message || '删除映射失败')
  }
}

// ===== 登录账号治理 =====

async function toggleAccountEnabled() {
  if (!detailAccount.value || !props.canManage) return
  const enabled = !detailAccount.value.enabled
  const ok = await askConfirm({
    title: enabled ? '启用员工账号？' : '停用员工账号？',
    message: enabled
      ? `将允许 ${detailAccount.value.username} 登录系统。`
      : `将禁止 ${detailAccount.value.username} 登录系统，并立即撤销该员工的现有会话。`,
    confirmText: enabled ? '确认启用' : '确认停用',
    cancelText: '取消',
    danger: !enabled
  })
  if (!ok) return
  try {
    await setLoginAccountEnabled(detailAccount.value.id, enabled)
    notify.success(enabled ? '账号已启用' : '账号已停用')
    await loadDetailAccount(detailAccount.value.employeeId)
  } catch (e: any) {
    notify.error(e?.message || '账号状态更新失败')
  }
}

async function resetAccountPassword() {
  if (!detailAccount.value || !props.canManage) return
  const ok = await askConfirm({
    title: '重置员工密码？',
    message: `账号 ${detailAccount.value.username} 的密码将重置为 123456，现有登录会话会立即失效。`,
    confirmText: '确认重置',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  try {
    await resetLoginPassword(detailAccount.value.id)
    notify.success('密码已重置为 123456')
    await loadDetailAccount(detailAccount.value.employeeId)
  } catch (e: any) {
    notify.error(e?.message || '密码重置失败')
  }
}

// ===== 加载 =====

async function reload() {
  if (!props.employeeId) return
  try {
    detail.value = await fetchEmployee(props.employeeId)
  } catch (e: any) {
    notify.error(e?.message || '加载员工详情失败')
  }
}

watch(
  () => props.employeeId,
  async id => {
    activeTab.value = 'basic'
    detail.value = null
    detailAccount.value = null
    if (!id) return
    loading.value = true
    try {
      detail.value = await fetchEmployee(id)
      loadRoleConfig(id)
      loadDetailAccount(id)
    } catch (e: any) {
      notify.error(e?.message || '加载员工详情失败')
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)
</script>

<style scoped lang="css">
.employee-detail-panel.pinned {
  position: sticky;
  top: 108px;
  right: auto;
  bottom: auto;
  left: auto;
  width: 100%;
  height: calc(100vh - 124px);
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

.employee-detail-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  overflow-y: auto;
  overflow-x: hidden;
  min-height: 0;
}

.employee-detail-content {
  display: grid;
  gap: var(--space-control-gap-wide);
  padding: var(--space-control-gap-wide);
}

.employee-detail-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  padding: 60px 20px;
  color: var(--color-text-faint);
  text-align: center;
}
.employee-detail-empty strong {
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  color: var(--color-text-muted);
}
.employee-detail-empty span {
  font-size: var(--fs-sm);
}

.employee-tab-panel {
  display: grid;
  gap: var(--space-control-gap-wide);
}

.emp-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm);
}
.emp-meta__pill {
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  border: 1px solid var(--color-border-light);
}
.emp-meta__pill.mono {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 12px;
  color: var(--color-text-muted);
}

.remark-block {
  margin-top: 4px;
  padding: 12px 16px;
  background: var(--color-surface-2);
  border-radius: var(--radius-sm);
}
.remark-block h4 {
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--color-text-muted);
}
.remark-block p {
  margin: 0;
  white-space: pre-wrap;
}

.mappings-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 4px;
}
.mappings-hint {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-muted);
  line-height: 1.6;
}
.mappings-table {
  border: 1px solid var(--color-border-2);
  border-radius: var(--radius-sm);
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-md);
  margin-top: var(--space-md);
}
.role-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-md);
  cursor: pointer;
  background: var(--color-surface);
}
.role-card.is-selected {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}
.role-card p {
  margin: var(--space-sm) 0 0;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.role-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-lg);
}

.login-account-box {
  margin-top: var(--space-md);
}
.login-account-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-sm);
  margin-top: var(--space-md);
}
.login-account-empty {
  margin-top: var(--space-md);
}

.dim {
  color: var(--color-text-muted);
  opacity: 0.75;
}

@media (max-width: 980px) {
  .employee-detail-panel.pinned {
    position: fixed;
    inset: 0;
    border: 0;
    border-radius: 0;
    z-index: 50;
  }
}
</style>
