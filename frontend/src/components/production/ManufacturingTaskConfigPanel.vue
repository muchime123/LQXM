<template>
  <section>
    <el-alert title="这里只维护任务单的产品名称和可选配置。产品未指定专属制程时，使用「制程模板」中维护的车间默认制程。" type="info" :closable="false" />
    <div class="filters">
      <el-select v-model="workshop" clearable placeholder="全部车间">
        <el-option v-for="name in workshops" :key="name" :value="name" :label="name" />
      </el-select>
      <el-select v-model="kind" clearable placeholder="全部类型">
        <el-option label="产品名称" value="PRODUCT_NAME" />
        <el-option label="可选配置" value="OPTIONAL_CONFIG" />
      </el-select>
      <span>{{ rows.length }} 项，其中产品 {{ rows.filter(r => r.configType === 'PRODUCT_NAME').length }} 项</span>
      <el-button type="primary" @click="openCreate">新增配置项</el-button>
    </div>
    <el-table :data="filtered" row-key="id">
      <el-table-column prop="workshopName" label="生产车间" min-width="160" />
      <el-table-column label="类型" width="110"><template #default="{ row }">{{ row.configType === 'PRODUCT_NAME' ? '产品名称' : '可选配置' }}</template></el-table-column>
      <el-table-column label="名称" min-width="200"><template #default="{ row }"><el-input v-model="row.configName" maxlength="200" /></template></el-table-column>
      <el-table-column label="产品对应制程" min-width="240"><template #default="{ row }">
        <el-select v-if="row.configType === 'PRODUCT_NAME'" v-model="row.processTemplateId" clearable filterable placeholder="使用车间默认制程">
          <el-option v-for="t in templates" :key="t.id" :value="t.id" :label="t.templateName" />
        </el-select><span v-else>不适用</span>
      </template></el-table-column>
      <el-table-column label="制程来源" min-width="150"><template #default="{ row }">
        {{ row.configType !== 'PRODUCT_NAME' ? '不适用' : row.processTemplateId ? '产品专属制程' : '车间默认制程' }}
      </template></el-table-column>
      <el-table-column label="启用" width="80"><template #default="{ row }"><el-switch v-model="row.active" /></template></el-table-column>
      <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" :loading="saving === row.id" @click="save(row)">保存</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dialogOpen" title="新增任务单配置" width="min(520px, calc(100vw - 32px))">
      <el-form label-width="100px">
        <el-form-item label="生产车间" required><el-select v-model="createForm.workshopId" class="full-width" filterable><el-option v-for="w in workshopRows" :key="w.id" :label="w.workshopName" :value="w.id" /></el-select></el-form-item>
        <el-form-item label="配置类型" required><el-radio-group v-model="createForm.configType"><el-radio-button value="PRODUCT_NAME">产品名称</el-radio-button><el-radio-button value="OPTIONAL_CONFIG">可选配置</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="配置名称" required><el-input v-model="createForm.configName" maxlength="200" /></el-form-item>
        <el-form-item v-if="createForm.configType==='PRODUCT_NAME'" label="产品制程"><el-select v-model="createForm.processTemplateId" clearable filterable placeholder="留空则使用车间默认" class="full-width"><el-option v-for="t in templates" :key="t.id" :value="t.id" :label="t.templateName" /></el-select></el-form-item>
        <el-form-item label="启用"><el-switch v-model="createForm.active" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogOpen=false">取消</el-button><el-button type="primary" :loading="creating" @click="createConfig">保存</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
interface Config { id: number; configName: string; workshopName: string; configType: string; active: boolean; processTemplateId: number | null }
const rows = ref<Config[]>([])
interface Workshop { id:number; workshopName:string; enabled:boolean }
const workshopRows=ref<Workshop[]>([])
const dialogOpen=ref(false),creating=ref(false)
const newForm=()=>({workshopId:undefined as number|undefined,configType:'PRODUCT_NAME',configName:'',processTemplateId:null as number|null,active:true})
const createForm=ref(newForm())
function openCreate(){createForm.value=newForm();dialogOpen.value=true}
async function createConfig(){if(!createForm.value.workshopId||!createForm.value.configName.trim()){ElMessage.warning('请选择车间并填写配置名称');return};creating.value=true;try{await http.post('/manufacturing/master/task-configs',{...createForm.value,processTemplateId:createForm.value.configType==='PRODUCT_NAME'?createForm.value.processTemplateId:null});dialogOpen.value=false;rows.value=(await http.get<Config[]>('/manufacturing/master/task-configs')).data.map(r=>({...r,active:Boolean(r.active)}));ElMessage.success('任务单配置已新增')}finally{creating.value=false}}
const templates = ref<{ id: number; templateName: string }[]>([])
const workshop = ref('')
const kind = ref('')
const saving = ref<number | null>(null)
const workshops = computed(() => [...new Set(rows.value.map(r => r.workshopName))])
const filtered = computed(() => rows.value.filter(r => (!workshop.value || r.workshopName === workshop.value) && (!kind.value || r.configType === kind.value)))
async function save(row: Config) {
  saving.value = row.id
  try {
    await http.put(`/manufacturing/master/task-configs/${row.id}`, { ...row, processTemplateId: row.processTemplateId || null })
    ElMessage.success(`已保存 ${row.configName}，仅影响后续任务配置`)
  } finally { saving.value = null }
}
const [configs, catalog, workshopData] = await Promise.all([http.get<Config[]>('/manufacturing/master/task-configs'), http.get<{ id: number; templateName: string; active: boolean }[]>('/manufacturing/master/templates'),http.get<Workshop[]>('/manufacturing/master/workshops')])
rows.value = configs.data.map(r => ({ ...r, active: Boolean(r.active) }))
templates.value = catalog.data.filter(t => t.active)
workshopRows.value=workshopData.data.filter(w=>Boolean(w.enabled))
</script>
<style scoped>
.filters { display: flex; gap: var(--space-3); align-items: center; margin: var(--space-4) 0; }
.filters .el-select { width: 200px; }
</style>
