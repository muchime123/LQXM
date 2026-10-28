param(
  [string]$DbUser = '',
  [string]$DbPassword = '',
  [int]$FrontendPort = 5173,
  [int]$BackendPort = 8081,
  [string]$CryptoSecretKey = '',
  [switch]$EnableOpenFolder,
  [switch]$DisableOpenFolder,
  [switch]$RestartBackend,
  [switch]$BackendOnly
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'
$jar = Join-Path $backend 'target\product-archive-0.0.1-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $jar)) {
  throw "Backend JAR not found: $jar. Run mvn -DskipTests package in backend first."
}

# 2026-10-13：后端**从副本启动**，不再直接跑 backend\target 里的 jar。
# 原因：Windows 上运行中的 JVM 会锁住 jar 文件，导致下一次 `mvn package` 的 repackage 阶段
# 无法把 jar 改名为 .jar.original，报 "Unable to rename '...product-archive-0.0.1-SNAPSHOT.jar'
# to '...product-archive-0.0.1-SNAPSHOT.jar.original'"（publish-stable 随之失败）。
# 副本放在与 target 隔离的目录里，打包与「正在跑的后端」从此互不影响。
$runDir = Join-Path $root '_lqxm_run\dev-local'
$runJar = Join-Path $runDir 'app.jar'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

# 粗判「是不是一个能跑的 fat jar」：瘦包（依赖未内嵌）通常只有几 MB。
# 用于拦住「上一次打包失败留在 target 里的瘦包」——那种 jar 直接用 java -jar 起不来。
function Test-FatJar([string]$Path) {
  return ((Get-Item -LiteralPath $Path).Length -gt 20MB)
}

# 可执行文件解析（2026-10-13）：不要依赖裸命令名。
# java：本机 Machine PATH 最前面是 Oracle 的 javapath（C:\Program Files\Common Files\Oracle\Java\javapath），
# 该目录只有 launcher、没有 jvm.dll，执行即崩（exit 0xC0000409、无任何输出），会让「裸 java 起后端」静默失败。
# ⇒ 显式优先 JAVA_HOME，其次本机已知 JDK 路径，最后才回落裸 java。
$javaExe = 'java'
if ($env:JAVA_HOME) {
  $cand = Join-Path $env:JAVA_HOME 'bin\java.exe'
  if (Test-Path -LiteralPath $cand) { $javaExe = $cand }
}
if ($javaExe -eq 'java' -and (Test-Path -LiteralPath 'D:\Program Files\Java\jdk-21.0.12\bin\java.exe')) {
  $javaExe = 'D:\Program Files\Java\jdk-21.0.12\bin\java.exe'
}

# node：同理，显式优先本机真实 node，再回落裸 node。
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

# 2026-10-13：配合 -RestartBackend，只终止「占用 BackendPort 且命令行指向本项目 jar」的 java 进程。
# 命中非 java 或命令行不匹配的占用者时拒绝终止（只告警），避免误杀机器上其它服务。
function Stop-BackendOnPort([int]$Port) {
  $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  if (-not $conns) { return $false }
  $stopped = $false
  foreach ($procId in ($conns | Select-Object -ExpandProperty OwningProcess -Unique)) {
    $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $p) { continue }
    $cmd = ''
    $wmi = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
    if ($wmi) { $cmd = [string]$wmi.CommandLine }
    $isOurJar = ($cmd -match 'product-archive' -or $cmd -match 'lqxm_run' -or $cmd -match [regex]::Escape($root))
    if ($p.ProcessName -ne 'java' -or -not $isOurJar) {
      Write-Warning "端口 $Port 被 $($p.ProcessName) (PID $procId) 占用，但命令行不指向本项目 jar，已跳过以免误杀。"
      continue
    }
    Write-Host "停止后端：PID $procId ($($p.ProcessName)) on port $Port"
    try { Stop-Process -Id $procId -Force -ErrorAction Stop }
    catch { & taskkill /PID $procId /F 2>&1 | Out-Null }
    $stopped = $true
  }
  if ($stopped) {
    for ($i = 0; $i -lt 24; $i++) {
      Start-Sleep -Milliseconds 500
      if (-not (Test-PortListening $Port)) { break }
    }
  }
  return $stopped
}

# 2026-09-18 修复：原版用反引号续行 + 无 BOM 的 UTF-8 保存，Windows PowerShell 5.1 按 ANSI/GBK
# 读取时中文注释行尾的标点会吞掉换行符，把下一行并进注释、续行符失效（-WorkingDirectory 报
# 「无法识别的 cmdlet」）。现改为单行调用 + UTF-8 BOM 保存，两类问题同时根治。

# -RestartBackend：先把在跑的后端停掉，让下面的启动逻辑重新拉起新 jar（前端与稳定实例都不受影响）。
if ($RestartBackend -and (Test-PortListening $BackendPort)) {
  $jarInfo = Get-Item -LiteralPath $jar
  Write-Host "重启后端：当前 jar 时间 $($jarInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))（改了 Java 代码或 application.yml 需先 mvn package，否则重启也还是旧代码）"
  Stop-BackendOnPort $BackendPort | Out-Null
  if (Test-PortListening $BackendPort) { throw "端口 $BackendPort 仍被占用，未能释放，已中止。" }
}

