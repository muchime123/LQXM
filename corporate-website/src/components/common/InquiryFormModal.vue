<script setup>
/**
 * 全局询盘表单弹窗。
 * - 由 useInquiryModal() 的 isOpen 状态控制显隐
 * - 支持两种模式：consult（获取包装方案，默认） / sample（申请样品试封）
 * - 支持从产品详情页带入 productSlug / productName
 * - 复用 Contact.vue 的完整表单逻辑（验证 + 验证码 + 提交 + 蜜罐）
 * - 关闭时自动重置表单状态
 */
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { submitInquiry, fetchProductDetail, fetchCaptcha } from '../../api/website'
import { useInquiryModal } from '../../composables/useInquiryModal'
import { useCompany } from '../../composables/useCompany'
import { useCopy } from '../../composables/useCopy'
import AppIcon from '../AppIcon.vue'
import FormField from './FormField.vue'

const {
  isOpen,
  inquiryType,
  productSlug: modalProductSlug,
  productName: modalProductName,
  sourcePage: modalSourcePage,
  sceneLabel: modalSceneLabel,
  closeInquiry,
} = useInquiryModal()

const { company } = useCompany()
const { copyText, toast } = useCopy()

const phoneRaw = computed(() => company.value?.phone || '4009008878')
const isSampleInquiry = computed(() => inquiryType.value === 'sample')

const formTitle = computed(() => (isSampleInquiry.value ? '申请样品试封' : '获取包装方案'))
const formSubtitle = computed(() =>
  isSampleInquiry.value
    ? '填写以下信息，我们的工程师将安排样品试封测试。'
    : '填写以下信息，我们的工程师将尽快与您联系。'
)

/* ============ 询盘表单 ============ */
const form = ref({
  name: '',
  company: '',
  phone: '',
  email: '',
  product: '',
  description: '',
  containerSize: '',
  packagingProduct: '',
  website: '',
})

const errors = ref({})
const submitting = ref(false)
const submitSuccess = ref(false)
const submitError = ref('')

/* 验证码 */
const captchaId = ref('')
const captchaQuestion = ref('')
const captchaAnswer = ref('')

async function loadCaptcha() {
  try {
    const data = await fetchCaptcha()
    captchaId.value = data.captchaId
    captchaQuestion.value = data.question
    captchaAnswer.value = ''
  } catch (err) {
    console.warn('Captcha load failed:', err)
  }
}

/* 根据 product slug 查询产品名 */
async function loadProductName() {
  const slug = modalProductSlug.value
  const presetName = modalProductName.value
  if (presetName) {
    form.value.product = presetName
    return
  }
  if (!slug) return
  try {
    const detail = await fetchProductDetail(slug)
    if (detail?.model?.name) {
      form.value.product = detail.model.name
    }
  } catch (err) {
    console.warn('Product lookup failed:', err)
  }
}

function validate() {
  const e = {}
  if (!form.value.name.trim()) e.name = '请输入联系人姓名'
  if (!form.value.phone.trim()) e.phone = '请输入手机号码或座机'
  else if (!/^[0-9\-\s]{5,}$/.test(form.value.phone)) e.phone = '请输入有效的电话号码'
  if (!form.value.description.trim()) e.description = '请简要描述您的需求'
  if (isSampleInquiry.value && !form.value.containerSize.trim()) e.containerSize = '请填写容器尺寸'
  if (isSampleInquiry.value && !form.value.packagingProduct.trim()) e.packagingProduct = '请填写包装产品'
  if (!captchaAnswer.value.trim()) e.captcha = '请输入验证码'
  errors.value = e
  return Object.keys(e).length === 0
}

function resetForm() {
  form.value = {
    name: '', company: '', phone: '', email: '', product: '',
    description: '', containerSize: '', packagingProduct: '', website: '',
  }
  errors.value = {}
  submitSuccess.value = false
  submitError.value = ''
  captchaAnswer.value = ''
}

