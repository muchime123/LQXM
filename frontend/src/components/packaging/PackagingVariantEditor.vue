<template>
  <LqDialog
    :model-value="visible"
    :title="isEdit ? '编辑规格' : '新增规格'"
    mode="form"
    size="lg"
    :close-on-click-modal="true"
    :loading="saving"
    confirm-text="保存规格"
    @update:model-value="emit('update:visible', false)"
    @confirm="$emit('save')"
  >
    <form class="variant-editor-form" @submit.prevent="$emit('save')">
      <div class="variant-editor-sections">
        <section v-for="group in formGroups" :key="group.title" class="variant-form-section">
          <h4>{{ group.title }}</h4>
          <div class="variant-form-grid">
            <label v-for="field in group.fields" :key="field.key" :class="{ wide: field.wide }">
              <span>{{ field.label }}</span>
              <textarea
                v-if="field.type === 'textarea'"
                :value="form[field.key]"
                rows="3"
                :data-variant-field="field.key"
                @input="updateField(field.key, ($event.target as HTMLTextAreaElement).value.trim())"
              ></textarea>
              <select
                v-else-if="field.type === 'select'"
                :value="form[field.key]"
                :data-variant-field="field.key"
                @change="updateField(field.key, ($event.target as HTMLSelectElement).value)"
              >
                <option value="">{{ field.key === 'shapeType' ? '跟随系列形状' : '请选择' }}</option>
                <option v-for="option in field.options" :key="option.key" :value="option.key">
                  {{ option.label }}
                </option>
              </select>
              <input
                v-else
                :value="form[field.key]"
                :placeholder="field.placeholder"
                :data-variant-field="field.key"
                @input="updateField(field.key, ($event.target as HTMLInputElement).value.trim())"
              />
            </label>
          </div>
        </section>
      </div>

      <div v-if="!isFilmModel" class="price-calculator">
        <div class="price-row">
          <label>
            <span>经销价（每箱）</span>
            <div class="price-input-group">
              <input
                :value="form.dealerPrice"
                placeholder="输入每箱价格"
                @input="updateField('dealerPrice', ($event.target as HTMLInputElement).value.trim())"
              />
              <span class="price-unit">元/箱</span>
            </div>
          </label>
          <div class="price-calculation">
            <span class="calc-label">≈</span>
            <span class="calc-value">{{ unitDealerPrice || '-' }}</span>
            <span class="calc-unit">元/个</span>
          </div>
          <div class="price-calculation usd">
            <span class="calc-label">≈</span>
            <span class="calc-value">{{ dealerPriceUsd || '-' }}</span>
            <span class="calc-unit">USD/箱</span>
            <span v-if="unitDealerPriceUsd" class="calc-unit small">≈ {{ unitDealerPriceUsd }} USD/个</span>
          </div>
        </div>
        <div class="price-row">
          <label>
            <span>零售价（每箱）</span>
            <div class="price-input-group">
              <input
                :value="form.retailPrice"
                placeholder="输入每箱价格"
                @input="updateField('retailPrice', ($event.target as HTMLInputElement).value.trim())"
              />
              <span class="price-unit">元/箱</span>
            </div>
          </label>
          <div class="price-calculation">
            <span class="calc-label">≈</span>
            <span class="calc-value">{{ unitRetailPrice || '-' }}</span>
            <span class="calc-unit">元/个</span>
          </div>
          <div class="price-calculation usd">
            <span class="calc-label">≈</span>
            <span class="calc-value">{{ retailPriceUsd || '-' }}</span>
            <span class="calc-unit">USD/箱</span>
            <span v-if="unitRetailPriceUsd" class="calc-unit small">≈ {{ unitRetailPriceUsd }} USD/个</span>
          </div>
        </div>
        <div class="price-hint">
          <span>单价 = 每箱价格 ÷ 每箱数量（需先填写「每箱数量(起订)」字段）</span>
          <label class="exchange-rate-inline">
            <span>汇率</span>
            <input
              :value="exchangeRate"
              type="number"
              step="0.01"
              min="0.01"
              max="100"
              @input="$emit('update:exchangeRate', Number(($event.target as HTMLInputElement).value))"
            />
            <em>CNY/USD</em>
          </label>
        </div>
      </div>

      <div v-if="isFilmModel" class="price-calculator">
        <div class="price-row">
          <div class="price-conversion">
            <span>经销价</span>
            <span v-if="dealerPriceUsd" class="usd-value">≈ {{ dealerPriceUsd }} USD</span>
          </div>
        </div>
        <div class="price-row">
          <div class="price-conversion">
            <span>零售价</span>
            <span v-if="retailPriceUsd" class="usd-value">≈ {{ retailPriceUsd }} USD</span>
          </div>
        </div>
        <div class="price-hint">
          <span>USD = CNY ÷ 汇率</span>
          <label class="exchange-rate-inline">
            <span>汇率</span>
            <input
              :value="exchangeRate"
              type="number"
              step="0.01"
              min="0.01"
              max="100"
              @input="$emit('update:exchangeRate', Number(($event.target as HTMLInputElement).value))"
            />
            <em>CNY/USD</em>
          </label>
        </div>
      </div>
    </form>
  </LqDialog>
