<template>
  <ListPageLayout class="profile-page-layout" title="公司信息" description="维护公司概览、品牌资产、账户和组织资料。">
    <!-- Tab 导航 -->
    <nav class="profile-page-layout__navigation" aria-label="公司资料导航">
      <div class="company-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >
          <span class="tab-icon" v-html="tab.icon"></span>
          {{ tab.label }}
        </button>
      </div>
    </nav>

    <!-- ===== 概览 Tab ===== -->
    <template v-if="activeTab === 'overview'">
      <LoadingState v-if="loading" />
      <div v-else class="overview-root">
        <!-- 顶部：公司身份区 -->
        <div class="hero-card">
          <div class="hero-left">
            <div
              class="hero-logo"
              :class="{ 'has-image': logoLoaded && primaryLogo && !logoFailed, 'no-logo': !primaryLogo || logoFailed }"
            >
              <img
                v-if="primaryLogo && !logoFailed"
                :src="companyAssetPreviewUrl(primaryLogo)"
                alt="Logo"
                @load="logoLoaded = true"
                @error="onLogoError"
              />
              <span v-if="(!logoLoaded || logoFailed) && companyInfo.companyName" class="logo-fallback">{{
                companyInitial
              }}</span>
            </div>
            <div class="hero-text">
              <h1>{{ companyInfo.companyName || '-' }}</h1>
              <p v-if="companyInfo.formerName" class="hero-former">曾用名：{{ companyInfo.formerName }}</p>
              <p class="hero-en">{{ companyInfo.companyNameEn || '-' }}</p>
              <p v-if="companyInfo.brandName" class="hero-brand">
                <span class="tag"
                  >品牌：{{ companyInfo.brandName
                  }}{{ companyInfo.brandNameEn ? ' / ' + companyInfo.brandNameEn : '' }}</span
                >
              </p>
            </div>
          </div>
          <div class="hero-stats">
            <div class="hero-stat">
              <strong>{{ yearsInBusiness }}</strong>
              <span>经营年限</span>
            </div>
            <div class="hero-stat">
              <strong>{{ companyInfo.registeredCapital || '-' }}</strong>
              <span>注册资本</span>
            </div>
            <div v-if="companyInfo.paidCapital" class="hero-stat">
              <strong>{{ companyInfo.paidCapital }}</strong>
              <span>实缴资本</span>
            </div>
            <div class="hero-stat">
              <strong>{{ companyInfo.companyType || '-' }}</strong>
              <span>企业类型</span>
            </div>
            <div v-if="companyInfo.insuredCount != null" class="hero-stat">
              <strong>{{ companyInfo.insuredCount }}</strong>
              <span>参保人数</span>
            </div>
            <div v-if="companyInfo.enterpriseScale" class="hero-stat">
              <strong>{{ companyInfo.enterpriseScale }}</strong>
              <span>企业规模</span>
            </div>
          </div>
          <button v-if="canManageCompany" class="edit-btn" title="编辑公司信息" @click="openEdit">
            <svg
