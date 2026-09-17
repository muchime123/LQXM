<template>
  <LqDialog
    :model-value="visible"
    title="编辑公司信息"
    size="lg"
    :close-on-click-modal="false"
    destroy-on-close
    mode="form"
    :loading="saving"
    confirm-text="保存"
    cancel-text="取消"
    @update:model-value="handleVisibleUpdate"
    @confirm="onSave"
    @cancel="onCancel"
    @close="onClose"
  >
    <LqTabs v-model="editDialogActiveTab">
      <el-tab-pane label="基本信息" name="basic">
        <FormSection title="公司标识" description="公司识别、归档与中英文对照信息。" :required-count="1">
          <div class="edit-form-grid">
            <div class="edit-form-field">
              <label class="required">公司名称</label>
              <input v-model="editForm.companyName" />
            </div>
            <div class="edit-form-field">
              <label>曾用名</label>
              <input v-model="editForm.formerName" placeholder="温州利强包装机械有限公司" />
            </div>
            <div class="edit-form-field">
              <label>英文名称</label>
              <input v-model="editForm.companyNameEn" />
            </div>
          </div>
        </FormSection>
        <FormSection title="品牌信息" description="对外宣传使用的品牌名称、英文和口号。">
          <div class="edit-form-grid">
            <div class="edit-form-field">
              <label>品牌名称</label>
              <input v-model="editForm.brandName" placeholder="利强" />
            </div>
            <div class="edit-form-field">
              <label>品牌英文</label>
              <input v-model="editForm.brandNameEn" placeholder="LIQIANG / LEACH PACK" />
            </div>
            <div class="edit-form-field">
              <label>品牌口号</label>
              <input v-model="editForm.brandSlogan" />
            </div>
          </div>
        </FormSection>
      </el-tab-pane>

      <el-tab-pane label="工商信息" name="business">
        <FormSection title="登记信息" description="营业执照与登记机关公示的核心信息。" :required-count="1">
          <div class="edit-form-grid">
            <div class="edit-form-field">
              <label>公司类型</label>
              <input v-model="editForm.companyType" placeholder="有限责任公司(自然人独资)" />
            </div>
            <div class="edit-form-field">
              <label>注册资本</label>
              <input v-model="editForm.registeredCapital" placeholder="1000万元人民币" />
            </div>
            <div class="edit-form-field">
              <label>实缴资本</label>
              <input v-model="editForm.paidCapital" placeholder="890万元人民币" />
            </div>
            <div class="edit-form-field">
              <label>参保人数</label>
              <input v-model="editForm.insuredCount" type="number" />
            </div>
            <div class="edit-form-field">
              <label>企业规模</label>
              <input v-model="editForm.enterpriseScale" placeholder="小型企业" />
            </div>
            <div class="edit-form-field">
              <label>成立日期</label>
              <input v-model="editForm.establishmentDate" type="date" />
            </div>
            <div class="edit-form-field">
              <label>营业期限</label>
              <input v-model="editForm.businessTerm" placeholder="2013年08月12日至长期" />
            </div>
            <div class="edit-form-field">
              <label>统一社会信用代码</label>
              <input v-model="editForm.unifiedSocialCode" />
              <span v-if="editErrors.unifiedSocialCode" class="edit-error-msg">{{ editErrors.unifiedSocialCode }}</span>
            </div>
            <div class="edit-form-field">
              <label>法定代表人</label>
              <input v-model="editForm.legalRepresentative" />
            </div>
          </div>
        </FormSection>
        <FormSection title="地址信息" description="注册地、实际办公地与生产工厂地址。">
          <div class="edit-form-grid">
            <div class="edit-form-field full-field">
              <label>注册地址</label>
              <input v-model="editForm.registeredAddress" />
            </div>
            <div class="edit-form-field full-field">
              <label>办公地址</label>
              <input v-model="editForm.officeAddress" />
            </div>
            <div class="edit-form-field full-field">
              <label>工厂地址</label>
              <input v-model="editForm.factoryAddress" />
            </div>
          </div>
        </FormSection>
        <FormSection title="经营信息" description="许可经营项目与一般经营项目的汇总描述。">
          <div class="edit-form-grid">
            <div class="edit-form-field full-field">
              <label>经营范围</label>
              <textarea v-model="editForm.businessScope" rows="3"></textarea>
            </div>
          </div>
        </FormSection>
      </el-tab-pane>

      <el-tab-pane label="对外资料" name="externalProfile">
        <FormSection title="市场定位" description="用于官网、店铺和招投标资料的对外展示内容。">
          <div class="edit-form-grid">
            <div class="edit-form-field full-field">
              <label>核心定位</label>
              <input v-model="editForm.corePositioning" placeholder="食品包装设备与包装耗材一体化供应商" />
            </div>
            <div class="edit-form-field full-field">
              <label>主营业务</label>
              <textarea
                v-model="editForm.mainBusiness"
                rows="2"
                placeholder="封口机、气调包装机、贴体包装机、包装容器、封口膜..."
              ></textarea>
            </div>
            <div class="edit-form-field full-field">
              <label>目标行业</label>
              <input v-model="editForm.targetIndustries" placeholder="食品加工、中央厨房、预制菜、生鲜、餐饮连锁..." />
            </div>
            <div class="edit-form-field full-field">
              <label>核心优势</label>
              <textarea
                v-model="editForm.advantages"
                rows="2"
                placeholder="设备稳定、封口牢固、支持寄样试封、设备与包材配套、支持定制..."
              ></textarea>
            </div>
          </div>
        </FormSection>
        <FormSection title="营收结构" description="营收构成占比，用于后续分析与市场策略。">
          <div class="edit-form-grid">
            <div class="edit-form-field">
              <label>设备营收占比(%)</label>
              <input v-model="editForm.revenueDevicePct" type="number" placeholder="70" />
            </div>
            <div class="edit-form-field">
              <label>耗材营收占比(%)</label>
              <input v-model="editForm.revenueConsumablePct" type="number" placeholder="30" />
            </div>
          </div>
        </FormSection>
      </el-tab-pane>

      <el-tab-pane label="联系信息" name="contact">
        <FormSection title="联系方式" description="客户与合作伙伴可触达的电话、邮箱与官网。">
          <div class="edit-form-grid">
            <div class="edit-form-field">
              <label>电话</label>
              <input v-model="editForm.phone" />
            </div>
            <div class="edit-form-field">
              <label>邮箱</label>
              <input v-model="editForm.email" type="email" />
              <span v-if="editErrors.email" class="edit-error-msg">{{ editErrors.email }}</span>
            </div>
            <div class="edit-form-field">
              <label>网站</label>
              <input v-model="editForm.website" placeholder="https://www.lqpack.com" />
              <span v-if="editErrors.website" class="edit-error-msg">{{ editErrors.website }}</span>
            </div>
          </div>
        </FormSection>
      </el-tab-pane>
    </LqTabs>
  </LqDialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import LqTabs from '../common/LqTabs.vue'
