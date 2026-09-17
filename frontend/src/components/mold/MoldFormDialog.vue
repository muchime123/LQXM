<template>
  <LqDialog
    v-model:model-value="visible"
    :title="isEdit ? '编辑模具' : '新建模具'"
    :subtitle="isEdit ? '维护模具编号、出模数、刻字版本等。' : '录入新的模具资产台账。'"
    size="lg"
    mode="form"
    :dirty="dirty"
    :loading="saving"
    :before-close="handleBeforeClose"
    confirm-text="保存"
    cancel-text="取消"
    @confirm="handleSave"
    @cancel="visible = false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" label-position="right" class="mold-form">
      <FormSection title="归属与编号" :required-count="2"
        description="模号（如 M20910-3F）全局唯一；先选归属产品，再选该系列下的规格族（可选快速新建）。">
        <el-form-item label="归属产品" prop="modelId">
          <el-select v-model="form.modelId" placeholder="选择包材系列" filterable
            :disabled="!!form.groupId"
            @change="onModelChanged">
            <el-option v-for="m in productModels" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
          <div v-if="form.groupId" class="model-locked-hint">
            <el-icon><InfoFilled /></el-icon>
            <span>已由规格族锁定，修改请先清除规格族</span>
          </div>
        </el-form-item>
        <el-form-item label="规格族">
          <div class="group-select-row">
            <el-select v-model="form.groupId" placeholder="先选归属产品后加载规格族"
              filterable clearable :disabled="!form.modelId"
              :loading="loadingGroups" style="flex: 1 1 0;"
              @change="onGroupChanged">
              <el-option v-for="g in moldGroups" :key="g.id" :label="`${g.name}${g.moldCount ? ` · ${g.moldCount}模` : ''}`" :value="g.id" />
            </el-select>
            <el-button type="primary" plain :disabled="!form.modelId" @click="openQuickCreateGroup">
              <el-icon style="margin-right: 4px;"><Plus /></el-icon>新建规格族
            </el-button>
          </div>
          <div v-if="form.groupId" class="field-hints">
            模具将归入该规格族（从 Excel 产品名标准化而来）；未选则模具仍挂在系列下，在包材详情中会落在分组之外。
          </div>
        </el-form-item>
        <el-form-item label="模号" prop="moldCode">
          <el-input v-model="form.moldCode" placeholder="M20910-3F / P23128-6W / 纯数字 1812" @blur="parseMoldCode" />
          <div v-if="form.moldCode" class="field-hints">
            <span v-if="form.baseMoldCode">基础号：<strong>{{ form.baseMoldCode }}</strong></span>
            <span v-if="form.cavityCount">，一出几：<strong>{{ form.cavityCount }}</strong></span>
            <span v-if="form.moldTypeCode">，盒型：<strong>{{ form.moldTypeCode }}</strong></span>
            <el-tag v-if="isNumericOnly" size="small" type="warning" effect="light">纯数字待确认</el-tag>
          </div>
        </el-form-item>
        <el-form-item label="基础号">
          <el-input v-model="form.baseMoldCode" placeholder="如 M20910（默认从模号解析，可手动修正）" maxlength="40" />
          <div class="field-hints">同基础号的多套模具视为同一规格族的不同出模数版本。</div>
        </el-form-item>
      </FormSection>

      <FormSection title="模具特性" :required-count="0"
        description="模具角色、出模数、格数、表面结构等业务属性。">
        <div class="form-row">
          <el-form-item label="模具角色" class="grow">
            <el-select v-model="form.moldRole" placeholder="主模 / 配件模">
              <el-option label="主模" value="main" />
              <el-option label="配件模" value="accessory" />
              <el-option label="未知" value="unknown" />
            </el-select>
          </el-form-item>
          <el-form-item label="业务分类" class="grow">
            <el-select v-model="form.moldCategory" placeholder="方盒 / 圆杯 / 碗 / 桶 / 盖" allow-create filterable default-first-option>
              <el-option v-for="c in CATEGORY_OPTIONS" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="一出几" class="grow">
            <el-input-number v-model="form.cavityCount" :min="0" controls-position="right" placeholder="从 -3F 解析" />
          </el-form-item>
          <el-form-item label="盒型代码" class="grow">
            <el-input v-model="form.moldTypeCode" placeholder="F / B / W / G / WJ / FB" maxlength="4" />
          </el-form-item>
          <el-form-item label="格数" class="grow">
            <el-select v-model="form.compartmentCount" placeholder="单格 / 双格 / 数字" allow-create filterable default-first-option>
              <el-option label="单格" value="单格" />
              <el-option label="双格" value="双格" />
              <el-option label="三格" value="三格" />
              <el-option label="四格" value="四格" />
            </el-select>
          </el-form-item>
          <el-form-item label="表面结构" class="grow">
            <el-select v-model="form.surfaceStructure" placeholder="光面 / 圆筋 / 方筋 / 瓦楞 / 腐蚀" allow-create filterable default-first-option>
              <el-option v-for="s in STRUCTURE_OPTIONS" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="口径" class="grow">
            <el-input v-model="form.rawCaliber" placeholder="如 Ø95mm / 1414×10 / 顶部Ø90" maxlength="80" />
            <div class="field-hints">模具原始口径，未填时详情页将从关联规格推断显示。</div>
          </el-form-item>
          <el-form-item label="高度" class="grow">
            <el-input v-model="form.rawHeight" placeholder="如 25+35+45+55 / 50 / 80mm" maxlength="200" />
            <div class="field-hints">支持多个高度用 + 分隔，对应同一模具的不同高度档位。</div>
          </el-form-item>
        </div>
      </FormSection>

      <FormSection title="刻字与配件" :required-count="0"
        description="杯底刻字内容、版本分类；配件按 + 号拆分后结构化管理。">
        <el-form-item label="杯底刻字" prop="bottomEngraving">
          <el-input v-model="form.bottomEngraving" type="textarea" :rows="2"
            placeholder="例：通用 / 客户品牌 / 循环标+" maxlength="300" show-word-limit @blur="classifyEngraving" />
          <div class="field-hints">
            建议分类：
            <el-tag size="small" :type="engravingTypeTagType">
              {{ ENGRAVING_LABELS[form.engravingType as keyof typeof ENGRAVING_LABELS] || '未分类' }}
            </el-tag>
          </div>
        </el-form-item>
        <el-form-item label="刻字分类">
          <el-select v-model="form.engravingType" placeholder="自动或手动选择">
            <el-option v-for="e in Object.entries(ENGRAVING_LABELS)" :key="e[0]" :label="e[1]" :value="e[0]" />
          </el-select>
        </el-form-item>
        <el-form-item label="配件原文">
          <div class="raw-accessories-row">
            <el-input v-model="form.rawAccessoriesText" placeholder="杯胆+底+拉伸头 / 按 + 号拆分" @blur="syncAccessoriesFromRaw(false)" />
            <el-button type="primary" plain size="default" :disabled="!form.rawAccessoriesText?.trim()"
              @click="syncAccessoriesFromRaw(true)">
              从原文解析
            </el-button>
          </div>
          <div class="field-hints">
            修改原文后失焦自动同步到下方；如之前漏拆分可点右侧「从原文解析」强制覆盖（将按 + 号拆分为配件卡片）。
          </div>
        </el-form-item>

        <!-- 结构化配件卡片列表 -->
        <div class="accessories-section">
          <div class="accessories-header">
            <div class="accessories-title">
              配件列表
              <span class="accessories-count" v-if="accessories.length">
                {{ accessories.length }} 项
              </span>
            </div>
            <el-button size="small" type="primary" plain @click="openAddAccessory">
              <el-icon style="margin-right: 4px;"><Plus /></el-icon>新增配件
            </el-button>
          </div>

          <div v-if="accessories.length === 0" class="accessories-empty">
            <el-icon style="margin-right: 6px;"><InfoFilled /></el-icon>
            <span>暂无配件记录，点击「新增配件」录入杯胆/杯底/拉伸头等</span>
          </div>

          <div v-else class="accessories-list">
            <div v-for="a in accessories" :key="a.id" class="accessory-card">
              <div class="accessory-card-main">
                <div class="accessory-type-row">
                  <el-tag size="small" :type="accTypeTag(a.accessoryType)" effect="light">
                    {{ accTypeLabel(a.accessoryType) }}
                  </el-tag>
                  <el-tag v-if="a.accessoryCode" size="small" type="info" effect="plain" class="accessory-code-tag">
                    {{ a.accessoryCode }}
                  </el-tag>
                </div>
                <div class="accessory-meta-row">
                  <span class="meta-chip">数量 × {{ a.quantity }}</span>
                  <span class="meta-chip" :class="'scope-' + (a.sharedScope || 'dedicated')">
                    {{ scopeLabel(a.sharedScope) }}
                  </span>
                </div>
                <p v-if="a.remark" class="accessory-remark">{{ a.remark }}</p>
              </div>
              <div class="accessory-card-actions">
                <el-button link type="primary" size="small" @click="openEditAccessory(a)">编辑</el-button>
                <el-popconfirm title="删除该配件？" @confirm="removeAccessory(a.id)">
                  <template #reference>
                    <el-button link type="danger" size="small">删除</el-button>
                  </template>
                </el-popconfirm>
              </div>
            </div>
          </div>
        </div>
      </FormSection>

      <FormSection title="状态与备注" :required-count="0">
        <div class="form-row">
          <el-form-item label="状态" class="grow">
            <el-select v-model="form.status">
              <el-option label="在用" value="in_use" />
              <el-option label="停用" value="stopped" />
              <el-option label="维修中" value="repair" />
              <el-option label="待核实" value="unverified" />
            </el-select>
          </el-form-item>
          <el-form-item label="ID" class="grow">
            <span v-if="isEdit" class="field-hints">ID #{{ form.id }}，不可修改</span>
            <span v-else class="field-hints">保存后系统分配</span>
          </el-form-item>
        </div>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="1000" show-word-limit />
        </el-form-item>
      </FormSection>

      <FormSection title="官网发布" :required-count="0">
        <div class="form-row">
          <el-form-item label="发布到官网" class="grow">
            <el-switch v-model="form.websiteEnabled" active-text="已发布" inactive-text="未发布" />
          </el-form-item>
          <el-form-item label="官网排序" class="grow">
            <el-input-number v-model="form.websiteSortOrder" :min="0" :max="9999" controls-position="right" />
            <span class="field-hints">数字越小越靠前</span>
          </el-form-item>
        </div>
      </FormSection>
    </el-form>

    <!-- 配件新增/编辑弹窗 -->
    <LqDialog
      v-model:model-value="showAccessoryDialog"
      :title="editingAccessoryId ? '编辑配件' : '新增配件'"
      size="sm"
      mode="form"
      :loading="accessorySaving"
      confirm-text="保存"
      cancel-text="取消"
      @confirm="saveAccessory"
      @cancel="closeAccessoryDialog"
    >
      <el-form :model="accessoryForm" label-width="90px">
        <el-form-item label="配件类型">
          <el-select v-model="accessoryForm.accessoryType" filterable>
            <el-option label="杯胆" value="cup_liner" />
            <el-option label="杯底" value="cup_bottom" />
            <el-option label="拉伸头" value="drawing_head" />
            <el-option label="压料环" value="press_ring" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="配件编号">
          <el-input v-model="accessoryForm.accessoryCode" placeholder="如 B-2216（可选）" />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="accessoryForm.quantity" :min="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="共用范围">
          <el-select v-model="accessoryForm.sharedScope">
            <el-option label="专用" value="dedicated" />
            <el-option label="系列通用" value="series_shared" />
            <el-option label="全局通用" value="global_shared" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="accessoryForm.remark" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
    </LqDialog>
  </LqDialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import {
  fetchPackagingModels,
  fetchPackagingMold,
  createPackagingMold,
  updatePackagingMold,
  fetchMoldGroups,
  createMoldGroup,
  createMoldAccessory,
  updateMoldAccessory,
  deleteMoldAccessory,
  type MoldPayload,
  type MoldGroupPayload,
  type MoldAccessoryView,
  type MoldAccessoryPayload
} from '@/api'
import { askConfirm } from '@/composables'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, InfoFilled } from '@element-plus/icons-vue'
import LqDialog from '@/components/common/LqDialog.vue'
import FormSection from '@/components/common/FormSection.vue'

