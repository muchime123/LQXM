<#
  注册「稳定实例」（8082）开机 / 登录自启 —— 让它在任何重启后自动回来。

  为什么需要它（2026-10-15 定案）：
    稳定实例是一个**普通进程**，机器一重启就没了；而本机 Windows 更新常在傍晚
    自动重启（Active Hours = 9~19，一过 19:00 更新即可动手，实测总在 19:29）。
    两者叠加 ⇒ 只要傍晚被更新重启一次，8082 整夜都不会回来，第二天必须有人
    手动跑 start-stable.ps1。本脚本把这步交给计划任务，从此无需人工干预。

  任务设计（任务名 LQXM-Stable-8082）：
    - 触发 ①「开机后 1 分钟」②「用户登录后 30 秒」③「每 15 分钟自愈一次」
    - 动作  powershell -File scripts\start-stable.ps1 -Port 8082
    - 身份  当前用户。优先 S4U（= 「不管用户是否登录都运行」，**不留明文口令**）；
            若账户未被授予「作为批处理作业登录」等权利导致 S4U 失败，
            自动回落为「仅在用户登录时运行」（此时没有 ①，只有 ②③）。
    - 设置  StartWhenAvailable（错过触发点会被补跑）、忽略重复实例、
            失败自动重试 3 次。
    - 幂等  start-stable.ps1 自带「端口已监听就跳过」⇒ 开机与登录同时命中、
            或自愈周期重叠时，都不会拉起第二份实例。

  用法（**必须在【管理员】PowerShell 中运行**；注册计划任务需要管理员权限）：
    .\scripts\install-stable-autostart.ps1              # 安装 / 覆盖更新
    .\scripts\install-stable-autostart.ps1 -LogonOnly   # 不写 S4U，只做「登录时运行」
    .\scripts\install-stable-autostart.ps1 -NoWatchdog  # 关掉「每 15 分钟自愈」
    .\scripts\install-stable-autostart.ps1 -Remove      # 卸载

  安装后自检：
    Get-ScheduledTask -TaskName 'LQXM-Stable-8082' | Get-ScheduledTaskInfo
    # LastTaskResult 应为 0；NextRunTime 应滚动更新
#>
[CmdletBinding()]
param(
  [int]$Port = 8082,
  [string]$TaskName = 'LQXM-Stable-8082',
  [string]$RunAs = "$env:USERDOMAIN\$env:USERNAME",
  [int]$WatchdogMinutes = 15,
  [switch]$LogonOnly,
  [switch]$NoWatchdog,
  [switch]$Remove
)

$ErrorActionPreference = 'Stop'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  throw "需要管理员权限。请用管理员身份打开 PowerShell 再运行本脚本（注册计划任务必须提权）。"
}

if ($Remove) {
  if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "已卸载计划任务：$TaskName" -ForegroundColor Green
  } else {
    Write-Host "计划任务不存在，无需卸载：$TaskName"
  }
  return
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$startScript = Join-Path $root 'scripts\start-stable.ps1'
if (-not (Test-Path -LiteralPath $startScript)) {
  throw "找不到启动脚本：$startScript"
}

$argument = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}" -Port {1}' -f $startScript, $Port
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argument -WorkingDirectory $root

$logonTrigger = New-ScheduledTaskTrigger -AtLogOn -User $RunAs
try { $logonTrigger.Delay = 'PT30S' } catch { }

$triggers = @($logonTrigger)
if (-not $LogonOnly) {
  $bootTrigger = New-ScheduledTaskTrigger -AtStartup
  try { $bootTrigger.Delay = 'PT1M' } catch { }
  $triggers += $bootTrigger
}

if (-not $NoWatchdog -and $WatchdogMinutes -gt 0) {
  $watchdog = $null
  try {
    $watchdog = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(2) `
      -RepetitionInterval (New-TimeSpan -Minutes $WatchdogMinutes) `
      -RepetitionDuration ([TimeSpan]::MaxValue)
  } catch {
    # 某些系统对 MaxValue 重复时长会报「参数错误」⇒ 省略 duration，默认即为无限重复
    $watchdog = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(2) `
      -RepetitionInterval (New-TimeSpan -Minutes $WatchdogMinutes)
  }
  $triggers += $watchdog
}

$settings = New-ScheduledTaskSettingsSet `
  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
  -StartWhenAvailable `
  -MultipleInstances IgnoreNew `
  -ExecutionTimeLimit (New-TimeSpan -Minutes 5) `
  -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

$description = "LQXM 稳定实例（端口 $Port）。开机/登录自动拉起，并每 $WatchdogMinutes 分钟自愈一次；脚本幂等，已在运行则跳过。"

$mode = ''
$registered = $false
if (-not $LogonOnly) {
  try {
    $principal = New-ScheduledTaskPrincipal -UserId $RunAs -LogonType S4U -RunLevel Limited
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $triggers -Settings $settings `
      -Principal $principal -Description $description -Force | Out-Null
    $mode = 'S4U（开机 + 登录 + 自愈；无需保持登录）'
    $registered = $true
  } catch {
    Write-Host "S4U 方式不适用（$($_.Exception.Message)）" -ForegroundColor Yellow
    Write-Host "回落到「仅在用户登录时运行」—— 机器停在登录界面时不会自动拉起。" -ForegroundColor Yellow
    $triggers = @($triggers | Where-Object { $_.CimClass.CimClassName -ne 'MSFT_TaskBootTrigger' })
  }
}

if (-not $registered) {
  $principal = New-ScheduledTaskPrincipal -UserId $RunAs -LogonType Interactive -RunLevel Limited
  Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $triggers -Settings $settings `
    -Principal $principal -Description $description -Force | Out-Null
  $mode = 'Interactive（仅登录时 + 自愈）'
}

$info = Get-ScheduledTask -TaskName $TaskName | Get-ScheduledTaskInfo
Write-Host ""
Write-Host "已注册计划任务：$TaskName" -ForegroundColor Green
Write-Host "  运行身份 : $RunAs  [$mode]"
$triggerNames = ($triggers | ForEach-Object { $_.CimClass.CimClassName -replace 'MSFT_Task','' -replace 'Trigger','' }) -join ' / '
Write-Host "  触发器   : $triggerNames"
Write-Host "  下次运行 : $($info.NextRunTime)"
Write-Host "  动作     : $argument"
Write-Host ""
Write-Host "手动验证一次（不会影响已在跑的实例）：" -ForegroundColor Cyan
Write-Host "  Start-ScheduledTask -TaskName '$TaskName'; Start-Sleep 8; (Get-ScheduledTask -TaskName '$TaskName' | Get-ScheduledTaskInfo).LastTaskResult"
Write-Host "  # LastTaskResult = 0 即成功（若 8082 已在监听，脚本会打印『已在监听，跳过启动』）"
