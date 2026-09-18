param(
  [string]$DbUser = '',
  [string]$DbPassword = '',
  [int]$FrontendPort = 5173,
  [int]$BackendPort = 8081,
  [string]$CryptoSecretKey = '',
  [switch]$EnableOpenFolder,
  [switch]$DisableOpenFolder
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'
$jar = Join-Path $backend 'target\product-archive-0.0.1-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $jar)) {
  throw "Backend JAR not found: $jar. Run mvn -DskipTests package in backend first."
}

function Repair-ProcessPath {
  $pathValue = [Environment]::GetEnvironmentVariable('Path', 'Process')
  if ($pathValue) {
    [Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $pathValue, 'Process')
  }
}

Repair-ProcessPath

# 未显式传参时不注入环境变量，由 backend/.env 提供数据库与加密密钥配置。
# 注意：环境变量优先级高于 .env，随机生成密钥会导致存量加密数据无法解密。
if ($DbUser) { $env:DB_USERNAME = $DbUser }
if ($DbPassword) { $env:DB_PASSWORD = $DbPassword }
if ($CryptoSecretKey) { $env:CRYPTO_SECRET_KEY = $CryptoSecretKey }
$localOpenFolderEnabled = -not $DisableOpenFolder.IsPresent
$env:LOCAL_OPEN_FOLDER_ENABLED = if ($localOpenFolderEnabled) { 'true' } else { 'false' }
New-Item -ItemType Directory -Force -Path (Join-Path $backend 'logs') | Out-Null

# 端口已被占用则跳过对应进程：脚本可安全重复执行，不会拉起双实例。
function Test-PortListening([int]$Port) {
  return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

# 2026-09-18 修复：原版用反引号续行 + 无 BOM 的 UTF-8 保存，Windows PowerShell 5.1 按 ANSI/GBK
# 读取时中文注释行尾的标点会吞掉换行符，把下一行并进注释、续行符失效（-WorkingDirectory 报
# 「无法识别的 cmdlet」）。现改为单行调用 + UTF-8 BOM 保存，两类问题同时根治。

if (Test-PortListening $BackendPort) {
  Write-Host "Backend port $BackendPort already listening - skip backend start."
} else {
  Start-Process -FilePath 'java' -ArgumentList "-Dserver.port=$BackendPort", '-jar', $jar -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $backend 'logs\backend-local.out.log') -RedirectStandardError (Join-Path $backend 'logs\backend-local.err.log') | Out-Null
}

# 绑定 0.0.0.0（监听所有网卡）：局域网内手机/笔记本可通过 http://<本机局域网IP>:$FrontendPort 访问，
# 与 vite.config.js 的 server.host 保持一致。若只想本机访问才改成 127.0.0.1。
if (Test-PortListening $FrontendPort) {
  Write-Host "Frontend port $FrontendPort already listening - skip frontend start."
} else {
  Start-Process -FilePath 'node' -ArgumentList 'node_modules\vite\bin\vite.js', '--host', '0.0.0.0', '--port', $FrontendPort -WorkingDirectory $frontend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $frontend "frontend-$FrontendPort.out.log") -RedirectStandardError (Join-Path $frontend "frontend-$FrontendPort.err.log") | Out-Null
}

Write-Host "Start requested: frontend http://localhost:$FrontendPort (局域网 http://<本机IP>:$FrontendPort), backend http://127.0.0.1:$BackendPort"
Write-Host "Local open-folder enabled: $localOpenFolderEnabled"
Write-Host "Run scripts\check-health.ps1 -FrontendPort $FrontendPort -BackendPort $BackendPort to verify services."