viewBox="0 0 24 24"
fill="none"
stroke="currentColor"
stroke-width="2"
width="16"
height="16">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
            </svg>
            编辑
          </button>
        </div>

        <!-- 主内容区 -->
        <div class="overview-main">
          <div class="info-panels">
            <!-- 定位与业务 -->
            <div
              v-if="companyInfo.corePositioning || companyInfo.mainBusiness || companyInfo.targetIndustries"
              class="info-card"
            >
              <h3 class="card-title">
                <span class="title-bar"></span>
                定位与业务
              </h3>
              <div v-if="companyInfo.corePositioning" class="highlight-box">
                <p>{{ companyInfo.corePositioning }}</p>
              </div>
              <div v-if="companyInfo.mainBusiness || companyInfo.targetIndustries" class="biz-grid">
                <div v-if="companyInfo.mainBusiness" class="biz-item">
                  <label>主营业务</label>
                  <p>{{ companyInfo.mainBusiness }}</p>
                </div>
                <div v-if="companyInfo.targetIndustries" class="biz-item">
                  <label>服务行业</label>
                  <p>{{ companyInfo.targetIndustries }}</p>
                </div>
              </div>
            </div>

            <!-- 核心优势 -->
            <div v-if="companyInfo.advantages" class="info-card">
              <h3 class="card-title">
                <span class="title-bar"></span>
                核心优势
              </h3>
              <div class="advantage-tags">
                <span v-for="(item, idx) in parseAdvantages(companyInfo.advantages)" :key="idx" class="adv-tag">{{
                  item
                }}</span>
              </div>
            </div>

            <!-- 营收结构 -->
            <div
              v-if="companyInfo.revenueDevicePct != null || companyInfo.revenueConsumablePct != null"
              class="info-card"
            >
              <h3 class="card-title">
                <span class="title-bar"></span>
                营收结构
              </h3>
              <div class="revenue-bar">
                <div
                  v-if="companyInfo.revenueDevicePct != null"
                  class="revenue-segment revenue-device"
                  :style="{ width: companyInfo.revenueDevicePct + '%' }"
                >
                  设备 {{ companyInfo.revenueDevicePct }}%
                </div>
                <div
                  v-if="companyInfo.revenueConsumablePct != null"
                  class="revenue-segment revenue-consumable"
                  :style="{ width: companyInfo.revenueConsumablePct + '%' }"
                >
                  耗材 {{ companyInfo.revenueConsumablePct }}%
                </div>
              </div>
            </div>

            <!-- 联系方式 -->
            <div v-if="companyInfo.phone || companyInfo.email || companyInfo.website" class="info-card">
              <h3 class="card-title">
                <span class="title-bar"></span>
                联系方式
              </h3>
              <div class="contact-grid">
                <div
                  v-if="companyInfo.phone"
                  class="contact-item"
                  :class="{ copied: copiedField === 'phone' }"
                  title="点击复制"
                  @click="copyContact(companyInfo.phone, 'phone')"
                >
                  <span class="contact-icon">
                    <svg
                      v-if="copiedField === 'phone'"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2.5"
                      width="18"
                      height="18"
                    >
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                    <svg
                      v-else
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      width="18"
                      height="18"
                    >
                      <path
                        d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"
                      />
                    </svg>
                  </span>
                  <div class="contact-body">
                    <span class="contact-label">{{ copiedField === 'phone' ? '已复制' : '电话' }}</span>
                    <strong>{{ companyInfo.phone }}</strong>
                  </div>
                </div>
                <div
                  v-if="companyInfo.email"
                  class="contact-item"
                  :class="{ copied: copiedField === 'email' }"
                  title="点击复制"
                  @click="copyContact(companyInfo.email, 'email')"
                >
                  <span class="contact-icon">
                    <svg
                      v-if="copiedField === 'email'"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2.5"
                      width="18"
                      height="18"
                    >
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                    <svg
                      v-else
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      width="18"
                      height="18"
                    >
                      <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0 1.1.9-2 2-2z" />
                      <polyline points="22,6 12,13 2,6" />
                    </svg>
                  </span>
                  <div class="contact-body">
                    <span class="contact-label">{{ copiedField === 'email' ? '已复制' : '邮箱' }}</span>
                    <strong>{{ companyInfo.email }}</strong>
                  </div>
                </div>
                <a
                  v-if="companyInfo.website"
                  :href="normalizeWebsite(companyInfo.website)"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="contact-item contact-link"
                >
                  <span class="contact-icon">
                    <svg
viewBox="0 0 24 24"
fill="none"
stroke="currentColor"
stroke-width="2"
width="18"
height="18">
                      <circle cx="12" cy="12" r="10" />
                      <line