async function handleSubmit() {
  if (!validate()) return
  submitting.value = true
  submitSuccess.value = false
  submitError.value = ''
  try {
    const source = modalSourcePage.value || 'modal'
    const slug = modalProductSlug.value
    const scene = modalSceneLabel.value
    const payload = {
      name: form.value.name,
      company: form.value.company,
      phone: form.value.phone,
      email: form.value.email,
      product: form.value.product,
      description: form.value.description,
      sourcePage: `[弹窗] type=${inquiryType.value}${slug ? `&product=${slug}` : ''}${scene ? `&scene=${encodeURIComponent(scene)}` : ''}&source=${encodeURIComponent(source)}`,
      website: form.value.website,
      captchaId: captchaId.value,
      captchaAnswer: captchaAnswer.value,
    }
    if (isSampleInquiry.value) {
      const extra = `\n\n【样品试封信息】\n容器尺寸：${form.value.containerSize}\n包装产品：${form.value.packagingProduct}`
      payload.description = payload.description + extra
    }
    await submitInquiry(payload)
    submitSuccess.value = true
    setTimeout(() => {
      resetForm()
      closeInquiry()
    }, 3500)
    loadCaptcha()
  } catch (err) {
    console.warn('Submit failed:', err)
    const msg = err.message || ''
    if (msg.includes('CAPTCHA_INVALID')) {
      submitError.value = '验证码不正确或已过期，请重新填写'
      loadCaptcha()
    } else if (msg.includes('RATE_LIMITED') || msg.includes('429')) {
      submitError.value = '提交过于频繁，请稍后再试或直接拨打 400 900 8878'
    } else {
      submitError.value = '提交失败，请稍后重试或直接拨打 400 900 8878'
    }
    if (msg.includes('CAPTCHA_INVALID') || msg.includes('400')) {
      loadCaptcha()
    }
  } finally {
    submitting.value = false
  }
}

/* ============ 弹窗行为 ============ */
const dialogRef = ref(null)
const firstInputRef = ref(null)
let lastFocused = null

/* 打开：加载验证码 + 产品名 + 锁定滚动 + 焦点入第一输入框 */
watch(isOpen, async (open) => {
  if (open) {
    lastFocused = document.activeElement
    resetForm()
    /* 先加载，打开动画进行中并行跑网络请求 */
    loadCaptcha()
    loadProductName()
    document.documentElement.style.overflow = 'hidden'
    await nextTick()
    await new Promise((r) => setTimeout(r, 180)) /* 等 enter 动画过半 */
    firstInputRef.value?.focus?.()
  } else {
    document.documentElement.style.overflow = ''
    lastFocused?.focus?.()
    lastFocused = null
  }
})

/* ESC 关闭 */
function onKeyDown(e) {
  if (!isOpen.value) return
  if (e.key === 'Escape') closeInquiry()
}

/* 点击遮罩关闭（仅当直接点到 overlay 本身） */
function onOverlayClick(e) {
  if (e.target === e.currentTarget) closeInquiry()
}

onMounted(() => {
  window.addEventListener('keydown', onKeyDown)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
  document.documentElement.style.overflow = ''
})
</script>

