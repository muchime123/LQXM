<template>
  <section v-if="auth.can('manufacturing.baseline.manage')" class="tracking-benchmarks">
    <header><div><h2>生产跟单 · 产品节点参考间隔</h2><p>按产品工期分组设置自然日；空值沿用车间基准。这里只影响参考日期，不改变氚云工序或跟单节点。</p></div></header>
    <el-select v-model="selectedGroup" filterable placeholder="选择产品工期分组" class="group-select">
      <el-option v-for="group in groups" :key="group.id" :label="group.groupName" :value="group.id" />
    </el-select>
    <div v-if="selected"><el-button :loading="calculating" @click="refreshCandidates">计算历史样本</el-button><span class="hint">候选 P50 / P75 仅供审核，点击单行保存后才参与跟单参考日期。</span></div>
    <el-table v-if="selected" :data="stageRows" stripe size="small">
      <el-table-column prop="stageLabel" label="跟单节点" min-width="120" />
      <el-table-column prop="intervalLabel" label="参考区间" min-width="170" />
      <el-table-column label="车间基准" width="110"><template #default="{ row }">{{ row.workshopDays ?? '未设置' }} 天</template></el-table-column>
      <el-table-column label="本产品组参考间隔（天）" min-width="190">
        <template #default="{ row }"><el-input-number v-model="row.typicalDays" :min="0" :max="60" :precision="1" :step="0.5" controls-position="right" size="small" placeholder="沿用车间" /></template>
      </el-table-column>
      <el-table-column label="历史候选" min-width="190"><template #default="{ row }">{{ row.sampleCount ? `P50 ${row.p50Days} 天 / P75 ${row.p75Days} 天 · ${row.sampleCount} 段` : '暂无有效样本' }}<small v-if="row.excludedCount"> · 排除异常 {{ row.excludedCount }} 段</small></template></el-table-column>
      <el-table-column label="操作" width="95"><template #default="{ row }"><el-button link type="primary" :loading="saving === row.stageCode" @click="save(row)">保存</el-button></template></el-table-column>
    </el-table>
    <el-empty v-else description="请先在任务单产品与配置中建立工期分组" :image-size="52" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import type { TrackingBenchmarkWorkshop } from '@/api/productionTracking'

interface Group { id: number; groupName: string; workshopKind: string; active: boolean }
interface Saved { groupId: number; stageCode: string | null; typicalDays: number | null; sampleCount: number | null; p50Days: number | null; p75Days: number | null; excludedCount: number | null }
interface StageRow { stageCode: string; stageLabel: string; intervalLabel: string; workshopDays: number | null; typicalDays: number | null; sampleCount: number; p50Days: number | null; p75Days: number | null; excludedCount: number }
const auth = useCurrentUser()
const groups = ref<Group[]>([])
const selectedGroup = ref<number | null>(null)
const workshops = ref<TrackingBenchmarkWorkshop[]>([])
const saved = ref<Saved[]>([])
const saving = ref<string | null>(null)
const calculating = ref(false)
const selected = computed(() => groups.value.find(group => group.id === selectedGroup.value))
const stageRows = computed<StageRow[]>(() => {
  if (!selected.value) return []
  const workshop = workshops.value.find(item => item.workshopKind === selected.value?.workshopKind)
  return (workshop?.stages ?? []).map(stage => {
    const value = saved.value.find(item => item.groupId === selectedGroup.value && item.stageCode === stage.stageCode)
    return { stageCode: stage.stageCode, stageLabel: stage.stageLabel, intervalLabel: stage.intervalLabel,
      workshopDays: stage.typicalDays, typicalDays: value?.typicalDays ?? null, sampleCount: value?.sampleCount ?? 0,
      p50Days: value?.p50Days ?? null, p75Days: value?.p75Days ?? null, excludedCount: value?.excludedCount ?? 0 }
  })
})
async function load() {
  const [groupRes, matrixRes, savedRes] = await Promise.all([
    http.get<Group[]>('/manufacturing/master/duration-groups'),
    http.get<{ workshops: TrackingBenchmarkWorkshop[] }>('/manufacturing/followups/node-benchmarks'),
    http.get<Saved[]>('/manufacturing/followups/product-node-benchmarks')
  ])
  groups.value = groupRes.data.filter(group => group.active)
  workshops.value = matrixRes.data.workshops
  saved.value = savedRes.data
  if (!groups.value.some(group => group.id === selectedGroup.value)) selectedGroup.value = groups.value[0]?.id ?? null
}
async function save(row: StageRow) {
  if (selectedGroup.value == null) return
  saving.value = row.stageCode
  try {
    await http.put('/manufacturing/followups/product-node-benchmarks', {
      groupId: selectedGroup.value, stageCode: row.stageCode, typicalDays: row.typicalDays
    })
    await load()
    ElMessage.success('产品节点参考间隔已更新')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') }
  finally { saving.value = null }
}
async function refreshCandidates() {
  if (selectedGroup.value == null) return
  calculating.value = true
  try {
    await http.post(`/manufacturing/followups/product-node-benchmarks/${selectedGroup.value}/refresh-candidates`, {})
    await load()
    ElMessage.success('历史候选已更新，已发布的参考值未改变')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '计算历史样本失败') }
  finally { calculating.value = false }
}
onMounted(() => { if (auth.can('manufacturing.baseline.manage')) void load() })
</script>

<style scoped>
.tracking-benchmarks { padding: 16px; border: 1px solid var(--color-border, #e2e8f0); border-radius: 12px; background: var(--color-surface, #fff); display: grid; gap: 14px; }
.tracking-benchmarks header h2 { margin: 0 0 4px; font-size: 17px; }
.tracking-benchmarks header p { margin: 0; color: var(--color-text-secondary, #64748b); font-size: 12px; }
.group-select { width: min(340px, 100%); }
.hint { margin-left: 12px; color: var(--color-text-secondary, #64748b); font-size: 12px; }
</style>
