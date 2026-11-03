/**
 * 全局询盘弹窗状态管理。
 * 任意页面调用 openInquiry() 即可在当前页弹出询盘表单，无需跳转到 /contact。
 *
 * 用法：
 *   import { useInquiryModal } from '@/composables/useInquiryModal'
 *   const { openInquiry } = useInquiryModal()
 *   openInquiry({ type: 'consult', product: '450L', source: 'home' })
 */
import { ref } from 'vue'

const isOpen = ref(false)
const inquiryType = ref('consult') // 'consult' | 'sample'
const productSlug = ref('')
const productName = ref('')
const sourcePage = ref('')
const sceneLabel = ref('')
const sceneKey = ref('')
/* 方案文章上下文：让询盘能区分「看完哪篇文章后发起」，而不只是一个固定来源标记 */
const articleSlug = ref('')
const articleTitle = ref('')

export function useInquiryModal() {
  function openInquiry(opts = {}) {
    inquiryType.value = opts.type === 'sample' ? 'sample' : 'consult'
    productSlug.value = opts.productSlug || ''
    productName.value = opts.productName || ''
    sourcePage.value = opts.source || ''
    sceneLabel.value = opts.sceneLabel || ''
    sceneKey.value = opts.sceneKey || ''
    articleSlug.value = opts.articleSlug || ''
    articleTitle.value = opts.articleTitle || ''
    isOpen.value = true
  }

  function closeInquiry() {
    isOpen.value = false
  }

  return {
    isOpen,
    inquiryType,
    productSlug,
    productName,
    sourcePage,
    sceneLabel,
    sceneKey,
    articleSlug,
    articleTitle,
    openInquiry,
    closeInquiry,
  }
}