</template>

<script setup lang="ts">
import LqDialog from '../common/LqDialog.vue'

interface VariantFormField {
  key: string
  label: string
  type?: string
  placeholder?: string
  wide?: boolean
  options?: ReadonlyArray<{ key: string; label: string }>
}

interface VariantFormGroup {
  title: string
  fields: VariantFormField[]
}

defineProps<{
  visible: boolean
  isEdit: boolean
  form: Record<string, string>
  formGroups: VariantFormGroup[]
  isFilmModel: boolean
  exchangeRate: number
  dealerPriceUsd: string
  retailPriceUsd: string
  unitDealerPrice: string
  unitRetailPrice: string
  unitDealerPriceUsd: string
  unitRetailPriceUsd: string
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'save'): void
  (e: 'update:form', key: string, value: string): void
  (e: 'update:exchangeRate', value: number): void
}>()

function updateField(key: string, value: string) {
  emit('update:form', key, value)
}
</script>

<style scoped>
.variant-editor-form {
  display: flex;
  flex-direction: column;
}

.variant-editor-sections {
  display: flex;
  flex-direction: column;
  gap: var(--space-section-gap);
}

.variant-form-section h4 {
  margin: 0 0 8px;
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  color: var(--color-text-muted);
}

.variant-form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-card-gap);
}

.variant-form-grid label.wide {
  grid-column: 1 / -1;
}

.variant-form-grid label span {
  display: block;
  margin-bottom: 4px;
  font-size: var(--fs-sm);
  color: var(--color-text-muted);
}

.variant-form-grid input,
.variant-form-grid textarea,
.variant-form-grid select {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-heading);
  font: inherit;
  font-size: var(--fs-sm);
}

.variant-form-grid input:focus,
.variant-form-grid textarea:focus,
.variant-form-grid select:focus {
  outline: none;
  border-color: var(--color-primary);
}

.price-calculator {
  flex-shrink: 0;
  padding: 16px 24px;
  margin-top: 16px;
  border-top: 1px solid var(--color-border);
  background: var(--color-surface-alt);
}

.price-row {
  display: flex;
  align-items: center;
  gap: var(--space-section-gap);
  margin-bottom: 8px;
}

.price-row label {
  flex: 0 0 auto;
}

.price-input-group {
  display: flex;
  align-items: center;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.price-input-group input {
  width: 120px;
  padding: 6px 10px;
  border: 0;
  background: var(--color-surface);
  font: inherit;
  font-size: var(--fs-sm);
}

.price-unit {
  padding: 0 10px;
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  white-space: nowrap;
}

.price-calculation {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.price-calculation.usd {
  color: var(--color-success);
}

.calc-label {
  color: var(--color-text-muted);
}

.calc-value {
  font-weight: var(--fw-bold);
  color: var(--color-text-heading);
}

.calc-unit {
  font-size: var(--fs-xs);
}

.calc-unit.small {
  font-size: 10px;
  opacity: 0.8;
}

.price-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-section-gap);
  margin-top: 8px;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.exchange-rate-inline {
  display: flex;
  align-items: center;
  gap: 6px;
}

.exchange-rate-inline input {
  width: 64px;
  padding: 4px 6px;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-sm);
  font: inherit;
  font-size: var(--fs-xs);
}

.exchange-rate-inline em {
  color: var(--color-text-muted);
  font-style: normal;
  font-size: var(--fs-xs);
}

.price-conversion {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-wide);
}

.usd-value {
  color: var(--color-success);
  font-weight: var(--fw-bold);
}

.btn-sm {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 16px;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-heading);
  font: inherit;
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  cursor: pointer;
}

.btn-sm.primary {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: var(--color-surface);
}

.btn-sm:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
