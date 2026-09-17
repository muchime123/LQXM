<template>
  <div class="milestones-page">
    <div class="milestones-header">
      <h3>发展历程</h3>
      <el-button type="primary" size="small" @click="openMilestoneDialog()"> + 添加里程碑 </el-button>
    </div>

    <div v-if="!milestonesLoading && milestones.length === 0" class="empty">
      <p>暂无里程碑记录</p>
    </div>

    <div v-loading="milestonesLoading" class="timeline">
      <div v-for="(m, i) in milestones" :key="m.id" class="timeline-item">
        <div class="timeline-dot">
          <span class="dot-icon">{{ m.icon || '📅' }}</span>
        </div>
        <div v-if="i < milestones.length - 1" class="timeline-line" />
        <div class="timeline-card">
          <div class="timeline-year">{{ m.year }}</div>
          <div class="timeline-title">
            {{ m.title }}
            <el-tag v-if="m.websiteEnabled === false" size="small" type="info" effect="plain">官网隐藏</el-tag>
          </div>
          <div v-if="m.description" class="timeline-desc">{{ m.description }}</div>
          <div v-if="m.fileName" class="timeline-file">
            <svg
viewBox="0 0 24 24"
fill="none"
stroke="currentColor"
stroke-width="2"
width="14"
height="14">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
            </svg>
            {{ m.fileName }}
          </div>
          <div class="timeline-actions">
            <el-button size="small" text @click="editMilestone(m)">编辑</el-button>
            <el-button
size="small"
text
type="danger"
@click="removeMilestone(m)">删除</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <MilestoneFormDialog
      v-model="showMilestoneDialog"
      :is-edit="!!editingMilestone"
      :form-data="milestoneFormData"
      :saving="milestoneSaving"
      @save="handleMilestoneSave"
      @close="showMilestoneDialog = false"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import MilestoneFormDialog from './MilestoneFormDialog.vue'
import { getMilestones, createMilestone, updateMilestone, deleteMilestone } from '../../api/company'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
import { notify } from '../../composables/ui/useToast'
import type { CompanyMilestone } from '../../types/company'

const milestones = ref<CompanyMilestone[]>([])
const milestonesLoading = ref(false)
const showMilestoneDialog = ref(false)
const milestoneSaving = ref(false)
const editingMilestone = ref<CompanyMilestone | null>(null)
const milestoneFormData = ref<Partial<CompanyMilestone>>({ year: new Date().getFullYear(), sortOrder: 0 })

async function loadMilestones() {
  milestonesLoading.value = true
  try {
    milestones.value = await getMilestones()
  } catch {
    notify('加载发展历程失败', 'error')
  } finally {
    milestonesLoading.value = false
  }
}

function openMilestoneDialog() {
  showMilestoneDialog.value = true
  editingMilestone.value = null
  milestoneFormData.value = { year: new Date().getFullYear(), sortOrder: 0 }
}

function editMilestone(m: CompanyMilestone) {
  editingMilestone.value = m
  milestoneFormData.value = { ...m }
  showMilestoneDialog.value = true
}

async function handleMilestoneSave(data: Partial<CompanyMilestone>) {
  if (!data.year || !data.title) {
    notify('请填写年份和标题', 'warning')
    return
  }
  milestoneSaving.value = true
  try {
    if (editingMilestone.value) {
      await updateMilestone(editingMilestone.value.id, data)
      notify('更新成功')
    } else {
      await createMilestone(data)
      notify('创建成功')
    }
    showMilestoneDialog.value = false
    await loadMilestones()
  } catch {
    notify('操作失败', 'error')
  } finally {
    milestoneSaving.value = false
  }
}

async function removeMilestone(m: CompanyMilestone) {
  const ok = await askConfirm({
    title: '删除里程碑',
    message: `确定要删除里程碑「${m.title}」吗？此操作不可撤销。`,
    confirmText: '删除',
    danger: true
  })
  if (ok !== true) return
  try {
    await deleteMilestone(m.id)
    notify('已删除')
    await loadMilestones()
  } catch {
    notify('删除失败', 'error')
  }
}

onMounted(loadMilestones)
</script>

<style scoped>
.milestones-page {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  padding: 28px;
}

.milestones-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 28px;
}

.milestones-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text-heading);
}

.empty {
  text-align: center;
  padding: 48px 0;
  color: var(--color-text-faint);
}

.timeline {
  position: relative;
}

.timeline-item {
  position: relative;
  display: flex;
  gap: var(--space-section-gap-wide);
  margin-bottom: 24px;
}

.timeline-item:last-child {
  margin-bottom: 0;
}

.timeline-dot {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-light));
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 2px 8px var(--color-primary-ring);
}

.dot-icon {
  font-size: 20px;
}

.timeline-line {
  position: absolute;
  left: 23px;
  top: 48px;
  width: 2px;
  height: calc(100% - 24px);
  background: linear-gradient(180deg, var(--color-primary), var(--color-border-light));
}

.timeline-card {
  flex: 1;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  padding: 18px 24px;
  box-shadow: var(--shadow-xs);
  transition: all var(--transition-fast);
}

.timeline-card:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-sm);
}

.timeline-year {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-primary);
  margin-bottom: 6px;
}

.timeline-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-heading);
  margin-bottom: 8px;
}

.timeline-desc {
  font-size: 13px;
  color: var(--color-text-secondary);
  line-height: 1.6;
  margin-bottom: 8px;
}

.timeline-file {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-text-faint);
  padding: 4px 12px;
  background: var(--color-surface-alt);
  border-radius: var(--radius-md);
  margin-bottom: 8px;
}

.timeline-actions {
  display: flex;
  gap: var(--space-control-gap);
}
</style>
