import { reactive } from 'vue'

interface LoadingState {
  visible: boolean
  text: string
  count: number
}

const loadingState = reactive<LoadingState>({
  visible: false,
  text: '处理中...',
  count: 0
})

function showLoading(text = '处理中...'): void {
  loadingState.count++
  loadingState.text = text
  loadingState.visible = true
}

function hideLoading(): void {
  loadingState.count = Math.max(0, loadingState.count - 1)
  if (loadingState.count === 0) {
    loadingState.visible = false
  }
}

function resetLoading(): void {
  loadingState.count = 0
  loadingState.visible = false
}

async function withLoading<T>(fn: () => Promise<T>, text?: string): Promise<T> {
  showLoading(text)
  try {
    return await fn()
  } finally {
    hideLoading()
  }
}

export { loadingState, showLoading, hideLoading, resetLoading, withLoading }