<template>
  <teleport to="body">
    <transition name="modal-fade">
      <div
        v-if="isOpen"
        class="inquiry-modal-overlay"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="'inquiry-modal-title'"
        @click="onOverlayClick"
      >
        <transition name="modal-pop" appear>
          <div v-if="isOpen" ref="dialogRef" class="inquiry-modal" @click.stop>
            <!-- 关闭按钮 -->
            <button
              type="button"
              class="inquiry-modal__close"
              aria-label="关闭弹窗"
              @click="closeInquiry"
            >
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>

            <div class="inquiry-modal__grid">
              <!-- 左侧品牌与产品上下文（样品模式下展示样品图） -->
              <aside class="inquiry-modal__side">
                <div class="side-badge">LIQIANG · 利强科技</div>
                <h3 class="side-title" :id="'inquiry-modal-title'">{{ formTitle }}</h3>
                <p class="side-desc">{{ formSubtitle }}</p>

                <!-- 带入的产品上下文 -->
                <div v-if="form.product || modalProductName" class="side-context">
                  <div class="context-label">关注产品</div>
                  <div class="context-value">{{ form.product || modalProductName }}</div>
                </div>

                <!-- 带入的应用场景上下文 -->
                <div v-if="modalSceneLabel" class="side-context">
                  <div class="context-label">应用场景</div>
                  <div class="context-value">{{ modalSceneLabel }}</div>
                </div>

                <!-- 样品模式专属提示 -->
                <div v-if="isSampleInquiry" class="side-tip side-tip--sample">
                  <AppIcon name="check" :size="16" />
                  <span>工程师免费提供样品试封测试与封合参数建议</span>
                </div>
                <div v-else class="side-tip">
                  <AppIcon name="automation" :size="16" />
                  <span>24 小时内响应，选型 / 工艺 / 产线一站式建议</span>
                </div>

                <div class="side-contact">
                  <div><strong>咨询热线</strong><a :href="`tel:${phoneRaw}`">{{ phoneRaw }}</a></div>
                </div>
              </aside>

              <!-- 右侧表单 -->
              <div class="inquiry-modal__body">
                <form @submit.prevent="handleSubmit" class="modal-form-body">
                  <div class="form-row">
                    <FormField label="联系人" :required="true" :error="errors.name" html-for="im-name">
                      <input
                        ref="firstInputRef"
                        id="im-name"
                        v-model="form.name"
                        type="text"
                        placeholder="您的姓名"
                        :class="{ invalid: errors.name }"
                      />
                    </FormField>
                    <FormField label="公司名称" html-for="im-company">
                      <input id="im-company" v-model="form.company" type="text" placeholder="贵公司名称" />
                    </FormField>
                  </div>

                  <div class="form-row">
                    <FormField label="联系电话" :required="true" :error="errors.phone" html-for="im-phone">
                      <input id="im-phone" v-model="form.phone" type="tel" placeholder="手机或座机" :class="{ invalid: errors.phone }" />
                    </FormField>
                    <FormField label="邮箱" html-for="im-email">
                      <input id="im-email" v-model="form.email" type="email" placeholder="邮箱地址（选填）" />
                    </FormField>
                  </div>

                  <FormField label="关注产品" html-for="im-product">
                    <input
                      id="im-product"
                      v-model="form.product"
                      type="text"
                      :placeholder="form.product ? '已从上下文带入，可修改' : '请输入关注的产品型号（选填）'"
                    />
                  </FormField>

                  <!-- 样品试封专属字段 -->
                  <div v-if="isSampleInquiry" class="form-row">
                    <FormField label="容器尺寸" :required="true" :error="errors.containerSize" html-for="im-container">
                      <input
                        id="im-container"
                        v-model="form.containerSize"
                        type="text"
                        placeholder="如 200x150x50mm"
                        :class="{ invalid: errors.containerSize }"
                      />
                    </FormField>
                    <FormField label="包装产品" :required="true" :error="errors.packagingProduct" html-for="im-packproduct">
                      <input
                        id="im-packproduct"
                        v-model="form.packagingProduct"
                        type="text"
                        placeholder="如 卤制熟食 / 生鲜肉类"
                        :class="{ invalid: errors.packagingProduct }"
                      />
                    </FormField>
                  </div>

                  <FormField label="需求描述" :required="true" :error="errors.description" html-for="im-desc">
                    <textarea
                      id="im-desc"
                      v-model="form.description"
                      rows="4"
                      :placeholder="isSampleInquiry ? '请描述您的保鲜要求、目标产能等信息…' : '请描述您的产品、容器尺寸、目标产能、保鲜要求等信息…'"
                      :class="{ invalid: errors.description }"
                    ></textarea>
                  </FormField>

                  <!-- 验证码 -->
                  <div class="captcha-row" :class="{ invalid: errors.captcha }">
                    <label class="captcha-label">验证码 <span class="req">*</span></label>
                    <div class="captcha-input-group">
                      <span class="captcha-question">{{ captchaQuestion || '加载中…' }}</span>
                      <input
                        v-model="captchaAnswer"
                        type="text"
                        placeholder="计算结果"
                        class="captcha-input"
                        :class="{ invalid: errors.captcha }"
                        autocomplete="off"
                      />
                      <button type="button" class="captcha-refresh" @click="loadCaptcha" :disabled="submitting" title="换一题">↻</button>
                    </div>
                    <small v-if="errors.captcha" class="captcha-error">{{ errors.captcha }}</small>
                  </div>

                  <!-- 蜜罐 -->
                  <div class="hp-field" aria-hidden="true">
                    <label>网站（请勿填写）</label>
                    <input v-model="form.website" type="text" tabindex="-1" autocomplete="off" />
                  </div>

                  <button type="submit" class="submit-button" :disabled="submitting">
                    <span v-if="!submitting && !submitSuccess">{{ isSampleInquiry ? '提交样品需求 →' : '发送询盘 →' }}</span>
                    <span v-else-if="submitting">发送中…</span>
                    <span v-else><AppIcon name="check" :size="14" /> 需求已提交</span>
                  </button>

                  <p v-if="submitSuccess" class="submit-notice success">
                    需求已提交，我们的工作人员将尽快与您联系。或直接<a href="#" @click.prevent="copyText(phoneRaw)">复制电话号码</a>。
                  </p>
                  <p v-if="submitError" class="submit-notice error">
                    {{ submitError }}
                  </p>

                  <p class="modal-privacy-note">
                    <AppIcon name="lock" :size="12" /> 提交即视为您同意我们基于沟通需求使用上述信息，严格保密不外泄。
                  </p>
                </form>
              </div>
            </div>
          </div>
        </transition>
      </div>
    </transition>

    <transition name="toast">
      <div v-if="toast" class="copy-toast" role="status">{{ toast }}</div>
    </transition>
  </teleport>
