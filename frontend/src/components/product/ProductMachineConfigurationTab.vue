<template>
  <section class="machine-config-tab">
    <header class="config-header">
      <div>
        <span>产品配置</span>
        <h3>机型参数与适配包材</h3>
        <p>产品只按实际机型档位区分；模具形状、出数和封口口径不再创建产品规格。</p>
      </div>
      <button type="button" class="primary" @click="managerVisible = true">管理机型档位</button>
    </header>

    <div v-if="loading" class="state">正在加载机型与包材关系…</div>
    <template v-else>
      <nav class="scope-tabs" aria-label="适配范围">
        <button v-if="tiers.length===0" type="button" :class="{ active: selectedTierId === null }" @click="selectScope(null)">产品通用</button>
        <button v-for="tier in tiers" :key="tier.id" type="button" :class="{ active: selectedTierId === tier.id }" @click="selectScope(tier.id)">
          {{ tier.tierLabel || tier.tierCode }}
        </button>
      </nav>

      <section v-if="selectedTier" class="tier-summary">
        <div><small>机型编码</small><strong>{{ selectedTier.tierCode }}</strong></div>
        <div><small>设备尺寸</small><strong>{{ selectedTier.machineSize || '未填写' }}</strong></div>
        <div><small>净重 / 毛重</small><strong>{{ weightSummary }}</strong></div>
        <div><small>包装尺寸</small><strong>{{ selectedTier.cartonSize || selectedTier.woodBoxSize || '未填写' }}</strong></div>
      </section>
      <p v-else class="scope-help">当前产品没有机型档位，适配包材将直接维护在产品层级。</p>

      <div class="packaging-toolbar">
        <div><strong>适配包材</strong><span>已选择 {{ selectedIds.length }} 项</span></div>
        <input v-model.trim="keyword" type="search" placeholder="搜索包材、尺寸、容量" />
      </div>

      <div v-if="filteredGroups.length === 0" class="state">没有找到可选择的包材规格。</div>
      <div v-else class="packaging-groups">
        <section v-for="group in filteredGroups" :key="group.modelId" class="packaging-group">
          <h4>{{ group.modelName }}</h4>
          <div class="packaging-options">
            <label v-for="option in group.options" :key="option.id">
              <input v-model="selectedIds" type="checkbox" :value="option.id" />
              <span><b>{{ option.variantName || option.dimensionLabel }}</b><small>{{ option.dimensionLabel }}<template v-if="option.capacityMl"> · {{ option.capacityMl }}ml</template></small></span>
            </label>
          </div>
        </section>
      </div>

      <footer class="config-footer">
        <span>包材的模具形状、封口口径和高度仍由包材自身维护。</span>
        <button type="button" class="primary" :disabled="saving" @click="saveCompatibility">{{ saving ? '保存中…' : '保存适配关系' }}</button>
      </footer>
    </template>

    <MachineTierManager :visible="managerVisible" :model-id="modelId" :model-name="modelName" @close="managerVisible = false" @changed="handleTierChanged" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import MachineTierManager from './MachineTierManager.vue'
import { notify } from '@/composables/ui/useToast'
import {
  fetchMachineTiers, fetchProductPackagingCompatibility, replaceProductPackagingCompatibility,
  type CatalogMachineTier, type PackagingCompatibilityOption, type ProductPackagingCompatibilityMatrix
} from '@/api/catalog/machineTiers'

const props = defineProps<{ modelId:number; modelName?:string }>()
const emit = defineEmits<{ changed:[] }>()
const tiers = ref<CatalogMachineTier[]>([])
const matrix = ref<ProductPackagingCompatibilityMatrix>({ options:[], productPackagingVariantIds:[], machineTierAssignments:[] })
const selectedTierId = ref<number|null>(null)
const selectedIds = ref<number[]>([])
const keyword = ref('')
const loading = ref(false)
const saving = ref(false)
const managerVisible = ref(false)

const selectedTier = computed(() => tiers.value.find(item => item.id === selectedTierId.value) || null)
const weightSummary = computed(() => {
  if (!selectedTier.value) return ''
  const values = [selectedTier.value.netWeightKg && `净重 ${selectedTier.value.netWeightKg}kg`, selectedTier.value.grossWeightKg && `毛重 ${selectedTier.value.grossWeightKg}kg`].filter(Boolean)
  return values.join(' / ') || '未填写'
})
const filteredGroups = computed(() => {
  const key = keyword.value.toLocaleLowerCase()
  const groups = new Map<number,{modelId:number;modelName:string;options:PackagingCompatibilityOption[]}>()
  matrix.value.options.filter(item => !key || [item.modelName,item.variantName,item.dimensionLabel,item.capacityMl].some(value => String(value||'').toLocaleLowerCase().includes(key))).forEach(item => {
    const group=groups.get(item.modelId)||{modelId:item.modelId,modelName:item.modelName,options:[]}; group.options.push(item); groups.set(item.modelId,group)
  })
  return [...groups.values()]
})

