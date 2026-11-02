<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { fetchWebsiteCompanyContentCutoverReadiness, fetchWebsiteCompanyContentPreview, fetchWebsiteCompanyProfile, fetchWebsiteCompanyProfileParity, fetchWebsiteCompanyProfileReadiness, publishWebsiteCompanyProfile, saveWebsiteCompanyProfileDraft, updatePublishedWebsiteCompanyProfile } from '@/api/website/companyProfile'
import type { WebsiteCompanyContentCutoverReadiness, WebsiteCompanyContentPreview, WebsiteCompanyProfile, WebsiteCompanyProfileFields, WebsiteCompanyProfileParity, WebsiteCompanyProfileReadiness } from '@/api/website/companyProfile'
import { notify } from '@/composables/ui/useToast'
import WebsiteMediaPicker from '@/components/website/WebsiteMediaPicker.vue'

type TextField = Exclude<keyof WebsiteCompanyProfileFields, 'logoMediaId' | 'establishmentDate'>
const groups: Array<{ title: string; fields: Array<{ key: TextField; label: string; multiline?: boolean }> }> = [
  { title: '品牌与定位', fields: [
    { key: 'companyName', label: '公开公司名称' }, { key: 'companyNameEn', label: '英文公司名称' },
    { key: 'brandName', label: '品牌名称' }, { key: 'brandNameEn', label: '英文品牌名称' },
    { key: 'brandSlogan', label: '品牌主张' }, { key: 'corePositioning', label: '核心定位', multiline: true }
  ] },
  { title: '公开介绍', fields: [
    { key: 'mission', label: '使命', multiline: true }, { key: 'vision', label: '愿景', multiline: true },
    { key: 'values', label: '价值观', multiline: true }, { key: 'mainBusiness', label: '主营业务', multiline: true },
    { key: 'targetIndustries', label: '目标行业', multiline: true }, { key: 'advantages', label: '优势', multiline: true },
    { key: 'manufacturingStrengths', label: '制造能力', multiline: true }, { key: 'enterpriseScale', label: '企业规模' }
  ] },
  { title: '公开联系方式', fields: [
    { key: 'officeAddress', label: '办公地址' }, { key: 'factoryAddress', label: '工厂地址' },
    { key: 'phone', label: '联系电话' }, { key: 'email', label: '邮箱' }, { key: 'website', label: '网址' }
  ] }
]
const form = reactive<Record<TextField, string> & { establishmentDate: string; logoMediaId: number | null }>({
  companyName: '', companyNameEn: '', brandName: '', brandNameEn: '', brandSlogan: '', mission: '', vision: '',
  values: '', corePositioning: '', mainBusiness: '', targetIndustries: '', advantages: '', manufacturingStrengths: '',
  enterpriseScale: '', officeAddress: '', factoryAddress: '', phone: '', email: '', website: '',
  establishmentDate: '', logoMediaId: null
})
const profile = ref<WebsiteCompanyProfile | null>(null)
const loading = ref(false)
const saving = ref(false)
const checking = ref(false)
const readiness = ref<WebsiteCompanyProfileReadiness | null>(null)
const parity = ref<WebsiteCompanyProfileParity | null>(null)
const comparing = ref(false)
const publishing = ref(false)
const cutover = ref<WebsiteCompanyContentCutoverReadiness | null>(null)
const checkingCutover = ref(false)
const preview = ref<WebsiteCompanyContentPreview | null>(null)
const previewing = ref(false)

async function loadPreview() {
  previewing.value = true
  try { preview.value = await fetchWebsiteCompanyContentPreview() }
  catch (error) { notify.errorWithDetail('读取官网独立内容预览失败', error) }
  finally { previewing.value = false }
}

async function checkCutover() {
  checkingCutover.value = true
  try { cutover.value = await fetchWebsiteCompanyContentCutoverReadiness() }
  catch (error) { notify.errorWithDetail('检查官网公司内容切换条件失败', error) }
  finally { checkingCutover.value = false }
}

