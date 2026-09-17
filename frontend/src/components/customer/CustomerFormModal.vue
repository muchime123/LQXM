<template>
  <LqDialog
    :model-value="visible"
    :title="isCreateMode ? '新增客户' : '编辑客户档案'"
    :subtitle="isCreateMode ? '建立客户主档案' : (data?.customerCode ? data.customerCode : '')"
    size="lg"
    :close-on-click-modal="false"
    destroy-on-close
    mode="form"
    :loading="saving"
    :dirty="dirty"
    :before-close="handleBeforeClose"
    confirm-text="保存"
    @close="requestClose"
    @confirm="doSave"
    @cancel="requestClose"
  >
    <div class="customer-form">
      <FormSection title="基本信息" description="客户身份与分类" :required-count="1">
        <div class="form-grid">
          <div class="form-field form-field--full">
            <label class="required">客户名称</label>
            <el-input v-model="form.customerName" placeholder="客户正式名称，如：嘉兴市四季水产食品有限公司" />
          </div>
          <div class="form-field">
            <label>客户简称</label>
            <el-input v-model="form.shortName" placeholder="如：四季水产" />
          </div>
          <div class="form-field">
            <label>客户类型</label>
            <el-select v-model="form.customerType" style="width: 100%">
              <el-option
v-for="t in typeOptions"
:key="t"
:label="t"
:value="t" />
            </el-select>
          </div>
          <div class="form-field">
            <label>所属行业</label>
            <el-input v-model="form.industry" placeholder="如：水产食品 / 食品加工" />
          </div>
          <div class="form-field">
            <label>客户来源</label>
            <el-input v-model="form.source" placeholder="如：展会 / 老客户介绍 / 官网询盘" />
          </div>
        </div>
      </FormSection>

      <FormSection title="资质与地址" description="企业主体信息与联系地址">
        <div class="form-grid">
          <div class="form-field">
            <label>统一社会信用代码</label>
            <el-input v-model="form.creditCode" placeholder="18 位信用代码" />
          </div>
          <div class="form-field form-field--span2">
            <label>省市区县</label>
            <el-cascader
              v-model="regionPath"
              :options="chinaRegionOptions"
              clearable
              filterable
              placeholder="请选择省市区县（可输入关键字搜索）"
              :props="{ expandTrigger: 'hover' }"
              style="width: 100%"
              @change="handleRegionChange"
            />
          </div>
          <div class="form-field form-field--full">
            <label>公司地址</label>
            <div class="address-row">
              <el-input
                v-model="form.address"
                clearable
                placeholder="详细地址，可点击右侧按钮解析出省市区县"
              />
              <el-button
                :loading="parsing"
                :disabled="!form.address || !form.address.trim()"
                @click="parseFromAddress"
              >
                解析省市区
              </el-button>
            </div>
          </div>
          <div class="form-field form-field--full">
            <label>官网</label>
            <el-input v-model="form.website" placeholder="https://" />
          </div>
        </div>
      </FormSection>

      <FormSection
        v-if="!isCreateMode"
        title="销售出库参考"
        description="来自氚云销售出库台账，可一键带入档案字段"
      >
        <div v-if="refLoading" class="ref-hint">正在读取销售出库数据…</div>
        <div v-else-if="!reference || reference.orderCount === 0" class="ref-hint">
          该客户暂无销售出库记录，地区与联系方式需人工填写
        </div>
        <div v-else class="ref-body">
          <div class="ref-summary">
            共 <b>{{ reference.orderCount }}</b> 单出库
            <template v-if="reference.lastShipDate">· 最近出库 {{ reference.lastShipDate }}</template>
            <template v-if="reference.lastShippingMethod">· 最近发货方式「{{ reference.lastShippingMethod }}」</template>
          </div>
          <div class="ref-row">
            <span class="ref-row__label">出库单地区</span>
            <div class="ref-row__values">
              <span v-if="!reference.regions.length" class="ref-empty">—</span>
              <span v-for="r in reference.regions" :key="r" class="ref-chip">{{ r }}</span>
            </div>
            <el-button
              size="small"
              :disabled="!reference.suggestedProvince"
              :title="reference.suggestedProvince
                ? `把省份写为「${reference.suggestedProvince}」`
                : '地区标注无法定出唯一省份，请人工填写'"
              @click="applySuggestedProvince"
            >
              带入省份
            </el-button>
          </div>
          <div class="ref-row">
            <span class="ref-row__label">收货地址</span>
            <div class="ref-row__values">
              <span v-if="!reference.addresses.length" class="ref-empty">
                无（自提一类运输方式不产生收货地址）
              </span>
              <button
                v-for="a in reference.addresses"
                :key="a"
                type="button"
                class="ref-address"
                :title="`带入为档案地址：${a}`"
                @click="applyAddress(a)"
              >
                {{ a }}
              </button>
            </div>
          </div>
          <div class="ref-row">
            <span class="ref-row__label">联系电话</span>
            <div class="ref-row__values">
              <span v-if="!reference.contactPhones.length" class="ref-empty">—</span>
              <span v-for="p in reference.contactPhones" :key="p" class="ref-chip">{{ p }}</span>
            </div>
            <span />
          </div>
          <div class="ref-row">
            <span class="ref-row__label">经销商</span>
            <div class="ref-row__values">
              <span v-if="!reference.dealerNames.length" class="ref-empty">—</span>
              <span v-for="d in reference.dealerNames" :key="d" class="ref-chip">{{ d }}</span>
            </div>
            <span />
          </div>
        </div>
      </FormSection>

      <FormSection title="客户关系" description="关系状态与启用状态（业务员关系在详情页维护）">
        <div class="form-grid">
          <div class="form-field">
            <label>关系状态</label>
            <el-select v-model="form.relationStatus" style="width: 100%">
              <el-option
