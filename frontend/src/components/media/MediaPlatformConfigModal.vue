<template>
  <LqDialog
    :model-value="visible"
    title="平台配置"
    subtitle="管理自媒体平台（名称、图标、主题色）"
    size="md"
    mode="detail"
    :show-footer="true"
    :show-cancel="false"
    :close-on-click-modal="false"
    destroy-on-close
    class="media-platform-dialog"
    confirm-text="完成"
    @close="handleClose"
    @confirm="handleClose"
  >
    <div class="dialog-form-body">
      <div class="add-row">
        <div class="add-fields">
          <div class="field-group">
            <label>名称</label>
            <input v-model="newPlatform.name" placeholder="如：YouTube" @keyup.enter="addPlatform" />
          </div>
          <div class="field-group add-field-icon">
            <label>图标</label>
            <input v-model="newPlatform.icon" placeholder="📱" maxlength="4" />
          </div>
          <div class="field-group add-field-color">
            <label>颜色</label>
            <div class="color-pick-row">
              <input v-model="newPlatform.color" type="color" class="color-input" />
              <input v-model="newPlatform.color" placeholder="var(--color-text-muted)" class="color-text" />
            </div>
          </div>
        </div>
        <button type="button" class="btn-add" :disabled="!newPlatform.name.trim()" @click="addPlatform">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
            stroke-linecap="round"
          >
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" /></svg
          >添加
        </button>
      </div>
      <div class="platform-table-wrap">
        <table class="platform-table">
          <thead>
            <tr>
              <th style="width: 50px">预览</th>
              <th>平台名称</th>
              <th style="width: 80px">图标</th>
              <th style="width: 110px">主题色</th>
              <th style="width: 80px">状态</th>
              <th style="width: 120px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="cfg in platformConfigs" :key="cfg.id" :class="{ editing: editingId === cfg.id }">
              <td>
                <span class="preview-badge" :style="{ background: hexToRgba(cfg.color), color: cfg.color }"
                  >{{ cfg.icon }} {{ cfg.name }}</span
                >
              </td>
              <td>
                <template v-if="editingId === cfg.id">
                  <input v-model="editForm.name" class="inline-input" />
                </template>
                <template v-else>
                  <strong>{{ cfg.name }}</strong>
                </template>
              </td>
              <td>
                <template v-if="editingId === cfg.id">
                  <input v-model="editForm.icon" class="inline-input" style="width: 60px" maxlength="4" />
                </template>
                <template v-else>
                  <span class="icon-circle">{{ cfg.icon }}</span>
                </template>
              </td>
              <td>
                <template v-if="editingId === cfg.id">
                  <div class="color-pick-row">
                    <input v-model="editForm.color" type="color" class="color-input-small" />
                    <input v-model="editForm.color" class="inline-input" style="width: 80px" />
                  </div>
                </template>
                <template v-else>
                  <span class="color-swatch" :style="{ background: cfg.color }"></span>
                  <code>{{ cfg.color }}</code>
                </template>
              </td>
              <td>
                <template v-if="editingId === cfg.id">
                  <label class="toggle-label">
                    <input v-model="editForm.isActive" type="checkbox" />
                    <span>{{ editForm.isActive ? '启用' : '禁用' }}</span>
                  </label>
                </template>
                <template v-else>
                  <StatusTag
                    :type="cfg.isActive ? 'tag-ok' : 'tag-neutral'"
                    :label="cfg.isActive ? '启用' : '禁用'"
                    size="sm"
                  />
                </template>
              </td>
              <td class="action-cell">
                <template v-if="editingId === cfg.id">
                  <button type="button" class="icon-action save" @click="saveEdit(cfg)">
                    <svg
                      viewBox="0 0 24 24"
                      width="15"
                      height="15"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2.5"
                      stroke-linecap="round"
                    >
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                  </button>
                  <button type="button" class="icon-action" @click="cancelEdit">
                    <svg
                      viewBox="0 0 24 24"
                      width="15"
                      height="15"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2.5"
                      stroke-linecap="round"
                    >
                      <line x1="18" y1="6" x2="6" y2="18" />
                      <line x1="6" y1="6" x2="18" y2="18" />
                    </svg>
                  </button>
                </template>
                <template v-else>
                  <button type="button" class="icon-action" @click="startEdit(cfg)">
                    <svg
                      viewBox="0 0 24 24"
                      width="14"
                      height="14"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                    >
                      <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" />
                    </svg>
                  </button>
                  <button type="button" class="icon-action danger" @click="removePlatform(cfg)">
                    <svg
                      viewBox="0 0 24 24"
                      width="14"
                      height="14"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                    >
                      <polyline points="3 6 5 6 21 6" />
                      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
                      <path d="M10 11v6" />
                      <path d="M14 11v6" />
                      <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
                    </svg>
                  </button>
                </template>
              </td>
            </tr>
            <tr v-if="platformConfigs.length === 0">
              <td colspan="6" class="empty-cell">暂无平台配置，请添加</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </LqDialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import StatusTag from '../common/StatusTag.vue'
