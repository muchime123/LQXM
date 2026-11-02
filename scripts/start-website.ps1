<#
  启动「官网前端」dev 服务器（corporate-website，独立 Vue 工程）。

  与开发实例（frontend 5173 / backend 8081）完全隔离：
    - 端口 5180，监听 0.0.0.0（局域网可访问）
    - 内置 /api 代理到 127.0.0.1:8081（开发后端），因此需先起 8081

  用法：
    .\scripts\start-website.ps1                 # 后台启动官网前端
    .\scripts\start-website.ps1 -Port 5181      # 换端口（需同步改 vite.config.js 的 port）
    .\scripts\start-website.ps1 -Foreground     # 前台启动（便于看日志）
#>
param(
  [int]$Port = 5180,
  [switch]$Foreground
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$website = Join-Path $root 'corporate-website'

if (-not (Test-Path -LiteralPath $website)) {
  throw "官网前端目录不存在：$website"
}

$nodeExe = 'node'
if (Test-Path -LiteralPath 'D:\Program Files\nodejs\node.exe') {
  $nodeExe = 'D:\Program Files\nodejs\node.exe'
}

function Repair-ProcessPath {
  $pathValue = [Environment]::GetEnvironmentVariable('Path', 'Process')
  if ($pathValue) {
    [Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $pathValue, 'Process')
  }
}
Repair-ProcessPath

function Test-PortListening([int]$p) {
  return [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
}

if (Test-PortListening $Port) {
  Write-Host "官网前端端口 $Port 已在监听，跳过启动（避免拉起重复实例）。"
  Write-Host "如需重启：先结束占用 $Port 的进程后重试。"
  return
}

$nodeModules = Join-Path $website 'node_modules'
if (-not (Test-Path -LiteralPath (Join-Path $nodeModules 'vite\bin\vite.js'))) {
  Write-Host "官网前端依赖缺失，先执行 npm install ..."
  $npmExe = 'npm'
  if (Test-Path -LiteralPath 'D:\Program Files\nodejs\npm.cmd') {
    $npmExe = 'D:\Program Files\nodejs\npm.cmd'
  }
  Push-Location $website
  try {
    & $npmExe install 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "npm install 失败（exit $LASTEXITCODE）。" }
  } finally {
    Pop-Location
  }
}

$webLogDir = Join-Path $website 'logs'
New-Item -ItemType Directory -Force -Path $webLogDir | Out-Null
$outLog = Join-Path $webLogDir ("website-" + $Port + ".out.log")
$errLog = Join-Path $webLogDir ("website-" + $Port + ".err.log")

# 工作目录已是 corporate-website，vite 会自动读取 vite.config.js，无需再指定 -C/--config。
if ($Foreground) {
  Write-Host "前台启动官网前端：http://localhost:$Port （Ctrl+C 停止）"
  & $nodeExe node_modules\vite\bin\vite.js --host 0.0.0.0 --port $Port
} else {
  Start-Process -FilePath $nodeExe -ArgumentList 'node_modules\vite\bin\vite.js', '--host', '0.0.0.0', '--port', $Port -WorkingDirectory $website -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog | Out-Null

  $up = $false
  $waited = 0
  for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    $waited = $i + 1
    if (Test-PortListening $Port) { $up = $true; break }
  }
  if ($up) {
    Write-Host "官网前端已就绪：http://127.0.0.1:$Port（约 $waited 秒，局域网 http://<本机IP>:$Port）"
    Write-Host "日志：$outLog"
  } else {
    Write-Host "官网前端在 $waited 秒内未监听 $Port，疑似启动失败。" -ForegroundColor Red
    if (Test-Path -LiteralPath $errLog) {
      Write-Host "  --- $errLog (tail 15) ---" -ForegroundColor DarkGray
      Get-Content -LiteralPath $errLog -Tail 15 | ForEach-Object { Write-Host ("  " + $_) }
    }
    throw "官网前端启动失败（端口 $Port 未监听）。"
  }
}