x1="2"
y1="12"
x2="22"
y2="12" />
                      <path
                        d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"
                      />
                    </svg>
                  </span>
                  <div class="contact-body">
                    <span class="contact-label">网站</span>
                    <strong>{{ companyInfo.website }}</strong>
                  </div>
                </a>
              </div>
            </div>

            <!-- 工商信息 -->
            <div class="info-card">
              <h3 class="card-title">
                <span class="title-bar"></span>
                工商信息
              </h3>
              <div class="info-grid">
                <div class="info-row">
                  <label>统一社会信用代码</label>
                  <span class="field-value">{{ companyInfo.unifiedSocialCode || '-' }}</span>
                </div>
                <div class="info-row">
                  <label>法定代表人</label>
                  <span class="field-value">{{ companyInfo.legalRepresentative || '-' }}</span>
                </div>
                <div class="info-row">
                  <label>注册资本</label>
                  <span class="field-value">{{ companyInfo.registeredCapital || '-' }}</span>
                </div>
                <div class="info-row">
                  <label>营业期限</label>
                  <span class="field-value">{{ companyInfo.businessTerm || '-' }}</span>
                </div>
                <div class="info-row full">
                  <label>注册地址</label>
                  <span class="field-value">{{ companyInfo.registeredAddress || '-' }}</span>
                </div>
                <div class="info-row full">
                  <label>办公地址</label>
                  <span class="field-value">{{ companyInfo.officeAddress || '-' }}</span>
                </div>
                <div v-if="companyInfo.factoryAddress" class="info-row full">
                  <label>工厂地址</label>
                  <span class="field-value">{{ companyInfo.factoryAddress || '-' }}</span>
                </div>
                <div class="info-row full">
                  <label>经营范围</label>
                  <span class="field-value scope">{{ companyInfo.businessScope || '-' }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 右侧栏 -->
          <div class="overview-side">
            <div class="stats-panel">
              <div class="stats-cards-row">
                <!-- 专利知识产权 -->
                <div v-if="overviewStats.certificateTotal != null" class="stats-cards-card">
                  <div class="stats-cards-header">
                    <span class="stats-cards-icon stats-cards-icon-patent">
                      <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        width="18"
                        height="18"
                      >
                        <path
                          d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"
                        />
                      </svg>
                    </span>
                    <span class="stats-cards-label">知识产权</span>
                  </div>
                  <div class="stats-cards-body">
                    <div class="stats-cards-number">{{ overviewStats.certificateTotal }}</div>
                    <div v-if="statsCertBreakdown" class="stats-cards-sub">{{ statsCertBreakdown }}</div>
                  </div>
                  <div class="stats-cards-footer">
                    <span
                      v-if="(overviewStats.certificateExpired ?? 0) > 0"
                      class="stats-cards-chip stats-cards-chip-warn"
                      >已失效 {{ overviewStats.certificateExpired }}</span
                    >
                    <span v-else class="stats-cards-chip stats-cards-chip-green">有效</span>
                  </div>
                </div>

                <!-- 公司证照资质 -->
                <div v-if="overviewStats.companyCertTotal != null" class="stats-cards-card">
                  <div class="stats-cards-header">
                    <span class="stats-cards-icon stats-cards-icon-cert">
                      <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        width="18"
                        height="18"
                      >
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                        <polyline points="14 2 14 8 20 8" />
                        <line
x1="16"
y1="13"
x2="8"
y2="13" />
                        <line
x1="16"
y1="17"
x2="8"
y2="17" />
                      </svg>
                    </span>
                    <span class="stats-cards-label">证照资质</span>
                  </div>
                  <div class="stats-cards-body">
                    <div class="stats-cards-number">{{ overviewStats.companyCertTotal }}</div>
                    <div v-if="statsCertBreakdownCompany" class="stats-cards-sub">{{ statsCertBreakdownCompany }}</div>
                  </div>
                </div>

                <!-- 自媒体账号 -->
                <div v-if="overviewStats.mediaAccountTotal != null" class="stats-cards-card">
                  <div class="stats-cards-header">
                    <span class="stats-cards-icon stats-cards-icon-media">
                      <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        width="18"
                        height="18"
                      >
                        <rect
x="2"
y="2"
width="20"
height="20"
rx="2.18" />
                        <line
x1="8"
y1="2"
x2="8"
y2="22" />
                        <line
x1="16"
y1="2"
x2="16"
y2="22" />
                        <line
x1="2"
y1="8"
x2="22"
y2="8" />
                        <line
x1="2"
y1="16"
x2="22"
y2="16" />
                      </svg>
                    </span>
                    <span class="stats-cards-label">自媒体账号</span>
                  </div>
                  <div class="stats-cards-body">
                    <div class="stats-cards-number">{{ overviewStats.mediaAccountTotal }}</div>
                    <div v-if="statsPlatformBreakdown" class="stats-cards-sub">{{ statsPlatformBreakdown }}</div>
                  </div>
                  <div class="stats-cards-footer">
                    <template v-if="overviewStats.mediaAccountByPlatform">
                      <span
                        v-for="(count, platform) in overviewStats.mediaAccountByPlatform"
                        :key="platform"
                        class="stats-cards-chip"
                        >{{ platform }} {{ count }}</span
                      >
                    </template>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="companyInfo.licenseFileName" class="panel">
              <h3 class="section-title">营业执照</h3>
              <img
                :src="companyFieldFileUrl('company', companyInfo.licenseFileName)"
                alt="营业执照"
                class="license-thumb"
                @click="openAssetPreview({ fileName: companyInfo.licenseFileName, category: 'company' })"
              />
            </div>
          </div>
        </div>

        <CompanyInfoEditorModal
          v-model:visible="showEdit"
          :detail="companyInfo"
          :saving="companyEditSaving"
          @close="showEdit = false"
          @save="onCompanySave"
        />
      </div>
    </template>

    <!-- ===== 品牌资产 Tab ===== -->
    <template v-else-if="activeTab === 'assets'">
      <CompanyBrandAssetsTab
        :company-info="companyInfo"
        :initial-category="typeof route.query.category === 'string' ? route.query.category : undefined"
        @changed="onBrandAssetsChanged"
        @imported="onBrandAssetsImported"
        @preview-asset="openAssetPreview($event)"
      />
    </template>

    <!-- ===== 财务账户 Tab（仅管理员） ===== -->
    <template v-else-if="activeTab === 'accounts' && canManageCompany">
      <CompanyAccountsTab :file-url-resolver="companyFieldFileUrl" />
    </template>

    <!-- ===== 知识库 Tab（仅管理员） ===== -->
    <template v-else-if="activeTab === 'kb' && isAdmin">
      <CompanyKnowledgeTab />
    </template>

    <!-- ===== 发展历程 Tab ===== -->
    <template v-else-if="activeTab === 'milestones'">
      <CompanyMilestonesTab />
    </template>

  </ListPageLayout>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
// ========== 组件导入 ==========
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import CompanyInfoEditorModal from '@/components/company/CompanyInfoEditorModal.vue'
import CompanyBrandAssetsTab from '@/components/company/CompanyBrandAssetsTab.vue'
import CompanyAccountsTab from '@/components/company/CompanyAccountsTab.vue'
import CompanyKnowledgeTab from '@/components/company/CompanyKnowledgeTab.vue'
import CompanyMilestonesTab from '@/components/company/CompanyMilestonesTab.vue'
// ========== API 导入 ==========
import { getCompanyInfo, updateCompanyInfo, getAssets, getCompanyStats } from '@/api/company'
// ========== Composables 导入 ==========
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { notify } from '@/composables/ui/useToast'
import { useFileAssets } from '@/composables/upload/useFileAssets'
import { usePreviewAsset, useImported } from '@/composables/ui/useAppBridge'
// ========== Utils 导入 ==========
import { findFileAssetHandle, fileAssetPreviewUrlByOptions } from '@/utils/ui/fileAssetUrls'
import { tagList } from '@/utils/common/textUtils'
// ========== Constants 导入 ==========
import { COMPANY_MANAGEMENT_TABS, type CompanyManagementTabKey } from '@/constants/company-management'
// ========== Types 导入 ==========
import { FILE_OWNER_TYPE_KEYS } from '@/types/fileAsset'
import type { CompanyAsset, CompanyInfo, CompanyOverviewStats } from '@/types/company'

defineOptions({ inheritAttrs: false })

const emit = defineEmits(['changed'])
const openAssetPreview = usePreviewAsset()
const onImported = useImported()
const route = useRoute()

const { can, user } = useCurrentUser()
/** 是否可维护公司信息（编辑公司信息/素材/账号/里程碑等），由 company.manage 权限控制 */
const canManageCompany = computed(() => can('company.manage'))

// ========== Tab 导航 ==========
/** 知识库仅 ADMIN 可见；财务账户按 company.manage 权限展示。 */
const ADMIN_ONLY_TAB_KEYS: CompanyManagementTabKey[] = ['accounts', 'kb']
const isAdmin = computed(() => !!user.value?.roles?.includes('ADMIN'))
const tabs = computed(() => COMPANY_MANAGEMENT_TABS.filter(tab => tab.key === 'accounts' ? canManageCompany.value : !ADMIN_ONLY_TAB_KEYS.includes(tab.key) || isAdmin.value))

const activeTab = ref<CompanyManagementTabKey>('overview')
watch([() => route.query.tab, tabs], ([tab, list]) => {
  if (typeof tab === 'string' && list.some(item => item.key === tab)) activeTab.value = tab as CompanyManagementTabKey
}, { immediate: true })
const loading = ref(false)
const companyInfo = ref<CompanyInfo>({})
const brandAssets = ref<CompanyAsset[]>([])
const companyFileAssets = useFileAssets({
  ownerType: FILE_OWNER_TYPE_KEYS.company,
  notify
})

async function loadData() {
  loading.value = true
  try {
    companyInfo.value = (await getCompanyInfo().catch(() => ({}))) || {}
  } catch (err) {
    notify('加载公司信息失败', 'error')
    console.error(err)
  } finally {
    loading.value = false
  }
}

async function loadBrandAssets() {
  try {
    brandAssets.value = await getAssets()
    await refreshCompanyFileHandles()
  } catch {
    brandAssets.value = []
  }
}

async function refreshCompanyFileHandles() {
  try {
    await companyFileAssets.load()
  } catch {
    // Legacy file URLs remain available if unified handles cannot be loaded.
  }
}

function companyFileHandle(fileName: string | null | undefined, category?: string | null) {
  if (!fileName) return null
  return (
    companyFileAssets.items.value.find(
      handle => handle.fileName === fileName && (!category || handle.category === category)
    ) || findFileAssetHandle(companyFileAssets.items.value, fileName)
  )
}

function companyAssetPreviewUrl(asset: CompanyAsset | null | undefined) {
  const handle = companyFileHandle(asset?.fileName, asset?.category)
  return (
    companyFileAssets.previewUrl(handle) ||
    fileAssetPreviewUrlByOptions({
      ownerType: FILE_OWNER_TYPE_KEYS.company,
      assetId: asset?.id,
      category: 'company',
      fileName: asset?.fileName,
      modelId: null
    })
  )
}

function companyFieldFileUrl(category: string, fileName: string | null | undefined) {
  const handle = companyFileHandle(fileName, category)
  return (
    companyFileAssets.previewUrl(handle) ||
    fileAssetPreviewUrlByOptions({
      ownerType: FILE_OWNER_TYPE_KEYS.company,
      assetId: handle?.assetId,
      category,
      fileName,
      modelId: null
    })
  )
}

function onInfoUpdated(updatedInfo: CompanyInfo) {
  companyInfo.value = updatedInfo
}

// ====== ====== ====== ====== ====== ====== ====== ====== ====== ======
// 公司编辑弹窗 - CompanyInfoEditorModal
// ====== ====== ====== ====== ====== ====== ====== ====== ====== ======

const showEdit = ref(false)
const companyEditSaving = ref(false)

async function onCompanySave(data: CompanyInfo) {
  companyEditSaving.value = true
  try {
    const updated = await updateCompanyInfo(data)
    notify('公司信息已更新', 'success')
    showEdit.value = false
    onInfoUpdated(updated)
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : '未知错误'
    notify('保存失败: ' + message, 'error')
  } finally {
    companyEditSaving.value = false
  }
}

loadData()
loadBrandAssets()

// ====== ====== ====== ====== ====== ====== ====== ====== ====== ======
// 概览 Tab - Overview Logic
// ====== ====== ====== ====== ====== ====== ====== ====== ====== ======

const logoLoaded = ref(false)
const logoFailed = ref(false)

function onLogoError(_e: Event) {
  logoLoaded.value = false
  logoFailed.value = true
}

const primaryLogo = computed(() => {
  const logos = (brandAssets.value || [])
    .filter(a => a.category === 'brand_logo' && a.fileExists !== false)
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  return logos.find(a => a.defaultAsset) || logos[0] || null
})

watch(primaryLogo, () => {
  logoLoaded.value = false
  logoFailed.value = false
})

const companyInitial = computed(() => {
  const name = companyInfo.value?.companyName || companyInfo.value?.companyNameEn || ''
  if (!name) return '?'
  return name.charAt(0).toUpperCase()
})

const yearsInBusiness = computed(() => {
  const date = companyInfo.value?.establishmentDate
  if (!date) return '-'
  const d = new Date(date)
  if (isNaN(d.getTime())) return '-'
  const now = new Date()
  const years = now.getFullYear() - d.getFullYear()
  const hasPassed = now.getMonth() > d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() >= d.getDate())
  return (hasPassed ? years : Math.max(0, years - 1)) + ' 年'
})

