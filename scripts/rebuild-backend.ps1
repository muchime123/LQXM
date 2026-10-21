<#
.SYNOPSIS
    一键「打包后端 + 重启开发实例后端（8081）」。

.DESCRIPTION
    等价于手册 §2.3 的三步：
        cd backend
        mvn -o -DskipTests -Djacoco.skip=true package
        cd ..
        .\scripts\start-local.ps1 -RestartBackend
    另外做了几件事：解析 mvn 真实路径、把 Maven 输出落盘到 backend\logs\mvn-package.log、
    校验产物是「fat jar」且时间戳确实变新，最后确认 8081 真的在监听。

.PARAMETER SkipPackage
    跳过打包，只重启后端（确认已打过包时用，快很多）。

.PARAMETER Online
    去掉 Maven 的 -o（离线）参数，允许联网解析依赖。新增/升级依赖时用。

.PARAMETER Clean
    先 clean 再 package（默认增量打包；改了大量代码或遇到诡异编译问题时用）。

.PARAMETER WithFrontend
    顺带确保前端 5173 也在跑（默认不管前端，只动后端）。

.PARAMETER BackendPort
    开发后端端口，默认 8081。

.EXAMPLE
    .\scripts\rebuild-backend.ps1
    # 打包 + 重启 8081（最常用）

.EXAMPLE
    .\scripts\rebuild-backend.ps1 -SkipPackage
    # 只重启 8081，不重新打包
#>

param(
  [switch]$SkipPackage,
  [switch]$Online,
  [switch]$Clean,
  [switch]$WithFrontend,
  [int]$BackendPort = 8081
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $root 'backend'
$jar = Join-Path $backend 'target\product-archive-0.0.1-SNAPSHOT.jar'
$logDir = Join-Path $backend 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$mvnLog = Join-Path $logDir 'mvn-package.log'

function Write-Step([string]$Text) {
  Write-Host ''
  Write-Host "==> $Text" -ForegroundColor Cyan
}

# 修复 PATH：IDE 集成终端会注入小写 http_proxy / Path，与 Windows 原生大写变量撞车，
# 触发 PowerShell 5.1 的 Start-Process 缺陷（详见操作手册 §5.4）。从资源管理器双击
# 启动时本就没有这个问题，这里只是兜底，保证任何环境下行为一致。
function Repair-ProcessPath {
  $pathValue = [Environment]::GetEnvironmentVariable('Path', 'Process')
  if ($pathValue) {
    [Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $pathValue, 'Process')
  }
}

Repair-ProcessPath

# 解析 mvn：优先 PATH，其次 MAVEN_HOME / M2_HOME，最后几个常见安装目录。
# 不依赖裸命令名，避免 PATH 被改写时静默失败。
function Resolve-Mvn {
  $found = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
  if (-not $found) { $found = Get-Command 'mvn' -ErrorAction SilentlyContinue }
  if ($found -and $found.Source) { return $found.Source }

  $candidates = @()
  foreach ($base in @($env:MAVEN_HOME, $env:M2_HOME, 'D:\Program Files\apache-maven-3.9.9', 'D:\Program Files\apache-maven-3.9.6', 'C:\Program Files\apache-maven-3.9.9', 'C:\Program Files\apache-maven-3.9.6')) {
    if ($base) { $candidates += (Join-Path $base 'bin\mvn.cmd') }
  }
  $candidates += (Get-ChildItem -Path 'D:\Program Files','C:\Program Files' -Filter 'apache-maven-*' -Directory -Recurse -Depth 3 -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'bin\mvn.cmd' })

  foreach ($cand in $candidates) {
    if ($cand -and (Test-Path -LiteralPath $cand)) { return $cand }
  }
  return $null
}

if (-not (Test-Path -LiteralPath $backend)) {
  throw "找不到后端目录：$backend（请把仓库放在预期位置，或检查 scripts 目录是否被移动过）。"
}

