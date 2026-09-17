import Chart from 'chart.js/auto'
import type { ChartConfiguration } from 'chart.js'

/**
 * Chart.js 图表管理器
 * 统一管理图表实例的创建、销毁和配置，避免内存泄漏和重复代码
 */

interface ChartInstance {
  destroy: () => void
}

type ChartConfigType = ChartConfiguration

const CHART_FONT = { family: '-apple-system, "Microsoft YaHei", sans-serif', size: 12 }

export class ChartManager {
  private charts: Map<string, ChartInstance>

  constructor() {
    this.charts = new Map()
  }

  create(id: string, target: string | HTMLElement, config: ChartConfigType): ChartInstance | null {
    this.destroy(id)

    const canvas = typeof target === 'string' ? document.getElementById(target) : target
    if (!canvas) {
      console.warn(`[ChartManager] 找不到 canvas 元素: ${target}`)
      return null
    }

    try {
      const chart = new Chart(canvas as HTMLCanvasElement, config)
      this.charts.set(id, chart)
      return chart
    } catch (err) {
      console.error(`[ChartManager] 图表 ${id} 渲染失败`, err)
      return null
    }
  }

  get(id: string): ChartInstance | null {
    return this.charts.get(id) || null
  }

  destroy(id: string): void {
    const chart = this.charts.get(id)
    if (chart) {
      try {
        chart.destroy()
      } catch (err) {
        console.warn(`[ChartManager] 销毁图表 ${id} 失败`, err)
      }
      this.charts.delete(id)
    }
  }

  destroyAll(): void {
    this.charts.forEach((_, id) => this.destroy(id))
  }

  has(id: string): boolean {
    return this.charts.has(id)
  }
}

export const ChartConfig = {
  font: CHART_FONT,

  baseOptions(extra: Record<string, unknown> = {}): Record<string, unknown> {
    return {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      ...extra
    }
  },

  barOptions(extra: Record<string, unknown> = {}): Record<string, unknown> {
    return {
      ...this.baseOptions(extra),
      scales: {
        y: { beginAtZero: true, ticks: { stepSize: 1, font: CHART_FONT } },
        x: { ticks: { font: CHART_FONT, maxRotation: 45, autoSkip: true, maxTicksLimit: 30 } }
      }
    }
  },

  lineOptions(extra: Record<string, unknown> = {}): Record<string, unknown> {
    return this.baseOptions(extra)
  }
}

export { CHART_FONT }
