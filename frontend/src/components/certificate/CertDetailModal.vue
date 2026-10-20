<template>
  <LqDialog
    :model-value="visible"
    :title="modalTitle"
    :subtitle="modalSubtitle"
    size="lg"
    :close-on-click-modal="false"
    destroy-on-close
    :mode="isEditMode || isCreateMode ? 'form' : 'detail'"
    :show-footer="true"
    :loading="modalSaving"
    @close="$emit('close')"
  >
    <div v-if="!isEditMode && !isCreateMode" class="cert-meta">
      <StatusTag
        :type="certStatusTagClass(modalCurrentData.assetStatus)"
        :label="modalCurrentData.assetStatus || '-'"
      />
      <span class="cert-meta__tag">{{ isTrademark ? `第${data.niceClass}类` : (modalCurrentData.patentType || modalCurrentData.assetCategory || '-') }}</span>
    </div>

    <template v-if="isEditMode || isCreateMode">
      <div class="cert-form">
        <FormSection title="基本信息" description="证书核心身份与当前法律状态" :required-count="3">
          <div class="cert-grid">
            <div class="cert-field">
              <label class="required">资产状态</label>
              <el-select v-model="modalForm.assetStatus" style="width: 100%">
                <el-option label="有效" value="有效" />
                <el-option label="已失效" value="已失效" />
                <el-option label="未生效" value="未生效" />
                <el-option label="已转让" value="已转让" />
              </el-select>
            </div>
            <div class="cert-field">
              <label class="required">资产类别</label>
              <el-select v-model="modalForm.assetCategory" style="width: 100%">
                <el-option label="专利" value="专利" />
                <el-option label="软著" value="软著" />
                <el-option label="商标" value="商标" />
              </el-select>
            </div>
            <div v-if="!isTrademark" class="cert-field">
              <label>专利类型</label>
              <el-select v-model="modalForm.patentType" clearable placeholder="--" style="width: 100%">
                <el-option label="发明专利" value="发明专利" />
                <el-option label="实用新型" value="实用新型" />
                <el-option label="外观设计" value="外观设计" />
                <el-option label="软著" value="软著" />
              </el-select>
            </div>
            <div v-if="isTrademark" class="cert-field">
              <label>国际分类号</label>
              <el-input v-model.number="modalForm.niceClass" type="number" min="1" max="45" placeholder="1-45" />
            </div>
            <div class="cert-field">
              <label>法律状态</label>
              <el-input v-model="modalForm.legalStatus" placeholder="如：授权有效" />
            </div>
            <div class="cert-field cert-field--full">
              <label class="required">{{ isTrademark ? '商标名称' : '名称' }}</label>
              <el-input v-model="modalForm.name" :placeholder="isTrademark ? '商标名称' : '专利/证书名称'" />
            </div>
          </div>
        </FormSection>

        <FormSection v-if="!isTrademark" title="号码信息" description="官方登记号码">
          <div class="cert-grid cert-grid--3">
            <div class="cert-field">
              <label>专利号/证书号</label>
              <el-input v-model="modalForm.patentNo" />
            </div>
            <div class="cert-field">
              <label>申请号</label>
              <el-input v-model="modalForm.applicationNo" />
            </div>
            <div class="cert-field">
              <label>公开号</label>
              <el-input v-model="modalForm.publicationNo" />
            </div>
            <div class="cert-field">
              <label>查询号（纯数字）</label>
              <el-input
                v-model="modalForm.queryNo"
                placeholder="留空则按申请号/专利号自动提取"
                @blur="onQueryNoBlur"
              />
            </div>
          </div>
        </FormSection>

        <FormSection v-if="isTrademark" title="注册信息" description="商标注册号与申请号">
          <div class="cert-grid cert-grid--3">
            <div class="cert-field">
              <label>商标注册号</label>
              <el-input v-model="modalForm.patentNo" />
            </div>
            <div class="cert-field">
              <label>申请号</label>
              <el-input v-model="modalForm.applicationNo" />
            </div>
          </div>
        </FormSection>

        <FormSection v-if="!isTrademark" title="权属信息" description="权利人与代理信息">
          <div class="cert-grid cert-grid--3">
            <div class="cert-field">
              <label>申请人</label>
              <el-input v-model="modalForm.applicant" />
            </div>
            <div class="cert-field">
              <label>发明人</label>
              <el-input v-model="modalForm.inventors" placeholder="多人用分号分隔" />
            </div>
            <div class="cert-field">
              <label>代理机构</label>
              <el-input v-model="modalForm.agency" />
            </div>
          </div>
        </FormSection>

        <FormSection v-if="isTrademark" title="权属信息" description="注册人与代理信息">
          <div class="cert-grid cert-grid--3">
            <div class="cert-field">
              <label>注册人</label>
              <el-input v-model="modalForm.applicant" />
            </div>
            <div class="cert-field cert-field--full">
              <label>注册人地址</label>
              <el-input v-model="modalForm.applicantAddress" />
            </div>
            <div class="cert-field">
              <label>代理机构</label>
              <el-input v-model="modalForm.agency" />
            </div>
          </div>
        </FormSection>

        <FormSection title="时间信息" description="申请、授权与到期时间">
          <div class="cert-grid cert-grid--3">
            <div class="cert-field">
              <label>申请日</label>
              <LqDatePicker v-model="modalForm.applyDate" />
            </div>
            <div class="cert-field">
              <label>{{ isTrademark ? '注册日期' : '授权日' }}</label>
              <LqDatePicker v-model="modalForm.grantDate" />
            </div>
            <div class="cert-field">
              <label>法定年限（年）</label>
              <el-input v-model.number="modalForm.legalYears" type="number" min="1" />
            </div>
          </div>
        </FormSection>

        <FormSection title="官网展示" description="控制该档案是否以及按什么优先级出现在企业官网">
          <div class="cert-grid cert-grid--3">
            <div class="cert-field">
              <label>官网公开</label>
              <el-switch v-model="modalForm.websiteEnabled" active-text="公开" inactive-text="不公开" />
            </div>
            <div class="cert-field">
              <label>重点展示</label>
              <el-switch v-model="modalForm.websiteFeatured" active-text="重点" inactive-text="普通" />
            </div>
            <div class="cert-field">
              <label>官网排序</label>
              <el-input-number v-model="modalForm.websiteSortOrder" :min="0" :step="1" controls-position="right" />
            </div>
          </div>
          <p class="website-publish-tip">排序数字越小越靠前；设为重点会自动开启官网公开，下线时会自动取消重点。</p>
        </FormSection>

        <FormSection v-if="!isTrademark" title="缴费信息" description="年费维护与发票记录">
          <div class="cert-grid">
            <div class="cert-field">
              <label>缴费状态</label>
              <el-select v-model="modalForm.feeStatus" clearable placeholder="--" style="width: 100%">
                <el-option label="待查询/待核实" value="待查询/待核实" />
                <el-option label="已支付" value="已支付" />
                <el-option label="未支付" value="未支付" />
                <el-option label="已失效待缴费" value="已失效待缴费" />
                <el-option label="已登记/已公告" value="已登记/已公告" />
              </el-select>
            </div>
            <div class="cert-field">
              <label>上次缴费金额</label>
              <el-input v-model.number="modalForm.lastFeeAmount" type="number" placeholder="元" step="0.01" />
            </div>
            <div class="cert-field">
              <label>下次查询日期</label>
              <LqDatePicker v-model="modalForm.feeQueryDate" />
            </div>
            <div class="cert-field">
              <label>年费截止日</label>
              <LqDatePicker v-model="modalForm.feeDueDate" />
            </div>
            <div class="cert-field cert-field--full">
              <label>缴费明细</label>
              <el-input v-model="modalForm.feeDetail" />
            </div>
            <div class="cert-field">
              <label>发票状态</label>
              <el-select v-model="modalForm.invoiceStatus" clearable placeholder="--" style="width: 100%">
                <el-option label="未开票" value="未开票" />
                <el-option label="已开票" value="已开票" />
                <el-option label="已归档" value="已归档" />
              </el-select>
            </div>
            <div class="cert-field">
              <label>代理机构联系人</label>
              <el-input v-model="modalForm.agencyContact" placeholder="联系人姓名" />
            </div>
          </div>
        </FormSection>

        <FormSection v-if="isTrademark" title="商品/服务" description="核定使用商品或服务项目">
          <div class="cert-grid">
            <div class="cert-field cert-field--full">
              <label>核定使用商品/服务项目</label>
              <el-input
                v-model="modalForm.niceClassDescription"
                type="textarea"
                :rows="5"
                placeholder="如：盆（容器）；黄油碟；碗；瓶；调味瓶..."
              />
            </div>
          </div>
        </FormSection>

        <FormSection title="附件与备注" :description="isTrademark ? '商标图样、注册证与备注' : '说明书、证书扫描件与备注'">
          <div class="cert-grid cert-grid--2">
            <div class="cert-field">
              <label>{{ isTrademark ? '商标图样' : '说明书文件' }}</label>
              <div
                class="file-upload-cell"
                :class="{ 'drag-over': specDrop.isDragOver && !modalForm.specFile }"
                v-bind="specDrop.dragHandlers"
              >
                <template v-if="modalForm.specFile">
                  <div class="file-preview-item">
                    <img
                      v-if="isImageFile(modalForm.specFile)"
                      :src="certificatePreviewUrl(modalForm.specFile)"
                      class="file-thumb"
                      title="点击预览"
                      @click="modalEmitPreview('specFile')"
                    />
                    <span
                      v-else
                      class="file-name-text file-name-clickable"
                      :title="modalForm.specFile"
                      @click="modalEmitPreview('specFile')"
                    >
                      {{ modalForm.specFile }}
                    </span>
                    <button class="btn-icon btn-remove-file" title="删除文件" @click="modalHandleRemoveFile('specFile')">
                      x
                    </button>
                  </div>
                </template>
                <button
                  v-else
                  class="btn-sm btn-upload"
                  :disabled="modalUploadingField === 'specFile'"
                  @click="modalHandleFileUpload('specFile')"
                >
                  {{ modalUploadingField === 'specFile' ? '上传中...' : (isTrademark ? '+ 上传商标图样（可拖拽）' : '+ 上传说明书文件（可拖拽）') }}
                </button>
              </div>
            </div>
            <div class="cert-field">
              <label>{{ isTrademark ? '注册证' : '证书图片' }}</label>
              <div
                class="file-upload-cell"
                :class="{ 'drag-over': certDrop.isDragOver && !modalForm.certFile }"
                v-bind="certDrop.dragHandlers"
              >
                <template v-if="modalForm.certFile">
                  <div class="file-preview-item">
                    <img
                      v-if="isImageFile(modalForm.certFile)"
                      :src="certificatePreviewUrl(modalForm.certFile)"
                      class="file-thumb"
                      title="点击预览"
                      @click="modalEmitPreview('certFile')"
                    />
                    <span
                      v-else
                      class="file-name-text file-name-clickable"
                      :title="modalForm.certFile"
                      @click="modalEmitPreview('certFile')"
                    >
                      {{ modalForm.certFile }}
                    </span>
                    <button class="btn-icon btn-remove-file" title="删除文件" @click="modalHandleRemoveFile('certFile')">
                      x
                    </button>
                  </div>
                </template>
                <button
                  v-else
                  class="btn-sm btn-upload"
                  :disabled="modalUploadingField === 'certFile'"
                  @click="modalHandleFileUpload('certFile')"
                >
                  {{ modalUploadingField === 'certFile' ? '上传中...' : (isTrademark ? '+ 上传注册证（可拖拽）' : '+ 上传证书图片（可拖拽）') }}
                </button>
              </div>
            </div>
            <div class="cert-field cert-field--full">
              <label>备注</label>
              <el-input v-model="modalForm.remark" type="textarea" :rows="3" />
            </div>
            <div v-if="!isTrademark" class="cert-field cert-field--full">
              <label>审查进度备注</label>
              <el-input
                v-model="modalForm.reviewProgress"
                type="textarea"
                :rows="3"
                placeholder="审查意见、补正记录、进度说明等"
              />
            </div>
          </div>
        </FormSection>
      </div>
    </template>

    <template v-else>
      <div v-if="hasKpi" class="cert-kpi">
        <div
          v-if="data.daysRemaining != null"
          class="cert-kpi__item"
          :class="kpiDaysClass"
        >
          <span class="cert-kpi__value">{{ kpiDaysText }}</span>
          <span class="cert-kpi__label">距到期</span>
        </div>
        <div v-if="data.expireDate" class="cert-kpi__item">
          <span class="cert-kpi__value">{{ fmtDate(data.expireDate) }}</span>
          <span class="cert-kpi__label">到期日</span>
        </div>
        <div v-if="data.feeStatus" class="cert-kpi__item">
          <span class="cert-kpi__value">{{ data.feeStatus }}</span>
          <span class="cert-kpi__label">缴费状态</span>
        </div>
        <div
          v-if="data.feeDueDate"
          class="cert-kpi__item"
          :class="{ 'cert-kpi__item--danger': feeDueOverdue }"
        >
          <span class="cert-kpi__value">{{ fmtDate(data.feeDueDate) }}</span>
          <span class="cert-kpi__label">年费截止</span>
        </div>
        <div v-if="data.lastFeeAmount != null" class="cert-kpi__item">
          <span class="cert-kpi__value">¥{{ data.lastFeeAmount }}</span>
          <span class="cert-kpi__label">上次缴费</span>
        </div>
      </div>

      <div v-if="certImage" class="cert-photo-card">
        <img
          :src="certImage"
          class="cert-photo"
          alt="证书图片"
          title="点击查看大图"
          @click="modalEmitPreview('certFile')"
        />
        <button class="cert-photo-zoom" @click="modalEmitPreview('certFile')">点击查看大图</button>
      </div>

      <!-- C17 LqDescriptions 推广：将原 5 段手写 info-item/info-label/info-value 网格替换为统一描述列表 -->
      <LqDescriptions
        :groups="detailGroups"
        :column="2"
        :border="false"
        card
      >
        <template #value-patentNo="{ value }">
          <span class="cert-patent-no">{{ value }}</span>
        </template>
        <template #value-feeStatus="{ value }">
          <span class="cert-value-tag" :class="certFeeTagClass(String(value ?? ''))">{{ value }}</span>
        </template>
        <template #value-invoiceStatus="{ value }">
          <span class="cert-value-tag" :class="invoiceStatusTagClass(String(value ?? ''))">{{ value }}</span>
        </template>
        <template #value-specFile="{ value }">
          <span class="file-link" title="点击预览" @click="modalEmitPreview('specFile')">
            <img
              v-if="isImageFile(String(value ?? ''))"
              :src="certificatePreviewUrl(value as string)"
              class="file-thumb-sm"
            />
            <template v-else>{{ value }}</template>
          </span>
        </template>
        <template #value-certFile="{ value }">
          <span class="file-link" title="点击预览" @click="modalEmitPreview('certFile')">
            <img
              v-if="isImageFile(String(value ?? ''))"
              :src="certificatePreviewUrl(value as string)"
              class="file-thumb-sm"
            />
            <template v-else>{{ value }}</template>
          </span>
        </template>
        <template #value-daysRemaining="{ value }">
          <span
            :class="{
              'days-danger': Number(value) <= 0,
              'days-warn': Number(value) > 0 && Number(value) <= 180
            }"
            >{{ Number(value) }}天</span
          >
        </template>
      </LqDescriptions>

      <FeeRecordsPanel v-if="data.id && !isTrademark" :certificate-id="data.id" @updated="$emit('feeUpdated')" />
    </template>

    <template #footer>
      <div class="cert-dialog-foot">
        <template v-if="isEditMode || isCreateMode">
          <button @click="$emit('close')">取消</button>
          <button class="primary" :disabled="modalSaving" @click="modalDoSave">
            {{ modalSaving ? '保存中...' : '保存' }}
          </button>
        </template>
        <template v-else>
          <button class="danger" @click="$emit('delete')">删除</button>
          <button class="primary" @click="$emit('edit')">编辑此证书</button>
        </template>
      </div>
    </template>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { CertificateDetail, CertificatePayload } from '../../types/certificate'