import FormSection from '../common/FormSection.vue'

interface CompanyForm {
  companyName: string
  formerName: string
  companyNameEn: string
  brandName: string
  brandNameEn: string
  brandSlogan: string
  mission: string
  vision: string
  values: string
  companyType: string
  registeredCapital: string
  paidCapital: string
  insuredCount: number | null
  enterpriseScale: string
  establishmentDate: string
  businessTerm: string
  corePositioning: string
  mainBusiness: string
  targetIndustries: string
  advantages: string
  revenueDevicePct: number | null
  revenueConsumablePct: number | null
  unifiedSocialCode: string
  legalRepresentative: string
  registeredAddress: string
  officeAddress: string
  factoryAddress: string
  businessScope: string
  phone: string
  email: string
  website: string
  businessModel: string
  serviceCapabilities: string
  customerTypes: string
  applicationScenarios: string
}

const defaultEditForm = (): CompanyForm => ({
  companyName: '',
  formerName: '',
  companyNameEn: '',
  brandName: '',
  brandNameEn: '',
  brandSlogan: '',
  mission: '',
  vision: '',
  values: '',
  companyType: '',
  registeredCapital: '',
  paidCapital: '',
  insuredCount: null,
  enterpriseScale: '',
  establishmentDate: '',
  businessTerm: '',
  corePositioning: '',
  mainBusiness: '',
  targetIndustries: '',
  advantages: '',
  revenueDevicePct: null,
  revenueConsumablePct: null,
  unifiedSocialCode: '',
  legalRepresentative: '',
  registeredAddress: '',
  officeAddress: '',
  factoryAddress: '',
  businessScope: '',
  phone: '',
  email: '',
  website: '',
  businessModel: '',
  serviceCapabilities: '',
  customerTypes: '',
  applicationScenarios: ''
})

