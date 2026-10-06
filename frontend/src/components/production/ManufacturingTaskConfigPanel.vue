<template>
  <div class="task-config">
    <!-- ==================== 左：生产车间（产线） ==================== -->
    <aside class="ws-panel">
      <div class="ws-panel-head">
        <h2>生产车间</h2>
        <span class="hint">共 {{ workshopCards.length }} 条产线</span>
      </div>
      <ul class="ws-list">
        <li
          v-for="w in workshopCards"
          :key="w.id"
          class="ws-item"
          :class="{ active: w.id === selectedId }"
          @click="selectedId = w.id"
        >
          <div class="ws-title">
            <span class="ws-name">{{ w.workshopName }}</span>
            <el-tag v-if="!w.enabled" size="small" type="info" effect="plain">停用</el-tag>
          </div>
          <div class="ws-tpl" :class="{ missing: !w.defaultTemplateName }">
            {{ w.defaultTemplateName || '未设默认制程' }}
          </div>
          <div class="ws-counts">
            <span class="c-product">产品 {{ w.productCount }}</span>
            <span class="c-config">配置 {{ w.configCount }}</span>
          </div>
        </li>
      </ul>
    </aside>

    <!-- ==================== 右：选中车间的产品与配置 ==================== -->
    <section v-if="current" class="detail">
      <header class="detail-head">
        <div class="detail-title">
          <h2>{{ current.workshopName }}</h2>
          <p class="detail-sub">
            默认制程
            <el-tag v-if="current.defaultTemplateName" size="small" effect="plain">{{ current.defaultTemplateName }}</el-tag>
            <el-tag v-else size="small" type="danger" effect="plain">未设置</el-tag>
            <span v-if="!current.defaultTemplateName" class="note">请维护产线默认制程或产品专属制程，建单时自动匹配</span>
          </p>
        </div>
        <div class="detail-stats">
          <span class="stat"><b>{{ current.products.length }}</b> 个产品名称</span>
          <span class="stat"><b>{{ current.configs.length }}</b> 个可选配置</span>
        </div>
      </header>

      <!-- 两个区块并排：宽度够就一行两列，不够自动回落单列（见 .blocks） -->
      <div class="blocks">
        <!-- ---------- 产品名称 ---------- -->
        <section class="block">
          <div class="block-head">
            <div>
              <h3>产品名称</h3>
              <p>本条产线可下单的产品。默认沿用上方车间制程，个别产品需要不同工艺时再单独指定。</p>
            </div>
            <el-button type="primary" size="small" @click="openCreate('PRODUCT_NAME')">新增产品名称</el-button>
          </div>
          <el-empty v-if="!current.products.length" :image-size="70" description="本条产线还没有产品名称" />
          <el-table v-else :data="current.products" stripe size="small" class="grid" max-height="440">
            <el-table-column label="产品名称" min-width="110">
              <template #default="{ row }"><el-input v-model="row.configName" maxlength="200" size="small" /></template>
            </el-table-column>
            <el-table-column label="生产制程" min-width="130">
              <template #default="{ row }">
                <el-select v-model="row.processTemplateId" clearable filterable size="small" class="full"
                  :placeholder="current.defaultTemplateName ? '车间默认' : '请选择制程'">
                  <el-option v-for="t in templates" :key="t.id" :value="t.id" :label="t.templateName" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="72">
              <template #default="{ row }"><el-switch v-model="row.active" size="small" /></template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :loading="saving === row.id" @click="save(row)">保存</el-button>
                <el-button link type="primary" @click="ruleEditor?.show(row.id)">配置规则</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <!-- ---------- 可选配置 ---------- -->
        <section class="block">
          <div class="block-head">
            <div>
              <h3>可选配置</h3>
              <p>建单时随产品一起选择的选项（灌装方式、材质、电压、包装等）。仅属于本条产线。</p>
            </div>
            <div class="block-actions">
              <el-input v-model="configKeyword" size="small" clearable placeholder="搜索配置名称" class="kw" />
              <el-button type="primary" size="small" @click="openCreate('OPTIONAL_CONFIG')">新增可选配置</el-button>
            </div>
          </div>
          <el-empty v-if="!current.configs.length" :image-size="70" description="本条产线还没有可选配置" />
          <template v-else>
            <el-table :data="filteredConfigs" stripe size="small" class="grid" max-height="440">
              <el-table-column label="配置名称" min-width="120">
                <template #default="{ row }"><el-input v-model="row.configName" maxlength="200" size="small" /></template>
              </el-table-column>
              <el-table-column label="启用" width="72">
                <template #default="{ row }"><el-switch v-model="row.active" size="small" /></template>
              </el-table-column>
              <el-table-column label="操作" width="72" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" :loading="saving === row.id" @click="save(row)">保存</el-button>
                </template>
              </el-table-column>
            </el-table>
            <p v-if="configKeyword && !filteredConfigs.length" class="none">没有匹配「{{ configKeyword }}」的配置</p>
          </template>
        </section>
      </div>
    </section>

    <el-empty v-else description="没有可维护的生产车间" />

    <ProductConfigurationRuleDialog ref="ruleEditor" />
    <!-- ==================== 新增配置 ==================== -->
    <el-dialog v-model="dialogOpen" :title="createForm.configType === 'PRODUCT_NAME' ? '新增产品名称' : '新增可选配置'" width="min(520px, calc(100vw - 32px))">
      <el-form label-width="92px">
        <el-form-item label="所属车间">
          <el-input :model-value="current?.workshopName || ''" disabled />
        </el-form-item>
        <el-form-item :label="createForm.configType === 'PRODUCT_NAME' ? '产品名称' : '配置名称'" required>
          <el-input v-model="createForm.configName" maxlength="200" :placeholder="createForm.configType === 'PRODUCT_NAME' ? '如 全自动气调机 500 型' : '如 灌装(量杯式)'" />
        </el-form-item>
        <el-form-item v-if="createForm.configType === 'PRODUCT_NAME'" label="生产制程">
          <el-select v-model="createForm.processTemplateId" clearable filterable class="full"
            :placeholder="'留空则沿用车间默认（' + (current?.defaultTemplateName || '未设置') + '）'">
            <el-option v-for="t in templates" :key="t.id" :value="t.id" :label="t.templateName" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="createForm.active" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import ProductConfigurationRuleDialog from './ProductConfigurationRuleDialog.vue'