import { createCertificate, updateCertificate } from '../../api/certificate'
import LqDialog from '../common/LqDialog.vue'
import LqDatePicker from '../common/LqDatePicker.vue'
import LqDescriptions from '../common/LqDescriptions.vue'
import type { DescGroup } from '../common/LqDescriptions.vue'
import FormSection from '../common/FormSection.vue'
import StatusTag from '../common/StatusTag.vue'
import FeeRecordsPanel from './FeeRecordsPanel.vue'
import { clearDirty, setDirty } from '../../composables/common/useDirty'
import { certFeeTagClass, certStatusTagClass } from '../../composables/common/useStatus'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
import { notify } from '../../composables/ui/useToast'
import { useFileAssets } from '../../composables/upload/useFileAssets'
import { useFileUploadWorkflow } from '../../composables/upload/useFileUploadWorkflow'
import { useLocalDropUpload } from '../../composables'
import { findFileAssetHandle, fileAssetPreviewUrlByOptions } from '../../utils/ui/fileAssetUrls'
import { FILE_OWNER_TYPE_KEYS } from '../../types/fileAsset'
import { fmtDate, todayIso } from '../../utils/common/dateUtils'
import { digitsOnly } from '../../utils/common/textUtils'
import { isImageFile } from '../../utils/ui/fileUtils'
import { certificateFileAssetId, type CertificateFileField } from '../../utils/certificate/certificateFileAsset'

