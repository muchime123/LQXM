/** Hide generated marketing placeholders until product copy is reviewed. */
export function reviewedSummary(name, summary) {
  if (!summary) return ''
  return /^.+，专业[^，。]*，品质保障。$/.test(summary.trim()) ? '' : summary
}
