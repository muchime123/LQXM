# 产品中心 P1-B 批次 1：官网「兼容适配」HTTP 响应基线抓取（只读）
#
# 用法（后端启动后执行）：
#   powershell -File .\scripts\capture-website-compatibility-baseline.ps1 -OutFile docs\p1b-batch1\website-product-baseline-before.json
#   # 执行批次 1 变更后
#   powershell -File .\scripts\capture-website-compatibility-baseline.ps1 -OutFile docs\p1b-batch1\website-product-baseline-after.json
#   # 再对比两个文件
#
# 说明：
#   - 纯 GET，只读；/api/website/products/{slug} 是公开端点，无需登录。
#   - 每个已发布产品只保留契约对照所需字段，避免抓取 gallery 大图导致文件过大。
#   - 输出结构：[{ slug, modelName, completenessScore, variantCount, variants,
#                 compatiblePackaging, compatibleMolds, hasCompatibilityKeys }]
#     hasCompatibilityKeys 用于区分「字段缺失」与「字段为 []」两种契约形态。

param(
  [string]$BaseUrl = 'http://localhost:8081',
  [string]$OutFile = 'docs/p1b-batch1/website-product-baseline.json'
)

$ErrorActionPreference = 'Stop'

try {
  $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 8
  Write-Host "health=$($health.status)"
} catch {
  Write-Error "后端未在 $BaseUrl 启动：$($_.Exception.Message)"
  exit 1
}

$list = Invoke-RestMethod -Uri "$BaseUrl/api/website/products?size=200" -TimeoutSec 20
$items = $list.items
if (-not $items) { $items = $list.content }
if (-not $items) { $items = @() }
Write-Host "published products = $($items.Count)"

$rows = foreach ($p in $items) {
  $slug = $p.websiteSlug
  if (-not $slug) { continue }
  $detail = Invoke-RestMethod -Uri "$BaseUrl/api/website/products/$([uri]::EscapeDataString($slug))" -TimeoutSec 20
  $names = $detail.PSObject.Properties.Name
  $hasKeys = ($names -contains 'compatiblePackaging') -or ($names -contains 'compatibleMolds')
  [ordered]@{
    slug                 = $slug
    modelName            = $p.name
    completenessScore    = $p.completenessScore
    variantCount         = $detail.variantCount
    variants             = @($detail.variants).Count
    compatiblePackaging  = @($detail.compatiblePackaging).Count
    compatibleMolds      = @($detail.compatibleMolds).Count
    hasCompatibilityKeys = $hasKeys
  }
}

$outDir = Split-Path -Parent $OutFile
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$rows | ConvertTo-Json -Depth 4 | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "written: $OutFile ($($rows.Count) rows)"

$nonEmpty = @($rows | Where-Object { $_.compatiblePackaging -gt 0 -or $_.compatibleMolds -gt 0 })
Write-Host "products with non-empty compatibility = $($nonEmpty.Count)  (期望 0)"
