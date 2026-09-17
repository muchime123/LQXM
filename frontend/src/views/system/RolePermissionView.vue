<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addRoleMembers,
  createRole,
  deleteRole,
  fetchRoleMembers,
  fetchSystemFeatures,
  fetchSystemPermissions,
  fetchSystemRoles,
  removeRoleMember,
  setPermissionEnabled,
  updateRole,
  type DataScope,
  type FeatureAction,
  type FeatureGrant,
  type FeatureItem,
  type RoleMember,
  type RolePayload,
  type SystemPermission,
  type SystemRole,
} from '@/api/accessControl'
import { fetchEmployees } from '@/api/employees/employees'
import { fetchDepartments, type DepartmentItem } from '@/api/employees/departments'

const roles = ref<SystemRole[]>([])
const features = ref<FeatureItem[]>([])
const permissions = ref<SystemPermission[]>([])
const loading = ref(false)
const saving = ref(false)

// ==================== 权限码启停用管理 ====================
const permManagerVisible = ref(false)
const permSavingId = ref<number | null>(null)

async function togglePermission(perm: SystemPermission, enabled: boolean) {
  permSavingId.value = perm.id
  try {
    await setPermissionEnabled(perm.id, enabled)
    perm.enabled = enabled
    ElMessage.success(`权限「${perm.permission_name}」${enabled ? '已启用' : '已停用'}，停用后立即对全员生效`)
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '操作失败')
  } finally {
    permSavingId.value = null
  }
}

// ==================== 功能分组 ====================
const CATEGORY_LABELS: Record<string, string> = {
  SALES: '销售业务',
  FINANCE: '财务结算',
  MFG: '生产制造',
  INVENTORY: '库存管理',
  DATA: '数据集成',
  PLATFORM: '平台管理',
}

const CATEGORY_ORDER = ['SALES', 'FINANCE', 'MFG', 'INVENTORY', 'DATA', 'PLATFORM']

const groupedFeatures = computed(() => {
  const groups = new Map<string, FeatureItem[]>()
  for (const f of features.value) {
    if (!groups.has(f.category)) groups.set(f.category, [])
    groups.get(f.category)!.push(f)
  }
  return CATEGORY_ORDER.filter((c) => groups.has(c)).map((c) => ({
    category: c,
    label: CATEGORY_LABELS[c] ?? c,
    items: groups.get(c)!,
  }))
})

/** 功能是否带"范围变体"（决定是否显示数据范围下拉） */
function hasScopeVariants(feature: FeatureItem) {
  return feature.actions.some((a) => a.action_kind === 'VIEW' && a.variant != null)
}

/** 功能的可选数据范围（按该功能存在的 read 变体码动态生成） */
function scopeOptions(feature: FeatureItem) {
  const variants = new Set(feature.actions.filter((a) => a.action_kind === 'VIEW' && a.variant).map((a) => a.variant))
  const opts: Array<{ value: DataScope; label: string; desc: string }> = []
  if (variants.has('ALL')) opts.push({ value: 'ALL', label: '全部数据', desc: '可查看本功能下全部业务数据' })
  if (variants.has('DEPARTMENT')) opts.push({ value: 'DEPARTMENT', label: '本部门', desc: '仅本部门员工负责的数据' })
  if (variants.has('SELF')) {
    opts.push({ value: 'SELF', label: '仅本人', desc: '仅本人经手的数据' })
    opts.push({ value: 'CUSTOM', label: '自定义', desc: '本人 + 指定员工/部门的数据' })
  }
  return opts
}

/** 功能的操作动作（非查看类），UI 逐个勾选 */
function actionButtons(feature: FeatureItem) {
  return feature.actions.filter((a) => a.action_kind !== 'VIEW')
}

function hasViewAction(feature: FeatureItem) {
  return feature.actions.some((a) => a.action_kind === 'VIEW')
}

