<#
  停止「稳定实例」（默认端口 8082）。

  只杀监听该端口的进程，不碰你的开发实例（5173 / 8081）。
#>
param(
  [int]$Port = 8082
)

$ErrorActionPreference = 'Stop'

$conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $conn) {
  Write-Host "端口 $Port 没有在监听的进程，无需停止。"
  return
}

$procId = $conn.OwningProcess
$proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
if ($proc) {
  Write-Host "正在停止稳定实例：pid=$procId ($($proc.ProcessName))，端口 $Port"
  Stop-Process -Id $procId -Force
  Start-Sleep -Milliseconds 800
  if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "警告：端口 $Port 仍在监听，可能未完全退出。" -ForegroundColor Yellow
  } else {
    Write-Host "已停止。" -ForegroundColor Green
  }
} else {
  Write-Host "进程 $procId 已不存在。"
}
