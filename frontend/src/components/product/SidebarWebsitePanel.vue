<script setup lang="ts">
import { computed } from 'vue'
import WebsitePublishStatus from '@/components/website/WebsitePublishStatus.vue'
import { getCorporateProductUrl } from '@/utils/websiteUrl'
/**
 * SidebarWebsitePanel — 产品编辑器侧边栏的「官网发布」面板
 * 集中维护官网发布开关、首页精选、官网 URL 标识与摘要，替代主表单中的官网发布分组。
 *
 * Props:
 *   form — reactive 编辑表单对象（websiteEnabled / websiteFeatured / websiteSlug / websiteSummary）
 *
 * Emits:
 *   fieldChange — 字段变更事件（key），父组件据此标记表单 dirty
 */
interface WebsiteForm {
  websiteEnabled?: boolean
  websiteFeatured?: boolean
  websiteSlug?: string
  websiteSummary?: string
  /** 店铺链接（淘宝/天猫/京东/1688/Shopify 等电商详情页 URL）；V121 重新加回 */
  shopUrl?: string
  [key: string]: any
}

const props = withDefaults(
  defineProps<{
    form: WebsiteForm
  }>(),
  {}
)

const emit = defineEmits<{
  'field-change': [key: string]
}>()
const publishEligibility = computed(() => {
  const missing: string[] = []
  if (!props.form.websiteSlug?.trim()) missing.push('URL 标识')
  if (!props.form.websiteSummary?.trim()) missing.push('官网摘要')
  return { eligible: missing.length === 0, reason: missing.length ? `缺少${missing.join('、')}` : '仍需通过档案完整度校验' }
})

function onFieldChange(key: string) {
  emit('field-change', key)
}

function openPreview() {
  const slug = props.form.websiteSlug?.trim()
  if (!props.form.websiteEnabled || !slug) return
  window.open(getCorporateProductUrl(slug), '_blank')
}
</script>

<template>
  <div class="sidebar-website-panel">
    <div class="sidebar-card-title">
      <span>官网发布</span>
      <WebsitePublishStatus :enabled="form.websiteEnabled" :eligible="publishEligibility.eligible" :reason="publishEligibility.reason" compact />
    </div>

    <div class="sw-grid">
      <!-- eslint-disable-next-line vue/no-mutating-props -->
      <label class="sw-row" @click.stop>
        <span class="sw-label">官网发布</span>
        <el-switch
          v-model="form.websiteEnabled"
          size="small"
          inline-prompt
          active-text="开"
          inactive-text="关"
          :disabled="!form.websiteEnabled && !publishEligibility.eligible"
          @change="onFieldChange('websiteEnabled')"
        />
      </label>
      <p v-if="!publishEligibility.eligible" class="sw-hint is-warning">
        {{ publishEligibility.reason }}，补齐后才能开启官网发布。
      </p>
      <!-- eslint-disable-next-line vue/no-mutating-props -->
      <label class="sw-row" @click.stop>
        <span class="sw-label">首页精选</span>
        <el-switch
          v-model="form.websiteFeatured"
          size="small"
          inline-prompt
          active-text="开"
          inactive-text="关"
          :disabled="!form.websiteEnabled"
          @change="onFieldChange('websiteFeatured')"
        />
      </label>
    </div>
    <div class="sw-featured-note" :class="{ active: form.websiteFeatured }">
      <strong>{{ form.websiteFeatured ? '已申请首页核心展示' : '首页展示条件' }}</strong>
      <p>需同时发布到官网、档案完整度达到 60 分；所有符合条件的核心产品都会进入首页轮播。</p>
      <p v-if="form.websiteFeatured">若保存后仍未显示，请先检查完整度；官网数据最多可能缓存 10 分钟。</p>
    </div>

    <div class="sw-field">
      <span class="sw-label">官网 URL 标识</span>
      <!-- eslint-disable-next-line vue/no-mutating-props -->
      <input
        v-model="form.websiteSlug"
        type="text"
        placeholder="如 automatic-map-machine"
        spellcheck="false"
        @input="onFieldChange('websiteSlug')"
      />
    </div>
    <div class="sw-field">
      <span class="sw-label">官网摘要</span>
      <!-- eslint-disable-next-line vue/no-mutating-props -->
      <textarea
        v-model="form.websiteSummary"
        rows="4"
        maxlength="500"
        placeholder="一句话介绍产品亮点，避免与内部备注混淆"
        @input="onFieldChange('websiteSummary')"
      ></textarea>
    </div>
    <div class="sw-field">
      <span class="sw-label">店铺链接</span>
      <!-- eslint-disable-next-line vue/no-mutating-props -->
      <input
        v-model="form.shopUrl"
        type="url"
        spellcheck="false"
        placeholder="如 https://detail.1688.com/offer/xxxx.html"
        @input="onFieldChange('shopUrl')"
      />
      <p class="sw-hint">淘宝 / 天猫 / 京东 / 1688 / Shopify 等电商详情页链接，留空则不显示跳转入口。</p>
    </div>

    <div class="sw-draft-preview" aria-label="产品官网摘要预览">
      <div class="sw-draft-preview__head">
        <span>官网卡片草稿</span>
        <em>{{ form.websiteFeatured ? '首页精选' : '产品中心' }}</em>
      </div>
      <strong>{{ form.name || '产品名称将在这里显示' }}</strong>
      <p>{{ form.websiteSummary || '填写官网摘要后，可在这里预判访客看到的核心介绍。' }}</p>
      <code>/products/{{ form.websiteSlug || 'url-identifier' }}</code>
    </div>

    <button v-if="form.websiteEnabled && form.websiteSlug" type="button" class="sw-preview" @click="openPreview">
      预览官网详情
    </button>
  </div>
