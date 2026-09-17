<script setup>
/**
 * 页脚布局组件。包含品牌、产品方案导航、联系方式、版权。
 * 公司数据从 useCompany 单例获取，与 Home/About/Contact 共享同一份数据。
 */
import { computed } from 'vue'
import { useCompany } from '../composables/useCompany'
import { useOfficialMedia } from '../composables/useOfficialMedia'
import { useCopy } from '../composables/useCopy'
import { useInquiryModal } from '../composables/useInquiryModal'
import AppIcon from './AppIcon.vue'

const { company } = useCompany()
const { findByPlatform } = useOfficialMedia()
const { copyText } = useCopy()
const { openInquiry } = useInquiryModal()

function openConsult() {
  openInquiry({ type: 'consult', source: 'footer' })
}
function openSample() {
  openInquiry({ type: 'sample', source: 'footer' })
}

const douyin = computed(() => findByPlatform('抖音'))
const douyinTitle = computed(() => (douyin.value ? `抖音：${douyin.value.accountName}` : '抖音'))
const douyinHref = computed(() => douyin.value?.link || '')

const companyName = computed(() => company.value?.companyName || '浙江利强包装科技有限公司')
const phone = computed(() => company.value?.phone || '4009008878')
const phoneText = computed(() => phone.value.replace(/(\d{4})(\d{3})(\d{4})/, '$1 $2 $3'))
const email = computed(() => company.value?.email || '768963166@qq.com')
const year = new Date().getFullYear()
const address = computed(() =>
  company.value?.factoryAddress ||
  company.value?.officeAddress ||
  company.value?.registeredAddress ||
  '浙江省温州市瓯海区南白象街道金洋路77号启迪未来产业园D栋2层'
)
</script>

<template>
  <footer>
    <div class="footer-grid">
      <div class="footer-brand">
        <RouterLink to="/" class="brand footer-brand__logo" aria-label="利强包装首页">
          <span class="brand-icon">
            <img src="/images/logo-only.png" alt="" loading="lazy" decoding="async" />
          </span>
          <span class="brand-text">
            <strong class="brand-title">利强</strong>
            <em class="brand-subtitle">LIQIANG</em>
          </span>
        </RouterLink>
        <div class="footer-brand-meta">
          <p class="footer-slogan">一站式包装合作伙伴</p>
          <a class="footer-phone" href="#" @click.prevent="copyText(phoneText)">
            <span class="footer-phone__label">服务热线</span>
            <strong>{{ phoneText }}</strong>
          </a>
        </div>
      </div>

      <div class="footer-nav-groups">
        <nav class="footer-nav-group" aria-label="业务导航">
          <strong>产品与方案</strong>
          <RouterLink to="/products">产品中心</RouterLink>
          <RouterLink to="/solutions">解决方案</RouterLink>
          <RouterLink to="/packaging">包材耗材</RouterLink>
          <RouterLink to="/molds">模具中心</RouterLink>
        </nav>
        <nav class="footer-nav-group" aria-label="企业导航">
          <strong>企业与合作</strong>
          <RouterLink to="/about">关于利强</RouterLink>
          <RouterLink to="/credentials">企业资质</RouterLink>
          <RouterLink to="/patents">专利与知识产权</RouterLink>
          <button type="button" class="footer-plain-link" @click="openSample()">样品试封</button>
          <RouterLink to="/about#contact">联系我们</RouterLink>
        </nav>
      </div>

      <div class="footer-contact">
        <strong>联系我们</strong>
        <span class="footer-addr">{{ address }}</span>
        <a href="#" @click.prevent="copyText(email)">{{ email }}</a>
        <button type="button" class="footer-consult-link" @click="openConsult()">提交包装需求 →</button>
      </div>
    </div>

    <div class="footer-bottom">
      <span>© {{ year }} {{ companyName }} 版权所有</span>
      <div class="footer-socials">
        <a v-if="douyinHref" :href="douyinHref" target="_blank" rel="noopener noreferrer" class="footer-social-link" :title="douyinTitle">
          <AppIcon name="douyin" :size="18" />
        </a>
      </div>
      <span class="footer-motto">食品包装 · 设备 / 耗材 / 模具 / 方案</span>
    </div>
  </footer>
</template>