function parseAdvantages(text: string | null | undefined) {
  return tagList(text)
}

function normalizeWebsite(url: string | null | undefined) {
  if (!url) return ''
  return url.match(/^https?:\/\//) ? url : 'https://' + url
}

const copiedField = ref('')
let copyTimer: ReturnType<typeof setTimeout> | undefined

async function copyContact(value: string | null | undefined, field: string) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    copiedField.value = field
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      copiedField.value = ''
    }, 1500)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = value
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    copiedField.value = field
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      copiedField.value = ''
    }, 1500)
  }
}

const overviewStats = ref<CompanyOverviewStats>({})
const statsLoading = ref(false)

async function loadStats() {
  statsLoading.value = true
  try {
    overviewStats.value = await getCompanyStats()
  } catch {
    overviewStats.value = {}
  } finally {
    statsLoading.value = false
  }
}

loadStats()

function openEdit() {
  showEdit.value = true
}

// ===== Stats Cards Computed（已内联到模板） =====
const statsCertBreakdown = computed(() => {
  const byCat = overviewStats.value.certificateByCategory
  if (!byCat) return ''
  return Object.entries(byCat)
    .map(([k, v]) => `${k} ${v}`)
    .join(' | ')
})

const statsCertBreakdownCompany = computed(() => {
  const byCat = overviewStats.value.companyCertByCategory
  if (!byCat) return ''
  return Object.entries(byCat)
    .map(([k, v]) => `${k} ${v}`)
    .join(' | ')
})