const props = defineProps<{
  visible: boolean
  data: CertificateDetail
  isEditMode: boolean
  isCreateMode: boolean
}>()

const emit = defineEmits<{
  close: []
  save: [payload: CertificatePayload]
  edit: []
  delete: []
  feeUpdated: []
  'preview-asset': [asset: unknown]
}>()

const modalSaving = ref(false)

const modalTitle = computed(() => {
  if (props.isCreateMode) return '新增证书'
  if (props.isEditMode) return '编辑证书'
  return props.data.name || '证书详情'
})

const modalSubtitle = computed(() => {
  if (props.isCreateMode || props.isEditMode) return ''
  return props.data.patentNo || ''
})

const modalCurrentData = computed(() => ({
  assetStatus: props.data.assetStatus || '',
  patentType: props.data.patentType || '',
  assetCategory: props.data.assetCategory || '',
  patentNo: props.data.patentNo || ''
}))

const isTrademark = computed(() => modalForm.assetCategory === '商标')

const hasKpi = computed(
  () =>
    props.data.daysRemaining != null ||
    !!props.data.expireDate ||
    !!props.data.feeStatus ||
    !!props.data.feeDueDate ||
    props.data.lastFeeAmount != null
)

const kpiDaysClass = computed(() => ({
  'cert-kpi__item--danger': props.data.daysRemaining < 0,
  'cert-kpi__item--warn': props.data.daysRemaining >= 0 && props.data.daysRemaining <= 180
}))