v-for="s in relationOptions"
:key="s"
:label="s"
:value="s" />
            </el-select>
          </div>
          <div class="form-field">
            <label>客户等级</label>
            <el-select v-model="form.level" style="width: 100%">
              <el-option label="普通" value="COMMON" />
              <el-option label="重点" value="KEY" />
            </el-select>
          </div>
          <div class="form-field">
            <label>启用状态</label>
            <el-select v-model="form.status" style="width: 100%">
              <el-option label="启用" value="启用" />
              <el-option label="停用" value="停用" />
            </el-select>
          </div>
        </div>
      </FormSection>

      <FormSection title="备注" description="补充说明">
        <div class="form-grid">
          <div class="form-field form-field--full">
            <label>备注</label>
            <el-input
v-model="form.remark"
type="textarea"
:rows="3"
placeholder="客户背景、合作情况等补充说明" />
          </div>
        </div>
      </FormSection>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { createCustomer, updateCustomer, parseCustomerAddress, fetchCustomerOutboundReference } from '../../api/customer'
import type { CustomerOutboundReference } from '../../api/customer'
import { clearDirty, notify, setDirty } from '../../composables'
import type { CustomerDetail, CustomerFilterOptions } from '../../types/customer'
import FormSection from '../common/FormSection.vue'
import LqDialog from '../common/LqDialog.vue'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
import { chinaRegionOptions, isMunicipalityLabel, resolveRegionPath } from '../../utils/chinaRegion'

const props = withDefaults(
  defineProps<{
    visible: boolean
    data: CustomerDetail | null
    isCreateMode: boolean
    isEditMode: boolean
    filterOptions?: CustomerFilterOptions | null
    /** 是否可维护客户档案（customer.manage 权限），默认放行以兼容旧用法 */
    canManage?: boolean
  }>(),
  { filterOptions: null, canManage: true }
)

const emit = defineEmits<{
  close: []
  save: [payload: Record<string, unknown>]
}>()

const typeOptions = computed(() => props.filterOptions?.customerTypes?.length
  ? props.filterOptions.customerTypes
  : ['企业', '合作社', '农场', '个体', '个人', '其他'])
const relationOptions = computed(() => props.filterOptions?.relationStatuses?.length
  ? props.filterOptions.relationStatuses
  : ['正常', '重点关注', '停止合作', '禁止联系'])