const props = defineProps<{
  visible: boolean
  moldId?: number | null
  modelId?: number | null
}>()
const emit = defineEmits<{
  'update:visible': [v: boolean]
  saved: []
}>()

const visible = ref(props.visible)
watch(() => props.visible, v => (visible.value = v))
watch(visible, v => emit('update:visible', v))

const formRef = ref<FormInstance>()
const saving = ref(false)
const dirty = ref(false)
const loadingGroups = ref(false)
const productModels = ref<{ id: number; name: string }[]>([])
const moldGroups = ref<{ id: number; modelId: number; name: string; moldCount: number }[]>([])

// ====== 配件管理 ======
const accessories = ref<MoldAccessoryView[]>([])
const showAccessoryDialog = ref(false)
const editingAccessoryId = ref<number | null>(null)
const accessorySaving = ref(false)
const accessoryForm = reactive<{
  accessoryType: string
  accessoryCode: string
  quantity: number
  sharedScope: string
  remark: string
}>({
  accessoryType: 'cup_liner',
  accessoryCode: '',
  quantity: 1,
  sharedScope: 'dedicated',
  remark: ''
})

const ACC_TYPE_LABELS: Record<string, string> = {
  cup_liner: '杯胆',
  cup_bottom: '杯底',
  drawing_head: '拉伸头',
  press_ring: '压料环',
  other: '其他'
}
const ACC_TYPE_TAGS: Record<string, string> = {
  cup_liner: 'primary',
  cup_bottom: 'success',
  drawing_head: 'warning',
  press_ring: 'info',
  other: 'info'
}
const SCOPE_LABELS: Record<string, string> = {
  dedicated: '专用',
  series_shared: '系列通用',
  global_shared: '全局通用'
}

