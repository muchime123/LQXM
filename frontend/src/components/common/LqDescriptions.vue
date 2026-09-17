<!--
  LqDescriptions - 统一描述列表组件
  基于 Element Plus el-descriptions 封装，统一详情页字段展示模式。

  - 详情页字段展示统一用描述列表组件，而非手写 div+span 网格
  - 字段标签与值通过 aria-labelledby 关联
  - 空值显示 — 并有 aria-label="无值"

  使用方式：
    <LqDescriptions :items="items" :column="2" border />

    <LqDescriptions :groups="groups" :column="3" border>
      <template #value-status="{ value }">
        <StatusTag :status="value" />
      </template>
    </LqDescriptions>
-->
<template>
  <div class="lq-desc" :class="{ 'lq-desc--card': card }">
    <el-descriptions
      v-if="!groups || groups.length === 0"
      :column="column"
      :border="border"
      :direction="direction"
      :size="size"
      :title="title"
      :extra="extra"
    >
      <el-descriptions-item
        v-for="item in items"
        :key="item.label"
        :span="item.span || (item.wide ? column : 1)"
        :label="item.label"
      >
        <span
          :class="{
            'lq-desc__value--mono': item.mono,
            'lq-desc__value--highlight': item.highlight,
            'lq-desc__value--empty': isEmpty(item.value)
          }"
          :aria-label="isEmpty(item.value) ? '无值' : undefined"
        >
          <slot
            v-if="item.slot"
            :name="`value-${item.slot}`"
            :value="item.value"
            :item="item"
          />
          <template v-else>{{ isEmpty(item.value) ? '—' : item.value }}</template>
          <button
            v-if="item.copyable && !isEmpty(item.value)"
            type="button"
            class="lq-desc__copy"
            :title="copyableTitle(item)"
            @click.stop="handleCopy(item)"
          >
            复制
          </button>
        </span>
      </el-descriptions-item>
    </el-descriptions>

    <template v-else>
      <div
        v-for="(group, gIdx) in groups"
        :key="gIdx"
        class="lq-desc__group"
      >
        <h4 v-if="group.title" class="lq-desc__group-title">{{ group.title }}</h4>
        <el-descriptions
          :column="group.column || column"
          :border="group.border ?? border"
          :direction="group.direction || direction"
          :size="group.size || size"
        >
          <el-descriptions-item
            v-for="item in group.items"
            :key="item.label"
            :span="item.span || (item.wide ? (group.column || column) : 1)"
            :label="item.label"
          >
            <span
              :class="{
                'lq-desc__value--mono': item.mono,
                'lq-desc__value--highlight': item.highlight,
                'lq-desc__value--empty': isEmpty(item.value)
              }"
              :aria-label="isEmpty(item.value) ? '无值' : undefined"
            >
              <slot
                v-if="item.slot"
                :name="`value-${item.slot}`"
                :value="item.value"
                :item="item"
                :group-index="gIdx"
              />
              <template v-else>{{ isEmpty(item.value) ? '—' : item.value }}</template>
              <button
                v-if="item.copyable && !isEmpty(item.value)"
                type="button"
                class="lq-desc__copy"
                :title="copyableTitle(item)"
                @click.stop="handleCopy(item)"
              >
                复制
              </button>
            </span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { digitsOnly } from '../../utils/common/textUtils'
import { copyText } from '../../utils/ui/clipboard'

export interface DescItem {
  /** 字段标签 */
  label: string
  /** 字段值 */
  value: string | number | null | undefined
  /** 跨列数（wide=true 时占满整行） */
  span?: number
  /** 占满整行 */
  wide?: boolean
  /** 等宽字体（代码/编号/金额） */
  mono?: boolean
  /** 高亮强调 */
  highlight?: boolean
  /** 自定义渲染插槽名（对应 #value-{slot}） */
  slot?: string
  /** 提供复制按钮：true 复制原文，'digits' 复制纯数字版 */
  copyable?: boolean | 'digits'
}

export interface DescGroup {
  /** 分组标题 */
  title?: string
  /** 分组字段列表 */
  items: DescItem[]
  /** 分组列数（覆盖全局） */
  column?: number
  /** 分组边框（覆盖全局） */
  border?: boolean
  /** 分组方向（覆盖全局） */
  direction?: 'horizontal' | 'vertical'
  /** 分组尺寸（覆盖全局） */
  size?: 'large' | 'default' | 'small'
}

