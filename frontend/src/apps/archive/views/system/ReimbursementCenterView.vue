<template>
  <ListPageLayout
    class="reimb-center"
    title="日常报销"
    description="钉钉「日常报销」审批数据的完整工作区：在「报销台账」页签只读检索与核对报销单（按状态 / 分类 / 发生日期，不做费用归集），在「钉钉同步」页签负责 API 拉取或 Excel 导入留档。两个页签按各自权限独立可见；未识别员工的单据需完善员工姓名、手机号与部门后重新同步。"
    content-label="日常报销管理"
  >
    <template #eyebrow>系统管理 · REIMBURSEMENT</template>

    <div class="reimb-center__body">
      <nav class="reimb-center__tabs" role="tablist" aria-label="日常报销视图">
        <button
          v-if="canLedger"
          type="button"
          role="tab"
          class="reimb-center__tab"
          :class="{ active: visibleTab === 'ledger' }"
          :aria-selected="visibleTab === 'ledger'"
          @click="switchTab('ledger')"
        >
          <el-icon class="reimb-center__tab-icon"><Files /></el-icon>
          报销台账
        </button>
        <button
          v-if="canSync"
          type="button"
          role="tab"
          class="reimb-center__tab"
          :class="{ active: visibleTab === 'sync' }"
          :aria-selected="visibleTab === 'sync'"
          @click="switchTab('sync')"
        >
          <el-icon class="reimb-center__tab-icon"><Connection /></el-icon>
          钉钉同步
        </button>
      </nav>

      <!-- 两个面板用 KeepAlive 缓存：切换 Tab 保留台账筛选/分页等状态；onActivated 负责重激活刷新 -->
      <KeepAlive>
        <ReimbursementLedgerPanel v-if="visibleTab === 'ledger'" />
      </KeepAlive>
      <KeepAlive>
        <DingtalkSyncPanel v-if="visibleTab === 'sync'" />
      </KeepAlive>
    </div>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Connection, Files } from '@element-plus/icons-vue'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import ReimbursementLedgerPanel from '@/components/reimbursement/ReimbursementLedgerPanel.vue'
import DingtalkSyncPanel from '@/components/reimbursement/DingtalkSyncPanel.vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'

type ReimbursementTab = 'ledger' | 'sync'

const auth = useCurrentUser()
const route = useRoute()
const router = useRouter()

// 报销台账 = expense.read.self / expense.manage；钉钉同步 = integration.dingtalk
const canLedger = computed(() => auth.can(['expense.read.self', 'expense.manage']))
const canSync = computed(() => auth.can('integration.dingtalk'))

function readQueryTab(): ReimbursementTab {
  return route.query.tab === 'sync' ? 'sync' : 'ledger'
}

const activeTab = ref<ReimbursementTab>(readQueryTab())

// 兼容旧入口重定向 /system/dingtalk?tab=sync 等场景：URL 变化而本组件已挂载时同步选中项
watch(
  () => route.query.tab,
  () => {
    const t = readQueryTab()
    if (t !== activeTab.value) activeTab.value = t
  }
)

/** 权限收敛后的实际可见页签（默认报销台账，不可见时回退到有权限的一项） */
const visibleTab = computed<ReimbursementTab>(() => {
  if (activeTab.value === 'sync' && canSync.value) return 'sync'
  if (activeTab.value === 'ledger' && canLedger.value) return 'ledger'
  if (canSync.value) return 'sync'
  return 'ledger'
})

function switchTab(tab: ReimbursementTab) {
  if (tab === visibleTab.value) return
  activeTab.value = tab
  router.replace({ query: { ...route.query, tab } }).catch(() => {})
}
</script>

<style scoped>
/* ===== 页内 Tab ===== */
.reimb-center__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap);
}

.reimb-center__tabs {
  position: relative;
  z-index: var(--z-base);
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-control-gap-tight);
  padding: var(--space-control-gap-tight);
  align-self: flex-start;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
}

.reimb-center__tab {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-control-gap-tight) var(--space-lg);
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  line-height: var(--lh-normal);
  cursor: pointer;
  transition:
    color var(--transition-base),
    background-color var(--transition-base),
    border-color var(--transition-base),
    box-shadow var(--transition-base);
}

.reimb-center__tab:hover {
  background: var(--color-surface-hover);
  color: var(--color-text-strong);
}

.reimb-center__tab.active {
  background: var(--color-surface);
  border-color: var(--color-primary-border);
  color: var(--color-primary);
  box-shadow: var(--shadow-sm);
}

.reimb-center__tab-icon {
  font-size: var(--fs-base);
}
</style>
