<template>
  <LqDialog
    :model-value="visible"
    title="地区写法别名"
    subtitle="把字典无法识别的历史写法登记到官方行政区，登记后分布与写入口立即按别名归一"
    size="md"
    mode="form"
    :loading="saving"
    confirm-text="登记写法"
    @close="requestClose"
    @confirm="doCreate"
    @cancel="requestClose"
  >
    <div class="region-alias">
      <div class="region-alias__form">
        <div class="form-grid">
          <div class="form-field">
            <label class="required">写法内容</label>
            <el-input
              v-model="form.alias"
              maxlength="60"
              placeholder="如：东昌府 / 南山 / 东莞（该写法的历史写法原文）"
            />
            <p v-if="seed && seed.alias" class="region-alias__hint">
              已从所选缺口带入写法「{{ seed.alias }}」，可修改后登记
            </p>
          </div>
          <div class="form-field">
            <label class="required">级别</label>
            <el-select v-model="form.kind" style="width: 100%" @change="onKindChange">
              <el-option label="省级写法（写法 → 官方省名）" value="PROVINCE" />
              <el-option label="地级市写法（写法 → 官方市名）" value="CITY" />
              <el-option label="区县写法（写法 → 官方区县名）" value="DISTRICT" />
            </el-select>
          </div>
          <div class="form-field form-field--span2">
            <label class="required">映射到官方行政区</label>
            <el-cascader
              v-model="regionPath"
              :options="chinaRegionOptions"
              clearable
              filterable
              :placeholder="regionCascaderPlaceholder"
              :props="{ expandTrigger: 'hover' }"
              style="width: 100%"
              @change="onRegionChange"
            />
            <p class="region-alias__hint">{{ regionRuleHint }}</p>
          </div>
          <div class="form-field form-field--span2">
            <label>备注</label>
            <el-input
              v-model="form.remark"
              maxlength="200"
              placeholder="选填：说明写法来源，如「历史导入写法」"
            />
          </div>
        </div>
      </div>

      <div class="region-alias__list">
        <div class="region-alias__list-head">
          <span>已登记别名（{{ aliases.length }}）</span>
          <span class="region-alias__list-tip">删除后分布/写入口回到登记前口径</span>
        </div>
        <div v-if="aliases.length === 0" class="region-alias__empty">
          暂无写法别名。先在上方登记一条「未收录」写法试试。
        </div>
        <div v-for="a in aliases" :key="a.id" class="region-alias__row">
          <span class="region-alias__kind" :class="`is-${a.kind.toLowerCase()}`">
            {{ kindLabel(a.kind) }}
          </span>
          <span class="region-alias__from">{{ a.alias }}</span>
          <span class="region-alias__arrow">→</span>
          <span class="region-alias__target">{{ a.target }}</span>
          <span v-if="a.province" class="region-alias__scope">（{{ a.province }}）</span>
          <span v-if="a.remark" class="region-alias__remark" :title="a.remark">{{ a.remark }}</span>
          <button
            type="button"
            class="region-alias__delete"
            :disabled="!canManage"
            title="删除该写法别名"
            @click="doDelete(a.id)"
          >
            删除
          </button>
        </div>
      </div>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  createCustomerRegionAlias,
  deleteCustomerRegionAlias,
  fetchCustomerRegionAliases,
  type RegionAliasItem
} from '@/api/customer'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { chinaRegionOptions, isMunicipalityLabel } from '@/utils/chinaRegion'
import LqDialog from '../common/LqDialog.vue'

export interface RegionAliasSeed {
  kind: 'CITY' | 'DISTRICT'
  alias: string
  province: string | null
}

const props = withDefaults(
  defineProps<{
    visible: boolean
    /** 从分布弹窗缺口带入：写法与归属省份预填 */
    seed?: RegionAliasSeed | null
    canManage?: boolean
  }>(),
  { seed: null, canManage: true }
)

const emit = defineEmits<{
  close: []
  changed: []
}>()

const aliases = ref<RegionAliasItem[]>([])
const loading = ref(false)
const saving = ref(false)
const regionPath = ref<(string | number)[] | null>(null)

const form = reactive<{
  kind: 'PROVINCE' | 'CITY' | 'DISTRICT'
  alias: string
  remark: string
}>({ kind: 'DISTRICT', alias: '', remark: '' })

const kindLabel = (k: string) =>
  k === 'PROVINCE' ? '省级' : k === 'CITY' ? '市级' : k === 'DISTRICT' ? '区县' : k

const regionCascaderPlaceholder = computed(() =>
  form.kind === 'PROVINCE'
    ? '选择省份（仅省级）'
    : form.kind === 'CITY'
      ? '选择 省 + 市'
      : '选择 省 + 市 + 区县'
)

const regionRuleHint = computed(() => {
  if (form.kind === 'PROVINCE') return '只选择省份一级，如「广东省」。'
  if (form.kind === 'CITY') return '城市写法须归属明确省份，如「东莞市」写法归属「广东省」。'
  return '区县写法须归属明确的省和市，如「南山区」归属「广东省 · 深圳市」。'
})

