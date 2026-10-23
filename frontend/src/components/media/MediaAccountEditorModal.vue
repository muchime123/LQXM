<template>
  <LqDialog
    :model-value="visible"
    size="lg"
    mode="form"
    :close-on-click-modal="false"
    destroy-on-close
    class="media-form-dialog editor-account-dialog"
    confirm-text="保存账号"
    :loading="saving"
    @update:model-value="val => emit('update:visible', val)"
    @close="handleClose"
    @confirm="handleSave"
  >
    <template #title>
      <div class="form-dialog-header">
        <div>
          <h3>{{ isNew ? '新建账号' : '编辑账号' }}</h3>
          <p>{{ isNew ? '添加自媒体账号资料' : '修改账号资料与主页信息' }}</p>
        </div>
        <span v-if="!isNew" :class="['platform-badge', platformClass(form.platform)]">
          {{ form.platform || '未选择平台' }}
        </span>
      </div>
    </template>

    <div class="editor-form-body">
      <FormSection
        title="所属平台"
        description="选择本账号归属的自媒体平台，决定徽标颜色和快捷筛选分类。"
        :required-count="1"
      >
        <div class="platform-chip-row editor-platform-row">
          <button
            v-for="cfg in platforms"
            :key="cfg.name"
            type="button"
            class="platform-chip editor-platform-chip"
            :class="{ 'chip-active': form.platform === cfg.name }"
            :style="form.platform === cfg.name ? { borderColor: cfg.color, background: cfg.bg, color: cfg.color } : {}"
            @click="form.platform = cfg.name"
          >
            <span class="chip-icon">{{ cfg.icon }}</span>
            <span class="chip-name">{{ cfg.name }}</span>
          </button>
        </div>
      </FormSection>

      <FormSection
        title="基础信息"
        description="账号名称、ID、运营人员和发布负责人等核心识别信息。"
        :required-count="1"
      >
        <div class="editor-grid">
          <label class="field-labeled editor-field editor-field-wide">
            <span class="field-label">账号名称 <span class="required">*</span></span>
            <el-input v-model="form.accountName" placeholder="填写账号名称" />
          </label>
          <label class="field-labeled editor-field">
            <span class="field-label">账号 ID</span>
            <el-input v-model="form.accountId" placeholder="唯一标识，选填" />
          </label>
          <label class="field-labeled editor-field">
            <span class="field-label">运营人员</span>
            <el-autocomplete
              v-model="form.operator"
              :fetch-suggestions="fetchOperatorSuggestions"
              placeholder="负责运营的人员"
              style="width: 100%"
            />
          </label>
          <label class="field-labeled editor-field">
            <span class="field-label">发布负责人</span>
            <el-autocomplete
              v-model="form.publishOwner"
              :fetch-suggestions="fetchPublishOwnerSuggestions"
              placeholder="负责批量发布的人员"
              style="width: 100%"
            />
          </label>
        </div>
      </FormSection>

      <FormSection title="账号数据" description="粉丝、获赞、视频数等关键运营指标（用于看板和排序对比）。">
        <div class="editor-grid editor-metrics-grid">
          <label class="field-labeled editor-field">
            <span class="field-label">粉丝数</span>
            <el-input v-model="form.followersCount" type="number" :min="0" placeholder="0" />
          </label>
          <label class="field-labeled editor-field">
            <span class="field-label">获赞数</span>
            <el-input v-model="form.likesCount" type="number" :min="0" placeholder="0" />
          </label>
          <label class="field-labeled editor-field">
            <span class="field-label">视频数</span>
            <el-input v-model="form.videoCount" type="number" :min="0" placeholder="0" />
          </label>
        </div>
      </FormSection>

      <FormSection title="补充信息" description="主页链接、标签、官网展示标记和备注，帮助快速检索与记录。">
        <div class="editor-grid">
          <label class="field-labeled editor-field editor-field-wide">
            <span class="field-label">主页链接</span>
            <el-input v-model="form.link" placeholder="账号主页 URL" />
          </label>
          <label class="field-labeled editor-field">
            <span class="field-label">标签</span>
            <LqTagInput v-model="form.tags" placeholder="输入标签后按 Enter 添加" />
          </label>
          <label class="field-labeled editor-field">
            <span class="field-label">官网官方展示</span>
            <div class="field-control-inline">
              <el-switch
                v-model="form.websiteOfficial"
                :active-value="true"
                :inactive-value="false"
                active-text="作为官网官方账号展示"
                inactive-text="仅内部管理"
              />
            </div>
          </label>
          <label class="field-labeled editor-field editor-field-wide">
            <span class="field-label">备注</span>
            <el-input v-model="form.note" placeholder="备注信息" />
          </label>
        </div>
      </FormSection>
    </div>
  </LqDialog>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import FormSection from '../common/FormSection.vue'