const kpiDaysText = computed(() => (props.data.daysRemaining < 0 ? '已过期' : `${props.data.daysRemaining}天`))

const feeDueOverdue = computed(() => !!props.data.feeDueDate && props.data.feeDueDate < todayIso())

function invoiceStatusTagClass(status: string): string {
  if (!status) return ''
  if (status.includes('已开票') || status.includes('已归档')) return 'tag-ok'
  if (status.includes('未开票')) return 'tag-warn'
  return ''
}

const certImage = computed(() => {
  const file = props.data.certFile
  if (!file || !isImageFile(file)) return ''
  return certificatePreviewUrl(file)
})

// C17 LqDescriptions 推广：将原 5 段手写 info-item 网格映射为分组描述列表
const detailGroups = computed<DescGroup[]>(() => {
  const d = props.data
  const isTM = d.assetCategory === '商标'

  if (isTM) {
    return [
      {
        title: '基本信息',
        items: [
          { label: '资产类别', value: d.assetCategory },
          { label: '国际分类', value: d.niceClass != null ? `第${d.niceClass}类` : null },
          { label: '法律状态', value: d.legalStatus },
          { label: '法定年限', value: d.legalYears != null ? `${d.legalYears}年` : null }
        ]
      },
      {
        title: '注册信息',
        items: [
          { label: '商标注册号', value: d.patentNo, mono: true, highlight: true, copyable: 'digits' },
          { label: '申请号', value: d.applicationNo, mono: true, copyable: true }
        ]
      },
      {
        title: '权属信息',
        items: [
          { label: '注册人', value: d.applicant },
          { label: '注册人地址', value: d.applicantAddress, wide: true },
          { label: '代理机构', value: d.agency }
        ]
      },
      {
        title: '时间信息',
        items: [
          { label: '申请日', value: fmtDate(d.applyDate) },
          { label: '注册日期', value: fmtDate(d.grantDate) },
          { label: '有效期至', value: fmtDate(d.expireDate), highlight: d.daysRemaining != null && d.daysRemaining <= 180 },
          ...(d.daysRemaining != null
            ? [
                {
                  label: '剩余天数',
                  value: d.daysRemaining as number,
                  slot: 'daysRemaining',
                  highlight: d.daysRemaining <= 180
                }
              ]
            : [])
        ]
      },
      ...(d.niceClassDescription
        ? [{ title: '商品/服务', items: [{ label: '核定使用商品/服务项目', value: d.niceClassDescription, wide: true }] }]
        : [])
    ]
  }

  const groups: DescGroup[] = [
    {
      title: '官网展示',
      items: [
        { label: '官网状态', value: d.websiteEnabled ? '已公开' : '未公开' },
        { label: '重点展示', value: d.websiteFeatured ? '是' : '否' },
        { label: '官网排序', value: d.websiteSortOrder ?? 0 }
      ]
    },
    {
      title: '基本信息',
      items: [
        { label: '资产类别', value: d.assetCategory },
        { label: '专利类型', value: d.patentType },
        { label: '法律状态', value: d.legalStatus },
        { label: '法定年限', value: d.legalYears != null ? `${d.legalYears}年` : null }
      ]
    },
    {
      title: '号码信息',
      items: [
        { label: '专利号/证书号', value: d.patentNo, mono: true, highlight: true, copyable: 'digits', slot: 'patentNo' },
        { label: '申请号', value: d.applicationNo, mono: true, copyable: true },
        { label: '公开号', value: d.publicationNo, mono: true, copyable: true },
        { label: '查询号', value: d.queryNo || digitsOnly(d.applicationNo) || digitsOnly(d.patentNo), mono: true, copyable: true }
      ]
    },
    {
      title: '权属信息',
      items: [
        { label: '申请人', value: d.applicant },
        { label: '发明人', value: d.inventors },
        { label: '审查员', value: d.examiner },
        { label: '代理机构', value: d.agency },
        { label: '代理联系人', value: d.agencyContact },
      ]
    },
    {
      title: '时间信息',
      items: [
        { label: '申请日', value: fmtDate(d.applyDate) },
        { label: '授权日', value: fmtDate(d.grantDate) },
        { label: '到期日', value: fmtDate(d.expireDate), highlight: d.daysRemaining != null && d.daysRemaining <= 180 },
        ...(d.daysRemaining != null
          ? [
              {
                label: '剩余天数',
                value: d.daysRemaining as number,
                slot: 'daysRemaining',
                highlight: d.daysRemaining <= 180
              }
            ]
          : [])
      ]
    },
    {
      title: '缴费信息',
      items: [
        { label: '缴费状态', value: d.feeStatus, slot: 'feeStatus' },
        { label: '上次缴费金额', value: d.lastFeeAmount != null ? `￥${d.lastFeeAmount}` : null },
        { label: '下次查询日', value: fmtDate(d.feeQueryDate) },
        { label: '年费截止日', value: fmtDate(d.feeDueDate), highlight: !!(d.feeDueDate && d.daysRemaining <= 180) },
        ...(d.feeDetail ? [{ label: '缴费明细', value: d.feeDetail, wide: true }] : []),
        ...(d.invoiceStatus ? [{ label: '发票状态', value: d.invoiceStatus, slot: 'invoiceStatus' }] : [])
      ]
    }
  ]

  // 附件与备注（条件渲染）
  const attachItems: DescGroup['items'] = []
  if (d.specFile) attachItems.push({ label: isTM ? '商标图样' : '说明书文件', value: d.specFile, slot: 'specFile' })
  if (d.certFile) attachItems.push({ label: isTM ? '注册证' : '证书图片', value: d.certFile, slot: 'certFile' })
  if (d.remark) attachItems.push({ label: '备注', value: d.remark, wide: true })
  if (!isTM && d.reviewProgress) attachItems.push({ label: '审查进度', value: d.reviewProgress, wide: true })
  if (attachItems.length) {
    groups.push({ title: '附件与备注', items: attachItems })
  }

  return groups
})