const statsPlatformBreakdown = computed(() => {
  const byPlat = overviewStats.value.mediaAccountByPlatform
  if (!byPlat) return ''
  return Object.entries(byPlat)
    .map(([k, v]) => `${k} ${v}`)
    .join(' | ')
})

// ====== ====== ====== ====== ====== ====== ====== ====== ====== ======
// 品牌资产 Tab - 事件转发
// ====== ====== ====== ====== ====== ====== ====== ====== ====== ======

async function onBrandAssetsChanged() {
  await loadBrandAssets()
  emit('changed')
}

function onBrandAssetsImported() {
  onImported()
}

onBeforeUnmount(() => {
  clearTimeout(copyTimer)
})
</script>

<style scoped>
/* ========== Tab 导航 ========== */
.company-tabs {
  gap: var(--space-control-gap-tight);
  margin-bottom: 24px;
  border-bottom: 1px solid var(--color-border-light);
  padding-bottom: 0;
}

.company-tabs button {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap);
  padding: 10px 18px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-secondary);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: all var(--transition-fast);
}

.company-tabs button:hover {
  color: var(--color-text-primary);
  background: var(--color-surface-hover);
  border-radius: var(--radius-md) var(--radius-md) 0 0;
}

.company-tabs button.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}

.tab-icon {
  flex-shrink: 0;
}