function accTypeLabel(t?: string) { return ACC_TYPE_LABELS[t || ''] || t || '其他' }
function accTypeTag(t?: string): string { return (ACC_TYPE_TAGS[t || ''] || 'info') as string }
function scopeLabel(s?: string) { return SCOPE_LABELS[s || ''] || s || '专用' }

function resetAccessoryForm() {
  accessoryForm.accessoryType = 'cup_liner'
  accessoryForm.accessoryCode = ''
  accessoryForm.quantity = 1
  accessoryForm.sharedScope = 'dedicated'
  accessoryForm.remark = ''
  editingAccessoryId.value = null
}

function openAddAccessory() {
  resetAccessoryForm()
  showAccessoryDialog.value = true
}

function openEditAccessory(a: MoldAccessoryView) {
  editingAccessoryId.value = a.id
  accessoryForm.accessoryType = a.accessoryType
  accessoryForm.accessoryCode = a.accessoryCode || ''
  accessoryForm.quantity = a.quantity
  accessoryForm.sharedScope = a.sharedScope
  accessoryForm.remark = a.remark || ''
  showAccessoryDialog.value = true
}

function closeAccessoryDialog() {
  showAccessoryDialog.value = false
  resetAccessoryForm()
}

async function saveAccessory() {
  if (!props.moldId) {
    ElMessage.warning('请先保存模具，再添加配件')
    return
  }
  try {
    accessorySaving.value = true
    const payload: MoldAccessoryPayload = {
      moldId: props.moldId,
      accessoryType: accessoryForm.accessoryType,
      accessoryCode: accessoryForm.accessoryCode || undefined,
      quantity: accessoryForm.quantity,
      sharedScope: accessoryForm.sharedScope,
      remark: accessoryForm.remark || undefined
    }
    if (editingAccessoryId.value) {
      const updated = await updateMoldAccessory(editingAccessoryId.value, payload)
      const idx = accessories.value.findIndex(a => a.id === editingAccessoryId.value)
      if (idx >= 0 && updated) accessories.value[idx] = updated
      ElMessage.success('配件已更新')
    } else {
      const created = await createMoldAccessory(payload)
      if (created) accessories.value.push(created)
      ElMessage.success('配件已添加')
    }
    showAccessoryDialog.value = false
    resetAccessoryForm()
  } catch (e: any) {
    ElMessage.error(e?.message || '保存配件失败')
  } finally {
    accessorySaving.value = false
  }
}

