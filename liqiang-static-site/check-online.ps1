$base = 'https://liqiang-website.zjlqbz.workers.dev'
$paths = @('/','/products.html','/about.html','/contact.html','/products/product-67.html','/sitemap.xml','/assets/css/style.css','/assets/images/media_147.png')
foreach ($p in $paths) {
  try {
    $r = Invoke-WebRequest -Uri ($base + $p) -UseBasicParsing -TimeoutSec 40
    $title = if ($r.Content -match '<title>(.*?)</title>') { $matches[1] } else { '' }
    Write-Output ("{0}  len={1,-6}  {2}  {3}" -f $r.StatusCode, $r.Content.Length, $p, $title.Substring(0, [Math]::Min(30, $title.Length)))
  } catch {
    $resp = $_.Exception.Response
    if ($resp) {
      $code = [int]$resp.StatusCode
      Write-Output ("{0}  {1}" -f $code, $p)
    } else {
      Write-Output ("NET-ERR  {0}  {1}" -f $p, $_.Exception.Message)
    }
  }
}
