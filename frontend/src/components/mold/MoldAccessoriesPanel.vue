<template>
  <div class="accessories-panel">
    <div class="panel-header">
      <div class="panel-title">
        <h4>配件组成</h4>
        <span class="panel-hint">
          不按高度分组（多高度同模换配件时，差异在规格层面）
        </span>
      </div>
      <div class="panel-actions">
        <el-button size="small" @click="showAddDialog = true">新增配件</el-button>
      </div>
    </div>

    <!-- 原始文本追溯 -->
    <div v-if="rawText" class="raw-trace">
      <span class="raw-prefix">原始整格文本：</span>
      <span class="raw-content">{{ rawText }}</span>
    </div>

    <!-- 配件卡片列表 -->
    <DataState v-if="!loading"
      :state="accessories.length ? 'ready' : 'empty'"
      empty-text="暂无配件记录"
      empty-hint="点击右上角「新增配件」，录入杯胆/杯底/拉伸头/压料环等。">
      <div class="accessory-list">
        <div v-for="a in accessories" :key="a.id" class="accessory-card">
          <div class="accessory-main">
            <div class="accessory-type-badge">
              <el-tag size="small" :type="accTypeTag(a.accessoryType)">{{
                accTypeLabel(a.accessoryType)
              }}</el-tag>
              <el-tag v-if="a.accessoryCode" size="small" type="info" effect="plain" class="code-tag">
                {{ a.accessoryCode }}
              </el-tag>
            </div>
            <div class="accessory-meta">
              <span class="meta-row">
                数量：<strong>{{ a.quantity }}</strong>
              </span>
              <span class="meta-row">
                <el-tag size="small" effect="light" :type="scopeTag(a.sharedScope)">
                  共用：{{ scopeLabel(a.sharedScope) }}
                </el-tag>
              </span>
            </div>
            <p v-if="a.remark" class="accessory-remark">{{ a.remark }}</p>
          </div>
          <div class="accessory-actions">
            <el-button link type="primary" size="small" @click="onEdit(a)">编辑</el-button>
            <el-popconfirm title="删除该配件？" @confirm="onRemove(a)">
              <template #reference>
                <el-button link type="danger" size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </div>
        </div>
      </div>
    </DataState>

    <!-- 新增/编辑 -->
    <LqDialog v-model:model-value="showAddDialog" :title="editingId ? '编辑配件' : '新增配件'" size="sm" mode="form"
      :loading="saving" @confirm="onSubmit" @cancel="closeForm" confirm-text="保存" cancel-text="取消">
      <el-form :model="form" label-width="90px">
        <el-form-item label="配件类型">
          <el-select v-model="form.accessoryType" filterable>
            <el-option label="杯胆" value="cup_liner" />
            <el-option label="杯底" value="cup_bottom" />
            <el-option label="拉伸头" value="drawing_head" />
            <el-option label="压料环" value="press_ring" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="配件编号">
          <el-input v-model="form.accessoryCode" placeholder="如 B-2216（可选）" />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="form.quantity" :min="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="共用范围">
          <el-select v-model="form.sharedScope">
            <el-option label="专用" value="dedicated" />
            <el-option label="系列通用" value="series_shared" />
            <el-option label="全局通用" value="global_shared" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="尺寸/来源/注意事项" />
        </el-form-item>
      </el-form>
    </LqDialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import {
  type MoldAccessoryView,
  createMoldAccessory,
  updateMoldAccessory,
  deleteMoldAccessory
} from '@/api'
import { ElMessage } from 'element-plus'
import DataState from '@/components/common/DataState.vue'
import LqDialog from '@/components/common/LqDialog.vue'

const props = defineProps<{
  moldId: number | undefined
  accessories: MoldAccessoryView[]
  rawText?: string
}>()
const emit = defineEmits<{ changed: [] }>()

const loading = ref(false)
const saving = ref(false)
const showAddDialog = ref(false)
const editingId = ref<number | null>(null)

const form = reactive<{
  accessoryType: string
  accessoryCode?: string
  quantity: number
  sharedScope: string
  remark?: string
}>({
  accessoryType: 'cup_liner',
  accessoryCode: '',
  quantity: 1,
  sharedScope: 'dedicated',
  remark: ''
})

