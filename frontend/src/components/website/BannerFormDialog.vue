<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { fetchBannerById, createBanner, updateBanner } from '@/api/website/banners'
import { BANNER_POSITION_LABELS } from '@/types/website'
import type {
  WebsiteBannerDetail,
  WebsiteBannerPayload,
  BannerPosition
} from '@/types/website'
import { notify } from '@/composables/ui/useToast'
import WebsiteImageField from '@/components/website/WebsiteImageField.vue'

const props = defineProps<{
  banner: WebsiteBannerDetail | null
  saving: boolean
  defaultPosition?: BannerPosition
}>()

const emit = defineEmits<{
  (e: 'update:banner', value: boolean): void
  (e: 'save'): void
}>()

const loading = ref(false)
const initialPosition = (props.defaultPosition || 'HERO') as BannerPosition
const form = ref<WebsiteBannerPayload>({
  position: initialPosition,
  title: '',
  subtitle: '',
  description: '',
  imageUrl: '',
  imageAssetId: undefined,
  buttonText: '',
  buttonUrl: '',
  secondaryButtonText: '',
  secondaryButtonUrl: '',
  badge: '',
  sortOrder: 0,
  websiteEnabled: true,
  startDate: undefined,
  endDate: undefined
})

const isEditing = computed(() => !!props.banner?.id)

watch(
  () => props.banner,
  async (newVal) => {
    if (newVal?.id) {
      loading.value = true
      try {
        const detail = await fetchBannerById(newVal.id)
        form.value = {
          position: detail.position,
          title: detail.title,
          subtitle: detail.subtitle || '',
          description: detail.description || '',
          imageUrl: detail.imageUrl || '',
          imageAssetId: detail.imageAssetId || undefined,
          buttonText: detail.buttonText || '',
          buttonUrl: detail.buttonUrl || '',
          secondaryButtonText: detail.secondaryButtonText || '',
          secondaryButtonUrl: detail.secondaryButtonUrl || '',
          badge: detail.badge || '',
          sortOrder: detail.sortOrder,
          websiteEnabled: detail.websiteEnabled,
          startDate: detail.startDate || undefined,
          endDate: detail.endDate || undefined
        }
      } catch (err) {
        notify.errorWithDetail('加载Banner详情失败', err)
      } finally {
        loading.value = false
      }
    } else {
      form.value = {
        position: (props.defaultPosition || 'HERO') as BannerPosition,
        title: '',
        subtitle: '',
        description: '',
        imageUrl: '',
        imageAssetId: undefined,
        buttonText: '',
        buttonUrl: '',
        secondaryButtonText: '',
        secondaryButtonUrl: '',
        badge: '',
        sortOrder: 0,
        websiteEnabled: true,
        startDate: undefined,
        endDate: undefined
      }
    }
  },
  { immediate: true }
)

function validate(): boolean {
  if (!form.value.title?.trim()) {
    notify('请输入标题', 'warning')
    return false
  }
  if (!form.value.position) {
    notify('请选择展示位置', 'warning')
    return false
  }
  return true
}

async function handleSubmit() {
  if (!validate()) return

  const payload: WebsiteBannerPayload = {
    position: form.value.position as BannerPosition,
    title: form.value.title!.trim(),
    subtitle: form.value.subtitle?.trim() || undefined,
    description: form.value.description?.trim() || undefined,
    imageUrl: form.value.imageUrl?.trim() || undefined,
    imageAssetId: form.value.imageAssetId || undefined,
    buttonText: form.value.buttonText?.trim() || undefined,
    buttonUrl: form.value.buttonUrl?.trim() || undefined,
    secondaryButtonText: form.value.secondaryButtonText?.trim() || undefined,
    secondaryButtonUrl: form.value.secondaryButtonUrl?.trim() || undefined,
    badge: form.value.badge?.trim() || undefined,
    sortOrder: form.value.sortOrder || 0,
    websiteEnabled: form.value.websiteEnabled ?? true,
    startDate: form.value.startDate || undefined,
    endDate: form.value.endDate || undefined
  }

  try {
    if (isEditing.value && props.banner?.id) {
      await updateBanner(props.banner.id, payload)
      notify('更新成功', 'success')
    } else {
      await createBanner(payload)
      notify('创建成功', 'success')
    }
    emit('save')
    emit('update:banner', false)
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  }
}

