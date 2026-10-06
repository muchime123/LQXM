<template>
  <el-dialog v-model="open" title="产品配置与命名规则" width="min(1100px, 95vw)" class="config-rule-dialog">
    <template v-if="rule">
      <!-- 产品信息 -->
      <el-descriptions :column="4" border size="small" class="rule-header">
        <el-descriptions-item label="产品">{{ rule.labels.productName }}</el-descriptions-item>
        <el-descriptions-item label="编码">{{ rule.labels.productCode }}</el-descriptions-item>
        <el-descriptions-item label="版本">版本 {{ rule.version }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="rule.status === 'PUBLISHED' ? 'success' : 'info'">{{ rule.status }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert class="rule-banner" title="保存为草稿后可试算名称；发布后新建单据使用该版本，历史配置保留原版本。预览不会建立库存产品。" type="info" :closable="false" show-icon />

      <!-- 基础配置 -->
      <section class="rule-section">
        <header class="rule-section__head">
          <h3>基础配置</h3>
          <span class="rule-section__hint">切换产品类型会重置必填项</span>
        </header>
        <el-form label-width="100px" :disabled="busy">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="产品类型">
                <el-select v-model="rule.definition.productType" style="width:100%">
                  <el-option label="机器" value="MACHINE"/>
                  <el-option label="模具" value="MOLD"/>
                  <el-option label="配件" value="PART"/>
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="名称含电气">
                <el-switch v-model="rule.definition.includeElectrical"/>
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="必填项目">
            <el-checkbox-group v-model="rule.definition.requiredFields" class="rule-required-fields">
              <el-checkbox v-for="key in applicableFields" :key="key" :value="key" :disabled="mandatoryFields.includes(key)">{{ PRODUCTION_CONFIG_FIELDS[key].label }}</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
        </el-form>
      </section>

      <!-- 配置项规则 -->
      <section class="rule-section">
        <header class="rule-section__head"><h3>配置项规则</h3></header>
        <el-table :data="rule.definition.options" max-height="280" size="small" border>
          <el-table-column label="配置项" min-width="140"><template #default="{row}">{{ rule.labels.optionNames[row.optionId] }}</template></el-table-column>
          <el-table-column label="分组" width="150"><template #default="{row}"><el-select v-model="row.groupCode" size="small" style="width:100%"><el-option v-for="(label,key) in groups" :key="key" :label="label" :value="key"/></el-select></template></el-table-column>
          <el-table-column label="进入名称" width="100" align="center"><template #default="{row}"><el-switch v-model="row.technical" :disabled="row.groupCode === 'PACKAGING'" size="small"/></template></el-table-column>
          <el-table-column label="互斥组（同组仅选一项）" min-width="180"><template #default="{row}"><el-input v-model="row.exclusiveGroup" maxlength="50" size="small" placeholder="留空表示无互斥"/></template></el-table-column>
        </el-table>
      </section>

      <!-- 试算配置 -->
      <section class="rule-section">
        <header class="rule-section__head">
          <h3>试算配置</h3>
          <span class="rule-section__hint">填写后先保存草稿，再预览</span>
        </header>
        <el-form label-width="110px" :disabled="busy">
          <el-row :gutter="16">
            <el-col v-for="key in sampleFields" :key="key" :xs="24" :sm="key === 'configNote' || key === 'boxShape' || key === 'voltage' || key === 'plug' || key === 'brand' ? 24 : 12" :lg="key === 'configNote' || key === 'boxShape' || key === 'voltage' || key === 'plug' || key === 'brand' ? 24 : 8">
              <el-form-item :label="PRODUCTION_CONFIG_FIELDS[key].label" :required="rule.definition.requiredFields.includes(key)" :error="result?.fieldErrors[key]">
                <ProductionConfigurationField v-model="sample[key]" :field="key" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="试算选配">
            <el-select v-model="optionIds" multiple placeholder="选择试算选配" style="width:100%">
              <el-option v-for="o in rule.definition.options" :key="o.optionId" :value="o.optionId" :label="rule.labels.optionNames[o.optionId]"/>
            </el-select>
          </el-form-item>
        </el-form>
        <div class="rule-preview-actions">
          <el-button :loading="busy" @click="preview">预览已保存规则</el-button>
        </div>
        <el-alert
          v-if="result"
          class="rule-preview-result"
          :title="result.namePreview || '名称待完善'"
          :description="[result.specificationPreview, ...Object.values(result.fieldErrors), ...result.confirmationItems].join('；')"
          :type="result.completionState === 'COMPLETE' ? 'success' : 'warning'"
          :closable="false"
          show-icon
        />
      </section>

      <!-- 版本历史 -->
      <section class="rule-section" v-if="versions.length">
        <header class="rule-section__head"><h3>版本历史</h3></header>
        <div class="rule-versions">
          <el-tag v-for="v in versions" :key="v.id" size="small" :type="v.status === 'PUBLISHED' ? 'success' : 'info'">
            版本 {{ v.version }} · {{ v.status }} · {{ v.publishedAt || '尚未发布' }}
          </el-tag>
        </div>
      </section>
    </template>
    <template #footer>
      <el-button @click="open=false">关闭</el-button>
      <el-button :loading="busy" @click="save">保存草稿</el-button>
      <el-button type="primary" :loading="busy" @click="publish">保存并发布</el-button>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { ConfigRule, NamePreview } from '@/api/productConfiguration'
import ProductionConfigurationField from './ProductionConfigurationField.vue'
import { PRODUCTION_CONFIG_FIELDS, configurationFieldsForType, configurationTrialFields } from '@/constants/productionConfiguration'
const open = ref(false), busy = ref(false), rule = ref<ConfigRule>(), result = ref<NamePreview>()
const versions = ref<Array<{id:number;version:number;status:string;publishedAt:string}>>([])
const sample = ref<Record<string,string>>({}), optionIds = ref<number[]>([])
const applicableFields = computed(() => configurationFieldsForType(rule.value?.definition.productType || 'MACHINE'))
const mandatoryFields = computed(() => rule.value?.definition.productType === 'PART' ? ['specificName','partSpecification'] : rule.value?.definition.productType === 'MOLD' ? ['compatibleModel','moldType','boxShape','moldArrangement','drawingVersion'] : ['boxShape','moldArrangement'])
const groups = {PROCESS:'工艺',FEEDING:'送料',FILM:'薄膜',FILLING:'灌装',CODING:'打码',STRUCTURE:'结构',ACCESSORY:'附件',PACKAGING:'包装',UNCLASSIFIED:'待分类'}
watch(() => rule.value?.definition.productType, (kind, previous) => {
  if(!rule.value || !previous || kind===previous)return
  rule.value.definition.requiredFields=kind==='PART'?['specificName','partSpecification']:kind==='MOLD'?['compatibleModel','moldType','boxShape','moldArrangement','drawingVersion']:['boxShape','boxHeight','moldArrangement','boxMaterial','voltage','plug','brand']
  result.value=undefined
})
const sampleFields = computed(() => configurationTrialFields(rule.value?.definition.productType || 'MACHINE', sample.value.boxShape || ''))
watch(() => JSON.stringify([sample.value, optionIds.value, rule.value?.definition]), () => { result.value=undefined })
async function show(id:number) { busy.value=true; result.value=undefined; sample.value={boxShape:'RECT',extraMold:'no'}; optionIds.value=[]; try { rule.value=(await http.post<ConfigRule>(`/product-configurations/products/${id}/rules`)).data; await history(); open.value=true } finally { busy.value=false } }
async function history() { if(rule.value) versions.value=(await http.get(`/product-configurations/products/${rule.value.baseProductId}/rules`)).data }
async function save() { if(!rule.value)return; busy.value=true; try { const r=rule.value; await http.put(`/product-configurations/products/${r.baseProductId}/rules/${r.id}`,{revision:r.revision,definition:r.definition}); r.revision++; result.value=undefined; ElMessage.success('草稿已保存') } finally {busy.value=false} }
async function preview() { if(!rule.value)return; busy.value=true; try {const r=rule.value; const allowed=Object.fromEntries(sampleFields.value.map(key=>[key,sample.value[key] || ''])); result.value=(await http.post<NamePreview>(`/product-configurations/products/${r.baseProductId}/rules/${r.id}/preview`,{...allowed,extraMold:sample.value.extraMold === 'yes',optionIds:optionIds.value})).data}finally{busy.value=false} }
async function publish() {await save(); if(!rule.value)return; busy.value=true; try {const r=rule.value; await http.post(`/product-configurations/products/${r.baseProductId}/rules/${r.id}/publish`,{revision:r.revision}); ElMessage.success('规则已发布'); open.value=false}finally{busy.value=false} }
defineExpose({show})
</script>
<style scoped>
.config-rule-dialog :deep(.el-dialog__body) {
  padding-top: var(--space-md-lg);
}
.rule-header {
  margin-bottom: var(--space-md-lg);
}
.rule-banner {
  margin-bottom: 0;
}
.rule-section {
  margin-top: var(--space-section-gap);
}
.rule-section__head {
  display: flex;
  align-items: baseline;
  gap: var(--space-control-gap);
  margin-bottom: var(--space-md-lg);
  padding-bottom: var(--space-sm-md);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.rule-section__head h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.rule-section__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rule-required-fields {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap-tight) var(--space-control-gap);
}
.rule-preview-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: var(--space-md-lg);
}
.rule-versions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap);
}
</style>