async function removeAccessory(id: number) {
  try {
    await deleteMoldAccessory(id)
    accessories.value = accessories.value.filter(a => a.id !== id)
    ElMessage.success('配件已删除')
  } catch (e: any) {
    ElMessage.error(e?.message || '删除失败')
  }
}

/** 从配件原文自动同步结构化列表（解析 + 号分隔） */
async function syncAccessoriesFromRaw(force = false) {
  const raw = (form.rawAccessoriesText || '').trim()
  if (!raw) return
  // force=false（blur 自动触发）：如果已经有结构化数据，不自动覆盖（用户可能直接编辑了卡片）
  if (!force && accessories.value.length > 0) return
  if (force && accessories.value.length > 0) {
    const ok = await askConfirm({
      title: '覆盖现有配件？',
      message: `已存在 ${accessories.value.length} 个配件，按原文重新解析会覆盖它们，是否继续？`,
      confirmText: '覆盖解析',
      cancelText: '取消',
      danger: true
    })
    if (ok !== true) return
  }
  const parts = raw.split(/[+＋、，;；]|\s*\/\s*/).map(p => p.trim()).filter(Boolean)
  const keywordTypeMap: Record<string, string> = {
    '杯胆': 'cup_liner', '胆': 'cup_liner',
    '杯底': 'cup_bottom', '底': 'cup_bottom',
    '拉伸头': 'drawing_head', '头': 'drawing_head',
    '压料环': 'press_ring', '环': 'press_ring'
  }
  const parsed: MoldAccessoryView[] = parts.filter(p => p !== '-' && p !== '无' && p !== '空').map((p, i) => {
    const type = Object.entries(keywordTypeMap).find(([k]) => p.includes(k))?.[1] || 'other'
    return {
      id: -(i + 1), // 临时负 ID，标记未保存
      accessoryType: type,
      accessoryCode: p,
      quantity: 1,
      sharedScope: 'dedicated',
      remark: p
    }
  })
  accessories.value = parsed
  if (parsed.length) {
    ElMessage.success(`已解析出 ${parsed.length} 个配件，保存模具后会落库`)
  }
}

