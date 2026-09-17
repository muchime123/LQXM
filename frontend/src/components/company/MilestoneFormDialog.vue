<template>
  <LqDialog
    :model-value="modelValue"
    :title="isEdit ? '编辑里程碑' : '新增里程碑'"
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
        <el-col :span="8">
          <el-form-item label="年份" required>
            <el-input-number
              v-model="localForm.year"
              :min="1900"
              :max="2099"
              :step="1"
              style="width: 100%"
              controls-position="right"
            />
          </el-form-item>
        </el-col>
        <el-col :span="16">
          <el-form-item label="图标 (emoji)">
            <el-input v-model="localForm.icon" placeholder="🎯" maxlength="10" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="标题" required>
        <el-input v-model="localForm.title" placeholder="如：公司正式成立" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input
v-model="localForm.description"
type="textarea"
:rows="3"
placeholder="详细说明" />
      </el-form-item>
      <el-form-item label="关联文件名">
        <el-input v-model="localForm.fileName" placeholder="如：营业执照.pdf" />
      </el-form-item>
      <el-form-item label="排序权重">
        <el-input-number v-model="localForm.sortOrder" :min="0" style="width: 100%" />
      </el-form-item>
      <el-form-item label="官网展示">
        <el-switch v-model="localForm.websiteEnabled" />
        <span class="form-hint">关闭后该里程碑不会出现在官网"关于我们"页面</span>
      </el-form-item>
    </el-form>
  </LqDialog>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'

interface MilestoneFormData {
  year: number
  icon: string
  title: string
  description: string
  fileName: string
  sortOrder: number
  websiteEnabled: boolean
}

const props = defineProps<{
  modelValue: boolean
  isEdit: boolean
  formData: Partial<MilestoneFormData>
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', val: boolean): void
  (e: 'save', formData: MilestoneFormData): void
  (e: 'close'): void
}>()

const localForm = reactive<MilestoneFormData>({
  year: new Date().getFullYear(),
  icon: '',
  title: '',
  description: '',
  fileName: '',
  sortOrder: 0,
  websiteEnabled: true
})

watch(
  () => props.modelValue,
  val => {
    if (val && props.formData) {
      Object.assign(localForm, {
        year: props.formData.year ?? new Date().getFullYear(),
        icon: props.formData.icon ?? '',
        title: props.formData.title ?? '',
        description: props.formData.description ?? '',
        fileName: props.formData.fileName ?? '',
        sortOrder: props.formData.sortOrder ?? 0,
        websiteEnabled: props.formData.websiteEnabled ?? true
      })
    }
  }
)

function handleSave() {
  emit('save', { ...localForm })
}
</script>
