$smap = Get-Content -Raw -LiteralPath 'dist\sitemap.xml'
$locs = [regex]::Matches($smap, '<loc>(.*?)</loc>') | ForEach-Object { $_.Groups[1].Value }
$base = 'https://liqiang-website.zjlqbz.workers.dev'
# probe: 首页、产品中心、sitemap 里一个产品详情页、assets 一张图、robots
$probes = @('/index.html', '/products.html', $locs[1], '/assets/images/media_3.jpg', '/robots.txt', '/_redirects')
foreach ($p in $probes) {
  $status = 'NET'
  for ($i = 0; $i -lt 3; $i++) {
    try {
      $r = Invoke-WebRequest -Uri ($base + $p) -UseBasicParsing -TimeoutSec 45
      $status = "$($r.StatusCode)"
      break
    } catch {
      $resp = $_.Exception.Response
      if ($resp) { $status = "[$([int]$resp.StatusCode)]"; break }
      Start-Sleep -Milliseconds 500
    }
  }
  Write-Output ("{0,-5}  {1}" -f $status, $p)
}
Write-Output "--- sitemap loc count: $($locs.Count) ; first loc: $($locs[0])"
