<template>
  <section v-if="stats.platforms.length" class="platform-stats">
    <div class="platform-stat-card psc-all" :class="{ active: !activePlatform }" @click="$emit('select-platform', '')">
      <div class="psc-header">
        <span class="psc-all-label">全部平台</span><strong>{{ stats.totalAccounts }} 个账号</strong>
      </div>
      <div class="psc-body">
        <div class="psc-item">
          <label>本周更新</label><strong>{{ stats.updatesThisWeek }}</strong>
        </div>
        <div class="psc-item">
          <label>本月更新</label><strong>{{ stats.updatesThisMonth }}</strong>
        </div>
        <div class="psc-item">
          <label>总粉丝</label><span>{{ statsFormatNum(allFollowers) }}</span>
        </div>
        <div class="psc-item">
          <label>总获赞</label><span>{{ statsFormatNum(allLikes) }}</span>
        </div>
      </div>
    </div>
    <div v-for="p in stats.platforms" :key="p.platform" class="platform-stat-card"
      :class="{ active: activePlatform === p.platform }" @click="$emit('select-platform', p.platform)">
      <div class="psc-header">
        <span :class="['platform-badge', platformClass(p.platform)]">{{ p.platform }}</span><strong>{{ p.accountCount }}
          个账号</strong>
      </div>
      <div class="psc-body">
        <div class="psc-item">
          <label>本周更新</label><strong>{{ p.updatesThisWeek }}</strong>
        </div>
        <div class="psc-item">
          <label>本月更新</label><strong>{{ p.updatesThisMonth }}</strong>
        </div>
        <div class="psc-item">
          <label>总粉丝</label><span>{{ statsFormatNum(p.totalFollowers) }}</span>
        </div>
        <div class="psc-item">
          <label>总获赞</label><span>{{ statsFormatNum(p.totalLikes) }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { platformClass } from '../../utils/media/platformUtils'

const props = defineProps({
  stats: {
    type: Object,
    required: true
  },
  activePlatform: {
    type: String,
    default: ''
  }
})

defineEmits(['select-platform'])

const allFollowers = computed(() => props.stats.platforms.reduce((s, p) => s + (p.totalFollowers || 0), 0))
const allLikes = computed(() => props.stats.platforms.reduce((s, p) => s + (p.totalLikes || 0), 0))

function statsFormatNum(n) {
  if (n >= 10000) return (n / 10000).toFixed(1).replace(/\.0$/, '') + 'w'
  return String(n)
}
</script>

<style scoped>
@import '../../styles/media-account-view.css';
</style>