</template>

<style scoped>
/* ============ 遮罩 ============ */
.inquiry-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(6, 21, 40, 0.55);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

/* ============ 弹窗主体 ============ */
.inquiry-modal {
  position: relative;
  width: 100%;
  max-width: 920px;
  max-height: min(90vh, 820px);
  background: #fff;
  border-radius: 20px;
  box-shadow: 0 30px 80px rgba(6, 21, 40, 0.28), 0 0 0 1px rgba(255, 255, 255, 0.05) inset;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.inquiry-modal__close {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 10;
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid var(--line);
  border-radius: 10px;
  color: var(--ink);
  cursor: pointer;
  transition: 0.2s;
}
.inquiry-modal__close:hover {
  background: var(--navy);
  color: #fff;
  transform: rotate(90deg);
}

/* ============ 两栏布局 ============ */
.inquiry-modal__grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  min-height: 0;
  height: 100%;
}

/* 左侧品牌侧 */
.inquiry-modal__side {
  background: linear-gradient(160deg, var(--navy-deep) 0%, var(--navy) 55%, #0f5c8b 100%);
  color: var(--on-navy-body);
  padding: 44px 32px 32px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  position: relative;
  overflow: hidden;
}
.inquiry-modal__side::before {
  content: '';
  position: absolute;
  top: -80px;
  right: -80px;
  width: 260px;
  height: 260px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(67, 198, 213, 0.18) 0%, transparent 65%);
}
.inquiry-modal__side::after {
  content: '';
  position: absolute;
  bottom: -60px;
  left: -60px;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(8, 118, 232, 0.25) 0%, transparent 65%);
}

.side-badge {
  position: relative;
  font-size: 11px;
  letter-spacing: 2px;
  color: var(--cyan);
  text-transform: uppercase;
  font-weight: 600;
  padding: 6px 12px;
  border: 1px solid rgba(67, 198, 213, 0.3);
  border-radius: 999px;
  align-self: flex-start;
  background: rgba(67, 198, 213, 0.08);
}
.side-title {
  position: relative;
  color: #fff;
  font-size: 26px;
  font-weight: 800;
  line-height: 1.25;
  margin: 6px 0 0;
}
.side-desc {
  position: relative;
  color: rgba(224, 236, 244, 0.82);
  font-size: 13.5px;
  line-height: 1.7;
  margin: 0;
}

.side-context {
  position: relative;
  margin-top: 6px;
  padding: 14px 16px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
}
.context-label {
  font-size: 11px;
  color: rgba(224, 236, 244, 0.65);
  letter-spacing: 1px;
  text-transform: uppercase;
  margin-bottom: 4px;
}
.context-value {
  color: #fff;
  font-weight: 700;
  font-size: 15px;
}

