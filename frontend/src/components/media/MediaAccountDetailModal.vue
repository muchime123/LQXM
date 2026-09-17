<template>
  <LqDialog
    :model-value="visible"
    size="lg"
    mode="detail"
    show-footer
    cancel-text="关闭"
    confirm-text="编辑账号"
    destroy-on-close
    class="media-detail-dialog"
    @update:model-value="val => emit('update:visible', val)"
    @close="emit('close')"
    @confirm="emit('edit')"
  >
    <template #title>
      <div class="detail-dialog-header">
        <div class="detail-avatar">
          {{ (account.accountName || account.platform || '账').slice(0, 1) }}
        </div>
        <div class="detail-hero-main">
          <div class="detail-hero-title-row">
            <h3>{{ account.accountName || '账号详情' }}</h3>
            <span :class="['platform-badge', platformClass(account.platform)]">
              {{ account.platform || '-' }}
            </span>
          </div>
          <div class="detail-hero-sub">
            <span>{{ account.accountId || '未填写账号 ID' }}</span>
            <span>{{ account.operator || '未设置运营' }}</span>
            <span :class="{ 'stale-text': isStale(account) }">
              最近更新：{{ account.lastUpdatedAt || '从未更新' }}
            </span>
          </div>
        </div>
        <div class="detail-metric-tags">
          <span class="detail-metric-tag" :class="{ empty: !account.followersCount }">
            <em>粉丝</em>
            <strong>{{ account.followersCount ? formatNum(parseNum(account.followersCount)) : '-' }}</strong>
          </span>
          <span class="detail-metric-tag" :class="{ empty: !account.likesCount }">
            <em>获赞</em>
            <strong>{{ account.likesCount ? formatNum(parseNum(account.likesCount)) : '-' }}</strong>
          </span>
          <span class="detail-metric-tag" :class="{ empty: !account.videoCount }">
            <em>视频</em>
            <strong>{{ account.videoCount || '-' }}</strong>
          </span>
        </div>
      </div>
    </template>

    <div class="detail-body">
      <section class="detail-section">
        <div class="detail-section-title">基础信息</div>
        <!-- C17 LqDescriptions 推广：将 detail-info-grid/detail-info-row 替换为统一描述列表 -->
        <LqDescriptions :items="basicInfoItems" :column="1" border>
          <template #value-link="{ value }">
            <a v-if="value" :href="value" target="_blank" rel="noopener" class="detail-link" :title="value">
              {{ value }}
            </a>
            <span v-else>-</span>
          </template>
          <template #value-tags="{ value }">
            <span class="detail-tags">
              <span v-for="t in tagList(value)" :key="t" class="detail-tag">{{ t }}</span>
              <span v-if="!value" class="empty-asset">暂无标签</span>
            </span>
          </template>
        </LqDescriptions>
      </section>

      <section class="detail-section">
        <div class="detail-section-title">
          最近更新记录
          <button type="button" class="row-btn accent" @click="emit('addUpdate')">新增更新</button>
        </div>
        <div v-if="updates.length" class="detail-update-list">
          <article v-for="record in updates" :key="record.id" class="detail-update-item">
            <div class="detail-update-date-block">
              <span class="detail-update-date">{{ record.updateDate || '-' }}</span>
            </div>
            <div class="detail-update-main">
              <div class="detail-update-title">{{ record.title || '未填写标题' }}</div>
              <div v-if="record.note" class="detail-update-note">{{ record.note }}</div>
              <div v-else-if="record.contentUrl" class="detail-update-note">{{ record.contentUrl }}</div>
            </div>
            <div class="detail-update-actions">
              <a v-if="record.contentUrl" :href="record.contentUrl" target="_blank" rel="noopener">打开内容</a>
              <button type="button" class="row-btn" @click="emit('editUpdate', record)">编辑</button>
              <button type="button" class="row-btn danger" @click="emit('removeUpdate', record)">删除</button>
            </div>
          </article>
        </div>
        <div v-else class="empty-state">暂无更新记录</div>
      </section>
    </div>
  </LqDialog>
</template>

<script setup>
import { computed } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import LqDescriptions from '../common/LqDescriptions.vue'
import { formatNum, parseNum, tagList, isStale } from '../../utils/media/mediaAccountUtils'
import { platformClass } from '../../utils/media/platformUtils'

