/**
 * 分析/复盘抽屉「右侧下钻列表」的**状态机唯一来源**。
 *
 * 【为什么单独一个模块】生产分析 / 周复盘 / 节点分析三处原本各写一份：
 *   `drilldownItems / drilldownTotal / page / size / loading` + `drilldownRequest` 防竞态计数器 +
 *   `openDrilldown`（置对象→翻第 1 页→load→scrollIntoView nearest）+ `loadDrilldown`（防竞态、分页）。
 * 三份几乎相同，唯一差异是下钻对象（bucket+label+extra / type）与请求参数不同——而这些恰恰是调用方自己的，
 * 不应由公共模块持有。这里把「列表状态 + 分页 + 防竞态 + 加载 + 错误 + 滚动」收进一个 composable，
 * 调用方只传 loader（闭包捕获自身下钻对象），open / close 一行收敛。
 *
 * 【边界】本模块只持有「下钻列表」的通用状态；具体下钻对象（bucket/label/extra/type）与 loader 是调用方的。
 * 这样节点分析（node/wait/waitMin 三种模式）与生产/周复盘（bucket+reason/reasonCategory）都能复用，不会被迫统一请求形状。
 */
import { nextTick, ref } from 'vue'

export type TrackingDrilldownLoader<T> = (
  page: number,
  size: number
) => Promise<{ items: T[]; total: number }>

export function useTrackingDrilldown<T>(opts?: { size?: number }) {
  const size = opts?.size ?? 30
  const active = ref(false)
  const items = ref<T[]>([])
  const total = ref(0)
  const page = ref(1)
  const loading = ref(false)
  const error = ref('')
  const panel = ref<HTMLElement | null>(null)

  /** 防竞态：后一次请求覆盖前一次，避免快速切换指标时旧响应覆盖新数据。 */
  let request = 0

  /** 标记激活并把分页回第 1 页（每次下钻都从第一页看起）。 */
  function open() {
    active.value = true
    page.value = 1
  }

  /** 收起：停掉在途请求、清空列表，保持右栏回到空态。 */
  function close() {
    active.value = false
    ++request
    items.value = []
  }

  /** 执行一次加载；loader 由调用方构造（已捕获其下钻对象）。错误写入内部 error，不向上抛。 */
  async function load(loader: TrackingDrilldownLoader<T>) {
    const req = ++request
    loading.value = true
    try {
      const result = await loader(page.value, size)
      if (req === request) {
        items.value = result.items
        total.value = result.total
        error.value = ''
      }
    } catch (e) {
      if (req === request) error.value = e instanceof Error ? e.message : '工单清单加载失败'
    } finally {
      if (req === request) loading.value = false
    }
  }

  /** 打开下钻后把面板滚到可视区内（nearest：不把左栏分析内容挤出视口）。 */
  async function scrollIntoView() {
    await nextTick()
    panel.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }

  return { active, items, total, page, size, loading, error, panel, open, close, load, scrollIntoView }
}