const form = reactive<MoldPayload & { id?: number; engravingType?: string }>({
  modelId: undefined,
  groupId: undefined,
  moldCode: '',
  baseMoldCode: '',
  moldTypeCode: '',
  cavityCount: undefined,
  moldRole: 'main',
  moldCategory: '',
  compartmentCount: '',
  surfaceStructure: '',
  bottomEngraving: '',
  engravingType: 'generic',
  rawAccessoriesText: '',
  rawCaliber: '',
  rawHeight: '',
  status: 'in_use',
  websiteEnabled: false,
  websiteSortOrder: 0,
  remark: ''
})

const isEdit = computed(() => Boolean(props.moldId))
const isNumericOnly = computed(() => /^\d+$/.test(form.moldCode?.trim() || ''))

const rules: FormRules = {
  modelId: [{ required: true, message: '请选择归属产品', trigger: 'change' }],
  moldCode: [{ required: true, message: '请填写模号', trigger: 'blur' }]
}

const CATEGORY_OPTIONS = ['方盒', '圆杯', '碗', '桶', '盖', '盒', '盆', '盘', '托']
const STRUCTURE_OPTIONS = ['光面', '圆筋', '方筋', '瓦楞', '腐蚀', '磨砂']
const ENGRAVING_LABELS: Record<string, string> = {
  blank: '空白',
  generic: '通用',
  brand_custom: '品牌专版',
  functional: '功能标识'
}

function engravingTypeTagType(): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  switch (form.engravingType) {
    case 'blank': return 'info'
    case 'generic': return 'success'
    case 'brand_custom': return 'warning'
    case 'functional': return 'primary'
    default: return 'info'
  }
}

// 模号解析：M20910-3F / P23128-6W / 纯数字
const MOLD_CODE_RE = /^([A-Za-z]+)?(\d+)(?:-(\d+)([A-Za-z]{1,4}))?$/
function parseMoldCode() {
  const code = form.moldCode?.trim() || ''
  const m = code.match(MOLD_CODE_RE)
  if (!m) return
  const prefix = m[1] || ''
  const number = m[2] || ''
  const cavity = m[3]
  const typeCode = m[4]
  if (prefix + number) form.baseMoldCode = prefix + number
  if (cavity) form.cavityCount = parseInt(cavity, 10)
  if (typeCode) form.moldTypeCode = typeCode.toUpperCase()
}

