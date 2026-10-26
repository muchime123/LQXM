<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, View } from '@element-plus/icons-vue'
import { fetchWebsiteDashboard } from '@/api/website/dashboard'
import type { WebsiteDashboard } from '@/types/website'
import { notify } from '@/composables/ui/useToast'
import MetricCard from '@/components/common/MetricCard.vue'
import MetricGrid from '@/components/common/MetricGrid.vue'
import { openCorporateWebsite } from '@/utils/websiteUrl'

const router = useRouter()
const loading = ref(true)
const dashboard = ref<WebsiteDashboard | null>(null)

// 发布前检查（业务档案是否满足官网公开条件）
const readinessChecks = computed(() => dashboard.value?.readinessIssues ?? [])
const readinessIssues = computed(() => readinessChecks.value.filter(i => i.count > 0))
const readinessAvailable = computed(() => Array.isArray(dashboard.value?.readinessIssues))
const readinessTips: Record<string, string> = {
  packagingNotForSale: '这些包材仍处于官网发布状态，但业务状态已经不是“在售”。',
  packagingReady: '业务档案已满足公开条件，可核对内容后开启官网发布。',
  moldNotEligible: '这些已发布模具不再满足“主模＋在用”的公开条件。',
  moldReady: '主模处于在用状态，可核对关联包材后开启官网发布。',
  solutionIncomplete: '已开启官网展示，但标题、标识或封面图等必要资料缺失。',
  solutionReady: '内容资料已经完整，可预览确认后正式发布。'
}

async function loadData() {
  loading.value = true
  try {
    dashboard.value = await fetchWebsiteDashboard()
  } catch (err) {
    notify.errorWithDetail('加载官网工作台失败', err)
  } finally {
    loading.value = false
  }
}

function openWebsite() {
  openCorporateWebsite('/')
}

function go(to: string) {
  router.push(to)
}

function goToReadinessItem(target: string, item: { id: number; name: string }) {
  router.push({ path: target, query: { id: String(item.id), keyword: item.name } })
}

await loadData()
</script>

<template>
  <div class="website-dashboard">
    <header class="page-header">
      <div class="page-header__content">
        <p class="page-header__eyebrow">官网运营</p>
        <h1 class="page-header__title">官网工作台</h1>
        <p class="page-header__desc">
          官网发布总览与待办事项。点击指标跳转到对应模块处理。
        </p>
      </div>
      <div class="page-header__actions">
        <el-button type="primary" @click="go('/website/visual-editor')">页面编辑</el-button>
        <el-button link :icon="View" @click="openWebsite">预览官网</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </header>

    <div class="overview-strip">
      <MetricGrid :columns="4" density="compact">
        <MetricCard
          title="待处理询盘"
          :value="dashboard?.pendingInquiries ?? 0"
          :tone="(dashboard?.pendingInquiries ?? 0) > 0 ? 'danger' : 'default'"
          :hint="`本月新增 ${dashboard?.recentInquiries ?? 0} 条`"
          :loading="loading"
          clickable
          @click="go('/website/inquiries')"
        />
        <MetricCard
          title="已发布产品"
          :value="dashboard?.publishedProducts ?? 0"
          tone="primary"
          :hint="`精选 ${dashboard?.featuredProducts ?? 0} 个`"
          :loading="loading"
          clickable
          @click="go('/products')"
        />
        <MetricCard
          title="待完善产品"
          :value="dashboard?.incompleteProducts ?? 0"
          :tone="(dashboard?.incompleteProducts ?? 0) > 0 ? 'warning' : 'default'"
          hint="完整度低于 40 分，不上官网"
          :loading="loading"
          clickable
          @click="go('/products')"
        />
      </MetricGrid>
    </div>

    <div class="health-section readiness-section">
      <div class="health-section__head">
        <div>
          <h2 class="section-title">发布前检查</h2>
          <p class="section-desc">集中检查业务档案是否满足官网公开条件。这里不修改源数据，只提供问题定位和处理入口。</p>
        </div>
        <el-tag :type="!readinessAvailable ? 'info' : readinessIssues.length ? 'warning' : 'success'" effect="light">
          {{ !readinessAvailable ? '等待后端更新' : readinessIssues.length ? `${readinessIssues.length} 类事项待处理` : '暂无待处理事项' }}
        </el-tag>
      </div>

      <el-alert
        v-if="!loading && dashboard && !readinessAvailable"
        title="当前后端仍在使用旧版工作台接口，重启后端服务后将显示发布前检查结果。"
        type="info"
        :closable="false"
        show-icon
      />
      <div v-loading="loading" class="readiness-grid">
        <article
          v-for="issue in readinessChecks"
          :key="issue.key"
          class="readiness-card"
          :class="{ 'is-empty': issue.count === 0, 'is-ready': issue.key.endsWith('Ready') && issue.count > 0 }"
        >
          <div class="readiness-card__top">
            <span>{{ issue.domain === 'packaging' ? '包材' : issue.domain === 'mold' ? '模具' : '解决方案' }}</span>
            <strong>{{ issue.count }}</strong>
          </div>
          <h3>{{ issue.label }}</h3>
          <p>{{ readinessTips[issue.key] }}</p>
          <div v-if="issue.samples.length" class="issue-card__samples">
            <button
              v-for="item in issue.samples"
              :key="item.id"
              type="button"
              class="issue-sample issue-sample--link"
              @click="goToReadinessItem(issue.target, item)"
            >{{ item.name }}</button>
            <span v-if="issue.count > issue.samples.length" class="issue-sample issue-sample--more">等 {{ issue.count }} 条</span>
          </div>
          <button v-if="issue.count" type="button" @click="go(issue.target)">前往源档案处理 →</button>
          <span v-else class="readiness-card__ok">当前正常</span>
        </article>
      </div>
    </div>
  </div>