const KIND_LABEL: Record<string, { label: string; tag: 'success' | 'primary' | 'warning' | 'danger' | 'info' }> = {
  CREATE: { label: '新增', tag: 'success' },
  UPDATE: { label: '编辑', tag: 'primary' },
  DELETE: { label: '删除', tag: 'danger' },
  EXECUTE: { label: '操作', tag: 'info' },
}

// ==================== 角色成员管理 ====================
const members = ref<RoleMember[]>([])
const memberLoading = ref(false)
const memberDialogVisible = ref(false)
const memberSaving = ref(false)
const employeeOptions = ref<Array<{ id: number; name: string; department: string | null }>>([])
const departmentOptions = ref<DepartmentItem[]>([])
const selectedMemberIds = ref<number[]>([])

const availableEmployees = computed(() =>
  employeeOptions.value.filter((emp) => !members.value.some((m) => m.id === emp.id))
)

// ==================== 角色编辑（功能授权模型） ====================
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
const form = ref<{ roleCode: string; roleName: string }>({ roleCode: '', roleName: '' })

interface EditableGrant {
  enabled: boolean
  dataScope: DataScope
  employeeIds: number[]
  departmentNames: string[]
  /** 'VIEW' + 勾选的功能下权限码 */
  actions: string[]
}
const grantMap = ref<Record<string, EditableGrant>>({})

const editingIsBuiltIn = computed(() => roles.value.find((r) => r.id === editingId.value)?.built_in ?? false)

async function loadRoles() {
  loading.value = true
  try {
    roles.value = await fetchSystemRoles()
  } finally {
    loading.value = false
  }
}

function emptyGrant(): EditableGrant {
  return { enabled: false, dataScope: 'SELF', employeeIds: [], departmentNames: [], actions: [] }
}

function buildGrantMap(grants: FeatureGrant[] = []) {
  const map: Record<string, EditableGrant> = {}
  for (const f of features.value) {
    const found = grants.find((g) => g.featureCode === f.feature_code)
    if (found) {
      map[f.feature_code] = {
        enabled: found.actions.length > 0,
        dataScope: found.dataScope ?? 'SELF',
        employeeIds: found.employeeIds ?? [],
        departmentNames: found.departmentNames ?? [],
        actions: [...found.actions],
      }
    } else {
      map[f.feature_code] = emptyGrant()
    }
  }
  grantMap.value = map
}

/** 启用/停用某功能：启用时若功能含查看动作则默认勾选查看（数据范围决定授哪个变体码） */
function toggleEnabled(feature: FeatureItem, enabled: boolean) {
  const g = grantMap.value[feature.feature_code]
  if (!g) return
  if (enabled) {
    const base: string[] = hasViewAction(feature) ? ['VIEW'] : []
    g.actions = base
    if (!hasScopeVariants(feature) && actionButtons(feature).length === 1) {
      // 平台/纯执行类功能：单动作直接勾上，减少点选
      g.actions = base.concat(actionButtons(feature).map((a) => a.permission_code))
    }
  } else {
    g.actions = []
  }
}

function isActionChecked(featureCode: string, code: string) {
  return (grantMap.value[featureCode]?.actions ?? []).includes(code)
}

function toggleAction(feature: FeatureItem, action: FeatureAction) {
  const g = grantMap.value[feature.feature_code]
  if (!g) return
  g.actions = isActionChecked(feature.feature_code, action.permission_code)
    ? g.actions.filter((c) => c !== action.permission_code)
    : [...g.actions, action.permission_code]
}

async function openCreate() {
  editingId.value = null
  form.value = { roleCode: '', roleName: '' }
  buildGrantMap()
  drawerVisible.value = true
}

async function openEdit(role: SystemRole) {
  editingId.value = role.id
  form.value = { roleCode: role.role_code, roleName: role.role_name }
  buildGrantMap(role.featureGrants)
  drawerVisible.value = true
  void loadMembers(role.id)
}

async function loadMembers(roleId: number) {
  memberLoading.value = true
  try {
    members.value = await fetchRoleMembers(roleId)
  } finally {
    memberLoading.value = false
  }
}

