<template>
  <LqDialog
    :model-value="modelValue"
    :title="isEdit ? '编辑资质/合规文件' : '新增资质/合规文件'"
    size="md"
    mode="form"
    destroy-on-close
    :loading="saving"
    @update:model-value="val => emit('update:modelValue', val)"
    @close="emit('close')"
    @confirm="handleSave"
  >
    <el-form :model="localForm" label-position="top">
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="名称" required>
            <el-input
              v-model="localForm.name"
              placeholder="如：ISO9001认证 / GB/T xxxx团体标准 / SGS检测报告"
              maxlength="200"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="分类">
            <el-select v-model="localForm.category" style="width: 100%">
              <el-option
                v-for="cat in COMPANY_CERTIFICATION_CATEGORIES"
                :key="cat"
                :label="cat"
                :value="cat"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="编号">
            <el-input
              v-model="localForm.certNumber"
              placeholder="标准号 / 报告编号 / 证书编号"
              maxlength="100"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="签发机构">
            <el-input
              v-model="localForm.issuingAuthority"
              placeholder="如：SGS / TÜV / 市监局 / 国标委"
              maxlength="200"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="状态">
            <el-select v-model="localForm.status" style="width: 100%">
              <el-option
                v-for="s in COMPANY_CERTIFICATION_STATUSES"
                :key="s"
                :label="s"
                :value="s"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="签发日期">
            <el-date-picker
              v-model="localForm.issueDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择日期"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="16">
          <el-form-item label="有效期">
            <div class="validity-wrapper">
              <el-date-picker
                v-model="localForm.expiryDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选择到期日期"
                style="flex: 1; min-width: 0"
                :disabled="!!localForm.isPermanent"
              />
              <el-checkbox
                v-model="localForm.isPermanent"
                class="permanent-checkbox"
              >
                长期有效
              </el-checkbox>
            </div>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="24">
          <el-form-item label="附件">
            <div
              class="file-upload-cell"
              :class="{ 'drag-over': fileDrop.isDragOver }"
              v-bind="fileDrop.dragHandlers"
            >
              <div v-if="fileNames.length" class="cert-files-grid">
                <div
                  v-for="(fn, idx) in fileNames"
                  :key="idx"
                  class="cert-file-pill"
                  :title="fn"
                >
                  <img
                    v-if="isImageFile(fn)"
                    :src="certificationPreviewUrl(fn, idx)"
                    class="cert-file-pill__thumb"
                    @click="emitPreview(fn, idx)"
                  />
                  <span
                    v-else
                    class="cert-file-pill__ico"
                    @click="emitPreview(fn, idx)"
                  >
                    📄
                  </span>
                  <span
                    class="cert-file-pill__name"
                    :class="isImageFile(fn) ? '' : 'cert-file-pill__name--clickable'"
                    @click="!isImageFile(fn) && emitPreview(fn, idx)"
                  >
                    {{ fn }}
                  </span>
                  <button
                    class="cert-file-pill__remove"
                    title="删除此附件"
                    @click.stop="handleRemoveFile(idx)"
                  >
                    ×
                  </button>
                </div>
              </div>
              <button
                class="btn-sm btn-upload btn-upload--inline"
                :disabled="uploading"
                @click="handleFileUpload"
              >
                {{ uploading ? '上传中...' : fileNames.length ? '+ 继续添加' : '+ 上传附件（可拖拽）' }}
              </button>
              <p v-if="!fileNames.length" class="file-upload-hint">
                支持 PDF / 图片 / Word，单文件 ≤ 50MB，可上传多个
              </p>
            </div>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="描述">
        <el-input
          v-model="localForm.description"
          type="textarea"
          :rows="3"
          placeholder="适用范围 / 检测项目 / 认证结论 / 标准简介等"
        />
      </el-form-item>

      <el-divider content-position="left">官网发布</el-divider>

      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="官网展示">
            <el-switch
              v-model="localForm.websiteEnabled"
              active-text="是"
              inactive-text="否"
            />
            <div class="form-hint">开启后该资质将出现在官网</div>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="首页精选">
            <el-switch
              v-model="localForm.websiteFeatured"
              :disabled="!localForm.websiteEnabled"
              active-text="是"
              inactive-text="否"
            />
            <div class="form-hint">在官网首页重点展示</div>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="官网排序">
            <el-input-number
              v-model="localForm.websiteSortOrder"
              :min="0"
              :max="9999"
              controls-position="right"
              style="width: 100%"
            />
            <div class="form-hint">升序排列，数字越小越靠前</div>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="官网描述">
        <el-input
          v-model="localForm.publicDescription"
          type="textarea"
          :rows="2"
          placeholder="官网公开描述（可与内部描述不同，留空则使用上方描述）"
        />
      </el-form-item>
    </el-form>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import {
  COMPANY_CERTIFICATION_CATEGORIES,
  COMPANY_CERTIFICATION_STATUSES,
  type CompanyCertificationPayload
} from '../../types/company'
import { useFileAssets } from '../../composables/upload/useFileAssets'
import { useFileUploadWorkflow } from '../../composables/upload/useFileUploadWorkflow'
import { useLocalDropUpload } from '../../composables'
import { findFileAssetHandle, fileAssetPreviewUrlByOptions } from '../../utils/ui/fileAssetUrls'
import { FILE_OWNER_TYPE_KEYS } from '../../types/fileAsset'
import { isImageFile } from '../../utils/ui/fileUtils'
import { askConfirm } from '../../composables'
import { notify } from '../../composables/ui/useToast'
import {
  certificationFileAssetId,
  parseCertificationFileNames,
  stringifyCertificationFileNames,
  type CertificationFileField
} from '../../utils/company/certificationFileAsset'

