<template>
  <el-dialog v-model="visible" title="生产配置产品审核" width="min(980px,96vw)" @opened="load(1)">
    <el-alert type="info" :closable="false" title="系统已按 skuKey 避免重复建档。确认新产品，或仅在确定是同一件货时归并到已有产品。" />
    <div class="toolbar"><el-input v-model="keyword" clearable placeholder="编号 / 名称 / 身份键" @keyup.enter="load(1)"/><el-button @click="load(1)">查询</el-button></div>
    <h3>待审核产品 <el-tag size="small" type="warning">{{ total }}</el-tag></h3>
    <el-alert v-if="!loading && total === 0" type="success" :closable="false"
      title="当前没有 CFG 待审核产品。CFG 档案只会在新规则生产任务检验合格入库时生成；尚未完工的配置产出见下方队列。" />
    <el-table :data="items" v-loading="loading" border empty-text="暂无待审核产品">
      <el-table-column prop="productCode" label="CFG 编号" width="210" />
      <el-table-column label="产品"><template #default="{row}"><b>{{ row.productName }}</b><div class="muted">{{ row.specification || '无规格' }} · {{ row.unit }}</div></template></el-table-column>
      <el-table-column prop="skuKey" label="身份键" min-width="220" show-overflow-tooltip />
      <el-table-column prop="outputCount" label="关联产出" width="90" />
      <el-table-column label="操作" width="190"><template #default="{row}">
        <el-button link type="success" @click="confirmNew(row)">确认为新产品</el-button>
        <el-button link type="primary" @click="openMerge(row)">归并已有产品</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total,prev,pager,next" @current-change="load" />
    <h3 class="queue-title">待完工自动建档 <el-tag size="small">{{ queuedTotal }}</el-tag></h3>
    <el-alert type="info" :closable="false" title="这些产出已有 skuKey，但尚未完工入库。完工时系统会自动复用已有产品或建立 CFG 待审核档案，现在不需要操作。" />
    <el-table :data="queuedOutputs" border max-height="260" empty-text="当前也没有等待自动建档的生产产出">
      <el-table-column prop="orderNo" label="生产任务" width="140" />
      <el-table-column label="产品"><template #default="{row}"><b>{{ row.productName }}</b><div class="muted">{{ row.specification || '无规格' }} · 计划 {{ row.plannedQuantity }} {{ row.unit }}</div></template></el-table-column>
      <el-table-column prop="skuKey" label="身份键" min-width="240" show-overflow-tooltip />
      <el-table-column prop="orderStatus" label="任务状态" width="110" />
    </el-table>
    <el-dialog v-model="mergeOpen" title="选择已有产品" width="620px" append-to-body>
      <p v-if="current">{{ current.productName }} · {{ current.specification || '无规格' }} · {{ current.unit }}</p>
      <el-select v-model="targetId" filterable remote :remote-method="searchCandidates" placeholder="搜索产品编号或名称" style="width:100%">
        <el-option v-for="p in candidates.filter(p=>p.unit===current?.unit)" :key="p.id" :value="p.id" :label="`${p.productCode} · ${p.productName} · ${p.specification || '无规格'}`" />
      </el-select>
      <template #footer><el-button @click="mergeOpen=false">取消</el-button><el-button type="primary" :disabled="!targetId" @click="merge">确认归并</el-button></template>
    </el-dialog>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref } from 'vue'; import { ElMessage,ElMessageBox } from 'element-plus'
import { fetchConfiguredProducts,fetchConfiguredProductCandidates,reviewConfiguredProduct,type ConfiguredProductIdentity,type ConfiguredProductCandidate,type ConfiguredOutputQueueItem } from '@/api/productInventory'
const visible=defineModel<boolean>({default:false}); const items=ref<ConfiguredProductIdentity[]>([]),total=ref(0),page=ref(1),keyword=ref(''),loading=ref(false)
const queuedOutputs=ref<ConfiguredOutputQueueItem[]>([]),queuedTotal=ref(0)
const mergeOpen=ref(false),current=ref<ConfiguredProductIdentity|null>(null),targetId=ref<number>(),candidates=ref<ConfiguredProductCandidate[]>([])
async function load(p=page.value){page.value=p;loading.value=true;try{const r=await fetchConfiguredProducts({keyword:keyword.value,page:p,size:20});items.value=r.items;total.value=r.total;queuedOutputs.value=r.queuedOutputs||[];queuedTotal.value=r.queuedTotal||0}finally{loading.value=false}}
async function confirmNew(row:ConfiguredProductIdentity){await ElMessageBox.confirm(`确认“${row.productName}”是一个新的正式产品？`,'确认产品身份');await reviewConfiguredProduct(row.id,{action:'CONFIRM_NEW'});ElMessage.success('已确认为正式产品');await load()}
async function openMerge(row:ConfiguredProductIdentity){current.value=row;targetId.value=undefined;candidates.value=[];mergeOpen.value=true;await searchCandidates(row.productName)}
async function searchCandidates(k:string){if(current.value)candidates.value=await fetchConfiguredProductCandidates(current.value.id,k)}
async function merge(){if(!current.value||!targetId.value)return;await ElMessageBox.confirm('归并会把该 CFG 身份及其库存聚合到目标产品，此操作需确保确为同一件货。','确认归并');await reviewConfiguredProduct(current.value.id,{action:'MERGE_EXISTING',targetProductId:targetId.value});mergeOpen.value=false;ElMessage.success('产品身份已归并');await load()}
</script>
<style scoped>.toolbar{display:flex;gap:8px;margin:14px 0}.toolbar .el-input{max-width:360px}.muted{font-size:12px;color:var(--el-text-color-secondary)}.el-pagination{margin-top:14px;justify-content:flex-end}h3{margin:16px 0 10px}.queue-title{margin-top:24px}.el-alert{margin-bottom:10px}</style>
