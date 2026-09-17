<template>
  <!-- 步骤一：上传文件 -->
  <el-dialog
    v-if="stage === 'upload'"
    :model-value="modelValue"
    title="导入外部身份（氚云 / 钉钉通讯录）"
    width="min(680px, 94vw)"
    align-center
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @open="handleOpen"
  >
    <div class="import-panel">
      <div class="import-upload-grid">
        <div class="import-upload-box">
          <div class="import-upload-box__label">
            氚云组织结构
            <span class="dim">（.xlsx，含「成员ID」列）</span>
          </div>
          <el-upload
            drag
            :auto-upload="false"
            :limit="1"
            accept=".xlsx"
            :on-change="(f: any) => (hyFile = f.raw)"
            :on-remove="() => (hyFile = null)"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽到此处，或 <em>点击选择</em></div>
          </el-upload>
        </div>
        <div class="import-upload-box">
          <div class="import-upload-box__label">
            内部员工名单
            <span class="dim">（.xlsx，姓名 / 手机号码 / 工作部门）</span>
          </div>
          <el-upload drag :auto-upload="false" :limit="1" accept=".xlsx" :on-change="(f: any) => (rosterFile = f.raw)" :on-remove="() => (rosterFile = null)">
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽到此处，或 <em>点击选择</em></div>
          </el-upload>
        </div>
        <div class="import-upload-box">
          <div class="import-upload-box__label">
            钉钉在职员工
            <span class="dim">（.xls / .xlsx，含「员工UserID」列）</span>
          </div>
          <el-upload
            drag
            :auto-upload="false"
            :limit="1"
            accept=".xls,.xlsx"
            :on-change="(f: any) => (ddFile = f.raw)"
            :on-remove="() => (ddFile = null)"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽到此处，或 <em>点击选择</em></div>
          </el-upload>
        </div>
      </div>
      <div class="import-actions">
        <span v-if="parseError" class="import-error">{{ parseError }}</span>
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button
          type="primary"
          :loading="parsing"
          :disabled="!hyFile && !ddFile && !rosterFile"
          @click="handleParse"
        >
          解析并预览匹配
        </el-button>
      </div>
    </div>
  </el-dialog>

  <!-- 步骤二：预览匹配 -->
  <el-dialog
    v-if="stage === 'preview'"
    :model-value="modelValue"
    title="确认绑定关系"
    width="min(92vw, 1280px)"
    align-center
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="import-summary">
      <el-tag>共 {{ rows.length }} 个身份</el-tag>
      <el-tag type="info">氚云 {{ stats.hyCount }}</el-tag>
      <el-tag type="warning">钉钉 {{ stats.ddCount }}</el-tag>
      <el-tag type="info">内部名单 {{ stats.rosterCount }}</el-tag>
      <el-tag type="success">已映射 {{ stats.mapped }}</el-tag>
      <el-tag type="danger">未匹配 {{ stats.unmatched }}</el-tag>
      <span class="dim import-summary__tip">
        未匹配身份可勾选「新建」自动创建员工档案，或在「绑定员工」中选择现有员工。
      </span>
    </div>

    <el-table
      :data="rows"
      max-height="calc(100vh - 280px)"
      size="small"
      border
    >
      <el-table-column label="来源" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.source === 'H3YUN' ? 'primary' : row.source === 'DINGTALK' ? 'warning' : 'info'" size="small" effect="plain">
            {{ row.source === 'H3YUN' ? '氚云' : row.source === 'DINGTALK' ? '钉钉' : '内部名单' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="name"
        label="姓名"
        width="100"
        fixed="left"
      />
      <el-table-column
        prop="externalUserId"
        label="外部ID"
        width="180"
        show-overflow-tooltip
      />
      <el-table-column
        prop="companyName"
        label="识别公司"
        width="170"
        show-overflow-tooltip
      >
        <template #default="{ row }">{{ row.companyName || '待确认' }}</template>
      </el-table-column>
      <el-table-column
        prop="department"
        label="工作部门"
        width="150"
        show-overflow-tooltip
      >
        <template #default="{ row }">{{ row.department || '未提供' }}</template>
      </el-table-column>
      <el-table-column prop="externalDepartment" label="来源组织路径" width="210" show-overflow-tooltip />
      <el-table-column prop="phone" label="手机号" width="120" />
      <el-table-column label="匹配" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="confidenceType(row.confidence)" size="small" effect="light">
            {{ confidenceLabel(row.confidence) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="绑定本地员工" width="240">
        <template #default="{ row }">
          <el-select
            v-model="row.bindEmployeeId"
            filterable
            remote
            clearable
            placeholder="选择员工"
            :remote-method="(q: string) => searchEmployees(q)"
            :loading="searching"
            size="small"
            style="width: 100%"
          >
            <el-option
              v-for="e in employeeOptions"
              :key="e.id"
              :label="e.name + (e.department ? ' · ' + e.department : '') + (e.employeeNo ? ' · ' + e.employeeNo : '')"
              :value="e.id"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="未匹配新建" width="110" align="center">
        <template #default="{ row }">
          <el-checkbox
            v-model="row.createIfMissing"
            :disabled="row.bindEmployeeId != null"
          />
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <div class="import-footer">
        <span v-if="applying" class="dim">正在写映射并回填历史数据…</span>
        <el-button :disabled="applying" @click="stage = 'upload'">上一步</el-button>
        <el-button :disabled="applying" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button
          type="primary"
          :loading="applying"
          :disabled="!applyCount"
          @click="handleApply"
        >
          确认导入（{{ applyCount }}）
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { fetchEmployees, parseExternalIdentities, applyExternalIdentities } from '@/api/employees/employees'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'imported'): void
}>()

// 每次关闭后整体复位：否则再次打开会停留在上一次的「确认绑定关系」预览并残留旧数据
watch(
  () => props.modelValue,
  (v) => {
    if (!v) handleOpen()
  },
)

const stage = ref<'upload' | 'preview'>('upload')

const hyFile = ref<File | null>(null)
const ddFile = ref<File | null>(null)
const rosterFile = ref<File | null>(null)
const parsing = ref(false)
const applying = ref(false)
const searching = ref(false)
const parseError = ref('')

interface Row {
  source: string
  externalUserId: string
  name: string
  phone: string
  companyName: string
  externalDepartment: string
  department: string
  position: string
  externalRoles: string
  status: string
  suggestedEmployeeId: number | null
  suggestedEmployeeName: string | null
  confidence: string
  hasMapping: boolean
  bindEmployeeId: number | null
  createIfMissing: boolean
}

const rows = ref<Row[]>([])
const employeeOptions = ref<Array<Record<string, any>>>([])
const stats = ref({ hyCount: 0, ddCount: 0, rosterCount: 0, mapped: 0, unmatched: 0 })

const applyCount = computed(
  () => rows.value.filter((r) => r.bindEmployeeId != null || r.createIfMissing).length,
)

function handleOpen() {
  stage.value = 'upload'
  hyFile.value = null
  ddFile.value = null
  rosterFile.value = null
  rows.value = []
  parseError.value = ''
  employeeOptions.value = []
  stats.value = { hyCount: 0, ddCount: 0, rosterCount: 0, mapped: 0, unmatched: 0 }
}

async function handleParse() {
  if (!hyFile.value && !ddFile.value && !rosterFile.value) return
  parseError.value = ''
  parsing.value = true
  try {
    const preview = await parseExternalIdentities(hyFile.value || undefined, ddFile.value || undefined, rosterFile.value || undefined)
    rows.value = preview.items.map((it: any) => ({
      ...it,
      bindEmployeeId: it.suggestedEmployeeId,
      createIfMissing: false,
    }))
    rows.value.forEach((r) => pushEmployeeOption(r.suggestedEmployeeId, r.suggestedEmployeeName))
    stats.value = {
      hyCount: preview.hyCount,
      ddCount: preview.ddCount,
      rosterCount: preview.rosterCount,
      mapped: preview.items.filter((i: any) => i.hasMapping).length,
      unmatched: preview.items.filter((i: any) =>
        ['AMBIGUOUS', 'AMBIGUOUS_PHONE', 'PHONE_NAME_CONFLICT', 'UNMATCHED', 'NO_NAME'].includes(i.confidence),
      ).length,
    }
    stage.value = 'preview'
    ElMessage.success(`解析成功：氚云 ${preview.hyCount} 人、钉钉 ${preview.ddCount} 人、内部名单 ${preview.rosterCount} 人`)
  } catch (e: any) {
    parseError.value = e?.response?.data?.message || e?.message || '解析失败'
  } finally {
    parsing.value = false
  }
}

function pushEmployeeOption(id: number | null, name: string | null) {
  if (id != null && name && !employeeOptions.value.some((e) => e.id === id)) {
    employeeOptions.value.push({ id, name, department: '', employeeNo: '' })
  }
}

async function searchEmployees(keyword: string) {
  searching.value = true
  try {
    const page = await fetchEmployees({ keyword, page: 0, size: 20 })
    employeeOptions.value = page.content || []
  } catch {
    /* ignore */
  } finally {
    searching.value = false
  }
}

async function handleApply() {
  applying.value = true
  try {
    const items = rows.value
      .filter((r) => r.bindEmployeeId != null || r.createIfMissing)
      .map((r) => ({
        source: r.source,
        externalUserId: r.externalUserId,
        name: r.name,
        phone: r.phone,
        companyName: r.companyName,
        externalDepartment: r.externalDepartment,
        department: r.department,
        position: r.position,
        externalRoles: r.externalRoles,
        status: r.status,
        employeeId: r.bindEmployeeId,
        createIfMissing: r.createIfMissing,
      }))
    const result = await applyExternalIdentities(items)
    ElMessage.success(`导入完成：映射 ${result.mappedCount} 条，新建员工 ${result.createdEmployeeCount} 个，跳过 ${result.skippedCount} 条；回填未归属订单 ${result.backfilledOutboundCount ?? 0} 张`)
    emit('imported')
    emit('update:modelValue', false)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '导入失败')
  } finally {
    applying.value = false
  }
}

function confidenceType(c: string): 'success' | 'warning' | 'danger' | 'info' {
  if (c === 'MAPPED' || c === 'HIGH' || c === 'HIGH_PHONE') return 'success'
  if (c === 'MEDIUM' || c === 'AMBIGUOUS' || c === 'AMBIGUOUS_PHONE') return 'warning'
  if (c === 'PHONE_NAME_CONFLICT') return 'danger'
  if (c === 'UNMATCHED') return 'danger'
  return 'info'
}

function confidenceLabel(c: string): string {
  const map: Record<string, string> = {
    MAPPED: '已映射',
    HIGH: '高置信',
    HIGH_PHONE: '手机辅助',
    MEDIUM: '中置信',
    AMBIGUOUS: '重名需确认',
    AMBIGUOUS_PHONE: '重名需确认',
    PHONE_NAME_CONFLICT: '手机号姓名冲突',
    UNMATCHED: '未匹配',
    NO_NAME: '缺姓名',
  }
  return map[c] || c
}
</script>

<style scoped>
.import-panel {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--radius-md, 8px);
  padding: 14px;
}
.import-upload-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.import-upload-box__label {
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.import-actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}
.dim {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.import-error {
  color: var(--el-color-danger);
  font-size: 13px;
  margin-right: auto;
}
.import-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.import-summary__tip {
  margin-left: 6px;
}
.import-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}
</style>