function selectScope(tierId:number|null){ selectedTierId.value=tierId; keyword.value=''; syncSelection() }
function syncSelection(){ selectedIds.value = selectedTierId.value === null ? [...matrix.value.productPackagingVariantIds] : [...(matrix.value.machineTierAssignments.find(item=>item.machineTierId===selectedTierId.value)?.packagingVariantIds||[])] }
async function load(){ loading.value=true; try { const [tierData,matrixData]=await Promise.all([fetchMachineTiers(props.modelId),fetchProductPackagingCompatibility(props.modelId)]); tiers.value=tierData; matrix.value=matrixData; if(tiers.value.length){if(selectedTierId.value===null||!tiers.value.some(item=>item.id===selectedTierId.value))selectedTierId.value=tiers.value[0].id}else selectedTierId.value=null; syncSelection() } catch(e){ notify.errorWithDetail('加载机型配置失败',e) } finally { loading.value=false } }
async function saveCompatibility(){ saving.value=true; try { matrix.value=await replaceProductPackagingCompatibility(props.modelId,selectedTierId.value,[...new Set(selectedIds.value)]); syncSelection(); emit('changed'); notify('适配包材已保存','success') } catch(e){ notify.errorWithDetail('保存适配包材失败',e) } finally { saving.value=false } }
async function handleTierChanged(){ await load(); emit('changed') }
watch(()=>props.modelId,load,{immediate:true})
</script>

<style scoped>
.machine-config-tab{min-height:100%;padding:20px}.config-header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding:18px;border:1px solid var(--color-border);border-radius:var(--radius-xl);background:var(--color-surface)}.config-header span{color:var(--color-primary);font-size:12px;font-weight:700}.config-header h3{margin:4px 0;font-size:20px}.config-header p,.scope-help{margin:0;color:var(--color-text-muted);font-size:13px}.primary{padding:9px 14px;border:1px solid var(--color-primary);border-radius:var(--radius-md);background:var(--color-primary);color:#fff;cursor:pointer}.scope-tabs{display:flex;gap:8px;overflow-x:auto;margin-top:16px;padding-bottom:4px}.scope-tabs button{white-space:nowrap;padding:8px 14px;border:1px solid var(--color-border);border-radius:999px;background:var(--color-surface);cursor:pointer}.scope-tabs button.active{border-color:var(--color-primary);background:var(--color-primary-bg);color:var(--color-primary)}.tier-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-top:14px}.tier-summary div{display:flex;flex-direction:column;gap:5px;padding:12px;border-radius:var(--radius-lg);background:var(--color-surface-alt)}.tier-summary small{color:var(--color-text-muted)}.scope-help{margin-top:14px;padding:12px;border-radius:var(--radius-lg);background:var(--color-primary-bg)}.packaging-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:18px}.packaging-toolbar div{display:flex;gap:10px}.packaging-toolbar span{color:var(--color-text-muted);font-size:12px}.packaging-toolbar input{width:min(340px,45%);min-height:38px;padding:0 10px;border:1px solid var(--color-border);border-radius:var(--radius-md)}.packaging-groups{display:flex;flex-direction:column;gap:12px;margin-top:12px}.packaging-group{padding:14px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface)}.packaging-group h4{margin:0 0 10px}.packaging-options{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:8px}.packaging-options label{display:flex;align-items:flex-start;gap:8px;padding:9px;border:1px solid var(--color-border);border-radius:var(--radius-md);cursor:pointer}.packaging-options span{display:flex;min-width:0;flex-direction:column}.packaging-options small{margin-top:3px;color:var(--color-text-muted)}.config-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:18px;padding-top:14px;border-top:1px solid var(--color-border);color:var(--color-text-muted);font-size:12px}.state{display:grid;min-height:180px;place-items:center;color:var(--color-text-muted)}@media(max-width:800px){.tier-summary{grid-template-columns:1fr 1fr}.config-header,.config-footer,.packaging-toolbar{align-items:stretch;flex-direction:column}.packaging-toolbar input{width:100%}}
</style>
