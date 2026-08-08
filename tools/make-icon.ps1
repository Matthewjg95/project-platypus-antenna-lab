# Generates the Antenna Lab application icon.
#
# The mark is the radar glyph from the Project Platypus firmware's Room Scan /
# ShadowScan menu: concentric rings, a sweep line to the upper right, and a dot
# on that line. It is redrawn here rather than cropped from a photograph of the
# device screen -- a blurry off-angle LCD shot does not survive being scaled to
# 16 px, and an app icon has to be legible at every size Windows asks for.
#
# Output is a multi-resolution .ico. Windows picks the nearest size, so shipping
# one 256 px image and letting it downscale produces a smeared taskbar icon.
#
#   pwsh -File make-icon.ps1
#
# Re-run only when the artwork changes; the generated .ico is committed.

Add-Type -AssemblyName System.Drawing

$outDir = Join-Path (Split-Path $PSScriptRoot -Parent) "app\src\main\resources\dev\antennalab\app"
$icoPath = Join-Path $outDir "antenna-lab.ico"
$pngPath = Join-Path $outDir "antenna-lab-256.png"

# Instrument palette, matching instrument.css.
$bg = [System.Drawing.ColorTranslator]::FromHtml("#0B0F14")
$accent = [System.Drawing.ColorTranslator]::FromHtml("#22D3EE")
$ring2 = [System.Drawing.ColorTranslator]::FromHtml("#1B93A6")

function New-IconBitmap([int]$s) {
    $bmp = New-Object System.Drawing.Bitmap($s, $s, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

    # Rounded-square chassis, so the icon reads as an instrument rather than a
    # floating line drawing on the taskbar.
    $r = [Math]::Max(2, [int]($s * 0.18))
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($s - $d - 1, 0, $d, $d, 270, 90)
    $path.AddArc($s - $d - 1, $s - $d - 1, $d, $d, 0, 90)
    $path.AddArc(0, $s - $d - 1, $d, $d, 90, 90)
    $path.CloseFigure()
    $brush = New-Object System.Drawing.SolidBrush($bg)
    $g.FillPath($brush, $path)

    $cx = $s / 2.0
    $cy = $s / 2.0
    $stroke = [Math]::Max(1.0, $s * 0.045)

    # Below ~24 px the third ring turns into mud, so drop it and let the outer
    # two carry the shape.
    $radii = if ($s -le 24) { @(0.36, 0.18) } else { @(0.37, 0.26, 0.15) }

    $i = 0
    foreach ($f in $radii) {
        $rad = $s * $f
        $colour = if ($i -eq 0) { $accent } else { $ring2 }
        $pen = New-Object System.Drawing.Pen($colour, $stroke)
        $g.DrawEllipse($pen, [float]($cx - $rad), [float]($cy - $rad), [float]($rad * 2), [float]($rad * 2))
        $pen.Dispose()
        $i++
    }

    # Sweep line to the upper right at 45 degrees, with the return dot on it.
    $ang = -45.0 * [Math]::PI / 180.0
    $outer = $s * 0.37
    $x2 = $cx + [Math]::Cos($ang) * $outer
    $y2 = $cy + [Math]::Sin($ang) * $outer
    $penSweep = New-Object System.Drawing.Pen($accent, $stroke)
    $penSweep.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $penSweep.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $g.DrawLine($penSweep, [float]$cx, [float]$cy, [float]$x2, [float]$y2)
    $penSweep.Dispose()

    $dotR = [Math]::Max(1.0, $s * 0.055)
    $dotBrush = New-Object System.Drawing.SolidBrush($accent)
    $g.FillEllipse($dotBrush, [float]($x2 - $dotR), [float]($y2 - $dotR), [float]($dotR * 2), [float]($dotR * 2))
    $dotBrush.Dispose()

    $brush.Dispose(); $path.Dispose(); $g.Dispose()
    return $bmp
}

# Every size Windows asks for: taskbar, Alt-Tab, Explorer tiles, and the large
# icon the installer and properties dialog use.
$sizes = @(16, 24, 32, 48, 64, 128, 256)
$pngs = @{}
foreach ($s in $sizes) {
    $bmp = New-IconBitmap $s
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs[$s] = $ms.ToArray()
    if ($s -eq 256) { $bmp.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png) }
    $ms.Dispose(); $bmp.Dispose()
}

# Write the .ico container by hand: ICONDIR, then one ICONDIRENTRY per image,
# then the PNG payloads. Windows Vista and later read PNG-compressed entries at
# every size, which keeps the file small and the edges clean.
$fs = [System.IO.File]::Create($icoPath)
$bw = New-Object System.IO.BinaryWriter($fs)
$bw.Write([UInt16]0)                 # reserved
$bw.Write([UInt16]1)                 # type 1 = icon
$bw.Write([UInt16]$sizes.Count)

$offset = 6 + (16 * $sizes.Count)
foreach ($s in $sizes) {
    $bytes = $pngs[$s]
    # 0 means 256 in a single byte field.
    $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
    $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
    $bw.Write([byte]0)               # palette size, 0 = truecolour
    $bw.Write([byte]0)               # reserved
    $bw.Write([UInt16]1)             # colour planes
    $bw.Write([UInt16]32)            # bits per pixel
    $bw.Write([UInt32]$bytes.Length)
    $bw.Write([UInt32]$offset)
    $offset += $bytes.Length
}
foreach ($s in $sizes) { $bw.Write($pngs[$s]) }
$bw.Flush(); $bw.Dispose(); $fs.Dispose()

Write-Host "wrote $icoPath ($((Get-Item $icoPath).Length) bytes, $($sizes.Count) sizes)"
Write-Host "wrote $pngPath"
