<template>
  <section class="machine-config-tab">
    <header class="config-header">
      <div><span>设备配置</span><h3>机型参数</h3><p>按实际设备机型维护尺寸、重量和封口能力。</p></div>
      <button type="button" class="primary" @click="managerVisible = true">管理机型档位</button>
    </header>
    <div v-if="loading" class="state">正在加载机型档位…</div>
    <div v-else-if="!tiers.length" class="state">当前产品尚无机型档位。</div>
    <div v-else class="tier-list">
      <section v-for="tier in tiers" :key="tier.id" class="tier-card">
        <h4>{{ tier.tierLabel || tier.tierCode }}</h4>
        <dl>
          <div><dt>机型编码</dt><dd>{{ tier.tierCode }}</dd></div>
          <div><dt>设备尺寸</dt><dd>{{ tier.machineSize || '未填写' }}</dd></div>
          <div><dt>净重 / 毛重</dt><dd>{{ weightSummary(tier) }}</dd></div>
          <div><dt>包装尺寸</dt><dd>{{ tier.cartonSize || tier.woodBoxSize || '未填写' }}</dd></div>
        </dl>
      </section>
    </div>
    <MachineTierManager :visible="managerVisible" :model-id="modelId" :model-name="modelName" @close="managerVisible = false" @changed="handleTierChanged" />
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import MachineTierManager from './MachineTierManager.vue'
import { notify } from '@/composables/ui/useToast'
import { fetchMachineTiers, type CatalogMachineTier } from '@/api/catalog/machineTiers'

const props = defineProps<{ modelId: number; modelName?: string }>()
const emit = defineEmits<{ changed: [] }>()
const tiers = ref<CatalogMachineTier[]>([])
const loading = ref(false)
const managerVisible = ref(false)

function weightSummary(tier: CatalogMachineTier) {
  return [tier.netWeightKg && `净重 ${tier.netWeightKg}kg`, tier.grossWeightKg && `毛重 ${tier.grossWeightKg}kg`].filter(Boolean).join(' / ') || '未填写'
}
async function load() {
  loading.value = true
  try { tiers.value = await fetchMachineTiers(props.modelId) }
  catch (error) { notify.errorWithDetail('加载机型配置失败', error) }
  finally { loading.value = false }
}
async function handleTierChanged() { await load(); emit('changed') }
watch(() => props.modelId, load, { immediate: true })
</script>

<style scoped>
.machine-config-tab { min-height: 100%; padding: 20px; }
.config-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 18px; border: 1px solid var(--color-border); border-radius: var(--radius-xl); background: var(--color-surface); }
.config-header span { color: var(--color-primary); font-size: 12px; font-weight: 700; }
.config-header h3 { margin: 4px 0; font-size: 20px; }
.config-header p { margin: 0; color: var(--color-text-muted); font-size: 13px; }
.primary { padding: 9px 14px; border: 1px solid var(--color-primary); border-radius: var(--radius-md); background: var(--color-primary); color: #fff; cursor: pointer; }
.tier-list { display: grid; gap: 12px; margin-top: 16px; }
.tier-card { padding: 16px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); }
.tier-card h4 { margin: 0 0 12px; }
.tier-card dl { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 0; }
.tier-card dl div { display: flex; flex-direction: column; gap: 5px; padding: 12px; border-radius: var(--radius-lg); background: var(--color-surface-alt); }
.tier-card dt { color: var(--color-text-muted); font-size: 12px; }
.tier-card dd { margin: 0; }
.state { display: grid; min-height: 180px; place-items: center; color: var(--color-text-muted); }
@media (max-width: 800px) { .config-header { flex-direction: column; } .tier-card dl { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