/** 级联选择结果写回省/市/区县三字段；直辖市约定「市」与省同名（如北京市）。 */
function handleRegionChange(path: (string | number)[] | null) {
  const p = (path ?? []).map(String)
  if (!p.length) {
    form.province = ''
    form.city = ''
    form.district = ''
  } else {
    const province = p[0]
    form.province = province
    if (isMunicipalityLabel(province)) {
      form.city = province
      form.district = p.length > 1 ? p[p.length - 1] : ''
    } else {
      form.city = p.length > 1 ? p[1] : ''
      form.district = p.length > 2 ? p[2] : ''
    }
  }
  syncRegionPath()
}

const form = reactive<Record<string, any>>({
  customerName: '',
  shortName: '',
  customerType: '企业',
  industry: '',
  creditCode: '',
  province: '',
  city: '',
  district: '',
  address: '',
  website: '',
  source: '',
  level: 'COMMON',
  relationStatus: '正常',
  status: '启用',
  remark: ''
})

const regionPath = ref<string[]>([])

function syncRegionPath() {
  regionPath.value = resolveRegionPath(form.province, form.city, form.district)
}

watch(() => [form.province, form.city, form.district], syncRegionPath, { immediate: true })

const original = ref('')
const dirty = ref(false)
const saving = ref(false)
const parsing = ref(false)
const reference = ref<CustomerOutboundReference | null>(null)
const refLoading = ref(false)

/** 读取该客户的销售出库参考信息（出库单地区/地址/电话/经销商），仅编辑模式加载。 */
async function loadOutboundReference(customerId?: number) {
  reference.value = null
  if (props.isCreateMode || !customerId) return
  refLoading.value = true
  try {
    reference.value = await fetchCustomerOutboundReference(customerId)
  } catch {
    // 参考信息读取失败不阻断编辑主流程，静默降级为「暂无参考」
    reference.value = null
  } finally {
    refLoading.value = false
  }
}

/** 把出库单地区标注定出的省份带入档案（不覆盖已填写的合法省份，由用户先行清空再带入）。 */
function applySuggestedProvince() {
  const province = reference.value?.suggestedProvince
  if (!province) return
  const current = String(form.province || '')
  if (current && current !== province) {
    form.province = province
    form.city = ''
    form.district = ''
    notify(`省份已由「${current}」改为「${province}」，请补充市/区县`, 'warning')
    return
  }
  form.province = province
  notify(`已带入省份「${province}」`, 'success')
}

/** 把出库单收货地址带入档案地址，并立即尝试解析省市区县。 */
async function applyAddress(address: string) {
  if (!address) return
  form.address = address
  await parseFromAddress()
}

function snapshot(): string {
  return JSON.stringify({ ...form })
}

watch(
  () => props.visible,
  visible => {
    if (!visible) {
      dirty.value = false
      clearDirty('客户编辑')
      return
    }
    const d = props.data
    Object.keys(form).forEach(k => delete form[k])
    if (props.isCreateMode) {
      Object.assign(form, {
        customerName: '',
        shortName: '',
        customerType: '企业',
        industry: '',
        creditCode: '',
        province: '',
        city: '',
        district: '',
        address: '',
        website: '',
        source: '',
        level: 'COMMON',
        relationStatus: '正常',
        status: '启用',
        remark: ''
      })
    } else if (d) {
      Object.assign(form, {
        customerName: d.customerName || '',
        shortName: d.shortName || '',
        customerType: d.customerType || '企业',
        industry: d.industry || '',
        creditCode: d.creditCode || '',
        province: d.province || '',
        city: d.city || '',
        district: d.district || '',
        address: d.address || '',
        website: d.website || '',
        source: d.source || '',
        level: d.level || 'COMMON',
        relationStatus: d.relationStatus || '正常',
        status: d.status || '启用',
        remark: d.remark || ''
      })
    }
    original.value = snapshot()
    dirty.value = false
    void loadOutboundReference(d?.id)
  },
  { immediate: true }
)

watch(
  form,
  () => {
    dirty.value = snapshot() !== original.value
    if (dirty.value) setDirty('客户编辑')
    else clearDirty('客户编辑')
  },
  { deep: true }
)

