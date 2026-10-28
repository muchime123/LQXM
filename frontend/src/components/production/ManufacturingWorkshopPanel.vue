<template>
  <!--
    车间管理（2026-10-27 收敛为弹窗）：任务下达在氚云进行，本表只是产品归属用的字典——
    产品配置与参考工期按「车间 + 产品名」匹配，车间名只有这一处来源。
    不再有启用/停用：车间存在即可用，字段是历史遗留，代码已不再读写。
  -->
  <el-dialog v-model="visible" title="车间管理" width="min(520px, calc(100vw - 32px))">
    <p class="tip">产品配置与参考工期按「车间 + 产品名」匹配，车间名只有这一处来源；任务下达在氚云进行，这里只维护车间名单本身。</p>
    <div class="toolbar">
      <span class="count">共 {{ rows.length }} 个车间</span>
      <el-button type="primary" size="small" @click="openCreate">新增车间</el-button>
    </div>
    <el-table :data="rows" row-key="id" size="small" max-height="380">
      <el-table-column prop="workshopName" label="车间名称" min-width="220" />
      <el-table-column label="操作" width="80">
        <template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button></template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" :title="form.id ? '编辑车间' : '新增车间'"
      width="min(420px, calc(100vw - 32px))" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="车间名称" required>
          <el-input v-model="form.workshopName" maxlength="200" placeholder="与氚云任务单的产线名称保持一致" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()
const visible = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })

interface Workshop { id: number; workshopName: string }
const rows = ref<Workshop[]>([])
const dialogOpen = ref(false)
const saving = ref(false)
const form = reactive<{ id?: number; workshopName: string }>({ workshopName: '' })

async function load() {
  const w = await http.get<Workshop[]>('/manufacturing/master/workshops')
  rows.value = w.data
}
// 每次打开都重取，别处（产品配置）改动后这里不会陈旧。
watch(visible, v => { if (v) void load() }, { immediate: true })

function openCreate() { form.id = undefined; form.workshopName = ''; dialogOpen.value = true }
function openEdit(row: Workshop) { form.id = row.id; form.workshopName = row.workshopName; dialogOpen.value = true }

async function save() {
  if (!form.workshopName?.trim()) { ElMessage.warning('请填写车间名称'); return }
  saving.value = true
  try {
    if (form.id) await http.put(`/manufacturing/master/workshops/${form.id}`, { workshopName: form.workshopName })
    else await http.post('/manufacturing/master/workshops', { workshopName: form.workshopName })
    dialogOpen.value = false
    await load()
    ElMessage.success('车间已保存')
  } finally { saving.value = false }
}
</script>

<style scoped>
.tip { margin: 0 0 12px; color: var(--color-text-secondary); font-size: 13px; line-height: 1.6; }
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.count { color: var(--color-text-secondary); font-size: 12px; }
</style>
