<template>
  <!-- 通用节点参考工期：两级参考体系里的「兜底」那一级。
       2026-10-22 从生产跟单页迁移到这里（生产产品与工期管理），读/写接口与交互完全不变。 -->
  <el-dialog
    :model-value="modelValue"
    title="通用节点参考工期"
    width="min(760px, 94vw)"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
    @open="load"
  >
    <p class="muted baseline-hint">
      通用参考是产品未发布参考天数时的兜底值：跟在制单的产品若有产品参考值就用产品的，没有才用这里的值，两者都没有则不推算该节点。
      参考完成时间＝按「一般情况下这道工序需要几天」逐节点推算，供跟单员判断进度是否偏慢；超参考未完成可记录原因，不影响保存。
      通用参考按跟单范围配置（封口机跟单 / 其他机器跟单），改完立即对全部在制单生效（参考值为派生、不落库，不保留旧判定）。
    </p>
    <div v-loading="baselineLoading" class="baseline-body">
      <section v-for="w in baselineWorkshops" :key="w.workshopKind" class="baseline-group">
        <h4>{{ w.workshopLabel }}</h4>
        <el-table :data="w.stages" size="small" border>
          <el-table-column prop="intervalLabel" label="节点区间" min-width="190" />
          <el-table-column label="参考天数" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.typicalDays" :min="0" :max="60" :step="0.5" size="small" controls-position="right" placeholder="不设" class="baseline-days" />
            </template>
          </el-table-column>
          <el-table-column label="现有来源" min-width="220">
            <template #default="{ row }">
              <span class="muted">{{ row.baselineNote || '未配置' }}<template v-if="row.sampleCount">（样本 {{ row.sampleCount }} 单）</template></span>
              <el-tag v-if="row.optionalStage" size="small" type="info" effect="plain" class="baseline-tag">按需工序</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
      <p v-if="!baselineLoading && !baselineWorkshops.length" class="muted">未获取到通用参考配置。</p>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="baselineSaving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { useTrackingBaseline } from '@/composables/tracking/useTrackingBaseline'

/**
 * `modelValue` 由父页面控制显隐；每次打开都重新拉取，避免显示上一次的旧值。
 * `saved` 在保存成功后触发，父页面据此重载基准矩阵（右栏「通用参考」列要跟着变）。
 */
defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const { baselineLoading, baselineSaving, baselineWorkshops, loadBaseline, saveBaseline } = useTrackingBaseline({
  reload: async () => undefined
})

function load() {
  void loadBaseline()
}

async function save() {
  const ok = await saveBaseline()
  if (!ok) return
  emit('update:modelValue', false)
  emit('saved')
}
</script>

<style scoped>
.baseline-hint { margin: 0 0 12px; line-height: 1.6; }
.baseline-body { min-height: 120px; }
.baseline-group { margin-bottom: 16px; }
.baseline-group:last-of-type { margin-bottom: 0; }
.baseline-group h4 { margin: 0 0 8px; font-size: 13px; font-weight: 600; }
.baseline-days { width: 130px; }
.baseline-tag { margin-left: 6px; }
</style>