function classifyEngraving() {
  const t = (form.bottomEngraving || '').trim()
  if (!t) { form.engravingType = 'blank'; return }
  if (t === '空白' || t === '无' || t === '-') { form.engravingType = 'blank'; return }
  if (t.includes('www') || t.includes('http') || t.includes('.com') || t.includes('专版')) {
    form.engravingType = 'brand_custom'; return
  }
  if (t.includes('循环') || t.includes('食品级') || t.includes('可回收')) {
    form.engravingType = 'functional'; return
  }
  if (t.includes('通用') || t.includes('标准')) { form.engravingType = 'generic'; return }
  form.engravingType = 'generic'
}

async function loadProductModels() {
  try {
    // 归属产品下拉直接读取包材系列（PackagingModel），保证未建模具的新系列也可选
    const data = await fetchPackagingModels({ page: 0, size: 9999 }) as any
    const items: { id: number; name: string }[] = []
    for (const m of (data.content || data || [])) {
      if (m.id && m.name) {
        items.push({ id: m.id, name: m.name })
      }
    }
    items.sort((a, b) => a.name.localeCompare(b.name))
    productModels.value = items
  } catch {
    // ignore
  }
}

async function loadMoldGroups(modelId: number | undefined) {
  moldGroups.value = []
  if (!modelId) return
  try {
    loadingGroups.value = true
    const data = await fetchMoldGroups({ modelId }) as any
    moldGroups.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.warning(e?.message || '加载规格族失败')
  } finally {
    loadingGroups.value = false
  }
}

/**
 * 归属系列切换：级联清空规格族（新系列下的规格族列表会变化，保留旧系列的 groupId 会跨系列）
 */
function onModelChanged(newModelId: number) {
  form.groupId = undefined
  loadMoldGroups(newModelId)
}

/**
 * 规格族切换：选中后自动校验并锁定归属系列（与 group.modelId 对齐）
 * <p>双归属收口的前端体现：选中规格族后，归属系列 select 禁用，
 * 避免用户在不知情下切换系列导致 group.model_id 与 mold.model_id 不一致。
 */
function onGroupChanged(newGroupId: number | undefined) {
  if (!newGroupId) {
    // 清空规格族时解锁系列选择
    return
  }
  const group = moldGroups.value.find(g => g.id === newGroupId)
  if (group && group.modelId && form.modelId !== group.modelId) {
    // 以规格族所属系列为准（与后端 resolveOwnership 口径一致）
    form.modelId = group.modelId
  }
}

/**
 * 快速新建规格族弹窗。在当前选择的 modelId 下创建，创建成功后自动选为当前模具的 groupId。
 */
async function openQuickCreateGroup() {
  if (!form.modelId) {
    ElMessage.warning('请先选择归属产品')
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('请输入规格族名称，如"1414方盒"', '新建规格族', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputPlaceholder: '如 1414方盒 / C118圆杯 / 2214长方盒',
      inputValidator: (v: string) => (v && v.trim().length > 0) || '请填写规格族名称',
      draggable: true
    })
    const name = value.trim()
    const payload: MoldGroupPayload = { modelId: form.modelId!, name }
    const created = await createMoldGroup(payload) as any
    await loadMoldGroups(form.modelId)
    form.groupId = created?.id
    ElMessage.success(`规格族"${name}"已创建`)
  } catch {
    // 用户取消不提示
  }
}