const props = defineProps({
  visible: { type: Boolean, default: false },
  account: { type: Object, default: () => ({}) },
  updates: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:visible', 'close', 'edit', 'addUpdate', 'editUpdate', 'removeUpdate'])

// C17 LqDescriptions 推广：基础信息字段映射
const basicInfoItems = computed(() => [
  { label: '主页链接', value: props.account.link, slot: 'link' },
  { label: '标签', value: props.account.tags, slot: 'tags', wide: true },
  { label: '备注', value: props.account.note, wide: true }
])
</script>

<style scoped>
@import '../../styles/media-account-view.css';

.media-detail-dialog :deep(.lq-dialog__head) {
  align-items: center;
  min-height: 64px;
  padding: 10px 18px 10px 28px;
  background: var(--color-surface);
}

.media-detail-dialog :deep(.lq-dialog__body) {
  padding: 0;
  max-height: 80vh;
  overflow-y: auto;
}

.media-detail-dialog :deep(.lq-dialog__foot) {
  padding: 12px 22px;
}

.detail-dialog-header {
  align-items: center;
  display: grid;
  flex: 1 1 auto;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: var(--space-md);
  min-width: 0;
  padding: 0;
}

.detail-avatar {
  align-items: center;
  background: var(--color-primary);
  border-radius: var(--radius-xl);
  color: var(--color-surface);
  display: flex;
  font-size: var(--fs-xl);
  font-weight: var(--fw-extrabold);
  height: 40px;
  justify-content: center;
  width: 40px;
}

.detail-hero-main {
  min-width: 0;
}

.detail-hero-title-row {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap);
}

.detail-hero-title-row h3 {
  color: var(--color-text-heading);
  font-size: 17px;
  font-weight: var(--fw-extrabold);
  line-height: var(--lh-tight);
  margin: 0;
}

.detail-hero-sub {
  align-items: center;
  color: var(--color-text-muted);
  display: flex;
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  gap: 6px 10px;
  margin-top: var(--space-control-gap-tight);
}

.detail-hero-sub span {
  min-width: 0;
}

.detail-metric-tags {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  justify-content: flex-end;
}

.detail-metric-tag {
  align-items: center;
  background: var(--color-primary-bg);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-lg);
  color: var(--color-text-heading);
  display: flex;
  flex-direction: column;
  font-size: var(--fs-base);
  font-weight: var(--fw-extrabold);
  gap: 2px;
  line-height: 1.15;
  min-width: 68px;
  padding: 6px 12px 7px;
  white-space: nowrap;
}

.detail-metric-tag em {
  color: var(--color-text-muted);
  font-size: var(--fs-xxs);
  font-style: normal;
  font-weight: var(--fw-bold);
}

.detail-metric-tag strong {
  color: var(--color-success-text);
  font-size: var(--fs-base);
  font-weight: var(--fw-extrabold);
  font-variant-numeric: tabular-nums;
}

.detail-metric-tag.empty {
  background: var(--color-surface-alt);
  border-color: var(--color-border);
  color: var(--color-text-faint);
}

.detail-metric-tag.empty strong {
  color: var(--color-text-faint);
}

.detail-body {
  align-items: start;
  background: var(--color-surface);
  display: grid;
  gap: 14px;
  grid-template-columns: minmax(0, 340px) minmax(0, 1fr);
  padding: 14px;
}

.detail-section {
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  margin-bottom: 0;
  overflow: hidden;
  padding: 14px;
}

.detail-section-title {
  align-items: center;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-heading-alt);
  display: flex;
  font-size: var(--fs-base);
  font-weight: var(--fw-extrabold);
  gap: var(--space-control-gap);
  justify-content: space-between;
  margin-bottom: 12px;
  padding-bottom: 10px;
}

.detail-info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px 14px;
}

.detail-info-row {
  align-items: flex-start;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  border-radius: 0;
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr);
  margin-bottom: 0;
  padding: 4px 0 6px;
}

.detail-info-wide {
  grid-column: 1 / -1;
}

.detail-info-row:last-child {
  margin-bottom: 0;
}