const props = defineProps<{
  modelValue: boolean
  isEdit: boolean
  formData: Partial<CompanyCertificationPayload>
  saving: boolean
  certificationId?: number | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', val: boolean): void
  (e: 'save', formData: CompanyCertificationPayload): void
  (e: 'close'): void
  (e: 'preview-asset', asset: unknown): void
}>()

const localForm = reactive<CompanyCertificationPayload>({
  name: '',
  nameEn: null,
  category: '检测报告',
  certNumber: '',
  issueDate: '',
  expiryDate: '',
  isPermanent: false,
  issuingAuthority: '',
  fileName: '',
  description: '',
  status: '有效',
  websiteEnabled: false,
  websiteFeatured: false,
  websiteSortOrder: 0,
  publicDescription: ''
})

const fileNames = ref<string[]>([])

watch(
  () => props.modelValue,
  val => {
    if (val && props.formData) {
      Object.assign(localForm, {
        name: props.formData.name ?? '',
        nameEn: props.formData.nameEn ?? null,
        category: props.formData.category ?? '检测报告',
        certNumber: props.formData.certNumber ?? '',
        issueDate: props.formData.issueDate ?? '',
        expiryDate: props.formData.expiryDate ?? '',
        isPermanent: props.formData.isPermanent ?? false,
        issuingAuthority: props.formData.issuingAuthority ?? '',
        fileName: props.formData.fileName ?? '',
        description: props.formData.description ?? '',
        status: props.formData.status ?? '有效',
        websiteEnabled: props.formData.websiteEnabled ?? false,
        websiteFeatured: props.formData.websiteFeatured ?? false,
        websiteSortOrder: props.formData.websiteSortOrder ?? 0,
        publicDescription: props.formData.publicDescription ?? ''
      })
      if (props.formData.isPermanent) {
        localForm.expiryDate = ''
      }
      fileNames.value = parseCertificationFileNames(props.formData.fileName)
    }
  }
)

// ===== 文件资产管理 =====

const certificationOwnerId = computed<number | null>(() => null)

const certificationFileAssets = useFileAssets({
  ownerType: FILE_OWNER_TYPE_KEYS.company,
  ownerId: certificationOwnerId,
  notify,
  onChanged: async () => {}
})

const certificationFileHandles = computed(() => certificationFileAssets.items.value)

watch(
  () => [props.modelValue, props.certificationId] as const,
  async ([visible, certId]) => {
    if (!visible || !certId) return
    await certificationFileAssets.loadCapabilities()
    await certificationFileAssets.load()
  },
  { immediate: true }
)

const { pickFiles, uploadFiles } = useFileUploadWorkflow({
  category: 'company',
  notify
})

const uploading = ref(false)

async function handleFileUpload() {
  uploading.value = true
  try {
    const results = await pickFiles({ multiple: false })
    const result = results[0] as { fileName?: string } | undefined
    if (result?.fileName) {
      fileNames.value.push(result.fileName)
      await certificationFileAssets.load()
    }
  } finally {
    uploading.value = false
  }
}

async function handleDropFiles(files: File[]) {
  if (!files.length) return
  uploading.value = true
  try {
    const results = await uploadFiles(files.slice(0, 1), { multiple: false, source: 'drag' })
    const result = results[0] as { fileName?: string } | undefined
    if (result?.fileName) {
      fileNames.value.push(result.fileName)
      await certificationFileAssets.load()
    }
  } finally {
    uploading.value = false
  }
}

const fileDrop = useLocalDropUpload({
  onFiles: files => handleDropFiles(files)
})