function handleClose() {
  emit('update:banner', false)
}

const positionOptions = computed(() =>
  (Object.keys(BANNER_POSITION_LABELS) as BannerPosition[]).map(key => ({
    value: key,
    label: BANNER_POSITION_LABELS[key]
  }))
)
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="isEditing ? '编辑Banner' : '新增Banner'"
    width="720px"
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <div v-loading="loading" class="banner-form">
      <el-form :model="form" label-width="120px" label-position="right">
        <el-form-item label="展示位置" required>
          <el-select v-model="form.position" placeholder="请选择" style="width: 200px">
            <el-option
              v-for="opt in positionOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="标题" required>
          <el-input
            v-model="form.title"
            placeholder="输入Banner主标题"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="副标题">
          <el-input
            v-model="form.subtitle"
            placeholder="输入副标题（可选）"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="输入描述文本（可选）"
            maxlength="1000"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="Banner图片">
          <WebsiteImageField
            v-model="form.imageUrl"
            aspect-ratio="12 / 5"
            hint="首页 Hero 建议 1920×800（2.4:1）纯场景/设备图，不要内嵌文字；标题/描述/按钮由下方表单独立编辑，官网以 HTML 渲染。"
          />
        </el-form-item>

        <el-form-item label="标签文字">
          <el-input
            v-model="form.badge"
            placeholder="如: NEW / HOT（可选）"
            maxlength="100"
            style="width: 200px"
          />
        </el-form-item>

        <el-divider content-position="left">按钮设置</el-divider>

        <el-form-item label="主按钮文本">
          <el-input
            v-model="form.buttonText"
            placeholder="如: 探索产品"
            maxlength="100"
            style="width: 200px"
          />
        </el-form-item>

        <el-form-item label="主按钮链接">
          <el-input
            v-model="form.buttonUrl"
            placeholder="如: /products 或 https://..."
            maxlength="500"
          />
        </el-form-item>

        <el-form-item label="次按钮文本">
          <el-input
            v-model="form.secondaryButtonText"
            placeholder="次要按钮文本（可选）"
            maxlength="100"
            style="width: 200px"
          />
        </el-form-item>

        <el-form-item label="次按钮链接">
          <el-input
            v-model="form.secondaryButtonUrl"
            placeholder="次要按钮链接（可选）"
            maxlength="500"
          />
        </el-form-item>

        <el-divider content-position="left">显示设置</el-divider>

        <el-form-item label="排序">
          <el-input-number
            v-model="form.sortOrder"
            :min="0"
            :max="9999"
            controls-position="right"
          />
          <span class="form-tip">数值越小越靠前</span>
        </el-form-item>

        <el-form-item label="官网可见">
          <el-switch v-model="form.websiteEnabled" />
          <span class="form-tip">{{ form.websiteEnabled ? '已发布到官网' : '官网隐藏' }}</span>
        </el-form-item>

        <el-form-item label="生效开始日期">
          <el-date-picker
            v-model="form.startDate"
            type="date"
            placeholder="留空表示立即生效"
            value-format="YYYY-MM-DD"
            style="width: 200px"
          />
        </el-form-item>

        <el-form-item label="生效结束日期">
          <el-date-picker
            v-model="form.endDate"
            type="date"
            placeholder="留空表示永久有效"
            value-format="YYYY-MM-DD"
            style="width: 200px"
          />
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSubmit">
        {{ isEditing ? '保存修改' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.banner-form {
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 8px;
}

.image-preview {
  margin-top: 8px;
}

.image-preview img {
  max-width: 100%;
  max-height: 200px;
  border-radius: 8px;
  border: 1px solid var(--color-border);
}

.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: var(--color-text-secondary);
}
</style>