/* ====== ====== ====== ====== ====== ====== ====== ====== ====== ====== */
/* 概览 Tab 样式 */
/* ====== ====== ====== ====== ====== ====== ====== ====== ====== ====== */

/* 顶部身份卡片 */
.hero-card {
  background: linear-gradient(135deg, var(--color-surface) 0%, var(--color-primary-bg-light) 100%);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-xl);
  padding: 32px 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3xl);
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);
  position: relative;
}

.edit-btn {
  position: absolute;
  top: 20px;
  right: 20px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: var(--color-glass-text);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-md);
  color: var(--color-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition-fast);
  backdrop-filter: blur(8px);
}

.edit-btn:hover {
  background: var(--color-text-on-primary);
  border-color: var(--color-primary);
  box-shadow: 0 2px 8px var(--color-primary-ring);
  transform: translateY(-1px);
}

.hero-left {
  display: flex;
  align-items: center;
  gap: var(--space-section-gap-wide);
  flex: 1;
  min-width: 0;
}

.hero-stats {
  display: flex;
  gap: var(--space-section-gap-wide);
  flex-shrink: 0;
  padding-left: 28px;
  border-left: 1px solid var(--color-primary-border-light);
}

.hero-stat {
  text-align: center;
  min-width: 72px;
}

.hero-stat strong {
  display: block;
  font-size: 16px;
  font-weight: 700;
  color: var(--color-text-heading);
  margin-bottom: 4px;
  white-space: nowrap;
}