async function openAddMember() {
  selectedMemberIds.value = []
  memberDialogVisible.value = true
  if (employeeOptions.value.length === 0) await loadEmployeeOptions()
}

async function loadEmployeeOptions() {
  try {
    const page = await fetchEmployees({ page: 0, size: 500, status: '在职', sort: 'name', direction: 'asc' })
    employeeOptions.value = (page.items || page.content || page.data || []).map((row: Record<string, any>) => ({
      id: row.id,
      name: row.name,
      department: row.department,
    }))
  } catch {
    employeeOptions.value = []
  }
}

async function addMembers() {
  if (!editingId.value || selectedMemberIds.value.length === 0) return
  memberSaving.value = true
  try {
    await addRoleMembers(editingId.value, selectedMemberIds.value)
    ElMessage.success(`已添加 ${selectedMemberIds.value.length} 名成员`)
    memberDialogVisible.value = false
    await Promise.all([loadMembers(editingId.value), loadRoles()])
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '添加成员失败')
  } finally {
    memberSaving.value = false
  }
}

async function removeMember(member: RoleMember) {
  if (!editingId.value) return
  try {
    await ElMessageBox.confirm(`确定将「${member.name}」移出该角色？`, '移除成员', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await removeRoleMember(editingId.value, member.id)
    ElMessage.success('已移除')
    await Promise.all([loadMembers(editingId.value), loadRoles()])
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '移除失败')
  }
}

function collectGrants(): FeatureGrant[] {
  const grants: FeatureGrant[] = []
  for (const f of features.value) {
    const g = grantMap.value[f.feature_code]
    if (!g || !g.enabled || g.actions.length === 0) continue
    if (g.dataScope === 'CUSTOM' && g.employeeIds.length === 0 && g.departmentNames.length === 0) {
      ElMessage.warning(`功能「${f.feature_name}」选择自定义范围，请至少指定员工或部门`)
      return []
    }
    grants.push({
      featureCode: f.feature_code,
      dataScope: hasScopeVariants(f) ? g.dataScope : null,
      employeeIds: g.dataScope === 'CUSTOM' ? g.employeeIds : [],
      departmentNames: g.dataScope === 'CUSTOM' ? g.departmentNames : [],
      actions: g.actions,
    })
  }
  return grants
}

