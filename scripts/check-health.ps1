<#
  服务探活。

  用法：
    .\scripts\check-health.ps1                          # 探活开发实例（前端 5173 / 后端 8081）
    .\scripts\check-health.ps1 -Stable                  # 探活稳定实例（单端口 8082）
    .\scripts\check-health.ps1 -FrontendPort 5173 -BackendPort 8081
    .\scripts\check-health.ps1 -BackendPort 8081 -EmployeeId 23   # 带身份探活 /api 接口

  说明：/api 下的接口都需要身份。不带 -EmployeeId 时，401 视为「服务在、接口需鉴权」，
  不计为失败；只有连接不上或 5xx 才判失败。
#>
param(
  [int]$FrontendPort = 5173,
  [int]$BackendPort = 8081,
  [int]$StablePort = 8082,
  [switch]$Stable,
  [int]$EmployeeId = 0
)

$ErrorActionPreference = 'Continue'
$failed = $false

$headers = @{}
if ($EmployeeId -gt 0) { $headers['X-Employee-Id'] = "$EmployeeId" }

function Test-Target([string]$Name, [string]$Uri, [bool]$Allow401) {
  try {
    $params = @{ Uri = $Uri; UseBasicParsing = $true; TimeoutSec = 8 }
    if ($headers.Count -gt 0) { $params['Headers'] = $headers }
    $resp = Invoke-WebRequest @params
    Write-Host ("[PASS] {0}: HTTP {1}" -f $Name, $resp.StatusCode) -ForegroundColor Green
    return $true
  } catch {
    $status = $null
    if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
    if ($Allow401 -and $status -eq 401) {
      Write-Host ("[PASS] {0}: HTTP 401（服务在，接口需鉴权）" -f $Name) -ForegroundColor Green
      return $true
    }
    Write-Host ("[FAIL] {0}: {1}" -f $Name, $_.Exception.Message) -ForegroundColor Red
    return $false
  }
}

if ($Stable) {
  # 稳定实例是单进程：同一端口同时提供前端与 API
  if (-not (Test-Target 'Stable 前端' "http://127.0.0.1:$StablePort/" $false)) { $failed = $true }
  if (-not (Test-Target 'Stable health' "http://127.0.0.1:$StablePort/actuator/health" $false)) { $failed = $true }
  if (-not (Test-Target 'Stable 业务接口' "http://127.0.0.1:$StablePort/api/catalog/stats" $true)) { $failed = $true }
  exit ($(if ($failed) { 1 } else { 0 }))
}

if (-not (Test-Target 'Frontend' "http://127.0.0.1:$FrontendPort/" $false)) { $failed = $true }
if (-not (Test-Target 'Backend health' "http://127.0.0.1:$BackendPort/actuator/health" $false)) { $failed = $true }
if (-not (Test-Target 'Catalog stats' "http://127.0.0.1:$BackendPort/api/catalog/stats" $true)) { $failed = $true }

if ($failed) { exit 1 }