withDefaults(
  defineProps<{
    /** 扁平字段列表（无分组时使用） */
    items?: DescItem[]
    /** 分组字段列表（有分组时使用，覆盖 items） */
    groups?: DescGroup[]
    /** 列数 */
    column?: number
    /** 是否显示边框 */
    border?: boolean
    /** 方向 */
    direction?: 'horizontal' | 'vertical'
    /** 尺寸 */
    size?: 'large' | 'default' | 'small'
    /** 标题（仅扁平模式） */
    title?: string
    /** 额外操作区（仅扁平模式） */
    extra?: string
    /** 分组卡片化（group 模式：每个分组独立卡片容器） */
    card?: boolean
  }>(),
  {
    items: () => [],
    groups: () => [],
    column: 2,
    border: true,
    direction: 'horizontal',
    size: 'default',
    title: '',
    extra: '',
    card: false
  }
)

function isEmpty(val: unknown): boolean {
  return val === null || val === undefined || val === '' || (typeof val === 'string' && val.trim() === '')
}

function copyablePayload(item: DescItem): string {
  const raw = String(item.value ?? '')
  return item.copyable === 'digits' ? digitsOnly(raw) : raw
}

function copyableTitle(item: DescItem): string {
  return item.copyable === 'digits' ? `复制纯数字${item.label}` : `复制${item.label}`
}

function handleCopy(item: DescItem) {
  copyText(copyablePayload(item), item.copyable === 'digits' ? `纯数字${item.label}` : item.label)
}
</script>

<style scoped>
.lq-desc {
  width: 100%;
}

.lq-desc__group + .lq-desc__group {
  margin-top: var(--space-section-gap, 16px);
}

/* 分组卡片化模式 */
.lq-desc--card .lq-desc__group {
  padding: var(--space-control-gap-wide, 12px) var(--space-control-gap-wide, 12px) var(--space-md, 12px);
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-lg);
}

.lq-desc--card .lq-desc__group-title {
  margin-bottom: var(--space-sm-md, 8px);
  padding-bottom: var(--space-sm-md, 8px);
  border-bottom: 1px solid var(--color-border-lighter);
  letter-spacing: 0.02em;
}

.lq-desc__group-title {
  margin: 0 0 var(--space-sm-md, 8px);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
}

.lq-desc__value--mono {
  font-family: var(--font-mono, 'SFMono-Regular', Consolas, monospace);
  font-size: var(--fs-sm);
}

.lq-desc__value--highlight {
  color: var(--color-primary);
  font-weight: var(--fw-medium);
}

.lq-desc__value--empty {
  color: var(--color-text-muted);
}

.lq-desc__copy {
  margin-left: var(--space-control-gap, 8px);
  padding: 1px 7px;
  font-size: var(--fs-xs);
  color: var(--color-primary);
  background: var(--color-primary-bg);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-sm);
  cursor: pointer;
  opacity: 0.75;
  transition: opacity var(--transition-fast);
  vertical-align: middle;
}

.lq-desc__copy:hover {
  opacity: 1;
}

/* 紧凑模式 - 彻底去除表格感，改为流式布局 */
.lq-desc :deep(.el-descriptions) {
  --el-descriptions-item-bordered-label-background: var(--color-surface-alt);
  background: transparent !important;
}

.lq-desc :deep(.el-descriptions__table) {
  display: block !important;
  width: 100% !important;
  background: transparent !important;
  border-collapse: separate !important;
  border-spacing: 0 !important;
}

.lq-desc :deep(.el-descriptions__colgroup) {
  display: none !important;
}

.lq-desc :deep(.el-descriptions__body),
.lq-desc :deep(.el-descriptions__table) > tbody,
.lq-desc :deep(.el-descriptions__table) > thead {
  display: block !important;
  background: transparent !important;
}

.lq-desc :deep(.el-descriptions__row),
.lq-desc :deep(tr) {
  display: flex !important;
  flex-wrap: wrap !important;
  width: 100% !important;
  margin-bottom: var(--space-sm-md, 8px);
  background: transparent !important;
  border: none !important;
}

.lq-desc :deep(.el-descriptions__cell),
.lq-desc :deep(td) {
  display: block !important;
  width: auto !important;
  max-width: 100% !important;
  padding: 4px 12px 4px 0 !important;
  border: none !important;
  background: transparent !important;
  text-align: left !important;
  vertical-align: middle !important;
}

.lq-desc :deep(.el-descriptions__label) {
  color: var(--color-text-muted);
  font-weight: var(--fw-medium);
  width: auto !important;
  flex-shrink: 0;
  margin-right: var(--space-control-gap, 8px);
  padding: 4px 8px 4px 0 !important;
  border: none !important;
  background: transparent !important;
}

.lq-desc :deep(.el-descriptions__content) {
  color: var(--color-text-primary);
  width: auto !important;
  flex: 1;
  min-width: 0;
  padding: 4px 12px 4px 0 !important;
  border: none !important;
  background: transparent !important;
}

/* 跨整行的字段 */
.lq-desc :deep(.el-descriptions__cell.is-bordered-label) {
  width: auto !important;
}
.lq-desc :deep(.el-descriptions__cell.is-bordered-content) {
  width: auto !important;
}
</style>