import LqTagInput from '../common/LqTagInput.vue'
import { platformClass } from '../../utils/media/platformUtils'
import { notify } from '../../composables/ui/useToast'

const props = defineProps({
  visible: { type: Boolean, required: true },
  account: { type: Object, default: null },
  platforms: { type: Array, default: () => [] },
  operators: { type: Array, default: () => [] },
  publishOwners: { type: Array, default: () => [] },
  saving: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'close', 'save'])

const isNew = computed(() => !props.account?.id)

const form = reactive({
  platform: '',
  accountName: '',
  accountId: '',
  link: '',
  note: '',
  operator: '',
  publishOwner: '',
  tags: '',
  followersCount: '',
  likesCount: '',
  videoCount: '',
  websiteOfficial: false
})

function initForm() {
  const defaultPlatform = props.platforms.length > 0 ? props.platforms[0].name : '抖音'
  if (isNew.value) {
    Object.assign(form, {
      platform: defaultPlatform,
      accountName: '',
      accountId: '',
      link: '',
      note: '',
      operator: '',
      publishOwner: '',
      tags: '',
      followersCount: '',
      likesCount: '',
      videoCount: '',
      websiteOfficial: false
    })
  } else if (props.account) {
    Object.assign(form, {
      platform: props.account.platform || defaultPlatform,
      accountName: props.account.accountName || '',
      accountId: props.account.accountId || '',
      link: props.account.link || '',
      note: props.account.note || '',
      operator: props.account.operator || '',
      publishOwner: props.account.publishOwner || '',
      tags: props.account.tags || '',
      followersCount: props.account.followersCount ?? '',
      likesCount: props.account.likesCount ?? '',
      videoCount: props.account.videoCount ?? '',
      websiteOfficial: !!props.account.websiteOfficial
    })
  }
}

watch(
  () => props.visible,
  val => {
    if (val) initForm()
  }
)

function editorNumberOrNull(value) {
  if (value === '' || value === null || value === undefined) return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

function fetchOperatorSuggestions(query, cb) {
  const q = (query || '').toLowerCase()
  cb(props.operators.filter(op => !q || String(op).toLowerCase().includes(q)).map(op => ({ value: op })))
}

function fetchPublishOwnerSuggestions(query, cb) {
  const q = (query || '').toLowerCase()
  cb(props.publishOwners.filter(o => !q || String(o).toLowerCase().includes(q)).map(o => ({ value: o })))
}

function handleSave() {
  if (!form.platform?.trim()) {
    notify('请选择平台', 'warning')
    return
  }
  if (!form.accountName?.trim()) {
    notify('请填写账号名称', 'warning')
    return
  }
  emit('save', {
    ...form,
    followersCount: editorNumberOrNull(form.followersCount),
    likesCount: editorNumberOrNull(form.likesCount),
    videoCount: editorNumberOrNull(form.videoCount),
    websiteOfficial: !!form.websiteOfficial
  })
}

function handleClose() {
  emit('update:visible', false)
  emit('close')
}
</script>

<style scoped>
@import '../../styles/media-account-view.css';

.editor-form-body {
  background: var(--color-surface);
  display: grid;
  gap: 14px;
  padding: 0;
}

.editor-section {
  border-top: 1px solid var(--color-border);
  padding-top: var(--space-md);
}

.editor-section:first-of-type {
  border-top: 0;
  padding-top: 0;
}

.editor-section-title {
  color: var(--color-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-extrabold);
  margin-bottom: 7px;
}

.editor-platform-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.editor-platform-chip {
  border-radius: var(--radius-pill);
  justify-content: flex-start;
  min-height: 30px;
  padding: 5px 10px;
}

.editor-platform-chip .chip-icon {
  font-size: var(--fs-sm);
}

.editor-platform-chip .chip-name {
  font-size: var(--fs-xs);
}

.editor-grid {
  display: grid;
  gap: 10px 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.editor-metrics-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.editor-field-wide {
  grid-column: 1 / -1;
}


.platform-chip-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
  gap: var(--space-control-gap);
}

.platform-chip {
  align-items: center;
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  color: var(--color-text-strong);
  cursor: pointer;
  display: flex;
  gap: var(--space-control-gap);
  justify-content: center;
  min-height: 36px;
  padding: 7px 10px;
}

.platform-chip:hover {
  border-color: var(--color-primary);
}

.platform-chip.chip-active {
  box-shadow: 0 8px 22px var(--color-primary-ring);
}

.chip-icon {
  font-size: var(--fs-md);
}

.chip-name {
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
}

.field-labeled {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}

.field-control-inline {
  display: flex;
  align-items: center;
  min-height: 32px;
}

.field-label {
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
}

.required {
  color: var(--color-danger);
}

.field-hint {
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
}

.platform-badge {
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-pill);
  color: var(--color-text-strong);
  display: inline-flex;
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  padding: 3px 8px;
  white-space: nowrap;
}
</style>