const ACC_TYPE_LABELS: Record<string, { label: string; tag: 'primary' | 'success' | 'warning' | 'info' | 'danger' }> = {
  cup_liner: { label: '杯胆', tag: 'primary' },
  cup_bottom: { label: '杯底', tag: 'success' },
  drawing_head: { label: '拉伸头', tag: 'warning' },
  press_ring: { label: '压料环', tag: 'info' },
  other: { label: '其他', tag: 'info' }
}
const SCOPE_LABELS: Record<string, { label: string; tag: 'primary' | 'success' | 'info' }> = {
  dedicated: { label: '专用', tag: 'info' },
  series_shared: { label: '系列通用', tag: 'primary' },
  global_shared: { label: '全局通用', tag: 'success' }
}
function accTypeLabel(t?: string) { return ACC_TYPE_LABELS[t || '']?.label || t || '其他' }
function accTypeTag(t?: string) { return ACC_TYPE_LABELS[t || '']?.tag || 'info' }
function scopeLabel(s?: string) { return SCOPE_LABELS[s || '']?.label || s || '专用' }
function scopeTag(s?: string) { return SCOPE_LABELS[s || '']?.tag || 'info' }

function onEdit(a: MoldAccessoryView) {
  editingId.value = a.id
  form.accessoryType = a.accessoryType
  form.accessoryCode = a.accessoryCode || ''
  form.quantity = a.quantity
  form.sharedScope = a.sharedScope
  form.remark = a.remark || ''
  showAddDialog.value = true
}

function closeForm() {
  editingId.value = null
  Object.assign(form, { accessoryType: 'cup_liner', accessoryCode: '', quantity: 1, sharedScope: 'dedicated', remark: '' })
  showAddDialog.value = false
}

async function onSubmit() {
  if (!props.moldId) return
  try {
    saving.value = true
    if (editingId.value) {
      await updateMoldAccessory(editingId.value, {
        moldId: props.moldId,
        accessoryType: form.accessoryType,
        accessoryCode: form.accessoryCode,
        quantity: form.quantity,
        sharedScope: form.sharedScope,
        remark: form.remark
      })
      ElMessage.success('已更新')
    } else {
      await createMoldAccessory({
        moldId: props.moldId,
        accessoryType: form.accessoryType,
        accessoryCode: form.accessoryCode,
        quantity: form.quantity,
        sharedScope: form.sharedScope,
        remark: form.remark
      })
      ElMessage.success('已新增')
    }
    closeForm()
    emit('changed')
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onRemove(a: MoldAccessoryView) {
  try {
    await deleteMoldAccessory(a.id)
    ElMessage.success('已删除')
    emit('changed')
  } catch (e: any) {
    ElMessage.error(e?.message || '失败')
  }
}

onMounted(() => {
  // accessories 通过 props 注入
})
</script>

<style scoped>
.accessories-panel {
  padding-top: var(--space-xs, 4px);
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-sm-md);
}

.panel-title {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm-md);
}

.panel-title h4 {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  margin: 0;
}

.panel-hint {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.raw-trace {
  display: flex;
  gap: var(--space-sm-md);
  padding: var(--space-sm-md) var(--space-md-lg);
  background: var(--color-bg-elevated, #f9fafb);
  border-radius: var(--card-radius);
  margin-bottom: var(--space-md-lg);
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.raw-trace .raw-prefix {
  flex-shrink: 0;
}

.raw-trace .raw-content {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  color: var(--color-text-secondary, #374151);
  word-break: break-word;
}

.accessory-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-md-lg);
}

.accessory-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: var(--space-lg-xl);
  border: 1px solid var(--color-border-subtle, #e5e7eb);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  transition: box-shadow 0.15s;
}

.accessory-card:hover {
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.05);
}

.accessory-type-badge {
  display: flex;
  align-items: center;
  gap: var(--space-xs, 4px);
  margin-bottom: var(--space-sm-md);
}

.accessory-type-badge .code-tag {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.accessory-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: var(--space-xs, 4px);
}

.meta-row {
  font-size: 12px;
  color: var(--color-text-secondary, #374151);
}

.meta-row strong {
  color: var(--color-text-primary, #111827);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.accessory-remark {
  margin: var(--space-sm-md) 0 0;
  font-size: 12px;
  color: var(--color-text-secondary, #374151);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}

.accessory-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-xs, 4px);
  flex-shrink: 0;
}
</style>
