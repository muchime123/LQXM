$base = 'https://liqiang-website.zjlqbz.workers.dev'
$probes = @('/products.html', '/products/', '/products', '/products/product-67.html', '/assets/images/media_1.jpg', '/contact.html')
foreach ($p in $probes) {
  $status = 'NET'; $loc = ''
  for ($i = 0; $i -lt 3; $i++) {
    try {
      $r = Invoke-WebRequest -Uri ($base + $p) -UseBasicParsing -TimeoutSec 40 -MaximumRedirection 0 -ErrorAction Stop
      $status = "$($r.StatusCode)"; $loc = [string]$r.Headers['Location']; break
    } catch {
      $resp = $_.Exception.Response
      if ($resp) { $status = "[$([int]$resp.StatusCode)]"; try { $loc = [string]$resp.Headers['Location'] } catch {}; break }
      Start-Sleep -Milliseconds 400
    }
  }
  Write-Output ("{0,-5}  loc={1,-45}  {2}" -f $status, $loc, $p)
}