const modalForm = reactive<Partial<CertificatePayload>>({})

watch(
  () => props.data,
  newData => {
    Object.keys(modalForm).forEach(k => delete (modalForm as Record<string, unknown>)[k])
    Object.assign(modalForm, newData)
  },
  { immediate: true }
)

const certificateOwnerId = computed(() => props.data?.id ?? null)
const certificateFileAssets = useFileAssets({
  ownerType: FILE_OWNER_TYPE_KEYS.certificate,
  ownerId: certificateOwnerId,
  notify,
  onChanged: async () => {}
})

const certificateFileHandles = computed(() => certificateFileAssets.items.value)

watch(
  () => [props.visible, props.data?.id] as const,
  async ([visible, certificateId]) => {
    if (!visible || !certificateId) return
    await certificateFileAssets.loadCapabilities()
    await certificateFileAssets.load()
  },
  { immediate: true }
)

const { pickFiles, uploadFiles } = useFileUploadWorkflow({
  category: 'certificate',
  notify
})

const modalUploadingField = ref<string | null>(null)

async function modalHandleFileUpload(field: CertificateFileField) {
  modalUploadingField.value = field
  try {
    const results = await pickFiles({ multiple: false })
    const result = results[0]
    if ('fileName' in result && result.fileName) {
      modalForm[field] = result.fileName
      await certificateFileAssets.load()
    }
  } finally {
    modalUploadingField.value = null
    if (field === 'specFile') specDrop.reset()
    else certDrop.reset()
  }
}