</template>

<style scoped>
.sidebar-website-panel {
  display: grid;
  gap: var(--space-sm);
  padding: 12px 0 4px;
  border-top: 1px solid var(--color-border);
}

.sidebar-card-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap);
}

.sidebar-card-title span {
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-extrabold);
}

.sidebar-card-title em {
  padding: 2px 7px;
  border-radius: var(--radius-pill);
  background: var(--color-border);
  color: var(--color-text-muted);
  font-style: normal;
  font-size: var(--fs-xxs);
  font-weight: var(--fw-bold);
  white-space: nowrap;
}

.sidebar-card-title em.done {
  background: var(--color-primary-bg-light);
  color: var(--color-primary);
}

.sw-grid {
  display: grid;
  gap: var(--space-control-gap);
}

.sw-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap);
  cursor: default;
}

.sw-label {
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  font-weight: 600;
}

.sw-hint {
  margin: 0;
  color: var(--color-text-tertiary);
  font-size: 11px;
  line-height: 1.4;
}

.sw-hint.is-warning { color: var(--color-warning-text); }

.sw-featured-note {
  display: grid;
  gap: 3px;
  padding: 9px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-subtle, var(--color-surface));
}

.sw-featured-note.active {
  border-color: color-mix(in srgb, var(--color-primary) 35%, var(--color-border));
  background: var(--color-primary-bg-light);
}

.sw-featured-note strong {
  color: var(--color-text-strong);
  font-size: 11px;
}

.sw-featured-note p {
  margin: 0;
  color: var(--color-text-tertiary);
  font-size: 11px;
  line-height: 1.5;
}

.sw-field {
  display: grid;
  gap: 4px;
}

.sw-field input,
.sw-field textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 7px 9px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-heading);
  font-size: var(--fs-xs);
  line-height: 1.4;
  outline: none;
  transition: border-color var(--transition-fast);
}

.sw-field input::placeholder,
.sw-field textarea::placeholder {
  color: var(--color-text-faint);
}

.sw-field input:focus,
.sw-field textarea:focus {
  border-color: var(--color-primary);
}

.sw-field textarea {
  resize: vertical;
}

.sw-preview {
  justify-self: start;
  padding: 4px 8px;
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.sw-preview:hover {
  background: var(--color-primary-bg-strong, var(--color-primary-bg));
}

.sw-draft-preview {
  display: grid;
  gap: 7px;
  padding: 12px;
  overflow: hidden;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: linear-gradient(145deg, var(--color-surface-alt), var(--color-surface));
}

.sw-draft-preview__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.sw-draft-preview__head span { color: var(--color-primary); font-size: 10px; font-weight: 700; letter-spacing: .08em; }
.sw-draft-preview__head em { color: var(--color-text-faint); font-size: 10px; font-style: normal; }
.sw-draft-preview > strong { overflow: hidden; color: var(--color-text-heading); font-size: 13px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.sw-draft-preview > p { display: -webkit-box; margin: 0; overflow: hidden; color: var(--color-text-secondary); font-size: 11px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.sw-draft-preview > code { overflow: hidden; color: var(--color-text-faint); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
</style>