.hero-stat span {
  font-size: 11px;
  color: var(--color-text-faint);
  font-weight: 500;
}

.hero-logo {
  width: 96px;
  height: 96px;
  border-radius: var(--radius-xl);
  overflow: hidden;
  flex-shrink: 0;
  background: var(--color-primary-bg);
  border: 2px solid var(--color-primary-border);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px var(--color-primary-ring);
}

.hero-logo.has-image {
  background: var(--color-text-on-primary);
  border-color: var(--color-border-lighter);
}

.hero-logo img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.logo-fallback {
  font-size: 40px;
  font-weight: 700;
  color: var(--color-primary);
  text-shadow: 0 1px 3px var(--shadow-color-dark);
}

.hero-text {
  min-width: 0;
}

.hero-text h1 {
  font-size: 24px;
  font-weight: 700;
  margin: 0 0 6px;
  color: var(--color-text-heading);
  letter-spacing: 0.5px;
}

.hero-former {
  font-size: 12px;
  color: var(--color-text-faint);
  margin: 0 0 4px;
}

.hero-en {
  font-size: 13px;
  color: var(--color-text-faint);
  margin: 0 0 10px;
  line-height: 1.4;
}

.tag {
  display: inline-block;
  padding: 4px 14px;
  font-size: 12px;
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  border: 1px solid var(--color-primary-border);
  font-weight: 500;
}

/* 主内容区 */
.overview-main {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: var(--space-section-gap-wide);
}

.info-panels {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}

.info-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  padding: 22px 26px;
  box-shadow: var(--shadow-xs);
  transition: all var(--transition-fast);
}

.info-card:hover {
  box-shadow: var(--shadow-sm);
  border-color: var(--color-primary-border-light);
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-heading);
  margin: 0 0 18px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.title-bar {
  width: 3px;
  height: 16px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--color-primary) 0%, var(--color-primary-light) 100%);
}

/* 高亮框 */
.highlight-box {
  padding: 16px 20px;
  background: linear-gradient(135deg, var(--color-primary-bg) 0%, var(--color-primary-bg-light) 100%);
  border-radius: var(--radius-lg);
  border-left: 3px solid var(--color-primary);
  margin-bottom: 16px;
}

.highlight-box p {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-heading);
  line-height: 1.7;
}

/* 业务网格 */
.biz-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.biz-item {
  padding: 14px 16px;
  background: var(--color-surface-alt);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border-lighter);
}

.biz-item label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-text-faint);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
}

.biz-item p {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-secondary);
  line-height: 1.65;
}

/* 优势标签 */
.advantage-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.adv-tag {
  display: inline-block;
  padding: 6px 16px;
  font-size: 13px;
  border-radius: var(--radius-pill);
  background: var(--color-success-bg-soft);
  color: var(--color-success-text);
  border: 1px solid var(--color-success-border);
  font-weight: 500;
  transition: all var(--transition-fast);
}

.adv-tag:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 6px var(--shadow-color-soft);
}

/* 营收结构 */
.revenue-bar {
  display: flex;
  height: 36px;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border-lighter);
}

.revenue-segment {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-on-primary);
  transition: width 0.3s;
  min-width: 60px;
}

.revenue-device {
  background: linear-gradient(90deg, var(--color-primary) 0%, var(--color-primary-light) 100%);
}

.revenue-consumable {
  background: linear-gradient(90deg, var(--color-success) 0%, var(--color-success) 100%);
}

/* 联系方式 */
.contact-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-card-gap);
}