/** 按所选级别从级联路径中取（省/市/区县），与客户表单口径一致（直辖市市名=省名）。 */
function regionFromPath(path: (string | number)[] | null) {
  const p = (path ?? []).map(String)
  if (!p.length) return { province: null, city: null, district: null }
  const province = p[0]
  if (isMunicipalityLabel(province)) {
    return { province, city: province, district: p.length > 1 ? p[p.length - 1] : null }
  }
  return { province, city: p.length > 1 ? p[1] : null, district: p.length > 2 ? p[2] : null }
}

function onRegionChange() {
  // 区县别名依赖省份作用域，级联已自带；无需额外联动
}

function onKindChange() {
  regionPath.value = null
}

function requestClose() {
  emit('close')
}

async function loadAliases() {
  loading.value = true
  try {
    aliases.value = await fetchCustomerRegionAliases()
  } catch {
    notify?.('别名列表加载失败', 'error')
  } finally {
    loading.value = false
  }
}

function resetForm(seed: RegionAliasSeed | null) {
  regionPath.value = null
  if (seed) {
    form.kind = seed.kind
    form.alias = seed.alias
  } else {
    form.kind = 'DISTRICT'
    form.alias = ''
  }
  form.remark = ''
}

watch(
  () => props.visible,
  visible => {
    if (!visible) return
    resetForm(props.seed)
    void loadAliases()
  },
  { immediate: true }
)

async function doCreate() {
  const alias = form.alias.trim()
  if (!alias) {
    notify?.('请填写写法内容', 'error')
    return
  }
  const { province, city, district } = regionFromPath(regionPath.value)
  let target = ''
  if (form.kind === 'PROVINCE') target = province ?? ''
  else if (form.kind === 'CITY') target = city ?? ''
  else target = district ?? ''
  if (!target) {
    notify?.('请按所选级别选择完整的官方行政区', 'error')
    return
  }
  if (form.kind === 'DISTRICT' && !province) {
    notify?.('区县写法别名必须同时选择归属省份', 'error')
    return
  }
  if (form.kind === 'CITY' && province && isMunicipalityLabel(province)) {
    notify?.('直辖市城市名与省名相同（官方写法），无需登记市级写法别名', 'error')
    return
  }
  saving.value = true
  try {
    await createCustomerRegionAlias({
      kind: form.kind,
      alias,
      province: form.kind === 'DISTRICT' ? province : null,
      target,
      remark: form.remark.trim() || undefined
    })
    notify?.('写法别名已登记，分布与写入口已按别名归一', 'success')
    resetForm(null)
    await loadAliases()
    emit('changed')
  } catch (err: any) {
    notify?.(err?.message || '登记失败', 'error')
  } finally {
    saving.value = false
  }
}

async function doDelete(id: number) {
  const item = aliases.value.find(a => a.id === id)
  const ok = await askConfirm({
    title: '删除写法别名',
    message: item
      ? `删除后，「${item.alias}」将不再映射到「${item.target}」，分布回到登记前口径。确定删除？`
      : '确定删除该写法别名？',
    danger: true
  })
  if (ok !== true) return
  try {
    await deleteCustomerRegionAlias(id)
    notify?.('写法别名已删除', 'success')
    await loadAliases()
    emit('changed')
  } catch (err: any) {
    notify?.(err?.message || '删除失败', 'error')
  }
}
</script>

<style scoped>
.region-alias {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.region-alias__form {
  border: 1px solid var(--color-border-light);
  border-radius: 10px;
  padding: 14px;
}
.region-alias__hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
.region-alias__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 280px;
  overflow: auto;
}
.region-alias__list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #606266;
  font-weight: 600;
}
.region-alias__list-tip {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}
.region-alias__empty {
  padding: 22px 12px;
  text-align: center;
  color: #909399;
  font-size: 13px;
  border: 1px dashed #e5e7eb;
  border-radius: 8px;
}
.region-alias__row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid #f0f1f3;
  border-radius: 8px;
  font-size: 13px;
  background: #fafbfc;
}
.region-alias__kind {
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
  background: #eef1f6;
  color: #606266;
}
.region-alias__kind.is-city {
  background: #ecf5ff;
  color: #409eff;
}
.region-alias__kind.is-district {
  background: #f0f9eb;
  color: #67c23a;
}
.region-alias__kind.is-province {
  background: #fdf6ec;
  color: #e6a23c;
}
.region-alias__from {
  font-weight: 600;
}
.region-alias__arrow {
  color: #c0c4cc;
}
.region-alias__target {
  color: #303133;
}
.region-alias__scope {
  color: #909399;
  font-size: 12px;
}
.region-alias__remark {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
  color: #909399;
  font-size: 12px;
}
.region-alias__delete {
  flex-shrink: 0;
  border: none;
  background: none;
  color: #f56c6c;
  cursor: pointer;
  font-size: 12px;
  padding: 2px 4px;
}
.region-alias__delete:hover {
  text-decoration: underline;
}
.region-alias__delete:disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}
</style>
