<#
  停止「稳定实例」（默认端口 8082）。

  只杀监听该端口的进程，不碰你的开发实例（5173 / 8081）。

  权限说明（2026-10-27）：稳定实例如果是从「管理员」PowerShell 启动的，它就是提升进程，
  普通窗口无权结束它（Stop-Process / taskkill 都报 Access is denied）。此时本脚本会自动
  弹一次 UAC，用管理员身份重跑自己来完成终止；UAC 取消或账户无法提权时，给出手工指引。

  -NoElevate 用于提权后的那一次重跑，避免失败时无限弹 UAC。
#>
param(
  [int]$Port = 8082,
  [switch]$NoElevate
)

$ErrorActionPreference = 'Stop'

function Test-PortBusy([int]$p) {
  return [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
}

function Test-IsAdmin {
  return ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

$conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $conn) {
  Write-Host "端口 $Port 没有在监听的进程，无需停止。"
  return
}

$procId = $conn.OwningProcess
$proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
if (-not $proc) {
  Write-Host "端口 $Port 的监听进程 $procId 已不存在。"
  return
}

Write-Host "正在停止稳定实例：pid=$procId ($($proc.ProcessName))，端口 $Port"

# ① Stop-Process（同权限能杀就杀）
try { Stop-Process -Id $procId -Force -ErrorAction Stop }
catch { Write-Host "  Stop-Process 失败：$($_.Exception.Message.Trim())" -ForegroundColor DarkGray }

# ② taskkill 兜底（有时 Stop-Process 被进程退出码/句柄拖住，taskkill 反而干净）
if (Test-PortBusy $Port) {
  $prevEap = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  & taskkill /PID $procId /F 2>&1 | Out-Null
  $tkExit = $LASTEXITCODE
  $ErrorActionPreference = $prevEap
  if ($tkExit -ne 0) { Write-Host "  taskkill 失败（退出码 $tkExit，多为 Access is denied）。" -ForegroundColor DarkGray }
}

# ③ 等端口真正释放（JVM 退出需要一点时间）
$dead = $false
for ($i = 0; $i -lt 20 -and -not $dead; $i++) {
  Start-Sleep -Milliseconds 500
  if (-not (Test-PortBusy $Port)) { $dead = $true }
}

if (-not $dead) {
  Write-Host "端口 $Port 仍在监听（pid=$procId）。" -ForegroundColor Yellow

  # 提升进程只能用提升权限结束：弹 UAC 重跑自己一次。
  if (-not $NoElevate -and -not (Test-IsAdmin)) {
    Write-Host "  该进程疑似由「管理员」会话启动（提升进程），当前窗口权限不足。正在申请管理员权限重试…" -ForegroundColor Yellow
    $psExe = (Get-Process -Id $PID).Path
    if (-not $psExe -or -not (Test-Path -LiteralPath $psExe)) { $psExe = Join-Path $PSHOME 'powershell.exe' }
    try {
      Start-Process -FilePath $psExe -Verb RunAs -Wait -ErrorAction Stop -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-Port', $Port, '-NoElevate'
      ) | Out-Null
    } catch {
      Write-Host "  提权重试未执行（UAC 被取消或当前账户无法提权）。" -ForegroundColor Yellow
    }
    if (-not (Test-PortBusy $Port)) { $dead = $true }
  }
}

if ($dead) {
  Write-Host "已停止。" -ForegroundColor Green
} else {
  Write-Host ""
  Write-Host "未能停止 pid=$procId。它大概率是从「管理员 PowerShell」里启动的提升进程。" -ForegroundColor Red
  Write-Host "任选一种：" -ForegroundColor Yellow
  Write-Host "  1) 用管理员身份打开 PowerShell，执行 .\scripts\stop-stable.ps1 -Port $Port" -ForegroundColor Yellow
  Write-Host "  2) 任务管理器 → 详细信息 → 结束 PID $procId" -ForegroundColor Yellow
  Write-Host "  3) 稳定实例若由计划任务 LQXM-Stable-8082 拉起，管理员下执行：schtasks /End /TN `"LQXM-Stable-8082`"" -ForegroundColor Yellow
  throw "停止稳定实例失败（端口 $Port 仍被 pid=$procId 占用）。"
}
