<#
.SYNOPSIS
    资金日记账 P1 —— 重启 8081 并跑接口层验收（在 LQ 自己的终端执行）

.DESCRIPTION
    做四件事：
      1) 停掉当前占用 8081 的 java 进程
      2) 备份上一个 app.jar（带时间戳，方便回滚）
      3) 用 backend/target 的新 jar 覆盖并启动 8081
      4) 轮询等就绪后跑 tools/verify_cashflow_p1.py，打印 PASS/FAIL

.PARAMETER RepoRoot
    仓库根目录，默认 C:\Users\20676\source\LQXM

.PARAMETER EmployeeId
    验收用的 X-Employee-Id，默认 23（管理员）

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\restart-8081-and-verify.ps1

.EXAMPLE
    # 只重启、不验收
    powershell -ExecutionPolicy Bypass -File scripts\restart-8081-and-verify.ps1 -SkipVerify

.NOTES
    必须在本机有权限停止 java 进程的终端里跑（通常自己的窗口即可）。
    脚本不修改数据库，只做进程与文件操作。
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = 'C:\Users\20676\source\LQXM',
    [int]$Port = 8081,
    [int]$EmployeeId = 23,
    [switch]$SkipVerify
)

$ErrorActionPreference = 'Stop'

$runDir  = Join-Path $RepoRoot '_lqxm_run\dev-local'
$appJar  = Join-Path $runDir  'app.jar'
$newJar  = Join-Path $RepoRoot 'backend\target\product-archive-0.0.1-SNAPSHOT.jar'
$outLog  = Join-Path $runDir  ('8081.out.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$errLog  = Join-Path $runDir  ('8081.err.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$javaExe = 'D:\Program Files\Java\jdk-21.0.12\bin\java.exe'

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn2($msg) { Write-Host "    $msg" -ForegroundColor Yellow }

# ---------- 0. 前置检查 ----------
Write-Step '前置检查'
if (-not (Test-Path $newJar)) { throw "找不到新 jar：$newJar`n请先在 backend 下 mvnw -o -DskipTests package" }
if (-not (Test-Path $javaExe)) {
    $javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
    if (-not $javaExe) { throw '找不到 java.exe' }
}
if (-not (Test-Path $runDir)) { New-Item -ItemType Directory -Path $runDir -Force | Out-Null }

$jarInfo = Get-Item $newJar
Write-Ok ("新 jar: {0:N0} 字节  构建于 {1:yyyy-MM-dd HH:mm:ss}" -f $jarInfo.Length, $jarInfo.LastWriteTime)

# ---------- 1. 停 8081 ----------
Write-Step "停止占用 $Port 的进程"
$listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listeners) {
    $pids = $listeners | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($p in $pids) {
        $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Ok "停止 PID=$p ($($proc.ProcessName))"
            Stop-Process -Id $p -Force
        }
    }
    # 等端口真正释放，最多 20 秒
    $waited = 0
    while ((Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) -and $waited -lt 20) {
        Start-Sleep -Seconds 1; $waited++
    }
    if ($waited -ge 20) { Write-Warn2 "端口 $Port 20 秒内未释放，后续启动可能失败" }
    else { Write-Ok "端口 $Port 已释放" }
} else {
    Write-Ok "端口 $Port 本来就空闲"
}