async function modalHandleDropFiles(field: CertificateFileField, files: File[]) {
  if (!files.length) return
  modalUploadingField.value = field
  try {
    const results = await uploadFiles(files.slice(0, 1), { multiple: false, source: 'drag' })
    const result = results[0] as { fileName?: string } | undefined
    if (result?.fileName) {
      modalForm[field] = result.fileName
      await certificateFileAssets.load()
    }
  } finally {
    modalUploadingField.value = null
    if (field === 'specFile') specDrop.reset()
    else certDrop.reset()
  }
}

const specDrop = useLocalDropUpload({
  onFiles: files => modalHandleDropFiles('specFile', files)
})

const certDrop = useLocalDropUpload({
  onFiles: files => modalHandleDropFiles('certFile', files)
})

async function modalHandleRemoveFile(field: CertificateFileField) {
  const fileName = modalForm[field] as string
  if (!fileName) return
  const ok = await askConfirm({
    title: '删除文件',
    message: `确定要删除文件「${fileName}」吗？删除后不可恢复。`,
    confirmText: '删除',
    danger: true
  })
  if (ok !== true) return
  const handle = findCertificateFileHandle(fileName)
  if (handle) {
    await certificateFileAssets.remove(handle.assetId)
  }
  modalForm[field] = ''
  if (field === 'specFile') specDrop.reset()
  else certDrop.reset()
}

