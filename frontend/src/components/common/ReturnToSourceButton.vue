<template>
  <el-button v-if="returnTarget" @click="goBackToSource">{{ label }}</el-button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

withDefaults(defineProps<{ label?: string }>(), { label: '返回来源页面' })

const route = useRoute()
const router = useRouter()
const returnTarget = computed(() => {
  const value = String(route.query.returnTo || '')
  return value.startsWith('/') && !value.startsWith('//') ? value : ''
})

function goBackToSource() {
  if (returnTarget.value) router.push(returnTarget.value)
}
</script>
