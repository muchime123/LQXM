<#
  「发布」——把当前代码冻结成一份新的稳定版，供同事使用。

  做四件事：
    1. 构建前端（vite build）→ 复制到 release/stable/web
    2. 打包后端（mvn package）→ 复制到 release/stable/app.jar
    3. 记录发布信息（时间、git commit）
    4. 重启稳定实例（如需，用 -Restart）

  关键点：产物是「复制」到 release/stable 的，不是软链。
  所以在你发布之前，无论你怎么改源码、重启开发实例，同事那边都不会变。

  用法：
    .\scripts\publish-stable.ps1              # 只冻结产物，不重启
    .\scripts\publish-stable.ps1 -Restart     # 冻结后重启稳定实例（同事会看到新版）
    .\scripts\publish-stable.ps1 -SkipBackend # 只发前端（后端没改时更快）
#>
param(
  [switch]$Restart,
  [switch]$SkipBackend,
  [int]$Port = 8082
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$frontend = Join-Path $root 'frontend'
$backend = Join-Path $root 'backend'
$stableDir = Join-Path $root 'release\stable'
$webDest = Join-Path $stableDir 'web'

if (-not (Test-Path -LiteralPath $stableDir)) {
  New-Item -ItemType Directory -Force -Path $webDest | Out-Null
}

# [预检 · 2026-10-13] 放在最前面（在任何产物被动之前）：
# Windows 上运行中的 JVM 会锁住 jar，repackage 阶段改名就会失败
# （"Unable to rename '...product-archive-0.0.1-SNAPSHOT.jar' to '...jar.original'"）。
# 这里先找出「正跑着本项目 target 里那个 jar」的 java 进程（通常就是开发后端），停掉它释放占用；
# 后端打包完成后再由 start-local.ps1 自动拉起（那边改成从隔离副本启动，不会再锁 target）。
# 之所以放最前：避免「前端已冻结上线、后端却打包失败」的半发布状态。
# 若后续步骤出错，开发后端可能停着没起来，手动补一条即可：.\scripts\start-local.ps1 -BackendOnly
$devStopped = $false
if (-not $SkipBackend) {
  $targetJar = Join-Path $backend 'target\product-archive-0.0.1-SNAPSHOT.jar'
  $jarLocked = $false
  if (Test-Path -LiteralPath $targetJar) {
    try {
      $fs = [System.IO.File]::Open($targetJar, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
      $fs.Close()
    } catch { $jarLocked = $true }
  }
  if ($jarLocked) {
    Write-Host "[预检] 目标 jar 被占用（运行中的 JVM 锁住了文件）。查找占用者…" -ForegroundColor Yellow
    $killed = @()
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
      $cmd = [string]$_.CommandLine
      if ($cmd -match 'product-archive-0\.0\.1-SNAPSHOT\.jar') {
        Write-Host ("  停止 PID {0}：{1}" -f $_.ProcessId, ($cmd -replace '\s+', ' ')) -ForegroundColor Yellow
        try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop }
        catch { & taskkill /PID $_.ProcessId /F 2>&1 | Out-Null }
        $killed += $_.ProcessId
      }
    }
    if ($killed.Count -eq 0) {
      throw "目标 jar 被占用，但没找到「用本项目 jar 启动的 java 进程」。请手动排查占用者（编辑器预览、杀毒软件、其它会话）后重试。"
    }
    Start-Sleep -Seconds 2
    try {
      $fs = [System.IO.File]::Open($targetJar, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
      $fs.Close()
    } catch { throw "已停止进程 $($killed -join ', ')，但 jar 仍被占用，请手动排查后重试。" }
    $devStopped = $true
    Write-Host "  jar 占用已释放（原占用 PID：$($killed -join ', ')）。" -ForegroundColor Yellow
  }
}

Write-Host "=== [1/4] 构建前端 ===" -ForegroundColor Cyan
# 显式用本机真实 node（不用裸命令名，也不依赖任何工具自带的运行时目录）。
$nodeExe = 'node'
if (Test-Path -LiteralPath 'D:\Program Files\nodejs\node.exe') {
  $nodeExe = 'D:\Program Files\nodejs\node.exe'
}
Push-Location $frontend
try {
  & $nodeExe 'node_modules\vite\bin\vite.js' build
  if ($LASTEXITCODE -ne 0) { throw "前端构建失败（退出码 $LASTEXITCODE）" }
} finally {
  Pop-Location
}

Write-Host "=== [2/4] 冻结前端产物 → release\stable\web ===" -ForegroundColor Cyan
robocopy (Join-Path $frontend 'dist') $webDest /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "复制前端产物失败（robocopy 退出码 $LASTEXITCODE）" }
Write-Host "  文件数：$((Get-ChildItem -Recurse -File $webDest | Measure-Object).Count)"