function findCertificateFileHandle(fileName: string) {
  return findFileAssetHandle(certificateFileHandles.value, fileName)
}

function certificateFileFieldForName(fileName: string): CertificateFileField | null {
  if (modalForm.specFile === fileName || props.data.specFile === fileName) return 'specFile'
  if (modalForm.certFile === fileName || props.data.certFile === fileName) return 'certFile'
  return null
}

function certificatePreviewUrl(fileName?: string | null) {
  if (!fileName) return ''
  const handle = findCertificateFileHandle(fileName)
  const field = certificateFileFieldForName(fileName)
  return (
    certificateFileAssets.previewUrl(handle) ||
    fileAssetPreviewUrlByOptions({
      ownerType: FILE_OWNER_TYPE_KEYS.certificate,
      assetId: handle?.assetId || (props.data?.id && field ? certificateFileAssetId(props.data.id, field) : null),
      category: 'certificate',
      fileName
    })
  )
}

function modalEmitPreview(field: CertificateFileField) {
  const fileName = props.isEditMode || props.isCreateMode ? (modalForm[field] as string) : (props.data[field] as string)
  if (!fileName) return
  const handle = findCertificateFileHandle(fileName)
  emit('preview-asset', {
    fileName,
    ownerType: FILE_OWNER_TYPE_KEYS.certificate,
    assetId: handle?.assetId || (props.data?.id ? certificateFileAssetId(props.data.id, field) : null),
    category: 'certificate'
  })
}

/** 查询号留空时按申请号/专利号自动提取纯数字部分，方便直接复制到官方检索框 */
function onQueryNoBlur() {
  const current = String(modalForm.queryNo ?? '').trim()
  if (current) {
    modalForm.queryNo = digitsOnly(current)
    return
  }
  const derived = digitsOnly(modalForm.applicationNo) || digitsOnly(modalForm.patentNo)
  if (derived) {
    modalForm.queryNo = derived
    setDirty()
  }
}

async function modalDoSave() {
  if (!modalForm.name) {
    notify('请填写证书名称', 'warning')
    return
  }
  modalSaving.value = true
  try {
    if (props.isCreateMode) {
      await createCertificate({ ...modalForm } as CertificatePayload)
    } else {
      await updateCertificate(props.data.id, { ...modalForm } as CertificatePayload)
    }
    emit('save', { ...modalForm } as CertificatePayload)
    notify('保存成功', 'success')
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  } finally {
    modalSaving.value = false
  }
}

watch(
  () => props.visible,
  v => {
    if (v) setDirty('证书编辑')
    else clearDirty('证书编辑')
  }
)
</script>

<style scoped>
/* ===== 证书详情/编辑弹窗 ===== */

/* footer 按钮组：自定义 #footer 插槽无 .footer-status 左推元素，需手动右对齐 */
.cert-dialog-foot {
  display: flex;
  gap: var(--space-control-gap);
  margin-left: auto;
}

/* 顶部状态条（详情模式） */
.cert-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-control-gap);
  margin-bottom: var(--space-section-gap);
  padding: var(--space-control-gap) var(--space-control-gap-wide);
  background: linear-gradient(135deg, var(--color-surface-raised) 0%, var(--color-primary-bg-light) 100%);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-lg);
}

.cert-meta__tag {
  padding: 2px 8px;
  background: var(--color-surface);
  color: var(--color-primary);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
}

/* 关键指标条（详情模式） */
.cert-kpi {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap);
  margin-bottom: var(--space-section-gap);
  padding: var(--space-control-gap);
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-lg);
}

.cert-kpi__item {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
  min-width: 110px;
  flex: 1 1 110px;
  padding: 10px 16px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.cert-kpi__item--warn {
  border-color: var(--color-warning);
  background: var(--color-warning-bg-soft);
}

.cert-kpi__item--danger {
  border-color: var(--color-danger);
  background: var(--color-danger-bg-soft);
  animation: cert-danger-pulse 2s ease-in-out infinite;
}

@keyframes cert-danger-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.32);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(239, 68, 68, 0);
  }
}

