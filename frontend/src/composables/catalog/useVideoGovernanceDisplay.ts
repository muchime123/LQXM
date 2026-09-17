export interface GovernedVideo {
  complete?: boolean
  missingItems?: string[]
}

export function useVideoGovernanceDisplay() {
  function completenessOf(video: GovernedVideo) {
    const missing = video.missingItems || []
    return missing.length === 0
      ? { ok: true, text: '完整' }
      : { ok: false, text: `缺 ${missing.length} 项` }
  }

  function completenessHint(video: GovernedVideo): string {
    return video.missingItems?.length ? `缺：${video.missingItems.join(' + ')}` : ''
  }

  function isVideoComplete(video: GovernedVideo): boolean {
    return Boolean(video.complete)
  }

  function completenessBadgeText(video: GovernedVideo): string {
    const missing = video.missingItems || []
    return missing.length === 0 ? '完整' : `缺 ${missing.length} 项`
  }

  return { completenessOf, completenessHint, isVideoComplete, completenessBadgeText }
}
