<template>
  <div class="process-mgmt-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">MANUFACTURING MASTER</p>
        <h1>制程与工序管理</h1>
        <p>维护车间工序主数据和制程模板。模板修改不影响已下达的任务（任务工序为快照）。</p>
      </div>
      <div class="master-actions">
        <el-button @click="workshopDrawerOpen=true">车间管理</el-button>
        <el-button @click="operationDrawerOpen=true">工序库</el-button>
        <el-button @click="taskConfigDrawerOpen=true">任务单产品与配置</el-button>
      </div>
    </header>

    <el-drawer v-model="taskConfigDrawerOpen" title="任务单产品与配置" size="min(1100px, 92vw)" destroy-on-close>
        <ManufacturingTaskConfigPanel />
    </el-drawer>
    <el-drawer v-model="workshopDrawerOpen" title="车间管理" size="min(900px, 92vw)" destroy-on-close @closed="loadTemplates">
        <ManufacturingWorkshopPanel />
    </el-drawer>
    <el-drawer v-model="operationDrawerOpen" title="工序库" size="min(1100px, 92vw)">
        <section class="panel">
          <div class="toolbar">
            <el-input v-model="opKeyword" clearable placeholder="工序编码 / 名称" @keyup.enter="loadOps" @clear="loadOps" class="search-input" />
            <el-select v-model="opCategoryFilter" clearable placeholder="全部分类" @change="loadOps" class="category-select">
              <el-option v-for="c in CATEGORY_OPTIONS" :key="c.value" :label="c.label" :value="c.value" />
            </el-select>
            <el-select v-model="opActiveFilter" clearable placeholder="全部状态" @change="loadOps" class="status-select">
              <el-option label="启用" :value="true" />
              <el-option label="停用" :value="false" />
            </el-select>
            <el-button type="primary" @click="openOpCreate">新增工序</el-button>
            <el-button :icon="Refresh" @click="loadOps">刷新</el-button>
          </div>

          <el-table :data="filteredOps" stripe v-loading="opLoading">
            <el-table-column prop="operationCode" label="工序编码" width="130" />
            <el-table-column prop="operationName" label="工序名称" min-width="160" />
            <el-table-column label="分类" width="120">
              <template #default="{ row }">
                <el-tag :type="categoryTagType(row.operationCategory)" size="small">{{ categoryLabel(row.operationCategory) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="defaultWorkshop" label="默认车间" width="120" show-overflow-tooltip />
            <el-table-column label="标准工时" width="110">
              <template #default="{ row }">{{ row.standardHours ? row.standardHours + ' h' : '—' }}</template>
            </el-table-column>
            <el-table-column prop="reportingUnit" label="单位" width="80" />
            <el-table-column label="需检验" width="80">
              <template #default="{ row }">
                <el-tag :type="row.requiresInspection ? 'warning' : 'info'" size="small">{{ row.requiresInspection ? '是' : '否' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sortOrder" label="排序" width="80" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openOpEdit(row)">编辑</el-button>
                <el-button link :type="row.active ? 'danger' : 'success'" @click="toggleOp(row)">{{ row.active ? '停用' : '启用' }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
    </el-drawer>

    <section class="workspace-intro">
      <div><strong>制程模板工作台</strong><span>选择模板后，在右侧编排生产工序和产品规则。</span></div>
      <el-button type="primary" @click="openTplCreate">新增制程模板</el-button>
    </section>
        <div class="template-layout">
          <!-- 左侧：模板列表 -->
          <section class="panel template-list">
            <div class="toolbar">
              <el-select v-model="selectedWorkshop" clearable placeholder="全部车间" class="workshop-filter" @change="currentTemplate=null">
                <el-option v-for="w in workshopRows.filter(item=>item.enabled)" :key="w.id" :label="w.workshopName" :value="w.workshopName" />
              </el-select>
              <el-switch v-model="tplOnlyActive" inline-prompt active-text="在用" inactive-text="全部" class="active-switch" />
              <el-button :icon="Refresh" @click="loadTemplates">刷新</el-button>
            </div>
            <el-alert title="车间默认制程在模板侧设置。产品没有专属制程时，生产任务自动使用对应车间的默认模板。" type="info" :closable="false" class="kw-alert" />
            <el-table :data="filteredTemplates" stripe v-loading="tplLoading" highlight-current-row @current-change="selectTemplate" class="template-table">
              <el-table-column prop="templateCode" label="编码" width="130" />
              <el-table-column prop="templateName" label="名称" min-width="160" />
              <el-table-column prop="workshopType" label="车间" width="100" show-overflow-tooltip />
              <el-table-column prop="operationCount" label="工序数" width="75" />
              <el-table-column prop="version" label="版本" width="65" />
              <el-table-column label="状态" width="75">
                <template #default="{ row }">
                  <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '启用' : '停用' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="车间默认" width="90">
                <template #default="{ row }"><el-tag size="small" :type="isWorkshopDefault(row.id) ? 'success' : 'info'">{{ isWorkshopDefault(row.id) ? '是' : '否' }}</el-tag></template>
              </el-table-column>
              <el-table-column label="操作" width="160" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openTplEdit(row)">编辑</el-button>
                  <el-button link :type="row.active ? 'danger' : 'success'" @click="toggleTpl(row)">{{ row.active ? '停用' : '启用' }}</el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>

          <!-- 右侧：选中模板的工序行编辑 -->
          <section class="panel template-detail">
            <el-empty v-if="!currentTemplate" description="选择左侧模板查看和编辑工序" />
            <template v-else>
              <div class="detail-head">
                <div>
                  <h2>{{ currentTemplate.templateName }}</h2>
                  <p class="meta">{{ currentTemplate.templateCode }} · {{ currentTemplate.workshopType || '未指定车间' }} · v{{ currentTemplate.version }}</p>
                </div>
                <div class="actions">
                  <el-button type="primary" @click="addOperation">添加工序</el-button>
                  <el-button type="success" :loading="savingOps" @click="saveOperations">保存工序配置</el-button>
                </div>
              </div>

              <el-alert v-if="currentTemplate.applicableProductKeyword" type="info" :closable="false" :title="'适用产品关键词：' + currentTemplate.applicableProductKeyword" class="kw-alert" />
              <el-alert v-if="currentTemplate.description" type="info" :closable="false" :title="currentTemplate.description" class="kw-alert" />

              <el-empty v-if="!templateOperations.length" description="该模板还没有工序，点击「添加工序」开始配置" />
              <el-table v-else :data="templateOperations" stripe class="ops-table">
                <el-table-column label="序号" width="90">
                  <template #default="{ row }">
                    <span class="drag-handle" draggable="true" title="拖拽调整顺序" @dragstart="onDragStart($event, row)" @dragover.prevent="onDragOver($event, row)" @drop="onDrop($event, row)">⋮⋮</span>
                    <span class="seq">{{ row.sequenceNo }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="operationName" label="工序" min-width="150">
                  <template #default="{ row }">
                    <strong>{{ row.operationName }}</strong>
                    <small class="op-code">{{ row.operationCode }}</small>
                  </template>
                </el-table-column>
                <el-table-column label="分类" width="110">
                  <template #default="{ row }">
                    <el-tag :type="categoryTagType(row.operationCategory)" size="small">{{ categoryLabel(row.operationCategory) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="系数" width="100">
                  <template #default="{ row }">
                    <el-input-number v-model="row.requirementCoefficient" :min="0.1" :precision="3" :step="0.1" size="small" class="coef-input" />
                  </template>
                </el-table-column>
                <el-table-column label="必做" width="70">
                  <template #default="{ row }"><el-switch v-model="row.required" /></template>
                </el-table-column>
                <el-table-column label="最终" width="70">
                  <template #default="{ row }"><el-switch v-model="row.isFinal" @change="onFinalChange(row)" /></template>
                </el-table-column>
                <el-table-column label="并行" width="70">
                  <template #default="{ row }"><el-switch v-model="row.allowParallel" /></template>
                </el-table-column>
                <el-table-column label="备注" min-width="150">
                  <template #default="{ row }"><el-input v-model="row.remark" size="small" placeholder="可选" /></template>
                </el-table-column>
                <el-table-column label="操作" width="80" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="danger" @click="removeOperation(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div class="rule-head">
                <div><h3>标准产品推荐规则</h3><p>销售订单选择这些标准产品时推荐本模板，生产人员仍可改选。</p></div>
                <el-button @click="openRuleCreate">添加产品规则</el-button>
              </div>
              <el-table :data="templateRules" size="small" stripe>
                <el-table-column prop="productName" label="标准产品" min-width="180" />
                <el-table-column label="订单方式" width="130"><template #default="{ row }">{{ modeLabel(row.customizationMode) }}</template></el-table-column>
                <el-table-column prop="priority" label="优先级" width="80" />
                <el-table-column label="状态" width="70"><template #default="{ row }"><el-tag size="small" :type="row.active ? 'success' : 'info'">{{ row.active ? '启用' : '停用' }}</el-tag></template></el-table-column>
                <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
                <el-table-column label="操作" width="120"><template #default="{ row }"><el-button link type="primary" @click="openRuleEdit(row)">编辑</el-button><el-button link type="danger" @click="removeRule(row)">删除</el-button></template></el-table-column>
              </el-table>
            </template>
          </section>
        </div>

    <!-- ============ 工序编辑弹窗 ============ -->
    <el-dialog v-model="opDialogOpen" :title="opForm?.id ? '编辑工序' : '新增工序'" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-form :model="opForm" label-width="100px">
        <el-form-item label="工序编码" required>
          <el-input v-model="opForm.operationCode" :disabled="!!opForm?.id" placeholder="如 DESIGN001（唯一编码，创建后不可改）" />
        </el-form-item>
        <el-form-item label="工序名称" required>
          <el-input v-model="opForm.operationName" placeholder="如 设计确认" />
        </el-form-item>
        <el-form-item label="分类" required>
          <el-select v-model="opForm.operationCategory" placeholder="选择分类" class="full-width">
            <el-option v-for="c in CATEGORY_OPTIONS" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认车间">
          <el-input v-model="opForm.defaultWorkshop" placeholder="如 装配车间（可选）" />
        </el-form-item>
        <el-form-item label="标准工时">
          <el-input-number v-model="opForm.standardHours" :min="0" :precision="2" :step="0.5" />
          <span class="unit-hint">小时</span>
        </el-form-item>
        <el-form-item label="报工单位">
          <el-input v-model="opForm.reportingUnit" placeholder="如 个 / 件 / 台 / 套 / 批" />
        </el-form-item>
        <el-form-item label="需检验">
          <el-switch v-model="opForm.requiresInspection" />
          <span class="hint">该工序是否需要质检员单独检验</span>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="opForm.sortOrder" :min="0" :step="1" />
          <span class="hint">数值小在前</span>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="opForm.description" type="textarea" :rows="2" placeholder="工序作业内容说明（可选）" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="opForm.active" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="opDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="savingOp" @click="saveOp">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 模板编辑弹窗 ============ -->
    <el-dialog v-model="tplDialogOpen" :title="tplForm?.id ? '编辑模板' : '新增模板'" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-form :model="tplForm" label-width="110px">
        <el-form-item label="模板编码" required>
          <el-input v-model="tplForm.templateCode" :disabled="!!tplForm?.id" placeholder="如 TPL-SEAL-NORMAL（唯一编码，创建后不可改）" />
        </el-form-item>
        <el-form-item label="模板名称" required>
          <el-input v-model="tplForm.templateName" placeholder="如 普通封口机" />
        </el-form-item>
        <el-form-item label="车间类型">
          <el-select v-model="tplForm.workshopType" clearable filterable placeholder="请选择生产车间" class="full-width">
            <el-option v-for="w in workshopRows.filter(item => item.enabled)" :key="w.id" :label="w.workshopName" :value="w.workshopName" />
          </el-select>
        </el-form-item>
        <el-form-item label="车间默认制程">
          <el-switch v-model="tplForm.defaultForWorkshop" :disabled="!tplForm.workshopType" />
          <span class="hint">开启后，本模板成为所选车间的默认制程，并替换该车间原默认模板</span>
        </el-form-item>
        <el-form-item label="适用产品关键词">
          <el-input v-model="tplForm.applicableProductKeyword" placeholder="如 封口机（用于销售订单下达生产时自动推荐模板）" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="tplForm.description" type="textarea" :rows="2" placeholder="模板适用场景说明（可选）" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="tplForm.active" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tplDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="savingTpl" @click="saveTpl">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 添加工序弹窗 ============ -->
    <el-dialog v-model="addOpDialogOpen" title="添加工序到模板" width="min(480px, calc(100vw - 32px))">
      <el-form :model="addOpForm" label-width="80px">
        <el-form-item label="工序" required>
          <el-select v-model="addOpForm.operationId" filterable placeholder="选择工序（仅显示启用的工序）" class="full-width">
            <el-option v-for="op in availableOps" :key="op.id" :label="op.operationCode + ' - ' + op.operationName" :value="op.id" />
          </el-select>
        </el-form-item>
        <el-empty v-if="!availableOps.length" description="没有可添加的工序（全部已添加或未启用）" :image-size="60" />
      </el-form>
      <template #footer>
        <el-button @click="addOpDialogOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!addOpForm.operationId" @click="confirmAddOp">添加</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="ruleDialogOpen" :title="ruleForm.id ? '编辑产品推荐规则' : '添加产品推荐规则'" width="min(520px, calc(100vw - 32px))">
      <el-form label-width="100px">
        <el-form-item label="标准产品" required><el-select v-model="ruleForm.productModelId" filterable style="width:100%"><el-option v-for="p in productOptions" :key="p.id" :value="p.id" :label="p.name" /></el-select></el-form-item>
        <el-form-item label="订单方式"><el-select v-model="ruleForm.customizationMode" clearable style="width:100%" placeholder="全部订单方式"><el-option v-for="m in MODE_OPTIONS" :key="m.value" :value="m.value" :label="m.label" /></el-select></el-form-item>
        <el-form-item label="推荐优先级"><el-input-number v-model="ruleForm.priority" :min="0" :max="999" /><span class="hint">数字越大越优先</span></el-form-item>
        <el-form-item label="备注"><el-input v-model="ruleForm.remark" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="ruleForm.active" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ruleDialogOpen=false">取消</el-button><el-button type="primary" :loading="savingRule" @click="saveRule">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import ManufacturingTaskConfigPanel from '@/components/production/ManufacturingTaskConfigPanel.vue'
import ManufacturingWorkshopPanel from '@/components/production/ManufacturingWorkshopPanel.vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  fetchOperationCatalog, saveOperationMaster,
  fetchTemplateCatalog, fetchTemplateMaster, saveTemplateMaster, replaceTemplateOperations,
  fetchTemplateRules, saveTemplateRule, deleteTemplateRule,
  type OperationMaster, type ProcessTemplateMaster, type TemplateOperationMaster, type TemplateProductRule
} from '@/api/manufacturing'
import { fetchCatalogModelsSimple } from '@/api/catalog/models'
import { http } from '@/api/http'

const MODE_OPTIONS=[{value:'STANDARD',label:'标准产品'},{value:'TOOLING_ADAPTATION',label:'工装模具适配'},{value:'CONFIGURATION_CHANGE',label:'配置改动'},{value:'SPECIAL_BUILD',label:'特殊定制'}]
const modeLabel=(v?:string)=>MODE_OPTIONS.find(x=>x.value===v)?.label||'全部方式'

const CATEGORY_OPTIONS = [
  { value: 'DESIGN', label: '设计' },
  { value: 'PURCHASE', label: '采购/下料' },
  { value: 'FABRICATION', label: '加工' },
  { value: 'TOOLING', label: '模具加工' },
  { value: 'ASSEMBLY', label: '装配' },
  { value: 'ELECTRICAL', label: '电气与气路' },
  { value: 'SURFACE', label: '表面处理' },
  { value: 'DEBUGGING', label: '调试' },
  { value: 'INSPECTION', label: '检验' },
  { value: 'OTHER', label: '其他' }
] as const

const categoryLabel = (v: string) => CATEGORY_OPTIONS.find(c => c.value === v)?.label || v
const categoryTagType = (v: string): 'info' | 'warning' | 'success' | 'primary' | 'danger' => {
  if (v === 'DESIGN') return 'primary'
  if (v === 'TOOLING') return 'warning'
  if (v === 'ASSEMBLY') return 'success'
  if (v === 'INSPECTION') return 'danger'
  return 'info'
}

const taskConfigDrawerOpen=ref(false)
const workshopDrawerOpen=ref(false)
const operationDrawerOpen=ref(false)

// ============ 工序主数据 ============
const opKeyword = ref('')
const opCategoryFilter = ref('')
const opActiveFilter = ref<boolean | ''>(true)
const opLoading = ref(false)
const operations = ref<OperationMaster[]>([])

const filteredOps = computed(() => {
  let list = operations.value
  if (opKeyword.value) {
    const k = opKeyword.value.toLowerCase()
    list = list.filter(o => o.operationCode.toLowerCase().includes(k) || o.operationName.toLowerCase().includes(k))
  }
  if (opCategoryFilter.value) list = list.filter(o => o.operationCategory === opCategoryFilter.value)
  if (opActiveFilter.value !== '') list = list.filter(o => o.active === opActiveFilter.value)
  return list
})

async function loadOps() {
  opLoading.value = true
  try { operations.value = await fetchOperationCatalog() }
  finally { opLoading.value = false }
}

const opDialogOpen = ref(false)
const savingOp = ref(false)
const opForm = ref<Partial<OperationMaster> & { active: boolean; requiresInspection: boolean; standardHours?: number; sortOrder: number }>({
  operationCode: '', operationName: '', operationCategory: 'ASSEMBLY',
  defaultWorkshop: '', standardHours: undefined, reportingUnit: '',
  requiresInspection: false, description: '', active: true, sortOrder: 0
})

function openOpCreate() {
  opForm.value = {
    operationCode: '', operationName: '', operationCategory: 'ASSEMBLY',
    defaultWorkshop: '', standardHours: undefined, reportingUnit: '',
    requiresInspection: false, description: '', active: true, sortOrder: operations.value.length
  }
  opDialogOpen.value = true
}

function openOpEdit(row: OperationMaster) {
  opForm.value = { ...row }
  opDialogOpen.value = true
}

async function saveOp() {
  if (!opForm.value.operationCode?.trim()) { ElMessage.warning('请填写工序编码'); return }
  if (!opForm.value.operationName?.trim()) { ElMessage.warning('请填写工序名称'); return }
  if (!opForm.value.operationCategory) { ElMessage.warning('请选择分类'); return }
  savingOp.value = true
  try {
    await saveOperationMaster(opForm.value.id, opForm.value as Record<string, unknown>)
    ElMessage.success('保存成功')
    opDialogOpen.value = false
    await loadOps()
  } finally { savingOp.value = false }
}

async function toggleOp(row: OperationMaster) {
  await saveOperationMaster(row.id, { ...row, active: !row.active })
  ElMessage.success(row.active ? '已停用' : '已启用')
  await loadOps()
}

// ============ 制程模板 ============
const tplLoading = ref(false)
const templates = ref<ProcessTemplateMaster[]>([])
const selectedWorkshop=ref('')
/** 默认只看在用：按氚云对齐后库里保留了停用的旧制程（同名的「半自动气调机」等），
 *  混在一起显示会出现两条同名模板、看起来像脏数据。切到「全部」可审计停用项。 */
const tplOnlyActive=ref(true)
const filteredTemplates=computed(()=>{
  const base=selectedWorkshop.value?templates.value.filter(t=>t.workshopType===selectedWorkshop.value):templates.value
  return tplOnlyActive.value?base.filter(t=>t.active):base
})
interface WorkshopRow { id:number; workshopName:string; enabled:boolean; defaultProcessTemplateId:number|null }
const workshopRows=ref<WorkshopRow[]>([])
const isWorkshopDefault=(templateId:number)=>workshopRows.value.some(w=>w.defaultProcessTemplateId===templateId)
const currentTemplate = ref<ProcessTemplateMaster | null>(null)
const templateOperations = ref<TemplateOperationMaster[]>([])
const templateRules = ref<TemplateProductRule[]>([])

async function loadTemplates() {
  tplLoading.value = true
  try {
    templates.value=await fetchTemplateCatalog()
    try { workshopRows.value=(await http.get<WorkshopRow[]>('/manufacturing/master/workshops')).data }
    catch { workshopRows.value=[]; ElMessage.error('车间主数据加载失败，请确认后端已更新并重启') }
  }
  finally { tplLoading.value = false }
}

async function selectTemplate(row: ProcessTemplateMaster | null) {
  if (!row) { currentTemplate.value = null; return }
  currentTemplate.value = row
  const [data,rules] = await Promise.all([fetchTemplateMaster(row.id),fetchTemplateRules(row.id)])
  templateOperations.value = data.operations.map(o => ({ ...o }))
  templateRules.value = rules
}

const ruleDialogOpen=ref(false),savingRule=ref(false)
const productOptions=ref<Array<{id:number;name:string}>>([])
const ruleForm=ref<{id?:number;productModelId?:number;customizationMode?:string;priority:number;active:boolean;remark:string}>({priority:100,active:true,remark:''})
async function ensureProducts(){if(!productOptions.value.length)productOptions.value=await fetchCatalogModelsSimple() as Array<{id:number;name:string}>}
async function openRuleCreate(){await ensureProducts();ruleForm.value={priority:100,active:true,remark:''};ruleDialogOpen.value=true}
async function openRuleEdit(row:TemplateProductRule){await ensureProducts();ruleForm.value={...row,remark:row.remark||''};ruleDialogOpen.value=true}
async function saveRule(){if(!currentTemplate.value||!ruleForm.value.productModelId){ElMessage.warning('请选择标准产品');return}savingRule.value=true;try{await saveTemplateRule(currentTemplate.value.id,ruleForm.value.id,ruleForm.value as Record<string,unknown>);ruleDialogOpen.value=false;templateRules.value=await fetchTemplateRules(currentTemplate.value.id);ElMessage.success('推荐规则已保存')}finally{savingRule.value=false}}
async function removeRule(row:TemplateProductRule){if(!currentTemplate.value)return;await deleteTemplateRule(currentTemplate.value.id,row.id);templateRules.value=await fetchTemplateRules(currentTemplate.value.id);ElMessage.success('推荐规则已删除')}

const tplDialogOpen = ref(false)
const savingTpl = ref(false)
const tplForm = ref<Partial<ProcessTemplateMaster> & { active: boolean }>({
  templateCode: '', templateName: '', workshopType: '',
  applicableProductKeyword: '', description: '', active: true, defaultForWorkshop: false
})

function openTplCreate() {
  tplForm.value = {
    templateCode: '', templateName: '', workshopType: '',
    applicableProductKeyword: '', description: '', active: true, defaultForWorkshop: false
  }
  tplDialogOpen.value = true
}

function openTplEdit(row: ProcessTemplateMaster) {
  tplForm.value = { ...row, defaultForWorkshop: isWorkshopDefault(row.id) }
  tplDialogOpen.value = true
}

async function saveTpl() {
  if (!tplForm.value.templateCode?.trim()) { ElMessage.warning('请填写模板编码'); return }
  if (!tplForm.value.templateName?.trim()) { ElMessage.warning('请填写模板名称'); return }
  savingTpl.value = true
  try {
    await saveTemplateMaster(tplForm.value.id, tplForm.value as Record<string, unknown>)
    ElMessage.success('保存成功')
    tplDialogOpen.value = false
    await loadTemplates()
  } finally { savingTpl.value = false }
}

async function toggleTpl(row: ProcessTemplateMaster) {
  await saveTemplateMaster(row.id, { ...row, active: !row.active, defaultForWorkshop: row.active ? false : isWorkshopDefault(row.id) })
  ElMessage.success(row.active ? '已停用' : '已启用')
  await loadTemplates()
}

// ============ 模板工序行编辑 ============
const addOpDialogOpen = ref(false)
const addOpForm = ref<{ operationId: number | undefined }>({ operationId: undefined })

const availableOps = computed(() => {
  const used = new Set(templateOperations.value.map(o => o.operationId))
  return operations.value.filter(o => o.active && !used.has(o.id))
})

function addOperation() {
  addOpForm.value = { operationId: undefined }
  addOpDialogOpen.value = true
}

function confirmAddOp() {
  if (!addOpForm.value.operationId) { ElMessage.warning('请选择工序'); return }
  const op = operations.value.find(o => o.id === addOpForm.value.operationId)
  if (!op) return
  const nextSeq = templateOperations.value.length + 1
  templateOperations.value.push({
    id: 0,
    operationId: op.id,
    sequenceNo: nextSeq,
    operationCode: op.operationCode,
    operationName: op.operationName,
    operationCategory: op.operationCategory,
    requirementCoefficient: 1,
    required: true,
    isFinal: false,
    allowParallel: false,
    remark: '',
    operationActive: true
  })
  addOpDialogOpen.value = false
  ElMessage.success('已添加，记得点击「保存工序配置」')
}

function removeOperation(row: TemplateOperationMaster) {
  const idx = templateOperations.value.indexOf(row)
  if (idx >= 0) {
    templateOperations.value.splice(idx, 1)
    templateOperations.value.forEach((o, i) => { o.sequenceNo = i + 1 })
  }
}

function onFinalChange(row: TemplateOperationMaster) {
  if (row.isFinal) {
    templateOperations.value.forEach(o => {
      if (o !== row) o.isFinal = false
    })
  }
}

// 拖拽排序（原生 HTML5 drag/drop）
let dragRow: TemplateOperationMaster | null = null
function onDragStart(_e: DragEvent, row: TemplateOperationMaster) { dragRow = row }
function onDragOver(e: DragEvent, _row: TemplateOperationMaster) { e.preventDefault() }
function onDrop(_e: DragEvent, row: TemplateOperationMaster) {
  if (!dragRow || dragRow === row) return
  const from = templateOperations.value.indexOf(dragRow)
  const to = templateOperations.value.indexOf(row)
  if (from < 0 || to < 0) return
  templateOperations.value.splice(from, 1)
  templateOperations.value.splice(to, 0, dragRow)
  templateOperations.value.forEach((o, i) => { o.sequenceNo = i + 1 })
  dragRow = null
}

const savingOps = ref(false)
async function saveOperations() {
  if (!currentTemplate.value) return
  if (!templateOperations.value.length) { ElMessage.warning('模板至少需要 1 道工序'); return }
  const finalCount = templateOperations.value.filter(o => o.isFinal).length
  if (finalCount !== 1) { ElMessage.warning('必须且只能设置 1 道最终工序'); return }
  savingOps.value = true
  try {
    await replaceTemplateOperations(currentTemplate.value.id, templateOperations.value.map(o => ({
      operationId: o.operationId,
      sequenceNo: o.sequenceNo,
      requirementCoefficient: o.requirementCoefficient,
      required: o.required,
      isFinal: o.isFinal,
      allowParallel: o.allowParallel,
      remark: o.remark || ''
    })))
    ElMessage.success('工序配置已保存')
    await loadTemplates()
    const refreshed = templates.value.find(t => t.id === currentTemplate.value?.id)
    if (refreshed) await selectTemplate(refreshed)
  } finally { savingOps.value = false }
}

await Promise.allSettled([loadOps(), loadTemplates()])
</script>

<style scoped>
.process-mgmt-page { display: grid; gap: var(--space-section-gap-wide, 16px); padding: var(--space-2xl, 24px); min-width: 0; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-lg-xl, 16px); flex-wrap: wrap; }
.page-head h1 { margin: 2px 0 6px; font-size: 26px; line-height: 1.2; }
.page-head p { margin: 0; color: var(--color-text-secondary, #5f6f82); }
.eyebrow { font-size: 11px; letter-spacing: .16em; color: var(--color-primary, #0f766e) !important; font-weight: 700; margin: 0; }
.master-actions { display:flex; gap:var(--space-md-lg, 12px); flex-wrap:wrap; }
.workspace-intro { display:flex; justify-content:space-between; align-items:center; gap:16px; padding:16px 20px; background:var(--color-surface, #fff); border:1px solid var(--color-border, #e2e8f0); border-radius:var(--radius-2xl, 12px); }
.workspace-intro div { display:grid; gap:4px; }
.workspace-intro strong { font-size:18px; color:var(--color-text-heading, #0f172a); }
.workspace-intro span { color:var(--color-text-secondary, #5f6f82); font-size:13px; }
.workshop-filter { width:220px; }
.active-switch { flex: none; }

/* ============ 面板卡片 ============ */
.panel { background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: var(--space-xl-2xl, 20px); box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,0.04)); min-width: 0; }
.toolbar { display: flex; gap: var(--space-md-lg, 12px); margin-bottom: var(--space-lg-xl, 16px); flex-wrap: wrap; align-items: center; }
.toolbar .search-input { width: 240px; }
.toolbar .category-select { width: 180px; }
.toolbar .status-select { width: 140px; }

/* 表格统一表头 */
.panel :deep(.el-table th.el-table__cell) { background: var(--color-surface-alt, #f8fafc); color: var(--color-text-heading, #0f172a); font-weight: 600; }
.panel :deep(.el-table) { --el-table-header-bg-color: var(--color-surface-alt, #f8fafc); --el-table-border-color: var(--color-border, #e2e8f0); }

.full-width { width: 100%; }
.unit-hint, .hint { margin-left: 10px; color: var(--color-text-secondary, #94a3b8); font-size: 12px; }

/* 模板左右布局 */
.template-layout { display: grid; grid-template-columns: minmax(520px, 1fr) minmax(560px, 1.4fr); gap: var(--space-lg-xl, 16px); align-items: start; }
.template-list .template-table { cursor: pointer; }

/* 模板详情 */
.detail-head {
  display: flex; justify-content: space-between; align-items: flex-start; gap: var(--space-lg-xl, 16px);
  margin-bottom: var(--space-lg-xl, 16px); flex-wrap: wrap;
  padding: var(--space-lg-xl, 16px);
  background: linear-gradient(135deg, var(--color-primary-bg-light, #f0fdfa) 0%, var(--color-surface-raised, #fbfcfd) 100%);
  border: 1px solid var(--color-primary-border, #a7f3d0); border-radius: var(--radius-xl, 10px);
}
.detail-head h2 { margin: 0 0 4px; font-size: 20px; color: var(--color-text-heading, #0f172a); }
.detail-head .meta { margin: 0; color: var(--color-text-secondary, #5f6f82); font-size: 13px; }
.detail-head .actions { display: flex; gap: var(--space-md-lg, 12px); }
.kw-alert { margin-bottom: var(--space-md-lg, 12px); }
.rule-head { display: flex; justify-content: space-between; align-items: center; gap: var(--space-md-lg, 12px); margin-top: var(--space-2xl, 24px); padding-top: var(--space-xl-2xl, 20px); border-top: 1px solid var(--color-border, #e2e8f0); }
.rule-head h3 { margin: 0 0 4px; font-size: 16px; color: var(--color-text-heading, #0f172a); }
.rule-head p { margin: 0; font-size: 12px; color: var(--color-text-secondary, #5f6f82); }

/* 工序行 */
.ops-table .drag-handle { cursor: grab; color: var(--color-text-secondary, #94a3b8); margin-right: 8px; font-size: 16px; user-select: none; }
.ops-table .drag-handle:active { cursor: grabbing; }
.ops-table .seq { font-weight: 600; }
.ops-table .op-code { display: block; color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
.ops-table .coef-input { width: 100px; }

/* ============ 弹窗 ============ */
:deep(.el-dialog) { border-radius: var(--radius-2xl, 14px); }
:deep(.el-dialog__header) { padding: var(--space-lg-xl, 16px) var(--space-2xl, 24px) var(--space-md-lg, 12px); margin-right: 0; border-bottom: 1px solid var(--color-border, #e2e8f0); }
:deep(.el-dialog__title) { font-size: 16px; font-weight: 700; color: var(--color-text-heading, #0f172a); }
:deep(.el-dialog__body) { padding: var(--space-xl-2xl, 20px) var(--space-2xl, 24px); }
:deep(.el-dialog__footer) { padding: var(--space-md-lg, 12px) var(--space-2xl, 24px) var(--space-lg-xl, 16px); border-top: 1px solid var(--color-border, #e2e8f0); }
:deep(.el-dialog__footer .el-button + .el-button) { margin-left: var(--space-md-lg, 12px); }

/* 弹窗内表单标签加粗强化 */
:deep(.el-dialog .el-form-item__label) { font-weight: 600; color: var(--color-text-heading, #0f172a); }

@media (max-width: 1100px) {
  .template-layout { grid-template-columns: 1fr; }
}
@media (max-width: 760px) {
  .process-mgmt-page { padding: 14px; }
  .master-actions,.workspace-intro { width:100%; }
  .workspace-intro { align-items:flex-start; flex-direction:column; }
  .toolbar .search-input, .toolbar .category-select, .toolbar .status-select { width: 100%; }
}
</style>
