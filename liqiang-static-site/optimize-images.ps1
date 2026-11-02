# 利强静态站 · 图片体积优化脚本
# 将 assets/images 中大于 700KB 的图片缩放至最长边 1400px 并转为 JPEG（质量 85），
# 有透明通道的先合成到白色背景。输出同名 .jpg 后删除原大图，保持 media 索引简洁。
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$dir = 'C:\Users\20676\source\LQXM\liqiang-static-site\assets\images'
$maxDim = 1400
$quality = 85

$encoderParams = New-Object System.Drawing.Imaging.EncoderParameters(1)
$encoderParams.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter(
    [System.Drawing.Imaging.Encoder]::Quality, [long]$quality)
$jpegCodec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
    Where-Object { $_.MimeType -eq 'image/jpeg' }

$targets = Get-ChildItem -LiteralPath $dir -File | Where-Object { $_.Length -gt 700KB }
$processed = 0
foreach ($f in $targets) {
    $nameNoExt = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
    $outPath = Join-Path $dir ($nameNoExt + '.jpg')
    try {
        # 从文件读取字节（用读写共享），再从内存流加载，规避索引/扫描进程的读锁
        $bytes = $null
        for ($try = 1; $try -le 8; $try++) {
            try {
                $fs = [System.IO.File]::Open($f.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                $ms = New-Object System.IO.MemoryStream
                $fs.CopyTo($ms)
                $fs.Dispose()
                $bytes = $ms.ToArray()
                $ms.Dispose()
                break
            } catch {
                if ($try -lt 8) { Start-Sleep -Milliseconds 1200 } else { throw }
            }
        }
        $srcStream = New-Object System.IO.MemoryStream(,$bytes)
        $src = [System.Drawing.Image]::FromStream($srcStream)
        $srcW = $src.Width; $srcH = $src.Height
        # 有透明通道时先合成到白底
        $draw = $src
        $srcPx = $src.PixelFormat
        if ([System.Drawing.Image]::IsAlphaPixelFormat($srcPx)) {
            $white = New-Object System.Drawing.Bitmap($srcW, $srcH, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
            $g = [System.Drawing.Graphics]::FromImage($white)
            $g.Clear([System.Drawing.Color]::White)
            $g.DrawImage($src, 0, 0, $srcW, $srcH)
            $g.Dispose()
            $draw = $white
            $src.Dispose()
        }
        # 等比缩放
        $scale = [Math]::Min(1.0, $maxDim / [Math]::Max($srcW, $srcH))
        $nw = [int][Math]::Round($srcW * $scale)
        $nh = [int][Math]::Round($srcH * $scale)
        $resized = New-Object System.Drawing.Bitmap($nw, $nh, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $rg = [System.Drawing.Graphics]::FromImage($resized)
        $rg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $rg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $rg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $rg.DrawImage($draw, 0, 0, $nw, $nh)
        $rg.Dispose()
        $resized.Save($outPath, $jpegCodec, $encoderParams)
        $resized.Dispose()
        if ($draw -ne $src) { $draw.Dispose() }
        if ($null -ne $src) { $src.Dispose() }
        if ($null -ne $srcStream) { $srcStream.Dispose() }
        try { Remove-Item -LiteralPath $f.FullName -ErrorAction Stop } catch { }
        $outSize = (Get-Item -LiteralPath $outPath).Length
        Write-Output ("{0} -> {1}  {2:N0}KB -> {3:N0}KB  ({4}x{5} -> {6}x{7})" -f $f.Name, $outPath, ($f.Length/1KB), ($outSize/1KB), $srcW, $srcH, $nw, $nh)
        $processed++
    } catch {
        Write-Output ("SKIP {0}: {1}" -f $f.Name, $_.Exception.Message)
    }
}
Write-Output ("processed: {0}/{1}" -f $processed, $targets.Count)