.side-tip {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  background: rgba(67, 198, 213, 0.1);
  border: 1px solid rgba(67, 198, 213, 0.22);
  border-radius: 10px;
  font-size: 12.5px;
  line-height: 1.6;
  color: rgba(224, 236, 244, 0.92);
}
.side-tip svg { flex-shrink: 0; margin-top: 2px; color: var(--cyan); }
.side-tip--sample { background: rgba(26, 107, 71, 0.18); border-color: rgba(94, 234, 170, 0.28); }
.side-tip--sample svg { color: #5eeaaa; }

.side-contact {
  position: relative;
  margin-top: auto;
  padding-top: 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.12);
  font-size: 13px;
  color: rgba(224, 236, 244, 0.7);
}
.side-contact > div { display: flex; align-items: center; justify-content: space-between; }
.side-contact strong { color: #fff; font-weight: 600; }
.side-contact a { color: var(--cyan); font-weight: 700; text-decoration: none; }
.side-contact a:hover { text-decoration: underline; }

/* 右侧表单 */
.inquiry-modal__body {
  padding: 40px 36px 32px;
  overflow-y: auto;
  min-height: 0;
}
.modal-form-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.submit-button {
  width: 100%;
  padding: 14px 24px;
  background: var(--blue);
  color: #fff;
  border: none;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  transition: 0.2s;
  margin-top: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.submit-button:hover:not(:disabled) { background: var(--navy); }
.submit-button:disabled { opacity: 0.7; cursor: not-allowed; background: var(--navy); }

.submit-notice {
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
}
.submit-notice.success { background: #e8f5ee; color: #1a6b47; }
.submit-notice.success a { color: #1a6b47; font-weight: 600; text-decoration: underline; }
.submit-notice.error { background: #fdecea; color: #a03020; }

.modal-privacy-note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0 0;
  padding: 10px 12px;
  background: #f6f8fb;
  border-radius: 8px;
  font-size: 11.5px;
  color: var(--muted);
  line-height: 1.5;
}
.modal-privacy-note svg { color: var(--muted); }

/* 验证码（沿用 contact.css 配色） */
.captcha-label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 6px;
}
.captcha-label .req { color: var(--danger); margin-left: 2px; }
.captcha-input-group { display: flex; align-items: center; gap: 8px; }
.captcha-question {
  padding: 10px 16px;
  background: var(--navy);
  color: #fff;
  border: 1px solid var(--navy);
  border-radius: 8px;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
  min-width: 100px;
  text-align: center;
}
.captcha-input {
  width: 120px;
  padding: 10px 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--ink);
  font-size: 14px;
  font-family: inherit;
  box-sizing: border-box;
  transition: border-color 0.2s, background 0.2s;
}
.captcha-input::placeholder { color: #a4a8b0; }
.captcha-input:focus { outline: none; border-color: var(--blue); background: #fff; }
.captcha-input.invalid { border-color: var(--danger); background: #fff5f5; }
.captcha-refresh {
  width: 38px;
  height: 38px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--muted);
  cursor: pointer;
  font-size: 18px;
  transition: 0.2s;
}
.captcha-refresh:hover:not(:disabled) { border-color: var(--blue); color: var(--blue); background: #fff; }
.captcha-error { display: block; margin-top: 4px; font-size: 12px; color: #e5484d; }

/* 蜜罐 */
.hp-field {
  position: absolute;
  left: -9999px;
  top: -9999px;
  opacity: 0;
  pointer-events: none;
  width: 1px;
  height: 1px;
  overflow: hidden;
}

/* ============ 动画 ============ */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.25s ease;
}
.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
.modal-pop-enter-active {
  transition: opacity 0.3s cubic-bezier(.2,.9,.3,1.2), transform 0.3s cubic-bezier(.2,.9,.3,1.2);
}
.modal-pop-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.modal-pop-enter-from,
.modal-pop-leave-to {
  opacity: 0;
  transform: translateY(12px) scale(0.98);
}

/* Toast（弹窗内独立使用，避免与 App.vue 重复样式冲突不生效） */
.copy-toast {
  position: fixed;
  left: 50%;
  bottom: 40px;
  transform: translateX(-50%);
  background: var(--footer-bg-deep);
  color: var(--white);
  font-size: 14px;
  padding: 10px 20px;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(6, 21, 40, .35);
  border: 1px solid rgba(67, 198, 213, .4);
  z-index: 10001;
  max-width: 90vw;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.toast-enter-active, .toast-leave-active {
  transition: opacity .25s ease, transform .25s ease;
}
.toast-enter-from, .toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(10px);
}

/* ============ 响应式 ============ */
@media (max-width: 780px) {
  .inquiry-modal-overlay { padding: 0; align-items: stretch; }
  .inquiry-modal {
    max-width: 100%;
    max-height: 100vh;
    height: 100vh;
    border-radius: 0;
  }
  .inquiry-modal__grid {
    grid-template-columns: 1fr;
    overflow-y: auto;
  }
  .inquiry-modal__side {
    padding: 56px 24px 24px;
    gap: 12px;
  }
  .inquiry-modal__body {
    padding: 28px 22px 32px;
    overflow: visible;
  }
  .side-title { font-size: 22px; }
  .form-row { grid-template-columns: 1fr; gap: 16px; }
  .captcha-question { font-size: 13px; padding: 9px 14px; min-width: 90px; }
  .captcha-input { width: 100px; }
}
@media (max-width: 420px) {
  .inquiry-modal__body { padding: 24px 18px 28px; }
  .side-tip { font-size: 12px; }
  .submit-button { font-size: 14px; padding: 13px 20px; }
}
</style>
