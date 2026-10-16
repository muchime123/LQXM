<#
  启动「稳定实例」——给同事用的那一份。

  与你的开发实例（前端 5173 / 后端 8081）完全隔离：
    - 后端：冻结 jar（release/stable/app.jar），端口 8082
    - 前端：冻结构建产物（release/stable/web/），由后端同源托管
    - 数据库：与开发实例共用同一个 MySQL（同事看到的是实时业务数据）

  同事访问：http://<本机局域网IP>:8082

  稳定实例默认「不执行数据库迁移、不跑定时任务」：
    - 迁移与定时任务由你的开发实例负责，避免两个进程同时归档/迁移打架
    - 如需让稳定实例接管定时任务，加 -EnableSchedulers

  用法：
    .\scripts\start-stable.ps1                 # 后台启动
    .\scripts\start-stable.ps1 -Foreground     # 前台启动（便于看日志）
    .\scripts\start-stable.ps1 -Port 8083      # 换端口
#>
param(
  [int]$Port = 8082,
  [switch]$EnableSchedulers,
  [switch]$Foreground
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $root 'backend'
$jar = Join-Path $root 'release\stable\app.jar'
$webDir = Join-Path $root 'release\stable\web'
$logDir = Join-Path $root 'release\stable\logs'

if (-not (Test-Path -LiteralPath $jar)) {
  throw "稳定实例 jar 不存在：$jar`n请先运行 .\scripts\publish-stable.ps1 生成冻结产物。"
}
if (-not (Test-Path -LiteralPath (Join-Path $webDir 'index.html'))) {
  throw "稳定前端产物不存在：$webDir`n请先运行 .\scripts\publish-stable.ps1 生成冻结产物。"
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Test-PortListening([int]$p) {
  return [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
}

if (Test-PortListening $Port) {
  Write-Host "端口 $Port 已在监听，跳过启动（避免拉起重复实例）。"
  Write-Host "如需重启：先运行 .\scripts\stop-stable.ps1 -Port $Port"
  return
}

# 静态资源用 file: 协议定位到冻结目录。Windows 绝对路径必须写成 file:///C:/... （三个斜杠）。
# 同时保留 Spring Boot 的默认 classpath 位置，避免 springdoc 等自带资源失效。
$webUri = 'file:///' + ($webDir -replace '\\', '/') + '/'
$staticLocations = "$webUri,classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/"

$javaArgs = @(
  '-jar', $jar,
  "--server.port=$Port",
  "--spring.web.resources.static-locations=$staticLocations"
)

# 迁移交给开发实例，稳定实例只读 schema（避免重启时 Flyway 报 "applied migration not resolved locally"）
$javaArgs += '--spring.flyway.enabled=false'
$javaArgs += '--erp.datasource.migrate-on-startup=false'

# 安全：稳定实例面向内网，禁止用 X-Employee-Id 头冒充身份；显式关闭匿名兼容管理员。
$javaArgs += '--app.security.allow-employee-header=false'
$javaArgs += '--app.security.compatibility-admin=false'

# 定时任务默认关闭：两个实例共用数据库，双跑归档/清理会互相竞争
if (-not $EnableSchedulers) {
  $javaArgs += '--app.archive.soft-delete.cron=-'
  $javaArgs += '--app.schedule.cleanup.cron=-'
  $javaArgs += '--app.schedule.session-cleanup.cron=-'
  $javaArgs += '--app.preview.cache.cleanup-cron=-'
  $javaArgs += '--app.schedule.mold-integrity.cron=-'
}

# JAVA_HOME 未设置时回落到本机已知 JDK21（与 publish-stable.ps1 一致），再兜底 PATH 上的 java
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = 'D:\Program Files\Java\jdk-21.0.12' }
$javaExe = Join-Path $javaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) { $javaExe = 'java' }

# 工作目录固定为 backend/：这样能读到 backend/.env（数据库口令、加密密钥），
# 且 FILES_ROOT=../storage/lq-files 相对路径正确解析到仓库根，与开发实例共享同一份文件存储。
if ($Foreground) {
  Write-Host "前台启动稳定实例：http://<本机IP>:$Port （Ctrl+C 停止）"
  & $javaExe @javaArgs
} else {
  $outLog = Join-Path $logDir 'stable.out.log'
  $errLog = Join-Path $logDir 'stable.err.log'
  try {
    Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $backend -WindowStyle Hidden `
      -RedirectStandardOutput $outLog -RedirectStandardError $errLog -ErrorAction Stop | Out-Null
  } catch {
    Write-Host "启动失败：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "若错误形如「已添加项。字典中的关键字…」，说明当前终端的环境变量存在大小写重复" -ForegroundColor Yellow
    Write-Host "（IDE 集成终端注入的小写 http_proxy/PATH 与 Windows 的大写 HTTP_PROXY/Path 撞车，属 PowerShell 5.1 的 Start-Process 缺陷）。" -ForegroundColor Yellow
    Write-Host "两个办法：① 在普通 PowerShell 窗口里运行本脚本；② 或改用前台模式 .\scripts\start-stable.ps1 -Foreground" -ForegroundColor Yellow
    throw
  }
  Write-Host "已请求启动稳定实例：http://<本机局域网IP>:$Port"
  Write-Host "日志：$outLog"
  Write-Host "探活：.\scripts\check-health.ps1 -Stable -StablePort $Port"
}
