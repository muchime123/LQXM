<template>
  <section>
    <div class="toolbar">
      <div><h2>生产车间</h2><p>维护任务下达时可选的生产车间。默认制程请在「制程模板」中设置。</p></div>
      <el-button type="primary" @click="openCreate">新增车间</el-button>
    </div>
    <el-table :data="rows" row-key="id">
      <el-table-column prop="workshopName" label="车间名称" min-width="180" />
      <el-table-column prop="orderReceiverName" label="接单员" min-width="120" />
      <el-table-column prop="warehouseClerkName" label="入库员" min-width="120" />
      <el-table-column label="默认制程" min-width="180"><template #default="{ row }">{{ templateName(row.defaultProcessTemplateId) }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dialogOpen" :title="form.id ? '编辑车间' : '新增车间'" width="min(520px, calc(100vw - 32px))">
      <el-form label-width="90px">
        <el-form-item label="车间名称" required><el-input v-model="form.workshopName" maxlength="200" /></el-form-item>
        <el-form-item label="接单员"><el-input v-model="form.orderReceiverName" maxlength="100" /></el-form-item>
        <el-form-item label="入库员"><el-input v-model="form.warehouseClerkName" maxlength="100" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogOpen=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
interface Workshop { id:number; workshopName:string; enabled:boolean; orderReceiverName?:string; warehouseClerkName?:string; defaultProcessTemplateId:number|null }
interface Template { id:number; templateName:string }
const rows=ref<Workshop[]>([]),templates=ref<Template[]>([]),dialogOpen=ref(false),saving=ref(false)
const empty=():Partial<Workshop>&{enabled:boolean}=>({workshopName:'',orderReceiverName:'',warehouseClerkName:'',enabled:true})
const form=ref(empty())
const templateName=(id:number|null)=>id ? templates.value.find(t=>t.id===id)?.templateName||'模板不可用' : '未设置'
function openCreate(){form.value=empty();dialogOpen.value=true}
function openEdit(row:Workshop){form.value={...row};dialogOpen.value=true}
async function load(){const [w,t]=await Promise.all([http.get<Workshop[]>('/manufacturing/master/workshops'),http.get<Template[]>('/manufacturing/master/templates')]);rows.value=w.data.map(x=>({...x,enabled:Boolean(x.enabled)}));templates.value=t.data}
async function save(){if(!form.value.workshopName?.trim()){ElMessage.warning('请填写车间名称');return};saving.value=true;try{if(form.value.id)await http.put(`/manufacturing/master/workshops/${form.value.id}`,form.value);else await http.post('/manufacturing/master/workshops',form.value);dialogOpen.value=false;await load();ElMessage.success('车间已保存')}finally{saving.value=false}}
await load()
</script>
<style scoped>
.toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:var(--space-4)}
.toolbar h2,.toolbar p{margin:0}.toolbar p{color:var(--color-text-secondary);margin-top:var(--space-1)}
</style>