async function loadForEdit() {
  if (!props.moldId) return
  try {
    const data = await fetchPackagingMold(props.moldId)
    const v = data.mold
    // 先加载该系列的规格族列表，保证回显时有下拉项
    await loadMoldGroups(v.modelId)
    Object.assign(form, {
      id: v.id,
      modelId: v.modelId,
      groupId: v.groupId,
      moldCode: v.moldCode,
      baseMoldCode: v.baseMoldCode,
      moldTypeCode: v.moldTypeCode,
      cavityCount: v.cavityCount,
      moldRole: v.moldRole || 'main',
      moldCategory: v.moldCategory,
      compartmentCount: v.compartmentCount,
      surfaceStructure: v.surfaceStructure,
      bottomEngraving: v.bottomEngraving,
      engravingType: v.engravingType || 'generic',
      rawAccessoriesText: v.rawAccessoriesText,
      rawCaliber: v.rawCaliber,
      rawHeight: v.rawHeight,
      status: v.status || 'in_use',
      websiteEnabled: v.websiteEnabled ?? false,
      websiteSortOrder: v.websiteSortOrder ?? 0,
      remark: v.remark
    })
    // 第一道：优先用后端返回的结构化配件
    const backendAccessories = data.accessories || []
    accessories.value = backendAccessories
    // 第二道（终极兜底）：若后端返回结构化配件为空，但 rawAccessoriesText 有内容
    // 说明某次导入/保存时结构化配件未写入，立即在内存中解析兜底
    if (backendAccessories.length === 0 && v.rawAccessoriesText?.trim()) {
      const raw = v.rawAccessoriesText.trim()
      const parts = raw.split(/[+＋、，;；]|\s*\/\s*/).map(p => p.trim()).filter(p => p && p !== '-' && p !== '无' && p !== '空')
      if (parts.length > 0) {
        const keywordTypeMap: Record<string, string> = {
          '杯胆': 'cup_liner', '胆': 'cup_liner',
          '杯底': 'cup_bottom', '底': 'cup_bottom',
          '拉伸头': 'drawing_head', '头': 'drawing_head',
          '压料环': 'press_ring', '环': 'press_ring'
        }
        accessories.value = parts.map((p, i) => {
          const type = Object.entries(keywordTypeMap).find(([k]) => p.includes(k))?.[1] || 'other'
          return {
            id: -(i + 1),
            accessoryType: type,
            accessoryCode: p,
            quantity: 1,
            sharedScope: 'dedicated',
            remark: p
          }
        })
        ElMessage.warning('已根据配件原文自动解析出配件卡片，保存模具后会正式落库')
      }
    }
    dirty.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || '加载模具失败')
    visible.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    modelId: undefined,
    groupId: undefined,
    moldCode: '',
    baseMoldCode: '',
    moldTypeCode: '',
    cavityCount: undefined,
    moldRole: 'main',
    moldCategory: '',
    compartmentCount: '',
    surfaceStructure: '',
    bottomEngraving: '',
    engravingType: 'generic',
    rawAccessoriesText: '',
    rawCaliber: '',
    rawHeight: '',
    status: 'in_use',
    websiteEnabled: false,
    websiteSortOrder: 0,
    remark: '',
    id: undefined
  })
  moldGroups.value = []
  accessories.value = []
  dirty.value = false
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (ok, fields) => {
    if (!ok) {
      if (fields?.moldCode?.length) ElMessage.error('模号必填')
      else if (fields?.modelId?.length) ElMessage.error('请选择归属产品')
      else ElMessage.error('表单还有未通过的项，请检查')
      return
    }
    try {
      saving.value = true
      let savedMoldId = props.moldId
      if (isEdit.value && savedMoldId) {
        await updatePackagingMold(savedMoldId, { ...form })
      } else {
        const created = await createPackagingMold({ ...form }) as any
        savedMoldId = created?.id
        ElMessage.success('已创建')
      }
      // 第三道保障：保存完模具后，把临时 ID（负号）的解析版配件逐个落库
      if (savedMoldId) {
        const pendingAccessories = accessories.value.filter(a => a.id < 0)
        if (pendingAccessories.length > 0) {
          let createdCount = 0
          for (const a of pendingAccessories) {
            try {
              const payload: MoldAccessoryPayload = {
                moldId: savedMoldId,
                accessoryType: a.accessoryType,
                accessoryCode: a.accessoryCode || undefined,
                quantity: a.quantity,
                sharedScope: a.sharedScope,
                remark: a.remark || undefined
              }
              const created = await createMoldAccessory(payload)
              if (created) createdCount++
            } catch {
              // 单条失败不阻断整体保存
            }
          }
          if (createdCount > 0) {
            ElMessage.success(`配件 ${createdCount} 项已落库`)
          }
        }
      }
      if (isEdit.value) ElMessage.success('已更新')
      dirty.value = false
      emit('saved')
      visible.value = false
    } catch (e: any) {
      ElMessage.error(e?.message || '保存失败')
    } finally {
      saving.value = false
    }
  })
}

async function handleBeforeClose(): Promise<boolean> {
  if (saving.value) return false
  if (dirty.value) {
    const ok = await askConfirm({
      title: '未保存的修改',
      message: '当前有未保存的修改，确定要关闭吗？',
      confirmText: '关闭',
      cancelText: '继续编辑',
      danger: true
    })
    return ok === true
  }
  return true
}