async function publish() {
  if (!profile.value || profile.value.status !== 'DRAFT') return
  try {
    const latest = await fetchWebsiteCompanyProfileReadiness()
    readiness.value = latest
    if (!latest.ready) { notify.error('请先处理发布前检查中的阻碍项'); return }
  } catch (error) {
    notify.errorWithDetail('发布前检查失败', error)
    return
  }
  try {
    await ElMessageBox.confirm('将此独立草稿标记为已发布。若部署环境已使用官网独立来源，发布会立即影响公开页面。发布后可通过受权限保护的入口编辑。',
      '确认发布官网公司介绍', { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' })
  } catch { return }
  publishing.value = true
  try {
    await publishWebsiteCompanyProfile()
    cutover.value = null
    preview.value = null
    await load()
    notify.success('官网公司介绍已发布；请核对部署环境的公司资料来源配置')
  } catch (error) {
    notify.errorWithDetail('发布官网公司介绍失败', error)
  } finally {
    publishing.value = false
  }
}

async function comparePublicFields() {
  comparing.value = true
  try {
    parity.value = await fetchWebsiteCompanyProfileParity()
  } catch (error) {
    notify.errorWithDetail('对拍官网公司资料失败', error)
  } finally {
    comparing.value = false
  }
}

async function checkReadiness() {
  checking.value = true
  try {
    readiness.value = await fetchWebsiteCompanyProfileReadiness()
  } catch (error) {
    notify.errorWithDetail('检查官网公司介绍草稿失败', error)
  } finally {
    checking.value = false
  }
}

async function load() {
  loading.value = true
  try {
    profile.value = await fetchWebsiteCompanyProfile()
    if (profile.value) {
      for (const group of groups) for (const field of group.fields) form[field.key] = profile.value[field.key] || ''
      form.establishmentDate = profile.value.establishmentDate || ''
      form.logoMediaId = profile.value.logoMediaId
    }
  } catch (error) {
    notify.errorWithDetail('加载官网公司介绍草稿失败', error)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.companyName.trim()) { notify.error('请填写公开公司名称'); return }
  const editingPublished = profile.value?.status === 'PUBLISHED'
  if (editingPublished) {
    try {
      await ElMessageBox.confirm('保存后会立即更新已发布的官网公司介绍。请确认公开文字、联系方式和 Logo 均已核对。',
        '确认更新公开内容', { type: 'warning' })
    } catch { return }
  }
  saving.value = true
  try {
    const fields = {} as WebsiteCompanyProfileFields
    for (const group of groups) for (const field of group.fields) {
      const text = form[field.key].trim()
      if (field.key === 'companyName') fields.companyName = text
      else fields[field.key] = text || null
    }
    fields.establishmentDate = form.establishmentDate || null
    fields.logoMediaId = form.logoMediaId
    profile.value = editingPublished
      ? await updatePublishedWebsiteCompanyProfile(fields)
      : await saveWebsiteCompanyProfileDraft(fields)
    readiness.value = null
    parity.value = null
    cutover.value = null
    preview.value = null
    notify.success(editingPublished ? '官网公司介绍已更新' : '官网公司介绍草稿已保存；公开官网内容未改变')
  } catch (error) {
    notify.errorWithDetail('保存官网公司介绍草稿失败', error)
  } finally {
    saving.value = false
  }
}

await load()
</script>

<template>
  <div v-loading="loading" class="website-company-profile-page">
    <header class="page-header">
      <div class="page-header__content">
        <p class="page-header__eyebrow">官网运营</p>
        <h1 class="page-header__title">官网公司介绍</h1>
        <p class="page-header__desc">此处内容独立于内部公司档案；草稿保存不影响官网，已发布内容更新会立即生效。</p>
      </div>
      <div class="page-header__actions">
        <el-button @click="load">刷新</el-button>
        <el-button :loading="comparing" @click="comparePublicFields">对拍公开字段</el-button>
        <el-button :loading="checking" @click="checkReadiness">发布前检查</el-button>
        <el-button :loading="checkingCutover" @click="checkCutover">检查公司内容切换</el-button>
        <el-button :loading="previewing" @click="loadPreview">预览独立来源响应</el-button>
        <el-button v-if="profile?.status === 'DRAFT'" :loading="publishing" :disabled="!readiness?.ready" @click="publish">发布独立内容</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ profile?.status === 'PUBLISHED' ? '保存并更新公开内容' : '保存草稿' }}</el-button>
      </div>
    </header>
    <el-alert v-if="!profile" title="尚无官网公司介绍草稿。请按当前公开页面逐项核对后填写，不会自动复制内部公司资料。" type="warning" :closable="false" />
    <el-alert v-else :title="`当前状态：${profile.status}；公开来源取决于部署配置`" type="info" :closable="false" />
    <section v-if="readiness" class="readiness">
      <el-alert :title="readiness.ready ? '内容具备后续审核条件' : '内容尚不具备切换条件'" :type="readiness.ready ? 'success' : 'warning'" :closable="false" />
      <ul v-if="readiness.blockers.length"><li v-for="item in readiness.blockers" :key="item">{{ item }}</li></ul>
      <ul v-if="readiness.warnings.length"><li v-for="item in readiness.warnings" :key="item">待审阅：{{ item }}</li></ul>
    </section>
    <section v-if="cutover" class="cutover">
      <h2>公司内容切换检查</h2>
      <el-alert :title="cutover.ready ? '公司介绍与发展历程满足严格对拍条件；仍需人工验收其他官网模块' : '公司内容尚不满足切换条件'" :type="cutover.ready ? 'success' : 'warning'" :closable="false" />
      <p>公司字段差异 {{ cutover.companyFieldDifferences }} 项；旧来源公开历程 {{ cutover.legacyMilestones }} 条，已发布一致 {{ cutover.publishedMilestoneMatches }} 条，官网额外已发布 {{ cutover.extraPublishedMilestones }} 条。</p>
      <p>旧来源公开官方链接 {{ cutover.legacySocialLinks }} 条，已发布一致 {{ cutover.publishedSocialLinkMatches }} 条，官网额外已发布 {{ cutover.extraPublishedSocialLinks }} 条。</p>
      <ul v-if="cutover.blockers.length"><li v-for="item in cutover.blockers" :key="item">{{ item }}</li></ul>
      <ul v-if="cutover.warnings.length"><li v-for="item in cutover.warnings" :key="item">待审阅：{{ item }}</li></ul>
    </section>
    <section v-if="preview" class="native-preview">
      <h2>独立来源公开响应预览</h2>
      <el-alert title="仅展示已发布的官网独立内容；此预览不切换公开接口。" type="info" :closable="false" />
      <el-descriptions v-if="preview.company" title="公司介绍" :column="1" border>
        <el-descriptions-item v-for="group in groups" :key="group.title" :label="group.title">
          <p v-for="field in group.fields" :key="field.key">{{ field.label }}：{{ preview.company[field.key] ?? '—' }}</p>
        </el-descriptions-item>
        <el-descriptions-item label="成立日期">{{ preview.company.establishmentDate ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="Logo 媒体 ID">{{ preview.company.logoMediaId ?? '—' }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-else title="尚无已发布的官网公司介绍；切换后公司资料将为空。" type="warning" :closable="false" />
      <h3>发展历程（{{ preview.milestones.length }} 条）</h3>
      <el-table :data="preview.milestones" border>
        <el-table-column prop="year" label="年份" width="100" />
        <el-table-column prop="title" label="标题" min-width="180" />
        <el-table-column prop="description" label="描述" min-width="260" />
      </el-table>
      <h3>官方链接（{{ preview.officialMediaAccounts.length }} 条）</h3>
      <el-table :data="preview.officialMediaAccounts" border>
        <el-table-column prop="platform" label="平台" width="120" />
        <el-table-column prop="accountName" label="账号名称" min-width="180" />
        <el-table-column prop="link" label="链接" min-width="260" />
      </el-table>
    </section>
    <section v-if="parity" class="parity">
      <h2>当前公开来源与独立草稿对拍</h2>
      <el-alert :title="`旧来源${parity.legacyExists ? '存在' : '不存在'}；独立草稿${parity.draftExists ? '存在' : '不存在'}；差异 ${parity.differentCount} 项。差异需人工确认，系统不会自动复制。`" type="info" :closable="false" />
      <el-table :data="parity.fields" border>
        <el-table-column prop="key" label="字段" width="180" />
        <el-table-column label="当前公开来源" min-width="260"><template #default="{ row }">{{ row.legacy ?? '—' }}</template></el-table-column>
        <el-table-column label="独立草稿" min-width="260"><template #default="{ row }">{{ row.draft ?? '—' }}</template></el-table-column>
        <el-table-column label="结果" width="90"><template #default="{ row }">{{ row.different ? '不同' : '一致' }}</template></el-table-column>
      </el-table>
    </section>
    <el-form label-position="top" :disabled="profile?.status === 'PUBLISHED'">
      <section v-for="group in groups" :key="group.title">
        <h2>{{ group.title }}</h2>
        <el-form-item v-for="field in group.fields" :key="field.key" :label="field.label">
          <el-input v-model="form[field.key]" :type="field.multiline ? 'textarea' : 'text'" :rows="field.multiline ? 3 : undefined" />
        </el-form-item>
      </section>
      <section>
        <h2>其他公开资料</h2>
        <el-form-item label="成立日期"><el-date-picker v-model="form.establishmentDate" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
        <el-form-item label="官网 Logo"><WebsiteMediaPicker v-model="form.logoMediaId" /></el-form-item>
      </section>
    </el-form>
  </div>
</template>

<style scoped>
.website-company-profile-page { max-width: 980px; }
.website-company-profile-page section { margin-top: 28px; }
.website-company-profile-page h2 { font-size: 18px; margin-bottom: 16px; }
</style>