if (Test-PortListening $BackendPort) {
  Write-Host "Backend port $BackendPort already listening - skip backend start."
} else {
  if (-not (Test-FatJar $jar)) {
    $mb = [math]::Round((Get-Item -LiteralPath $jar).Length / 1MB, 1)
    throw "源 jar 只有 ${mb}MB，像是没内嵌依赖的瘦包（多由一次失败的打包留下）。请先在 backend 下执行：mvn -o -DskipTests -Djacoco.skip=true package"
  }
  # 拷贝到隔离目录再启动，避免锁住 target 里的 jar 让下次打包失败（见本文件顶部说明）。
  $copied = $false
  for ($i = 0; $i -lt 3 -and -not $copied; $i++) {
    try { Copy-Item -LiteralPath $jar -Destination $runJar -Force -ErrorAction Stop; $copied = $true }
    catch { Start-Sleep -Milliseconds 800 }
  }
  if (-not $copied) {
    throw "无法把 jar 复制到 $runJar（可能仍被上一个后端实例占用）。先执行 .\scripts\start-local.ps1 -RestartBackend，或手动结束占用 $BackendPort 的 java 进程后重试。"
  }
  $srcInfo = Get-Item -LiteralPath $jar
  $runInfo = Get-Item -LiteralPath $runJar
  Write-Host "后端启动副本：$runJar（$([math]::Round($runInfo.Length / 1MB, 1))MB，源 jar 时间 $($srcInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))）"
  Start-Process -FilePath $javaExe -ArgumentList "-Dserver.port=$BackendPort", "-Dapp.security.allow-employee-header=true", '-jar', $runJar -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $backend 'logs\backend-local.out.log') -RedirectStandardError (Join-Path $backend 'logs\backend-local.err.log') | Out-Null
  # 启动后确认真的在监听：避免「脚本说启动了、其实进程已崩」（java 解析错会导致这种静默失败）。
  $up = $false
  $waited = 0
  for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    $waited = $i + 1
    if (Test-PortListening $BackendPort) { $up = $true; break }
  }
  if ($up) {
    Write-Host "后端已就绪：http://127.0.0.1:$BackendPort（约 $waited 秒）"
  } else {
    Write-Host "后端在 $waited 秒内未监听 $BackendPort，疑似启动失败。" -ForegroundColor Red
    # 关键错误先摘出来：真正的根因（Flyway / Hibernate 校验等）通常埋在堆栈中间，
    # 只看 tail 12 行往往全是 at ... 帧，看不到原因。
    foreach ($lf in @('logs\backend-local.err.log', 'logs\backend-local.out.log')) {
      $lp = Join-Path $backend $lf
      if (-not (Test-Path -LiteralPath $lp)) { continue }
      $hits = @(Select-String -LiteralPath $lp -Encoding UTF8 -Pattern 'APPLICATION FAILED TO START|Application run failed|checksum mismatch|Found more than one migration|Schema-validation|Caused by:|Port .* was already in use|Web server failed to start' |
          Select-Object -First 8)
      if ($hits.Count -gt 0) {
        Write-Host "  --- $lf 关键错误 ---" -ForegroundColor Yellow
        foreach ($h in $hits) { Write-Host ("  L" + $h.LineNumber + ": " + $h.Line.Trim()) -ForegroundColor Yellow }
      }
    }
    foreach ($lf in @('logs\backend-local.err.log', 'logs\backend-local.out.log')) {
      $lp = Join-Path $backend $lf
      if (Test-Path -LiteralPath $lp) {
        Write-Host "  --- $lf (tail 12) ---" -ForegroundColor DarkGray
        Get-Content -LiteralPath $lp -Tail 12 | ForEach-Object { Write-Host ("  " + $_) }
      }
    }
    throw "后端启动失败（端口 $BackendPort 未监听）。java 用的是：$javaExe"
  }
}

# 绑定 0.0.0.0（监听所有网卡）：局域网内手机/笔记本可通过 http://<本机局域网IP>:$FrontendPort 访问，
# 与 vite.config.js 的 server.host 保持一致。若只想本机访问才改成 127.0.0.1。
if ($BackendOnly) {
  Write-Host "Skip frontend start (-BackendOnly)."
} elseif (Test-PortListening $FrontendPort) {
  Write-Host "Frontend port $FrontendPort already listening - skip frontend start."
} else {
  Start-Process -FilePath $nodeExe -ArgumentList 'node_modules\vite\bin\vite.js', '--host', '0.0.0.0', '--port', $FrontendPort -WorkingDirectory $frontend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $frontend "frontend-$FrontendPort.out.log") -RedirectStandardError (Join-Path $frontend "frontend-$FrontendPort.err.log") | Out-Null
}

Write-Host "Start requested: frontend http://localhost:$FrontendPort (局域网 http://<本机IP>:$FrontendPort), backend http://127.0.0.1:$BackendPort"
Write-Host "Local open-folder enabled: $localOpenFolderEnabled"
Write-Host "Run scripts\check-health.ps1 -FrontendPort $FrontendPort -BackendPort $BackendPort to verify services."