const props = defineProps<{
  visible: boolean
  detail: Record<string, any>
  saving: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  save: [data: CompanyForm]
  close: []
}>()

const editDialogActiveTab = ref('basic')
const editForm = reactive<CompanyForm>(defaultEditForm())
const editErrors = reactive<Record<string, string>>({})

watch(
  () => props.visible,
  v => {
    if (v) {
      editDialogActiveTab.value = 'basic'
      const src = props.detail as CompanyForm | undefined
      const fields = Object.keys(defaultEditForm()) as (keyof CompanyForm)[]
      fields.forEach(k => {
        if (src && src[k] != null) {
          ;(editForm as Record<string, unknown>)[k] = src[k]
        } else {
          ;(editForm as Record<string, unknown>)[k] =
            k === 'insuredCount' || k === 'revenueDevicePct' || k === 'revenueConsumablePct' ? null : ''
        }
      })
      Object.keys(editErrors).forEach(key => delete editErrors[key])
    }
  }
)

function handleVisibleUpdate(val: boolean) {
  emit('update:visible', val)
}

function onCancel() {
  emit('update:visible', false)
  emit('close')
}

function onClose() {
  emit('close')
}

function validateEditEmail(email: string): boolean {
  if (!email) return true
  const regex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  return regex.test(email)
}

function validateEditUrl(url: string): boolean {
  if (!url) return true
  const regex = /^(https?:\/\/)?([\da-z\.-]+)\.([a-z\.]{2,6})([\/\w \.-]*)*\/?$/
  return regex.test(url)
}

function validateUnifiedSocialCode(code: string): boolean {
  if (!code) return true
  if (code.length !== 18) return false
  const regex = /^[0-9A-HJ-NP-RT-UW-Y]{2}\d{6}[0-9A-HJ-NP-RT-UW-Y]{10}$/
  if (!regex.test(code)) return false
  const weight = [1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28]
  const codeMap = '0123456789ABCDEFGHJKLMNPQRTUWXY'
  let sum = 0
  for (let i = 0; i < 17; i++) {
    const char = code[i]
    const index = codeMap.indexOf(char)
    if (index === -1) return false
    sum += index * weight[i]
  }
  const checkCode = codeMap[(31 - (sum % 31)) % 31]
  return code[17] === checkCode
}

function validateEditForm(): boolean {
  Object.keys(editErrors).forEach(key => delete editErrors[key])

  if (editForm.email && !validateEditEmail(editForm.email)) {
    editErrors.email = '请输入有效的邮箱地址'
  }
  if (editForm.website && !validateEditUrl(editForm.website)) {
    editErrors.website = '请输入有效的网址'
  }
  if (editForm.unifiedSocialCode && !validateUnifiedSocialCode(editForm.unifiedSocialCode)) {
    editErrors.unifiedSocialCode = '请输入有效的统一社会信用代码'
  }

  return Object.keys(editErrors).length === 0
}

function onSave() {
  if (!validateEditForm()) return
  emit('save', { ...editForm })
}
</script>

<style scoped>
/* C14 LqTabs 替代：:deep 样式穿透已收口到 LqTabs.vue 组件内部 */

.edit-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px 18px;
}

.edit-form-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.edit-form-field.full-field {
  grid-column: 1 / -1;
}

.edit-form-field label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  display: inline-flex;
  align-items: baseline;
}

.edit-form-field > label.required::before {
  content: '*';
  color: var(--color-danger);
  margin-right: 3px;
  font-weight: 700;
}

.edit-form-field input,
.edit-form-field textarea,
.edit-form-field select {
  padding: 8px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: 14px;
  color: var(--color-text-primary);
  background: var(--color-surface);
  transition: border-color var(--transition-fast);
  font-family: inherit;
}

.edit-form-field input:focus,
.edit-form-field textarea:focus,
.edit-form-field select:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring);
}

.edit-form-field textarea {
  resize: vertical;
  min-height: 60px;
}

.edit-error-msg {
  font-size: 12px;
  color: var(--color-danger);
  margin-top: 2px;
}

@media (max-width: 960px) {
  .edit-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