.detail-info-label {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
  font-weight: var(--fw-bold);
}

.detail-info-value {
  flex: 1;
  font-size: var(--fs-xs);
  color: var(--color-text-heading-alt);
  line-height: var(--lh-relaxed);
  min-width: 0;
  overflow-wrap: anywhere;
}

.detail-info-value a {
  color: var(--color-primary);
}

.detail-link {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap-tight);
}

.detail-tag {
  background: var(--color-info-bg);
  color: var(--color-info-text);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
}

.detail-update-list {
  max-height: 360px;
  overflow-y: auto;
  padding-left: var(--space-control-gap-tight);
}

.detail-update-item {
  align-items: start;
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  display: grid;
  gap: var(--space-control-gap-wide);
  grid-template-columns: 92px minmax(0, 1fr) auto;
  margin-bottom: 0;
  min-height: 48px;
  padding: 8px 8px 8px 14px;
  position: relative;
  transition: background 0.15s ease;
}

.detail-update-item:hover {
  background: var(--color-surface-hover);
}

.detail-update-item::before {
  background: var(--color-border);
  content: '';
  height: 100%;
  left: 4px;
  position: absolute;
  top: 0;
  width: 1px;
}

.detail-update-item::after {
  background: var(--color-primary);
  border: 2px solid var(--color-surface);
  border-radius: 50%;
  box-shadow: 0 0 0 1px var(--color-primary-border-light);
  content: '';
  height: 8px;
  left: 0;
  position: absolute;
  top: 14px;
  width: 8px;
}

.detail-update-item:last-child {
  margin-bottom: 0;
}

.detail-update-date-block {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.detail-update-date {
  font-size: var(--fs-xs);
  color: var(--color-text-strong);
  font-weight: var(--fw-bold);
  line-height: var(--lh-normal);
}

.detail-update-main {
  min-width: 0;
}

.detail-update-title {
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-update-note {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
  line-height: var(--lh-normal);
  margin-top: 3px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.detail-update-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  justify-content: flex-end;
  margin-top: 0;
  white-space: nowrap;
}

.detail-update-actions a {
  color: var(--color-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  text-decoration: none;
}

.detail-update-actions a:hover {
  text-decoration: underline;
}

.platform-badge {
  border-radius: var(--radius-md);
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  text-transform: uppercase;
}

.stale-text {
  color: var(--color-danger-text);
  font-weight: var(--fw-bold);
}

.empty-asset {
  color: var(--color-text-muted);
}

.empty-state {
  color: var(--color-text-faint);
  padding: 28px;
  text-align: center;
}

.row-btn {
  align-items: center;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-strong);
  cursor: pointer;
  display: inline-flex;
  font-size: var(--fs-sm);
  justify-content: center;
  line-height: var(--lh-none);
  min-height: 28px;
  padding: 5px 8px;
  white-space: nowrap;
}

.row-btn:hover {
  border-color: var(--color-primary-border);
  color: var(--color-primary);
}

.row-btn.accent {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-surface);
}

.row-btn.accent:hover {
  background: var(--color-primary);
  color: var(--color-surface);
}

.row-btn.danger {
  background-color: #ffffff !important;
  border-color: #fecaca !important;
  color: #be123c !important;
}

.row-btn.danger:hover {
  background-color: #fff1f2 !important;
  border-color: #dc2626 !important;
  color: #b91c1c !important;
}

.row-btn.danger:active {
  background-color: #fee2e2 !important;
  color: #be123c !important;
}

[data-theme='dark'] .row-btn.danger {
  background-color: transparent !important;
  border-color: rgba(220, 38, 38, 0.3) !important;
  color: #fca5a5 !important;
}
[data-theme='dark'] .row-btn.danger:hover {
  background-color: rgba(220, 38, 38, 0.15) !important;
  border-color: #f87171 !important;
  color: #f87171 !important;
}

@media (max-width: 960px) {
  .detail-body {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .detail-dialog-header {
    grid-template-columns: 40px minmax(0, 1fr);
  }

  .detail-metric-tags {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }

  .detail-body {
    padding: 10px;
  }

  .detail-metric-tag {
    flex-direction: row;
    gap: 6px;
  }
}
</style>