watch(visible, v => {
  if (v) {
    loadProductModels()
    if (props.moldId) loadForEdit()
    else {
      resetForm()
      if (props.modelId) {
        form.modelId = props.modelId
        loadMoldGroups(props.modelId)
      }
    }
  }
})

onMounted(() => {
  if (props.visible) {
    loadProductModels()
    if (props.moldId) loadForEdit()
    else if (props.modelId) {
      form.modelId = props.modelId
      loadMoldGroups(props.modelId)
    }
  }
})
</script>

<style scoped>
.mold-form {
  padding: var(--space-sm-md) var(--space-xs);
}

.form-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-form-item-gap);
}

.form-row + .form-row {
  margin-top: var(--space-sm-md);
}

.group-select-row {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  width: 100%;
}

.model-locked-hint {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  margin-top: var(--space-xs);
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

.model-locked-hint .el-icon {
  font-size: 13px;
}

.grow {
  flex: 1 1 0;
  min-width: 0;
}

.field-hints {
  margin-top: var(--space-xs);
  font-size: var(--fs-xs);
  color: var(--color-text-faint);
  line-height: var(--lh-relaxed);
}

.field-hints strong {
  color: var(--color-text-strong);
  font-family: var(--font-mono);
  font-weight: var(--fw-semibold);
}

.field-hints .el-tag {
  margin-left: var(--space-xs);
}

.raw-accessories-row {
  display: flex;
  align-items: stretch;
  gap: var(--space-sm);
  width: 100%;
}

.raw-accessories-row .el-input {
  flex: 1 1 0;
  min-width: 0;
}

.raw-accessories-row .el-button {
  flex-shrink: 0;
}

/* ====== 配件区块 ====== */

.accessories-section {
  margin-top: var(--space-sm-md);
  padding-top: var(--space-md-lg);
  border-top: 1px dashed var(--color-border-lighter);
}

.accessories-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-sm-md);
}

.accessories-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.accessories-title::before {
  content: '';
  display: inline-block;
  width: 3px;
  height: 14px;
  background: var(--color-primary);
  border-radius: var(--radius-sm);
}

.accessories-count {
  display: inline-flex;
  align-items: center;
  padding: 1px var(--space-sm);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-medium);
  color: var(--color-primary);
  background: var(--color-primary-bg);
  border-radius: var(--radius-pill);
  margin-left: var(--space-xs);
}

.accessories-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-2xl) var(--space-md-lg);
  background: var(--color-surface-alt);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  font-size: var(--fs-xs);
  color: var(--color-text-faint);
}

.accessories-empty .el-icon {
  font-size: 16px;
  color: var(--color-text-faint);
}

.accessories-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-sm-md);
}

.accessory-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: var(--space-md-lg);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  transition: all var(--transition-fast);
}

.accessory-card:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-xs);
}

.accessory-card-main {
  flex: 1;
  min-width: 0;
}

.accessory-type-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: var(--space-xs);
  flex-wrap: wrap;
}

.accessory-type-row .el-tag {
  font-weight: var(--fw-medium);
}

.accessory-code-tag {
  font-family: var(--font-mono);
}

.accessory-meta-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.meta-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px var(--space-sm);
  font-size: var(--fs-xxs);
  color: var(--color-text-muted);
  background: var(--color-surface-alt);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-lighter);
  white-space: nowrap;
}

.meta-chip.scope-dedicated {
  color: var(--color-info-text);
  background: var(--color-info-bg-soft);
  border-color: var(--color-info-border);
}

.meta-chip.scope-series_shared {
  color: var(--color-warning-text);
  background: var(--color-warning-bg-soft);
  border-color: var(--color-warning-border);
}

.meta-chip.scope-global_shared {
  color: var(--color-success-text);
  background: var(--color-success-bg-soft);
  border-color: var(--color-success-border);
}

.accessory-remark {
  margin: var(--space-xs) 0 0;
  font-size: var(--fs-xs);
  color: var(--color-text-secondary);
  line-height: var(--lh-relaxed);
  white-space: pre-wrap;
  word-break: break-word;
}

.accessory-card-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-xs);
  flex-shrink: 0;
  margin-left: var(--space-sm);
}

.accessory-card-actions .el-button {
  padding: 2px var(--space-sm);
  font-size: var(--fs-xxs);
}
</style>
