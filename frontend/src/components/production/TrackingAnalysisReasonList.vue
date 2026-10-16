<template><div v-if="items.length" class="reason-list"><button v-for="item in items" :key="item.name" type="button" @click="emit('select', item.value || item.name)"><span :title="item.name">{{ item.name }}</span><b>{{ item.count }} 单 ↗</b></button></div><p v-else class="empty">暂无记录</p></template>
<script setup lang="ts">
defineProps<{ items: { name: string; count: number; value?: string }[] }>()
const emit=defineEmits<{ select: [name: string] }>()
</script>
<style scoped>.reason-list{display:grid;gap:8px}.reason-list button{display:flex;justify-content:space-between;gap:12px;border:0;border-bottom:1px solid #edf1f5;padding:5px 0;background:none;text-align:left;cursor:pointer;color:inherit}.reason-list button:hover{color:#087c72}.reason-list span{overflow-wrap:anywhere}.reason-list b{white-space:nowrap;color:#52677f}.empty{color:#94a3b8}</style>