async function save() {
  if (!form.value.roleName.trim()) {
    ElMessage.warning('请填写角色名称')
    return
  }
  if (editingId.value === null && !form.value.roleCode.trim()) {
    ElMessage.warning('请填写角色编码')
    return
  }
  const grants = collectGrants()
  if (grants === null) return
  saving.value = true
  try {
    const payload: RolePayload = {
      roleCode: editingId.value === null ? form.value.roleCode : undefined,
      roleName: form.value.roleName.trim(),
      grants,
    }
    if (editingId.value === null) {
      await createRole(payload)
      ElMessage.success('角色已创建')
    } else {
      await updateRole(editingId.value, payload)
      ElMessage.success('已保存，成员刷新页面后权限生效')
    }
    drawerVisible.value = false
    await loadRoles()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(role: SystemRole) {
  if (role.built_in) {
    ElMessage.warning('内置角色不可删除')
    return
  }
  const count = role.memberCount ?? 0
  try {
    await ElMessageBox.confirm(
      `确定删除角色「${role.role_name}」？${count > 0 ? `该角色当前有 ${count} 名成员，删除后他们将失去该角色权限。` : ''}`,
      '删除角色',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteRole(role.id)
    ElMessage.success('角色已删除')
    await loadRoles()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '删除失败')
  }
}

// ==================== 初始化 ====================
async function initOptions() {
  if (employeeOptions.value.length === 0) {
    await loadEmployeeOptions()
  }
  if (departmentOptions.value.length === 0) {
    try {
      departmentOptions.value = await fetchDepartments()
    } catch {
      departmentOptions.value = []
    }
  }
}

await Promise.all([loadRoles(), fetchSystemFeatures().then((list) => (features.value = list)), fetchSystemPermissions().then((list) => (permissions.value = list))])
</script>

<template>
  <div class="role-permission-view">
    <div class="page-card">
      <div class="page-card__header">
        <div>
          <h2 class="page-card__title">角色与权限</h2>
          <p class="page-card__desc">
            以「业务功能」为单位给角色授权：每项功能可分别设置<b>数据范围</b>（全部 / 本部门 / 仅本人 / 自定义员工部门）与<b>操作权限</b>（查看 / 新增 / 编辑 / 删除 / 其他操作）。
            权限变更保存后，成员刷新页面即可生效。
          </p>
        </div>
        <div>
          <el-button @click="permManagerVisible = true">权限码管理</el-button>
          <el-button type="primary" @click="openCreate">新建角色</el-button>
        </div>
      </div>

      <el-table
v-loading="loading"
:data="roles"
border
stripe>
        <el-table-column prop="role_code" label="角色编码" width="180">
          <template #default="{ row }">
            <el-tag
v-if="row.built_in"
type="warning"
size="small"
effect="plain">内置</el-tag>
            <span class="role-code">{{ row.role_code }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="role_name" label="角色名称" min-width="140" />
        <el-table-column label="授权功能" width="110">
          <template #default="{ row }">{{ row.featureGrants?.length ?? 0 }} 项</template>
        </el-table-column>
        <el-table-column prop="memberCount" label="成员数" width="90" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑授权</el-button>
            <el-button
v-if="!row.built_in"
link
type="danger"
@click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 角色编辑抽屉 -->
    <el-drawer
v-model="drawerVisible"
:title="editingId === null ? '新建角色' : `编辑角色 · ${form.roleName}`"
size="920px"
destroy-on-close>
      <div class="drawer-body">
        <el-form label-width="80px" label-position="left">
          <el-row :gutter="12">
            <el-col :span="8">
              <el-form-item label="角色编码">
                <el-input v-model="form.roleCode" :disabled="editingId !== null" placeholder="如 CUSTOM_SERVICE" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="角色名称" required>
                <el-input v-model="form.roleName" placeholder="如 生产跟单员" maxlength="100" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="使用提示">
                <span class="hint-text">勾选功能后：数据范围决定能看哪些，操作决定能做什么</span>
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>

        <el-collapse>
          <el-collapse-item
v-for="group in groupedFeatures"
:key="group.category"
:title="`${group.label}（${group.items.length} 项功能）`"
:name="group.category">
            <div class="feature-list">
              <div
v-for="feature in group.items"
:key="feature.feature_code"
class="feature-row"
:class="{ 'feature-row--off': !grantMap[feature.feature_code]?.enabled }">
                <div class="feature-row__head">
                  <el-checkbox
                    :model-value="grantMap[feature.feature_code]?.enabled ?? false"
                    @change="(v: string | number | boolean) => toggleEnabled(feature, Boolean(v))"
                  >
                    <b>{{ feature.feature_name }}</b>
                    <span class="permission-code">{{ feature.feature_code }}</span>
                  </el-checkbox>
                  <div class="feature-row__scope">
                    <template v-if="grantMap[feature.feature_code]?.enabled && hasScopeVariants(feature)">
                      <span class="scope-label">数据范围</span>
                      <el-select
                        v-model="grantMap[feature.feature_code].dataScope"
                        size="small"
                        style="width: 150px"
                        :disabled="!grantMap[feature.feature_code].actions.includes('VIEW')"
                      >
                        <el-option
v-for="opt in scopeOptions(feature)"
:key="opt.value as string"
:value="opt.value"
:label="opt.label" />
                      </el-select>
                      <el-tooltip v-if="!grantMap[feature.feature_code].actions.includes('VIEW')" content="勾选下方「查看」后可选数据范围" placement="top">
                        <el-tag type="info" size="small" effect="plain">先勾查看</el-tag>
                      </el-tooltip>
                    </template>
                    <span v-else-if="grantMap[feature.feature_code]?.enabled && !hasScopeVariants(feature)" class="scope-flat">无数据范围限制</span>
                  </div>
                </div>

                <!-- 自定义名单 -->
                <div v-if="grantMap[feature.feature_code]?.enabled && grantMap[feature.feature_code].dataScope === 'CUSTOM'" class="feature-row__custom">
                  <div class="custom-picker">
                    <span class="custom-picker__label">可看员工</span>
                    <el-select
                      v-model="grantMap[feature.feature_code].employeeIds"
                      multiple
                      filterable
                      collapse-tags
                      collapse-tags-tooltip
                      size="small"
                      placeholder="选择员工"
                      style="width: 300px"
                      @click.stop
                      @visible-change="initOptions"
                    >
                      <el-option
                        v-for="emp in employeeOptions"
                        :key="emp.id"
                        :value="emp.id"
                        :label="emp.name + (emp.department ? ' · ' + emp.department : '')"
                      />
                    </el-select>
                    <span class="custom-picker__label">可看部门</span>
                    <el-select
                      v-model="grantMap[feature.feature_code].departmentNames"
                      multiple
                      filterable
                      collapse-tags
                      collapse-tags-tooltip
                      size="small"
                      placeholder="选择部门"
                      style="width: 240px"
                      @click.stop
                      @visible-change="initOptions"
                    >
                      <el-option
v-for="dep in departmentOptions"
:key="dep.name"
:value="dep.name"
:label="dep.name" />
                    </el-select>
                  </div>
                </div>

                <!-- 操作动作 -->
                <div v-if="grantMap[feature.feature_code]?.enabled" class="feature-row__actions">
                  <el-checkbox
                    v-if="hasViewAction(feature)"
                    :model-value="grantMap[feature.feature_code].actions.includes('VIEW')"
                    @change="(v: string | number | boolean) => {
                      const g = grantMap[feature.feature_code]
                      g.actions = Boolean(v) ? [...new Set([...g.actions, 'VIEW'])] : g.actions.filter((c) => c !== 'VIEW')
                    }"
                  >
                    <el-tag type="success" size="small" effect="light">查看</el-tag>
                    <span class="action-name">查看数据</span>
                  </el-checkbox>
                  <el-checkbox
                    v-for="act in actionButtons(feature)"
                    :key="act.permission_code"
                    :model-value="isActionChecked(feature.feature_code, act.permission_code)"
                    @change="toggleAction(feature, act)"
                  >
                    <el-tag :type="KIND_LABEL[act.action_kind]?.tag ?? 'info'" size="small" effect="light">
                      {{ KIND_LABEL[act.action_kind]?.label ?? '操作' }}
                    </el-tag>
                    <span class="action-name">{{ act.permission_name }}</span>
                  </el-checkbox>
                </div>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>

        <el-divider content-position="left">角色成员（{{ members.length }}）</el-divider>
        <div v-loading="memberLoading" class="member-group">
          <div class="member-group__header">
            <span>成员将继承该角色的数据范围与操作权限</span>
            <el-button
v-if="editingId !== null"
link
type="primary"
@click="openAddMember">添加成员</el-button>
          </div>
          <el-empty v-if="!memberLoading && members.length === 0" description="暂无成员" :image-size="50" />
          <div v-for="member in members" :key="member.id" class="member-row">
            <div class="member-row__info">
              <span class="member-row__name">{{ member.name }}</span>
              <span class="member-row__meta">{{ [member.employee_no, member.department].filter(Boolean).join(' · ') || '—' }}</span>
            </div>
            <el-button link type="danger" @click="removeMember(member)">移出</el-button>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button
v-if="editingId !== null && !editingIsBuiltIn"
type="danger"
plain
:disabled="saving"
@click="remove(roles.find((r) => r.id === editingId)!)">
            删除角色
          </el-button>
          <div class="drawer-footer__right">
            <el-button @click="drawerVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 添加成员弹窗 -->
    <el-dialog
v-model="memberDialogVisible"
title="添加成员"
width="520px"
destroy-on-close>
      <el-select
v-model="selectedMemberIds"
multiple
filterable
placeholder="搜索并选择员工（仅显示在职）"
style="width: 100%">
        <el-option
          v-for="emp in availableEmployees"
          :key="emp.id"
          :value="emp.id"
          :label="emp.name + (emp.department ? ' · ' + emp.department : '')"
        />
        <el-empty v-if="availableEmployees.length === 0" description="所有在职员工均已在该角色中" :image-size="50" />
      </el-select>
      <template #footer>
        <el-button @click="memberDialogVisible = false">取消</el-button>
        <el-button
type="primary"
:loading="memberSaving"
:disabled="selectedMemberIds.length === 0"
@click="addMembers">
          添加（{{ selectedMemberIds.length }}）
        </el-button>
      </template>
    </el-dialog>

    <!-- 权限码启停用管理 -->
    <el-dialog
v-model="permManagerVisible"
title="权限码管理"
width="660px"
destroy-on-close>
      <p class="perm-manager__desc">
        权限码是「功能授权」背后的执行单元，由系统注册并随功能自动展开，正常情况下无需手动调整。此处仅用于紧急下线某个权限码：停用后该权限立即从所有用户权限中失效，启用即恢复。
      </p>
      <el-table
:data="permissions"
border
size="small"
max-height="440">
        <el-table-column prop="permission_code" label="权限码" width="200">
          <template #default="{ row }">
            <span class="permission-code">{{ row.permission_code }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="permission_name" label="权限名称" min-width="180" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              :loading="permSavingId === row.id"
              inline-prompt
              active-text="启用"
              inactive-text="停用"
              @change="(val: string | number | boolean) => togglePermission(row, Boolean(val))"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.role-permission-view {
  padding: 20px;
}

.page-card {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: var(--radius-lg, 12px);
  padding: 20px;
}

.page-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-card__title {
  font-size: 18px;
  font-weight: 600;
  margin: 0;
}

.page-card__desc {
  font-size: 13px;
  color: var(--color-text-secondary, #6b7280);
  margin: 6px 0 0;
  line-height: 1.6;
}

.role-code {
  margin-left: 6px;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.hint-text {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.drawer-body {
  max-height: calc(100vh - 130px);
  overflow-y: auto;
  padding-right: 8px;
}

.feature-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.feature-row {
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 8px;
  padding: 8px 12px;
  transition: opacity 0.15s;
}

.feature-row--off {
  opacity: 0.6;
}

.feature-row__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.feature-row__head :deep(.el-checkbox) {
  white-space: normal;
}

.feature-row__scope {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.scope-label {
  font-size: 12px;
  color: var(--color-text-secondary, #6b7280);
}

.scope-flat {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.feature-row__custom {
  margin-top: 8px;
  padding: 8px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.custom-picker {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.custom-picker__label {
  font-size: 12px;
  color: var(--color-text-secondary, #6b7280);
  flex-shrink: 0;
}

.feature-row__actions {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
}

.feature-row__actions :deep(.el-checkbox) {
  margin-right: 0;
  white-space: normal;
  height: auto;
}

.action-name {
  margin-left: 4px;
  font-size: 13px;
}

.member-group {
  max-height: 240px;
  overflow-y: auto;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 8px;
  padding: 8px;
  min-height: 60px;
}

.member-group__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--color-text-secondary, #6b7280);
  padding: 2px 2px 8px;
  border-bottom: 1px dashed var(--color-border, #e5e7eb);
  margin-bottom: 8px;
}

.member-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 2px;
  border-bottom: 1px solid var(--color-border-light, #f3f4f6);
}

.member-row:last-child {
  border-bottom: none;
}

.member-row__info {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.member-row__name {
  font-weight: 500;
  flex-shrink: 0;
}

.member-row__meta {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.perm-manager__desc {
  font-size: 12px;
  color: var(--color-text-secondary, #6b7280);
  margin: 0 0 12px;
  line-height: 1.6;
}

.drawer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.drawer-footer__right {
  display: flex;
  gap: 8px;
}
</style>
