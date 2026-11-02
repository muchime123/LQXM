const mediaPattern = /\/api\/website\/media\/(\d+)\/content/g

export function websiteMediaUrls(html: string): string[] {
  return [...new Set(html.match(mediaPattern) || [])]
}

export function savedWebsiteHtml(html: string, previews: Map<string, string>): string {
  let result = html
  for (const [saved, preview] of previews) result = result.split(preview).join(saved)
  return result
}

export function previewWebsiteHtml(html: string, previews: Map<string, string>): string {
  let result = html
  for (const [saved, preview] of previews) result = result.split(saved).join(preview)
  return result
}
