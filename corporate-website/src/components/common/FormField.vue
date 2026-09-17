<script setup>
/**
 * 表单字段容器：label（可选必填标记）+ 控件插槽 + 错误提示。
 */
defineProps({
  label: { type: String, default: '' },
  required: { type: Boolean, default: false },
  error: { type: String, default: '' },
  htmlFor: { type: String, default: '' },
})
</script>

<template>
  <div class="form-field">
    <label v-if="label" :for="htmlFor || undefined">
      {{ label }}<span v-if="required" class="req">*</span>
    </label>
    <slot />
    <small v-if="error" class="form-error">{{ error }}</small>
  </div>
</template>
