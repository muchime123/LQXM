/**
 * 平台常量（自媒体平台颜色等）
 */

export const PLATFORMS: string[] = ['抖音', '视频号', '小红书', '快手']

export const PLATFORM_COLORS: Record<string, string> = {
  抖音: '#161823',
  视频号: '#07c160',
  小红书: '#ff2442',
  快手: '#ff4906'
}

export function platformColor(platform: string): string {
  return PLATFORM_COLORS[platform] || '#64748b'
}