.contact-item {
  display: flex;
  align-items: center;
  gap: var(--space-card-gap);
  padding: 16px;
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: all var(--transition-fast);
  text-decoration: none;
}

.contact-item:hover {
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg-light);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px var(--color-primary-ring);
}

.contact-item.copied {
  border-color: var(--color-success);
  background: var(--color-success-bg-soft);
  box-shadow: 0 0 0 3px var(--color-success-border);
}

.contact-link {
  cursor: pointer;
}

.contact-link:hover strong {
  text-decoration: underline;
}

.contact-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  background: linear-gradient(135deg, var(--color-primary-bg-light) 0%, var(--color-primary-bg) 100%);
  color: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all var(--transition-fast);
}

.contact-item.copied .contact-icon {
  background: linear-gradient(135deg, var(--color-success-bg-soft) 0%, var(--color-success-bg) 100%);
  color: var(--color-success);
}

.contact-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
  flex: 1;
}

.contact-label {
  font-size: 11px;
  font-weight: 500;
  color: var(--color-text-faint);
  text-transform: uppercase;
  letter-spacing: 0.3px;
  transition: color var(--transition-fast);
}

.contact-item.copied .contact-label {
  color: var(--color-success-text);
}

.contact-body strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color var(--transition-fast);
}

.contact-item.copied .contact-body strong {
  color: var(--color-success-text);
}

/* 工商信息 */
.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-section-gap) var(--space-section-gap-wide);
}

.info-row {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.info-row.full {
  grid-column: 1 / -1;
}

.info-row label {
  font-size: 11px;
  color: var(--color-text-faint);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.field-value {
  font-size: 14px;
  color: var(--color-text-primary);
  line-height: 1.6;
}

.field-value.scope {
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text-secondary);
}

/* 右侧栏 */
.overview-side {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.stats-panel {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  padding: 14px;
  box-shadow: var(--shadow-xs);
}

.stats-cards-row {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-card-gap);
}

.stats-cards-card {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: linear-gradient(180deg, var(--color-text-on-primary) 0%, var(--color-surface-alt) 100%);
  box-shadow: 0 8px 20px var(--shadow-color-faint);
}

.stats-cards-header {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  min-width: 0;
}

.stats-cards-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  color: var(--color-primary);
  background: var(--color-primary-bg-light);
}

.stats-cards-icon-cert {
  color: var(--color-warning);
  background: var(--color-warning-bg-soft);
}

.stats-cards-icon-media {
  color: var(--color-success);
  background: var(--color-success-bg);
}

.stats-cards-label {
  min-width: 0;
  color: var(--color-text-heading);
  font-size: 14px;
  font-weight: 700;
  line-height: 1.3;
}

.stats-cards-body {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.stats-cards-number {
  color: var(--color-text-heading);
  font-size: 28px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0;
}

.stats-cards-sub {
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.stats-cards-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.stats-cards-chip {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  max-width: 100%;
  padding: 2px 7px;
  border-radius: 999px;
  color: var(--color-text-secondary);
  background: var(--color-surface-hover);
  font-size: 11px;
  font-weight: 600;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.stats-cards-chip-green {
  color: var(--color-success);
  background: var(--color-success-bg);
}

.stats-cards-chip-warn {
  color: var(--color-warning);
  background: var(--color-warning-bg-soft);
}

.panel {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  padding: 22px;
  box-shadow: var(--shadow-xs);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-heading);
  margin: 0 0 14px;
}

.license-thumb {
  width: 100%;
  border-radius: var(--radius-lg);
  cursor: zoom-in;
  border: 1px solid var(--color-border-lighter);
  transition: transform var(--transition-fast);
}

.license-thumb:hover {
  transform: scale(1.02);
}

@media (max-width: 960px) {
  .hero-card {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-section-gap);
    padding-top: 56px;
  }

  .overview-main {
    grid-template-columns: 1fr;
  }

  .biz-grid,
  .contact-grid,
  .info-grid {
    grid-template-columns: 1fr;
  }
}

/* 品牌资产 Tab 样式已抽取到 company-brand-assets-tab.css */
.profile-page-layout__navigation {
  min-width: 0;
  margin-bottom: var(--space-section-gap);
  overflow-x: auto;
  scrollbar-width: thin;
}
</style>