# ---------------------------------------------------------------- ① 打包
if ($SkipPackage) {
  Write-Step '跳过打包（-SkipPackage），直接重启后端。'
} else {
  $mvnExe = Resolve-Mvn
  if (-not $mvnExe) {
    throw "找不到 mvn。请把 Maven 的 bin 目录加入 PATH，或设置 MAVEN_HOME 后重试。"
  }

  $beforeTime = if (Test-Path -LiteralPath $jar) { (Get-Item -LiteralPath $jar).LastWriteTime } else { $null }

  $mvnArgs = @()
  if ($Clean) { $mvnArgs += 'clean' }
  if (-not $Online) { $mvnArgs += '-o' }
  $mvnArgs += @('-DskipTests', '-Djacoco.skip=true', 'package')

  Write-Step "打包后端：$mvnExe $($mvnArgs -join ' ')"
  Write-Host "    工作目录：$backend"
  Write-Host "    Maven 日志：$mvnLog"

  Push-Location -LiteralPath $backend
  try {
    & $mvnExe @mvnArgs 2>&1 | Tee-Object -FilePath $mvnLog
    $exitCode = $LASTEXITCODE
  } finally {
    Pop-Location
  }

  $buildOk = ($null -eq $exitCode -or $exitCode -eq 0) -and
             (Test-Path -LiteralPath $mvnLog) -and
             [bool](Select-String -LiteralPath $mvnLog -Pattern 'BUILD SUCCESS' -Quiet)

  if (-not $buildOk) {
    Write-Host ''
    Write-Host '打包失败（Maven 未输出 BUILD SUCCESS）。日志尾部：' -ForegroundColor Red
    if (Test-Path -LiteralPath $mvnLog) {
      Get-Content -LiteralPath $mvnLog -Tail 30 | ForEach-Object { Write-Host ("  " + $_) }
    }
    Write-Host ''
    Write-Host '常见原因：' -ForegroundColor Yellow
    Write-Host '  1) 编译错误 —— 看上面日志里的 [ERROR] 行'
    Write-Host '  2) -o 离线模式下缺依赖 —— 加 -Online 重试：.\scripts\rebuild-backend.ps1 -Online'
    Write-Host '  3) jar 被占用（Unable to rename ... .jar.original）—— 见手册 §5.3'
    throw "后端打包失败，未重启后端（现在的 8081 仍是旧代码）。"
  }

  if (-not (Test-Path -LiteralPath $jar)) {
    throw "打包成功但找不到产物：$jar"
  }

  $sizeMb = [math]::Round((Get-Item -LiteralPath $jar).Length / 1MB, 1)
  if ($sizeMb -lt 20) {
    throw "产物只有 ${sizeMb}MB，像是没内嵌依赖的瘦包（一次失败的打包留下的）。请加 -Clean 重打：.\scripts\rebuild-backend.ps1 -Clean"
  }

  $afterTime = (Get-Item -LiteralPath $jar).LastWriteTime
  if ($beforeTime -and $afterTime -eq $beforeTime) {
    Write-Host "    提示：产物时间戳与打包前一致（$($afterTime.ToString('yyyy-MM-dd HH:mm:ss'))）——Maven 认为无变更、没有重打 jar。若改了代码却仍看到旧行为，加 -Clean 重打。" -ForegroundColor Yellow
  } else {
    Write-Host "    产物：$jar（${sizeMb}MB，$($afterTime.ToString('yyyy-MM-dd HH:mm:ss'))）"
  }
  Write-Host '打包完成。' -ForegroundColor Green
}

# ---------------------------------------------------------------- ② 重启开发后端
Write-Step "重启开发后端（端口 $BackendPort），前端 5173 与稳定实例 8082 不受影响。"

$startArgs = @{ RestartBackend = $true; BackendPort = $BackendPort }
if (-not $WithFrontend) { $startArgs['BackendOnly'] = $true }

& (Join-Path $PSScriptRoot 'start-local.ps1') @startArgs

# ---------------------------------------------------------------- ③ 确认结果
$up = [bool](Get-NetTCPConnection -LocalPort $BackendPort -State Listen -ErrorAction SilentlyContinue)
if ($up) {
  Write-Host ''
  Write-Host "完成：开发后端已就绪 http://127.0.0.1:$BackendPort ，前端刷新浏览器即可（前端有缓存时 Ctrl+F5）。" -ForegroundColor Green
} else {
  Write-Host ''
  Write-Host "端口 $BackendPort 仍未监听，后端可能启动失败。最近日志：" -ForegroundColor Red
  foreach ($lf in @('backend-local.err.log', 'backend-local.out.log')) {
    $lp = Join-Path $logDir $lf
    if (Test-Path -LiteralPath $lp) {
      Write-Host "  --- $lf (tail 15) ---" -ForegroundColor DarkGray
      Get-Content -LiteralPath $lp -Tail 15 | ForEach-Object { Write-Host ("  " + $_) }
    }
  }
  throw "重启后端失败，见 backend\logs\ 下的日志。"
}