# ---------- 2. 备份旧 jar ----------
Write-Step '备份当前 app.jar'
if (Test-Path $appJar) {
    $bak = Join-Path $runDir ('app.jar.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    Copy-Item $appJar $bak -Force
    Write-Ok "已备份到 $bak"
} else {
    Write-Warn2 '当前没有 app.jar，跳过备份'
}

# ---------- 3. 换 jar 并启动 ----------
Write-Step '部署新 jar 并启动'
Copy-Item $newJar $appJar -Force
Write-Ok "已覆盖 $appJar"

# allow-employee-header=true 仅开发实例开启，生产必须关闭
$argList = @(
    ('-Dserver.port=' + $Port),
    '-Dapp.security.allow-employee-header=true',
    '-jar', $appJar
)

#
# ⚠️ 这里刻意不用 Start-Process（2026-10-23 实测踩坑）：
# 本机环境变量里 http_proxy 与 HTTP_PROXY 同时存在（Windows 环境变量不区分大小写），
# Start-Process 重建环境块时会抛
#   「已添加项。字典中的关键字:"http_proxy" 所添加的关键字:"HTTP_PROXY"」
# 并直接退出（退出码 1），服务根本没起来。这是本机已知问题，start-stable.ps1 同源。
# 改用 .NET ProcessStartInfo：不重建环境块，绕开该冲突。
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $javaExe
$psi.WorkingDirectory = $RepoRoot
$psi.Arguments = ($argList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
$psi.UseShellExecute = $false          # 重定向输出必须是 false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$proc.EnableRaisingEvents = $true   # 不设则 HasExited 行为不可靠
$null = $proc.Start()

# 异步读走输出，写进时间戳日志；不读会因管道缓冲写满把 JVM 卡住
$outWriter = New-Object System.IO.StreamWriter($outLog, $false, [System.Text.Encoding]::UTF8)
$errWriter = New-Object System.IO.StreamWriter($errLog, $false, [System.Text.Encoding]::UTF8)
$outWriter.AutoFlush = $true
$errWriter.AutoFlush = $true
Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
    if ($EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $outWriter | Out-Null
Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action {
    if ($EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $errWriter | Out-Null
$proc.BeginOutputReadLine()
$proc.BeginErrorReadLine()

Write-Ok "已启动 PID=$($proc.Id)，日志 $outLog"

# ---------- 4. 等就绪 ----------
Write-Step '等待服务就绪（最多 120 秒）'
$readyUrl = "http://localhost:$Port/api/cashflow-journal/accounts?start=2026-10-01&end=2026-10-22"
$deadline = (Get-Date).AddSeconds(120)
$ready = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    try {
        # 必须绕过代理，否则本机请求会走 http_proxy 拿到假 502
        $r = Invoke-WebRequest -Uri $readyUrl -Headers @{ 'X-Employee-Id' = "$EmployeeId" } `
             -Proxy $null -TimeoutSec 8 -UseBasicParsing -ErrorAction Stop
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch {
        if (-not $proc.HasExited) { continue }
        break
    }
}

if ($proc.HasExited) {
    Write-Host ''
    Write-Host "!! 进程已退出（ExitCode=$($proc.ExitCode)），错误日志尾部：" -ForegroundColor Red
    if (Test-Path $errLog) { Get-Content $errLog -Tail 40 }
    throw '服务启动失败'
}
if (-not $ready) { throw '120 秒内未就绪，请查看 ' + $outLog }

Write-Ok '服务已就绪，接口 200'

# ---------- 5. 验收 ----------
if ($SkipVerify) {
    Write-Step '已按参数跳过验收'
    return
}

Write-Step '运行接口层验收'
$py = Join-Path $RepoRoot 'tools\verify_cashflow_p1.py'
$candidates = @(
    'C:\Users\20676\.workbuddy\binaries\python\versions\3.14.3\python.exe',
    'python'
)
$pyExe = $null
foreach ($c in $candidates) {
    if ($c -eq 'python') {
        $g = Get-Command python -ErrorAction SilentlyContinue
        if ($g) { $pyExe = $g.Source; break }
    } elseif (Test-Path $c) { $pyExe = $c; break }
}
if (-not $pyExe) { Write-Warn2 '找不到 python，跳过验收'; return }

& $pyExe $py --base "http://localhost:$Port" --employee $EmployeeId
$rc = $LASTEXITCODE
Write-Host ''
if ($rc -eq 0) {
    Write-Host "验收全部通过（退出码 0）" -ForegroundColor Green
} else {
    Write-Host "验收存在 FAIL（退出码 $rc），见上面失败项" -ForegroundColor Red
}
exit $rc
