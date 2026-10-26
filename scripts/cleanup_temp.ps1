# LQXM 临时文件清理脚本
# 用途：删除 AI Agent 工作期间产生的探针/诊断/运行副本/备份等一次性文件
# 安全范围：仅删除带明确"临时"标记的项目（_前缀、.bak-、__pycache__、缓存目录）
# 保留不动：backend/target、frontend/node_modules、corporate-website/node_modules、
#           _lqxm_run/dev-local/app.jar、storage/、release/、data/、docs/、tools/
# 使用：powershell -NoProfile -ExecutionPolicy Bypass -File scripts/cleanup_temp.ps1
# 或（一行命令，在项目根目录）：
#   powershell -NoProfile -ExecutionPolicy Bypass -Command ". scripts/cleanup_temp.ps1"

param(
    [switch]$DryRun   # 只扫描不删除，用于预览
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host "=== LQXM Temp Cleanup ===" -ForegroundColor Cyan
Write-Host "Root: $root"
Write-Host "Mode: $(if ($DryRun) { 'DRY RUN (no delete)' } else { 'DELETE' })"
Write-Host ""

$totalFreed = 0
$totalItems = 0

function Remove-Candidate {
    param(
        [string]$Path,
        [string]$Label = ''
    )
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $isDir = (Get-Item -LiteralPath $Path).PSIsContainer
    $size = if ($isDir) {
        (Get-ChildItem -LiteralPath $Path -Recurse -File -ErrorAction SilentlyContinue |
         Measure-Object -Property Length -Sum).Sum
    } else { (Get-Item -LiteralPath $Path).Length }
    if ($null -eq $size) { $size = 0 }
    $script:totalFreed += $size
    $script:totalItems++

    if ($DryRun) {
        $tag = if ($isDir) { '[DIR]' } else { '[FILE]' }
        Write-Host ("  WOULD DELETE {0} {1,-50} {2,10} MB" -f $tag, ($Label ? $Label : $Path.Replace("$root\", '')), [math]::Round($size/1MB,2))
    } else {
        Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction SilentlyContinue
        $tag = if ($isDir) { '[OK] DIR ' } else { '[OK] FILE' }
        Write-Host ("  {0} {1,-50} {2,10} MB" -f $tag, ($Label ? $Label : $Path.Replace("$root\", '')), [math]::Round($size/1MB,2))
    }
}

# ============================================================
# 1. 一级大目录：_ 开头的一次性运行/诊断副本
# ============================================================
$oneShotDirs = @(
    '_diag', '_probe_out', '_probe_out2', '_probe_out4', '_probe_ev',
    '_probe_ev_src', '_probe_xc', '_probe_xc2', '_probe_xc3',
    '_pt_run', '_pt_run2', '_lqxm_run_rt', '_lqxm_run2',
    '_ip_preview', '_rt_width_check', '_jdriver',
    '_archive_export', '_jcheck', '_probe_h3',
    'val_perler_work', 'BOOT-INF'
)
Write-Host "-- One-shot temp dirs --" -ForegroundColor DarkCyan
foreach ($d in $oneShotDirs) {
    Remove-Candidate -Path (Join-Path $root $d)
}

# ============================================================
# 2. 构建前/后快照对比目录（CI 审计残留）
# ============================================================
$buildCompareDirs = @(
    'frontend/_build_c8b_after', 'frontend/_build_c8b_before',
    'frontend/_build_c8a_before', 'frontend/_build_c8a_after',
    'frontend/_build_c8a_before2',
    'frontend/_build_check', 'frontend/_build_verify',
    'frontend/_audit_shots', 'frontend/dist/_verify_p1', 'frontend/dist/_verify_p1b',
    'frontend/dist-p2a', 'tools/__pycache__'
)
Write-Host "-- Build snapshot / audit dirs --" -ForegroundColor DarkCyan
foreach ($d in $buildCompareDirs) {
    Remove-Candidate -Path (Join-Path $root $d)
}

# ============================================================
# 3. _lqxm_run 内部的次级运行副本（保留 dev-local/app.jar）
# ============================================================
Write-Host "-- _lqxm_run secondary copies --" -ForegroundColor DarkCyan
$lqxmRunChildren = @('_lqxm_run/perf-8085', '_lqxm_run/stable-migration-patch')
foreach ($d in $lqxmRunChildren) {
    Remove-Candidate -Path (Join-Path $root $d)
}
# dev-local 里的备份 jar + 时间戳日志（保留 app.jar + app_OLD.jar + 8081.out.log + 8081.err.log）
$dl = Join-Path $root '_lqxm_run/dev-local'
if (Test-Path $dl) {
    Get-ChildItem $dl -File -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -match '\.bak-|app_OLD|8081\.(out|err)\.\d'
    } | ForEach-Object {
        Remove-Candidate -Path $_.FullName
    }
}

# ============================================================
# 4. .workbuddy / .codex — 旧 AI Agent 工作区（保留 .trae）
# ============================================================
Write-Host "-- Legacy AI workspace --" -ForegroundColor DarkCyan
foreach ($d in @('.workbuddy', '.codex')) {
    Remove-Candidate -Path (Join-Path $root $d)
}

# ============================================================
# 5. 根目录 _ 开头的一次性探针文件（java/py/txt/png/html/log/bat/sql/json）
# ============================================================
Write-Host "-- Root-level _* probe files --" -ForegroundColor DarkCyan
$probeExts = @('.java', '.py', '.txt', '.png', '.html', '.log', '.bat', '.sql', '.json', '.class', '.ts', '.cjs', '.mjs')
Get-ChildItem $root -File -ErrorAction SilentlyContinue | Where-Object {
    $_.Name.StartsWith('_') -and $probeExts -contains $_.Extension
} | ForEach-Object {
    Remove-Candidate -Path $_.FullName
}

# ============================================================
# 6. frontend 下 _ 开头的临时文件
# ============================================================
Write-Host "-- frontend _* temp files --" -ForegroundColor DarkCyan
Get-ChildItem (Join-Path $root 'frontend') -File -ErrorAction SilentlyContinue | Where-Object {
    $_.Name.StartsWith('_')
} | ForEach-Object {
    Remove-Candidate -Path $_.FullName
}

# ============================================================
# 7. 根目录 db_backup_* 数据库备份快照
# ============================================================
Write-Host "-- Root-level DB backup snapshots --" -ForegroundColor DarkCyan
Get-ChildItem $root -File -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -match '^db_backup.*\.(tsv|json|sql)$'
} | ForEach-Object {
    Remove-Candidate -Path $_.FullName
}

# ============================================================
# 8. backend/target/surefire-reports — 测试报告（可选）
#    默认保留：Maven 会重新生成；如果想清也可以，打开下面注释
# ============================================================
# Remove-Candidate -Path (Join-Path $root 'backend/target/surefire-reports')

Write-Host ""
Write-Host "=== Done ===" -ForegroundColor Green
Write-Host ("  Items: {0}" -f $totalItems)
Write-Host ("  Freed: {0:N2} MB" -f ($totalFreed / 1MB))
if ($DryRun) { Write-Host "  (dry run — nothing deleted)" -ForegroundColor Yellow }
