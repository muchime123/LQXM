<template>
  <div class="login-view">
    <div class="login-view__brand">
      <div class="login-view__brand-mark">利强</div>
      <h1 class="login-view__brand-title">利强包装科技有限公司</h1>
      <p class="login-view__brand-subtitle">业务 · 费用 · 利润 · 提成 全链路数字化</p>
    </div>

    <div class="login-view__panel">
      <div class="login-view__card">
        <h2 class="login-view__title">
          {{ bootstrapRequired ? '初始化管理员账号' : '欢迎回来' }}
        </h2>
        <p class="login-view__subtitle">
          {{ bootstrapRequired ? '系统首次运行，请先创建第一个管理员登录账号' : '请使用员工手机号登录系统' }}
        </p>

        <!-- 首次引导：创建第一个管理员账号 -->
        <el-form v-if="bootstrapRequired" :model="bootstrapForm" label-position="top" @submit.prevent="doBootstrap">
          <el-form-item label="选择员工（管理员）" required>
            <el-select
              v-model="bootstrapForm.employeeId"
              filterable
              placeholder="选择要作为管理员的员工"
              style="width: 100%"
            >
              <el-option
                v-for="emp in employeeOptions"
                :key="emp.id"
                :label="`${emp.name}（${emp.department || '未分部门'}）`"
                :value="emp.id"
              />
            </el-select>
            <p v-if="employeeOptions.length === 0 && !accountLoading" class="login-view__empty-hint">
              暂无可选员工，请确认后台已导入“在职”员工名册。
            </p>
          </el-form-item>
          <el-alert title="管理员账号将使用所选员工的手机号，初始密码为 123456。" type="info" :closable="false" />
          <el-button type="primary" class="login-view__submit" :loading="submitting" @click="doBootstrap">
            创建账号并登录
          </el-button>
          <p class="login-view__hint">该账号与员工绑定，权限继承自该员工的角色。</p>
        </el-form>

        <!-- 正式登录 -->
        <template v-else>
          <el-form :model="localForm" label-position="top" @submit.prevent="doLocalLogin">
            <el-form-item label="手机号" required>
              <el-input v-model="localForm.username" placeholder="请输入员工手机号" autocomplete="username" />
            </el-form-item>
            <el-form-item label="密码" required>
              <el-input
                v-model="localForm.password"
                type="password"
                show-password
                placeholder="首次登录密码为 123456"
                autocomplete="current-password"
                @keyup.enter="doLocalLogin"
              />
            </el-form-item>
            <el-button type="primary" class="login-view__submit" :loading="submitting" @click="doLocalLogin">
              登 录
            </el-button>
            <p class="login-view__hint">账号默认为员工手机号，初始密码为 123456。</p>
          </el-form>
        </template>

        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          :closable="false"
          show-icon
          class="login-view__error"
        />
      </div>

      <p class="login-view__footer">利强包装科技有限公司 · 内部业务系统</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { bootstrapAccount, getPublicEmployees } from '@/api/auth'
import { http } from '@/api/http'

interface EmployeeOption {
  id: number
  name: string
  department?: string | null
}

const route = useRoute()
const router = useRouter()
const auth = useCurrentUser()

const bootstrapRequired = ref(false)
const submitting = ref(false)
const accountLoading = ref(false)
const errorMessage = ref('')
const employeeOptions = ref<EmployeeOption[]>([])

const localForm = ref({ username: '', password: '' })
const bootstrapForm = ref({ employeeId: null as number | null })

/**
 * 登录后的落点：优先回显式 redirect（被守卫拦下时带过来的原目标），
 * 否则交给路由守卫解析默认落地页 —— 默认「生产跟单」，无权限回落「客户档案」。
 * 落点口径唯一来源：`@/utils/defaultLanding`（此处不写死路径）。
 */
const redirectPath = computed(() => {
  const q = route.query.redirect
  return typeof q === 'string' && q.startsWith('/') ? q : '/'
})

try {
  bootstrapRequired.value = (await fetchBootstrapRequired()).bootstrapRequired
  if (bootstrapRequired.value) {
    await loadEmployees()
  }
} catch {
  errorMessage.value = '无法连接后端服务，请确认系统已启动'
}

async function fetchBootstrapRequired() {
  return (await http.get<{ bootstrapRequired: boolean }>('/auth/bootstrap-required')).data
}

async function loadEmployees() {
  accountLoading.value = true
  try {
    employeeOptions.value = await getPublicEmployees()
  } catch {
    employeeOptions.value = []
  } finally {
    accountLoading.value = false
  }
}

function showError(err: unknown) {
  errorMessage.value =
    typeof err === 'object' && err && 'message' in err
      ? String((err as { message: string }).message)
      : '登录失败，请稍后重试'
}

async function doLocalLogin() {
  if (!localForm.value.username || !localForm.value.password) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.loginByLocal(localForm.value.username, localForm.value.password)
    ElMessage.success('登录成功')
    router.replace(redirectPath.value)
  } catch (err) {
    showError(err)
  } finally {
    submitting.value = false
  }
}

async function doBootstrap() {
  if (!bootstrapForm.value.employeeId) {
    errorMessage.value = '请选择管理员员工'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const result = await bootstrapAccount({
      employeeId: bootstrapForm.value.employeeId
    })
    localStorage.setItem('lq.token', result.token)
    ElMessage.success('管理员账号已创建')
    // 落点由守卫按权限解析（见 utils/defaultLanding.ts）：此处只 setItem 了 token，
    // 守卫的 `/` 分支会先 load 当前用户再决定进生产跟单还是客户档案。
    router.replace(redirectPath.value)
  } catch (err) {
    showError(err)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-view {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 72px;
  padding: 24px;
  background:
    radial-gradient(1200px 600px at 10% 0%, var(--color-primary-soft, rgba(59, 130, 246, 0.12)), transparent 60%),
    var(--color-bg, #f6f7f9);
}

.login-view__brand {
  max-width: 420px;
  color: var(--color-text, #1f2937);
}

.login-view__brand-mark {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-lg, 12px);
  background: var(--color-primary, #3b82f6);
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}

.login-view__brand-title {
  font-size: 28px;
  font-weight: 700;
  margin: 0 0 10px;
}

.login-view__brand-subtitle {
  font-size: 15px;
  color: var(--color-text-secondary, #6b7280);
  margin: 0;
}

.login-view__panel {
  width: 400px;
}

.login-view__card {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: var(--radius-lg, 12px);
  box-shadow: var(--shadow-md, 0 8px 24px rgba(0, 0, 0, 0.06));
  padding: 32px;
}

.login-view__title {
  font-size: 20px;
  font-weight: 700;
  margin: 0 0 6px;
}

.login-view__subtitle {
  font-size: 13px;
  color: var(--color-text-secondary, #6b7280);
  margin: 0 0 24px;
}

.login-view__submit {
  width: 100%;
}

.login-view__hint {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
  margin: 14px 0 0;
  text-align: center;
}

.login-view__empty-hint {
  font-size: 12px;
  color: var(--color-text-secondary, #6b7280);
  margin: 6px 0 0;
}

.login-view__error {
  margin-top: 16px;
}

.login-view__footer {
  text-align: center;
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
  margin: 16px 0 0;
}

@media (max-width: 860px) {
  .login-view {
    flex-direction: column;
    gap: 32px;
  }
  .login-view__brand {
    max-width: none;
    text-align: center;
  }
  .login-view__brand-mark {
    margin-left: auto;
    margin-right: auto;
  }
}
</style>