</template>

<style scoped>
.website-dashboard {
  display: flex;
  flex-direction: column;
  gap: var(--space-section-gap);
}

/* ==================== Header ==================== */
.page-header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-section-gap-wide);
  padding: var(--space-xl-2xl) var(--space-xl);
  overflow: hidden;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: linear-gradient(135deg, var(--color-surface) 0%, var(--color-surface-alt) 100%);
  box-shadow: var(--shadow-sm);
}

.page-header::before {
  position: absolute;
  inset: var(--space-section-gap) auto var(--space-section-gap) 0;
  width: 3px;
  border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
  background: var(--color-primary);
  content: '';
}

.page-header__content {
  position: relative;
  display: grid;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

.page-header__eyebrow {
  margin: 0;
  color: var(--color-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  letter-spacing: var(--tracking-wider);
}

.page-header__title {
  margin: 0;
  color: var(--color-text-heading);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
  letter-spacing: -0.02em;
}

.page-header__desc {
  max-width: 68ch;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-base);
  line-height: var(--lh-loose);
}

.page-header__actions {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-sm);
  flex: 0 0 auto;
}

/* ==================== Sections ==================== */
.overview-strip,
.health-section {
  min-width: 0;
}

.section-title {
  margin: 0;
  color: var(--color-text-heading);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  letter-spacing: -0.01em;
}

.section-desc {
  margin: var(--space-control-gap-tight) 0 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
}

.health-section {
  display: grid;
  gap: var(--space-md-lg);
}

.health-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap);
  flex-wrap: wrap;
}

/* ==================== Readiness cards ==================== */
.readiness-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-card-gap);
  min-height: 120px;
}

.readiness-card {
  display: grid;
  align-content: start;
  gap: var(--space-sm-md);
  min-height: 190px;
  padding: var(--card-padding);
  border: 1px solid var(--color-warning-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
}

.readiness-card.is-ready { border-color: var(--color-primary-border, var(--color-border)); }
.readiness-card.is-empty { border-color: var(--color-border-light); opacity: .72; }
.readiness-card__top { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.readiness-card__top span { color: var(--color-primary); font-size: var(--fs-xs); font-weight: var(--fw-semibold); }
.readiness-card__top strong { color: var(--color-text-heading); font-size: var(--fs-xl); font-variant-numeric: tabular-nums; }
.readiness-card h3 { margin: 0; color: var(--color-text-heading); font-size: var(--fs-md); }
.readiness-card p { min-height: 38px; margin: 0; color: var(--color-text-secondary); font-size: var(--fs-xs); line-height: var(--lh-loose); }
.readiness-card button { justify-self: start; margin-top: auto; padding: 0; border: 0; background: none; color: var(--color-primary); cursor: pointer; font-size: var(--fs-xs); font-weight: var(--fw-medium); }
.readiness-card__ok { margin-top: auto; color: var(--color-success-text); font-size: var(--fs-xs); }

/* ==================== Issue samples (shared) ==================== */
.issue-card__samples {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

.issue-sample {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  max-width: 100%;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  white-space: nowrap;
}

.issue-sample--more {
  background: transparent;
  border: 1px dashed var(--color-border-light);
}

.issue-sample--link { border: 0; cursor: pointer; font-family: inherit; }
.issue-sample--link:hover, .issue-sample--link:focus-visible { background: var(--color-primary-bg); color: var(--color-primary); outline: none; }

/* ==================== Responsive ==================== */
@media (max-width: 767px) {
  .readiness-grid { grid-template-columns: 1fr; }
  .page-header {
    align-items: stretch;
    flex-direction: column;
    gap: var(--space-control-gap-wide);
    padding: var(--space-control-gap-wide);
  }

  .page-header::before {
    inset: 0 var(--space-control-gap-wide) auto;
    width: auto;
    height: 3px;
    border-radius: 0 0 var(--radius-pill) var(--radius-pill);
  }

  .page-header__actions {
    justify-content: flex-start;
  }
}

@media (min-width: 768px) and (max-width: 1100px) {
  .readiness-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