async function handleRemoveFile(index: number) {
  const fileName = fileNames.value[index]
  if (!fileName) return
  const ok = await askConfirm({
    title: '删除附件',
    message: `确定要删除附件「${fileName}」吗？删除后不可恢复。`,
    confirmText: '删除',
    danger: true
  })
  if (ok !== true) return
  const handle = findCertificationFileHandle(fileName)
  if (handle && props.certificationId != null) {
    try {
      await certificationFileAssets.remove(handle.assetId)
    } catch {
      // 资质尚未保存或文件句柄不存在，仅从本地列表移除
    }
  }
  fileNames.value.splice(index, 1)
}

function findCertificationFileHandle(fileName: string) {
  return findFileAssetHandle(certificationFileHandles.value, fileName)
}

const CERT_FIELD: CertificationFileField = 'fileName'

function certificationPreviewUrl(fileName: string, index: number) {
  const handle = findCertificationFileHandle(fileName)
  return (
    certificationFileAssets.previewUrl(handle) ||
    fileAssetPreviewUrlByOptions({
      ownerType: FILE_OWNER_TYPE_KEYS.company,
      assetId:
        handle?.assetId ||
        (props.certificationId != null
          ? certificationFileAssetId(props.certificationId, CERT_FIELD, index)
          : null),
      category: 'company',
      fileName
    })
  )
}

function emitPreview(fileName: string, index: number) {
  const handle = findCertificationFileHandle(fileName)
  emit('preview-asset', {
    fileName,
    title: (localForm.name || props.formData.name || '资质') + '：附件',
    ownerType: FILE_OWNER_TYPE_KEYS.company,
    assetId:
      handle?.assetId ||
      (props.certificationId != null
        ? certificationFileAssetId(props.certificationId, CERT_FIELD, index)
        : null),
    category: 'company',
    ownerId: props.certificationId,
    id: props.certificationId
  })
}

// ===== 保存 =====

function handleSave() {
  if (localForm.isPermanent) {
    localForm.expiryDate = ''
  }
  localForm.fileName = stringifyCertificationFileNames(fileNames.value)
  emit('save', { ...localForm })
}
</script>

<style scoped>
@import '../../styles/company-certifications-tab.css';

/* 附件：多文件胶囊网格布局（参考证书弹窗） */
.cert-files-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 10px;
}

.cert-file-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 48px;
  min-width: 0;
  padding: 4px 8px 4px 4px;
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  transition: border-color var(--transition-fast), background var(--transition-fast);
}

.cert-file-pill:hover {
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg-strong, rgba(64, 158, 255, 0.08));
}

.cert-file-pill__thumb {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  cursor: pointer;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
}

.cert-file-pill__ico {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--color-text-secondary);
  transition: background var(--transition-fast);
}

.cert-file-pill__ico:hover {
  background: var(--color-primary-bg-light);
  color: var(--color-primary);
}

.cert-file-pill__name {
  flex: 1;
  min-width: 80px;
  max-width: 260px;
  font-size: var(--fs-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cert-file-pill__name--clickable {
  color: var(--color-primary);
  cursor: pointer;
  text-decoration: underline;
  text-decoration-style: dotted;
}

.cert-file-pill__name--clickable:hover {
  text-decoration-style: solid;
}

.cert-file-pill__remove {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border-radius: var(--radius-full);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-muted);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.cert-file-pill__remove:hover {
  color: var(--color-danger);
  border-color: var(--color-danger);
  background: var(--color-danger-bg-soft);
}

.btn-upload--inline {
  margin-top: 0;
}

.validity-wrapper {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.validity-wrapper :deep(.el-date-editor) {
  transition: opacity 0.2s, filter 0.2s;
}

.validity-wrapper :deep(.el-date-editor.is-disabled) {
  opacity: 0.55;
  filter: grayscale(0.3);
}

.permanent-checkbox {
  flex-shrink: 0;
  margin-left: 4px;
  font-size: 13px;
  color: var(--color-text-secondary, #374151);
  user-select: none;
  padding: 8px 12px;
  border-radius: var(--radius-md, 6px);
  border: 1px solid var(--color-border, #e2e8f0);
  background: var(--color-surface, #fff);
  transition: all 0.18s;
  cursor: pointer;
  height: 32px;
  line-height: 1;
}

.permanent-checkbox:hover {
  border-color: var(--color-primary, #0f766e);
  background: var(--color-primary-bg-light, #f0fdfa);
}

.permanent-checkbox.is-checked {
  border-color: var(--color-primary, #0f766e);
  background: var(--color-primary-bg-light, #f0fdfa);
  color: var(--color-primary, #0f766e);
  font-weight: 500;
}

.permanent-checkbox :deep(.el-checkbox__inner) {
  border-radius: var(--radius-xs, 4px);
}

.permanent-checkbox :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
  color: var(--color-primary, #0f766e);
}

.form-hint {
  font-size: 12px;
  color: var(--color-text-tertiary);
  line-height: 1.4;
  margin-top: 4px;
}
</style>