function resetDirty() {
  original.value = snapshot()
  dirty.value = false
  clearDirty('客户编辑')
}

function handleBeforeClose(done: () => void) {
  if (!dirty.value) {
    done()
    return
  }
  askConfirm({
    title: '未保存的修改',
    message: '当前有未保存的修改，确定要关闭吗？关闭后修改将丢失。',
    confirmText: '关闭',
    cancelText: '留下',
    danger: true
  }).then(ok => {
    if (ok) done()
  })
}

function requestClose() {
  if (dirty.value) {
    handleBeforeClose(() => emit('close'))
  } else {
    emit('close')
  }
}

/**
 * 公司地址 → 省市区县 解析回填（复用后端 ChinaAddressParser）。
 * 点击「解析省市区」表示“以地址为准重新推导”，故解析出非空结果时会覆盖当前手填的同名项；
 * 解析不出（如直辖市市/区县缺省、地址过短）的层级保持原值不动，避免误删。
 */
async function parseFromAddress() {
  const address = String(form.address || '').trim()
  if (!address) {
    notify('请先填写公司地址', 'warning')
    return
  }
  parsing.value = true
  try {
    const r = await parseCustomerAddress(address)
    const steps: Array<[keyof typeof r, string]> = [
      ['province', '省'],
      ['city', '市'],
      ['district', '区县']
    ]
    const applied: string[] = []
    for (const [key, label] of steps) {
      const parsed = r[key]
      if (!parsed) continue
      const current = String(form[key] || '')
      form[key] = parsed
      applied.push(current ? `${label}(${current} → ${parsed})` : `${label}(${parsed})`)
    }
    if (applied.length === 0) {
      if (r.province || r.city || r.district) {
        notify('省市区县与解析结果一致，无需更新', 'info')
      } else {
        notify('未能从该地址识别出省市区县，请人工填写', 'warning')
      }
    } else {
      notify(`已按地址更新：${applied.join('、')}`, 'success')
    }
  } catch (err) {
    notify.errorWithDetail('地址解析失败', err)
  } finally {
    parsing.value = false
  }
}

async function doSave() {
  if (!props.canManage) {
    notify('您没有维护客户档案的权限', 'warning')
    return
  }
  if (!form.customerName || !form.customerName.trim()) {
    notify('请填写客户名称', 'warning')
    return
  }
  saving.value = true
  try {
    const payload = { ...form }
    if (props.isCreateMode) {
      await createCustomer(payload)
    } else {
      await updateCustomer(props.data!.id, payload)
    }
    resetDirty()
    emit('save', payload)
    notify('保存成功', 'success')
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.customer-form {
  display: grid;
  gap: var(--space-form-item-gap);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-control-gap-wide);
}

.form-field {
  display: grid;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

.form-field--full {
  grid-column: 1 / -1;
}

.form-field--span2 {
  grid-column: span 2;
}

.address-row {
  display: flex;
  gap: var(--space-control-gap-tight);
}

.ref-hint {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  line-height: var(--lh-normal);
}

.ref-body {
  display: grid;
  gap: var(--space-control-gap-tight);
}

.ref-summary {
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
}

.ref-row {
  display: grid;
  grid-template-columns: 84px 1fr auto;
  align-items: start;
  gap: var(--space-control-gap-wide);
}

.ref-row__label {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  padding-top: 4px;
}

.ref-row__values {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

.ref-chip {
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
  padding: 2px 8px;
}

.ref-empty {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  line-height: 1.8;
}

.ref-address {
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: var(--fs-xs);
  line-height: 1.6;
  max-width: 100%;
  padding: 3px 8px;
  text-align: left;
  word-break: break-all;
}

.ref-address:hover {
  border-color: var(--color-border-strong);
  color: var(--color-text-primary);
}

.address-row .el-input {
  flex: 1;
  min-width: 0;
}

.form-field label {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
}

.form-field label.required::before {
  content: '* ';
  color: var(--color-danger);
}

.form-field .el-input,
.form-field .el-select,
.form-field .el-textarea {
  width: 100%;
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
