<template>
  <div class="drawer-tabs-row">
    <nav class="drawer-tabs" @wheel.prevent="handleTabWheel">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        :class="{ active: tab.key === active }"
        @click="emit('update:active', tab.key)"
      >
        {{ tab.label }}
        <span v-if="tab.badge !== undefined" class="tab-count">{{ tab.badge }}</span>
      </button>
    </nav>
    <div class="drawer-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
export interface MasterDetailTab {
  key: string
  label: string
  badge?: number
}

withDefaults(
  defineProps<{
    tabs: MasterDetailTab[]
    active?: string
  }>(),
  {
    active: ''
  }
)

const emit = defineEmits<{
  'update:active': [key: string]
}>()

function handleTabWheel(event: WheelEvent) {
  ;(event.currentTarget as HTMLElement).scrollLeft += event.deltaY
}
</script>