const ruleEditor = ref<InstanceType<typeof ProductConfigurationRuleDialog>>()

interface Config { id: number; workshopId: number; configCode: string; configName: string; workshopName: string; configType: string; active: boolean; processTemplateId: number | null }
interface Workshop { id: number; workshopName: string; enabled: boolean; defaultProcessTemplateId: number | null }
interface Template { id: number; templateName: string; active: boolean }

const configs = ref<Config[]>([])
const workshops = ref<Workshop[]>([])
const templates = ref<Template[]>([])
const selectedId = ref<number | null>(null)
const configKeyword = ref('')
const saving = ref<number | null>(null)

/** 车间卡片：把配置按 workshop_name 归到各产线下（后端返回的配置不带 workshopId，车间名唯一且改名会级联）。 */
const workshopCards = computed(() => workshops.value.map(w => {
  const mine = configs.value.filter(c => c.workshopId === w.id)
  const products = mine.filter(c => c.configType === 'PRODUCT_NAME')
  const ownConfigs = mine.filter(c => c.configType !== 'PRODUCT_NAME')
  return {
    ...w,
    defaultTemplateName: templates.value.find(t => t.id === w.defaultProcessTemplateId)?.templateName ?? null,
    products,
    configs: ownConfigs,
    productCount: products.length,
    configCount: ownConfigs.length
  }
}))

const current = computed(() => workshopCards.value.find(w => w.id === selectedId.value) ?? null)

const filteredConfigs = computed(() => {
  const list = current.value?.configs ?? []
  const k = configKeyword.value.trim().toLowerCase()
  return k ? list.filter(c => c.configName.toLowerCase().includes(k)) : list
})

async function load() {
  const [cfg, ws, tpl] = await Promise.all([
    http.get<Config[]>('/manufacturing/master/task-configs'),
    http.get<Workshop[]>('/manufacturing/master/workshops'),
    http.get<Template[]>('/manufacturing/master/templates')
  ])
  configs.value = cfg.data.map(r => ({ ...r, active: Boolean(r.active) }))
  workshops.value = ws.data
  templates.value = tpl.data.filter(t => t.active)
  // 默认落到第一条启用产线，避免首屏空白
  if (selectedId.value == null || !workshops.value.some(w => w.id === selectedId.value)) {
    selectedId.value = (workshops.value.find(w => w.enabled) ?? workshops.value[0])?.id ?? null
  }
}
onMounted(load)

// ============ 新增 ============
const dialogOpen = ref(false)
const creating = ref(false)
const newForm = () => ({ configType: 'PRODUCT_NAME', configName: '', processTemplateId: null as number | null, active: true })
const createForm = ref(newForm())

function openCreate(type: 'PRODUCT_NAME' | 'OPTIONAL_CONFIG') {
  if (!current.value) { ElMessage.warning('请先选择生产车间'); return }
  createForm.value = { ...newForm(), configType: type }
  dialogOpen.value = true
}

