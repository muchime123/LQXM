param(
  [int]$FrontendPort = 5173,
  [int]$BackendPort = 8081
)

$ErrorActionPreference = 'Stop'
$targets = @(
  @{ Name = 'Frontend'; Uri = "http://127.0.0.1:$FrontendPort/" },
  @{ Name = 'Backend health'; Uri = "http://127.0.0.1:$BackendPort/actuator/health" },
  @{ Name = 'Catalog stats'; Uri = "http://127.0.0.1:$BackendPort/api/catalog/stats" },
  @{ Name = 'Duplicate quality'; Uri = "http://127.0.0.1:$BackendPort/api/catalog/quality/duplicates" }
)

$failed = $false
foreach ($target in $targets) {
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $target.Uri -TimeoutSec 8
    Write-Host ("[PASS] {0}: HTTP {1}" -f $target.Name, $response.StatusCode)
  } catch {
    $failed = $true
    Write-Host ("[FAIL] {0}: {1}" -f $target.Name, $_.Exception.Message) -ForegroundColor Red
  }
}

if ($failed) {
  exit 1
}
