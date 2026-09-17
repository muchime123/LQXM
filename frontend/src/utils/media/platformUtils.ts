/**
 * 平台工具函数
 *
 * 设计：
 * 1. loadPlatformConfigs() 从 API 加载配置 → 返回完整对象 [{name, icon, color, cssClass, bg}]
 * 2. 失败时回退到 FALLBACK_CONFIG，同时注入回退样式
 * 3. refreshPlatformConfigs() 用于平台配置弹窗更新后重新加载
 */

import { fetchActivePlatformConfigs } from '../../api/media'

export interface PlatformConfig {
  name: string
  icon: string
  color: string
  cssClass: string
  bg: string
}

interface ApiPlatformConfig {
  name: string
  icon?: string
  color?: string
}

const FALLBACK_CONFIG_LIST: ApiPlatformConfig[] = [
  { name: '抖音', icon: '🎵', color: '#161823' },
  { name: '视频号', icon: '💬', color: '#07c160' },
  { name: '小红书', icon: '📕', color: '#ff2442' },
  { name: '快手', icon: '⚡', color: '#ff4906' }
]

let _loaded = false
let _loading = false
let _loadPromise: Promise<PlatformConfig[]> | null = null
let _platformConfigs: PlatformConfig[] = []

let _listeners: ((configs: PlatformConfig[]) => void)[] = []

export function onPlatformConfigsReady(fn: (configs: PlatformConfig[]) => void): void {
  if (_loaded) {
    fn(_platformConfigs)
    return
  }
  _listeners.push(fn)
}

function notifyListeners(): void {
  _listeners.forEach(fn => fn(_platformConfigs))
  _listeners = []
}

function hashOf(name: string): number {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash)
    hash = hash & hash
  }
  return Math.abs(hash)
}

function generateCssClass(name: string): string {
  const match = name.match(/[a-zA-Z0-9]+/g)
  if (match) return 'plat-' + match.join('').toLowerCase()
  return 'plat-' + hashOf(name).toString(36)
}

function autoConfig(name: string): PlatformConfig {
  const hue = hashOf(name) % 360
  const color = `hsl(${hue}, 55%, 35%)`
  return {
    name,
    icon: '📱',
    color,
    cssClass: generateCssClass(name),
    bg: `hsl(${hue}, 40%, 94%)`
  }
}

function buildConfig(name: string, apiCfg: ApiPlatformConfig): PlatformConfig {
  const cssClass = generateCssClass(name)
  const color = apiCfg?.color || autoConfig(name).color
  const bg = hexToRgbaBg(color, 0.12)
  const icon = apiCfg?.icon || '📱'
  return { name, icon, color, cssClass, bg }
}

export async function loadPlatformConfigs(): Promise<PlatformConfig[]> {
  if (_loaded) return _platformConfigs
  if (_loading && _loadPromise) return _loadPromise

  _loading = true
  _loadPromise = fetchActivePlatformConfigs()
    .then(list => {
      _platformConfigs = (list || []).map((c: { name: string }) => buildConfig(c.name, c))
      injectPlatformStyles(_platformConfigs)
      _loaded = true
      notifyListeners()
      return _platformConfigs
    })
    .catch(err => {
      console.warn('加载平台配置失败，使用默认配置', err)
      _platformConfigs = FALLBACK_CONFIG_LIST.map(c => buildConfig(c.name, c))
      injectPlatformStyles(_platformConfigs)
      _loaded = true
      notifyListeners()
      return _platformConfigs
    })
    .finally(() => {
      _loading = false
    })

  return _loadPromise
}

export function getPlatformConfigs(): PlatformConfig[] {
  return _platformConfigs
}

export function getPlatformConfig(name: string): PlatformConfig {
  const found = _platformConfigs.find(c => c.name === name)
  if (found) return found
  return autoConfig(name)
}

export function platformClass(name: string): string {
  return getPlatformConfig(name).cssClass
}

export function platformBg(name: string): string {
  return getPlatformConfig(name).bg
}

export function platformIcon(name: string): string {
  return getPlatformConfig(name).icon
}

export function platformColor(name: string): string {
  return getPlatformConfig(name).color
}

export function getPlatformNames(): string[] {
  return _platformConfigs.map(c => c.name)
}

let _styleEl: HTMLStyleElement | null = null

export function injectPlatformStyles(configs: PlatformConfig[]): void {
  if (_styleEl) _styleEl.remove()
  if (!configs || !configs.length) return

  const rules = configs
    .map(c => {
      return (
        `.platform-badge.${c.cssClass}{background:${c.bg};color:${c.color};}` +
        `.account-card:has(.platform-badge.${c.cssClass})::before{background:${c.color};}`
      )
    })
    .join('')

  _styleEl = document.createElement('style')
  _styleEl.id = 'dynamic-platform-styles'
  _styleEl.textContent = rules
  document.head.appendChild(_styleEl)
}

function hexToRgbaBg(hex: string, alpha = 0.12): string {
  hex = hex.replace('#', '')
  if (hex.length === 3) {
    hex = hex
      .split('')
      .map(c => c + c)
      .join('')
  }
  const r = parseInt(hex.substring(0, 2), 16)
  const g = parseInt(hex.substring(2, 4), 16)
  const b = parseInt(hex.substring(4, 6), 16)
  return `rgba(${r},${g},${b},${alpha})`
}

export async function refreshPlatformConfigs(): Promise<PlatformConfig[]> {
  _loaded = false
  _loading = false
  _loadPromise = null
  _platformConfigs = []
  _listeners = []
  return loadPlatformConfigs()
}