import { createPlatformConfig, deletePlatformConfig, fetchPlatformConfigs, updatePlatformConfig } from '../../api/media'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
import { notify } from '../../composables/ui/useToast'
import { refreshPlatformConfigs } from '../../utils/media/platformUtils'

const props = defineProps({
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'close', 'saved'])

const platformConfigs = ref([])
const editingId = ref(null)
const editForm = reactive({ name: '', icon: '', color: '', isActive: true })
const newPlatform = reactive({ name: '', icon: '📱', color: 'var(--color-text-muted)' })

watch(
  () => props.visible,
  async v => {
    if (v) {
      await loadConfigs()
    }
  }
)

async function loadConfigs() {
  try {
    platformConfigs.value = await fetchPlatformConfigs()
  } catch (err) {
    console.warn('加载平台配置失败', err)
  }
}

async function addPlatform() {
  const name = newPlatform.name.trim()
  if (!name) return
  try {
    await createPlatformConfig({
      name,
      icon: newPlatform.icon || '📱',
      color: newPlatform.color || 'var(--color-text-muted)',
      sortOrder: platformConfigs.value.length + 1,
      isActive: true
    })
    newPlatform.name = ''
    newPlatform.icon = '📱'
    newPlatform.color = 'var(--color-text-muted)'
    await loadConfigs()
    await refreshPlatformConfigs()
    emit('saved')
  } catch (err) {
    notify.errorWithDetail('添加失败', err)
  }
}

function startEdit(cfg) {
  editingId.value = cfg.id
  editForm.name = cfg.name
  editForm.icon = cfg.icon || '📱'
  editForm.color = cfg.color || 'var(--color-text-muted)'
  editForm.isActive = cfg.isActive
}

function cancelEdit() {
  editingId.value = null
}

async function saveEdit(cfg) {
  try {
    await updatePlatformConfig(cfg.id, {
      name: editForm.name.trim(),
      icon: editForm.icon || '📱',
      color: editForm.color || 'var(--color-text-muted)',
      isActive: editForm.isActive
    })
    editingId.value = null
    await loadConfigs()
    await refreshPlatformConfigs()
    emit('saved')
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  }
}

async function removePlatform(cfg) {
  const ok = await askConfirm({
    title: '删除平台配置',
    message: `确定删除平台 "${cfg.name}"？`,
    confirmText: '删除',
    danger: true
  })
  if (!ok) return
  try {
    await deletePlatformConfig(cfg.id)
    await loadConfigs()
    await refreshPlatformConfigs()
    notify('平台配置已删除')
    emit('saved')
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

function hexToRgba(hex, alpha = 0.12) {
  hex = hex.replace('#', '')
  if (hex.length === 3)
    hex = hex
      .split('')
      .map(c => c + c)
      .join('')
  const r = parseInt(hex.substring(0, 2), 16),
    g = parseInt(hex.substring(2, 4), 16),
    b = parseInt(hex.substring(4, 6), 16)
  return `rgba(${r},${g},${b},${alpha})`
}

function handleClose() {
  emit('update:visible', false)
  emit('close')
}
</script>

<style scoped>
@import '../../styles/media-account-view.css';

.media-platform-dialog .dialog-form-body {
  display: grid;
  gap: 12px;
  padding: 0;
}

.add-row {
  align-items: end;
  grid-template-columns: minmax(0, 1fr) auto;
}

.add-fields {
  align-items: end;
  display: grid;
  gap: var(--space-control-gap);
  grid-template-columns: minmax(160px, 1fr) 70px minmax(0, 1fr);
}

.add-field-icon {
  max-width: 70px;
}

.add-field-color {
  max-width: 220px;
}

.color-pick-row {
  min-width: 0;
}

.color-text {
  flex: 1 1 auto;
  min-width: 0;
  width: auto;
}

.platform-table-wrap {
  margin-top: 0;
}

.platform-table th {
  position: sticky;
  top: 0;
  z-index: 1;
}

@media (max-width: 767px) {
  .add-fields {
    grid-template-columns: 1fr;
  }

  .add-field-icon,
  .add-field-color {
    max-width: none;
  }
}
</style>