.cert-kpi__value {
  font-size: var(--fs-lg);
  font-weight: var(--fw-bold);
  color: var(--color-text-heading);
  line-height: 1.2;
}

.cert-kpi__item--warn .cert-kpi__value {
  color: var(--color-warning);
}

.cert-kpi__item--danger .cert-kpi__value {
  color: var(--color-danger);
}

.cert-kpi__label {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

/* 证书图片大图预览卡片（详情模式） */
.cert-photo-card {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: var(--space-section-gap);
  padding: var(--space-control-gap);
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-lg);
}

.cert-photo {
  max-width: 100%;
  max-height: 420px;
  border-radius: var(--radius-md);
  cursor: zoom-in;
  box-shadow: var(--shadow-md);
}

.cert-photo-zoom {
  position: absolute;
  right: var(--space-control-gap);
  bottom: var(--space-control-gap);
  padding: 4px 10px;
  font-size: var(--fs-xs);
  color: var(--color-primary);
  background: var(--color-primary-bg);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-sm);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast);
}

.cert-photo-card:hover .cert-photo-zoom {
  opacity: 1;
}

/* 编辑表单 */
.cert-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-section-gap);
}

.cert-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-control-gap-wide) var(--space-form-item-gap);
}

.cert-grid--3 {
  grid-template-columns: repeat(3, 1fr);
}

.cert-grid--2 {
  grid-template-columns: repeat(2, 1fr);
}

.cert-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.cert-field--full {
  grid-column: 1 / -1;
}

.cert-field > label {
  display: inline-flex;
  align-items: baseline;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

.cert-field > label.required::before {
  content: '*';
  margin-right: 3px;
  color: var(--color-danger);
  font-weight: 700;
}

/* 日期选择器内部实际是 el-date-editor，需穿透子组件确保通栏 */
.cert-field :deep(.el-date-editor) {
  width: 100%;
}

/* 剩余天数颜色（详情模式 LqDescriptions 槽位） */
.days-warn {
  color: var(--color-warning);
}

.days-danger {
  color: var(--color-danger);
}

/* 专利号大字突出（详情模式号码信息组） */
.cert-patent-no {
  font-family: var(--font-mono, 'SFMono-Regular', Consolas, monospace);
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
  letter-spacing: 0.03em;
}

/* 状态语义色标签（缴费状态 / 发票状态） */
.cert-value-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: 1.4;
}

.cert-value-tag.tag-ok {
  background: var(--color-success-bg-soft);
  color: var(--color-success-text);
  border: 1px solid var(--color-success-border);
}

.cert-value-tag.tag-warn {
  background: var(--color-warning-bg-soft);
  color: var(--color-warning-text);
  border: 1px solid var(--color-warning-border);
}

.cert-value-tag.tag-expired {
  background: var(--color-danger-bg-soft);
  color: var(--color-danger-text);
  border: 1px solid var(--color-danger-border);
}

/* ===== 文件上传 ===== */
.file-upload-cell {
  margin-top: 2px;
  padding: 6px;
  border: 1px dashed transparent;
  border-radius: var(--radius-md);
  transition:
    border-color var(--transition-fast),
    background var(--transition-fast),
    box-shadow var(--transition-fast);
}

.file-upload-cell.drag-over {
  border-color: var(--color-primary);
  background: var(--color-primary-bg-light);
  box-shadow: 0 0 0 3px var(--color-primary-ring-strong);
}

.file-preview-item {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  padding: var(--space-control-gap);
  background: var(--color-surface-raised);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.file-thumb {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.file-thumb-sm {
  width: 32px;
  height: 32px;
  object-fit: cover;
  border-radius: var(--radius-sm);
}

.file-name-text {
  flex: 1;
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name-clickable {
  cursor: pointer;
  color: var(--color-primary);
}

.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  background: var(--color-danger-bg-soft);
  color: var(--color-danger);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--fs-base);
  transition: background var(--transition-fast);
}

.btn-icon:hover {
  background: var(--color-danger);
  color: var(--color-surface);
}

.btn-sm {
  padding: 6px 12px;
  font-size: var(--fs-sm);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-sm:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.btn-sm.btn-upload {
  border-style: dashed;
  color: var(--color-primary);
}

.btn-sm.btn-upload:hover {
  background: var(--color-primary-bg);
}

.file-link {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  cursor: pointer;
  color: var(--color-primary);
}
</style>