async function createConfig() {
  const c = current.value
  if (!c) return
  if (!createForm.value.configName.trim()) { ElMessage.warning('请填写配置名称'); return }
  creating.value = true
  try {
    await http.post('/manufacturing/master/task-configs', {
      workshopId: c.id,
      configType: createForm.value.configType,
      configName: createForm.value.configName.trim(),
      processTemplateId: createForm.value.configType === 'PRODUCT_NAME' ? createForm.value.processTemplateId : null,
      active: createForm.value.active
    })
    dialogOpen.value = false
    await load()
    ElMessage.success('已新增')
  } finally { creating.value = false }
}

// ============ 保存 ============
async function save(row: Config) {
  saving.value = row.id
  try {
    await http.put(`/manufacturing/master/task-configs/${row.id}`, {
      configName: row.configName,
      active: row.active,
      processTemplateId: row.processTemplateId || null
    })
    ElMessage.success(`已保存「${row.configName}」，仅影响后续新建的任务`)
  } finally { saving.value = null }
}
</script>

<style scoped>
.task-config { display: grid; grid-template-columns: minmax(220px, 240px) minmax(0, 1fr); gap: var(--space-lg-xl, 16px); align-items: start; }

/* ---------- 左：产线列表 ---------- */
.ws-panel { background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: var(--space-lg-xl, 16px); box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04)); }
.ws-panel-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 12px; }
.ws-panel-head h2 { margin: 0; font-size: 15px; color: var(--color-text-heading, #0f172a); }
.ws-panel-head .hint { font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
.ws-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.ws-item { padding: 12px; border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-xl, 10px); cursor: pointer; transition: border-color .15s, background .15s; }
.ws-item:hover { border-color: var(--color-primary-border, #a7f3d0); background: var(--color-surface-alt, #f8fafc); }
.ws-item.active { border-color: var(--color-primary, #0f766e); background: var(--color-primary-bg-light, #f0fdfa); box-shadow: inset 3px 0 0 var(--color-primary, #0f766e); }
.ws-title { display: flex; align-items: center; gap: 6px; }
.ws-name { font-weight: 600; font-size: 14px; color: var(--color-text-heading, #0f172a); }
.ws-tpl { margin-top: 4px; font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.ws-tpl.missing { color: var(--color-danger, #dc2626); }
.ws-counts { display: flex; gap: 12px; margin-top: 8px; font-size: 12px; }
.c-product { color: var(--color-text-heading, #0f172a); }
.c-config { color: var(--color-text-secondary, #5f6f82); }

/* ---------- 右：详情 ---------- */
.detail { display: grid; gap: var(--space-lg-xl, 16px); min-width: 0; }

/* 产品名称 / 可选配置：页宽足够时一行两列，不够时自动回落单列。
   400px 阈值是按「产品名称」表格最小可用宽度（名称110 + 制程130 + 启用56 + 操作64 + 内边距）定的：
   左侧主导航 + 产线栏已吃掉约 520px，1500 屏右侧仅剩 ~845px，阈值再高就并排不了。 */
.blocks { display: grid; grid-template-columns: repeat(auto-fit, minmax(400px, 1fr)); gap: var(--space-lg-xl, 16px); align-items: start; min-width: 0; }
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; padding: 16px 20px; background: linear-gradient(135deg, var(--color-primary-bg-light, #f0fdfa) 0%, var(--color-surface-raised, #fbfcfd) 100%); border: 1px solid var(--color-primary-border, #a7f3d0); border-radius: var(--radius-2xl, 12px); }
.detail-title h2 { margin: 0 0 6px; font-size: 20px; color: var(--color-text-heading, #0f172a); }
.detail-sub { margin: 0; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; color: var(--color-text-secondary, #5f6f82); }
.detail-sub .note { font-size: 12px; color: var(--color-danger, #dc2626); }
.detail-stats { display: flex; gap: 16px; }
.stat { font-size: 13px; color: var(--color-text-secondary, #5f6f82); }
.stat b { font-size: 18px; color: var(--color-text-heading, #0f172a); margin-right: 2px; }

.block { min-width: 0; background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: var(--space-lg-xl, 16px); box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04)); }
.block-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.block-head h3 { margin: 0 0 4px; font-size: 15px; color: var(--color-text-heading, #0f172a); }
.block-head p { margin: 0; font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.block-actions { display: flex; align-items: center; gap: 8px; }
.kw { width: 200px; }
.full { width: 100%; }
.none { margin: 8px 0 0; font-size: 12px; color: var(--color-text-secondary, #94a3b8); }

.grid :deep(th.el-table__cell) { background: var(--color-surface-alt, #f8fafc); color: var(--color-text-heading, #0f172a); font-weight: 600; }
.grid :deep(th.el-table__cell .cell) { white-space: nowrap; }
.grid :deep(.el-table) { --el-table-border-color: var(--color-border, #e2e8f0); }

@media (max-width: 1100px) {
  .task-config { grid-template-columns: 1fr; }
}
@media (max-width: 760px) {
  .kw { width: 100%; }
  .block-actions { width: 100%; }
}
</style>