if (-not $SkipBackend) {
  Write-Host "=== [3/4] 打包后端 ===" -ForegroundColor Cyan
  if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:\Program Files\Java\jdk-21.0.12' }
  Push-Location $backend
  try {
    & 'mvn.cmd' -o '-DskipTests' '-Djacoco.skip=true' package
    if ($LASTEXITCODE -ne 0) { throw "后端打包失败（退出码 $LASTEXITCODE）" }
  } finally {
    Pop-Location
  }
  $builtJar = Join-Path $backend 'target\product-archive-0.0.1-SNAPSHOT.jar'
  $builtInfo = Get-Item -LiteralPath $builtJar
  # 拦住「打包其实没成功、只是留下一个瘦包」：绝不把不可运行的 jar 覆盖到同事用的 app.jar 上。
  if ($builtInfo.Length -lt 20MB) {
    throw "打包产出的 jar 只有 $([math]::Round($builtInfo.Length / 1MB, 1))MB，明显不是可运行的 fat jar（打包很可能失败了）。已中止，未覆盖 release\stable\app.jar。"
  }
  $copyOk = $false
  for ($i = 0; $i -lt 3 -and -not $copyOk; $i++) {
    try { Copy-Item -Force $builtJar (Join-Path $stableDir 'app.jar') -ErrorAction Stop; $copyOk = $true }
    catch { Start-Sleep -Milliseconds 800 }
  }
  if (-not $copyOk) {
    throw "无法覆盖 release\stable\app.jar（可能被正在运行的稳定实例占用）。先执行 .\scripts\stop-stable.ps1，再重试发布。"
  }
  Write-Host "  app.jar：$([math]::Round((Get-Item (Join-Path $stableDir 'app.jar')).Length / 1MB, 1)) MB（源 jar 时间 $($builtInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))）"

  if ($devStopped) {
    Write-Host "  重新拉起开发后端（它刚被停掉以释放 jar 占用）：.\scripts\start-local.ps1 -BackendOnly" -ForegroundColor Cyan
    try { & (Join-Path $PSScriptRoot 'start-local.ps1') -BackendOnly }
    catch { Write-Warning "开发后端自动拉起失败：$($_.Exception.Message)`n  → 可手动执行：.\scripts\start-local.ps1 -BackendOnly" }
  }
} else {
  Write-Host "=== [3/4] 跳过后端打包（-SkipBackend） ===" -ForegroundColor DarkGray
}

Write-Host "=== [4/4] 记录发布信息 ===" -ForegroundColor Cyan
$commit = ''
Push-Location $root
try { $commit = (git rev-parse --short HEAD 2>$null) } catch { }
Pop-Location
$stamp = "发布人=$env:USERNAME`n时间=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`ncommit=$commit`n后端=$(if ($SkipBackend) { '未更新' } else { '已更新' })"
if ($devStopped) { $stamp += "`n备注=发布前释放过 target jar 占用，并已重启开发后端" }
$stamp | Out-File (Join-Path $stableDir 'RELEASE.txt') -Encoding utf8
Write-Host ($stamp -replace "`n", ' | ')

if ($Restart) {
  Write-Host "=== 重启稳定实例 ===" -ForegroundColor Cyan
  & (Join-Path $PSScriptRoot 'stop-stable.ps1') -Port $Port
  & (Join-Path $PSScriptRoot 'start-stable.ps1') -Port $Port
} else {
  Write-Host ""
  Write-Host "产物已冻结。同事当前还在用旧版，直到你执行：" -ForegroundColor Yellow
  Write-Host "  .\scripts\stop-stable.ps1 ; .\scripts\start-stable.ps1" -ForegroundColor Yellow
  Write-Host "或直接：.\scripts\publish-stable.ps1 -Restart" -ForegroundColor Yellow
}
