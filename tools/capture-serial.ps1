# Captures raw serial output from the M5Tab5 into parser test fixtures.
#
# This exists because the serial parser is built against REAL captures, never a
# guessed protocol. It writes two files into core/src/test/resources/captures/:
#   tab5-raw-<date>.bin   exact bytes, untouched -- the authoritative fixture
#   tab5-raw-<date>.txt   best-effort text view for humans to read
#
# Usage:
#   pwsh -File capture-serial.ps1                # auto-detect the newest COM port
#   pwsh -File capture-serial.ps1 -Port COM7     # explicit port
#   pwsh -File capture-serial.ps1 -Baud 921600   # if 115200 shows garbage
#
# Capture runs for -Seconds (default 60). Flip the RF switch / change antenna
# mode on the device during the window so the capture includes whatever the
# firmware prints at a switch -- that transition line is exactly what the
# parser most needs to see.

param(
    [string]$Port = "",
    [int]$Baud = 115200,
    [int]$Seconds = 60
)

$outDir = Join-Path (Split-Path $PSScriptRoot -Parent) "core\src\test\resources\captures"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if (-not $Port) {
    # Prefer non-Bluetooth ports; Bluetooth links enumerate as ports too but are
    # never the board.
    $bt = Get-CimInstance Win32_PnPEntity -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "Bluetooth.*\((COM\d+)\)" } |
        ForEach-Object { $Matches[1] }
    $candidates = [System.IO.Ports.SerialPort]::GetPortNames() | Where-Object { $_ -notin $bt }
    if (-not $candidates) {
        Write-Error "No non-Bluetooth COM ports found. Is the board plugged in with a data cable?"
        exit 1
    }
    $Port = $candidates | Select-Object -Last 1
    Write-Host "Auto-detected port: $Port (candidates: $($candidates -join ', '))"
}

$stamp = Get-Date -Format "yyyy-MM-dd-HHmmss"
$binPath = Join-Path $outDir "tab5-raw-$stamp.bin"
$txtPath = Join-Path $outDir "tab5-raw-$stamp.txt"

$sp = New-Object System.IO.Ports.SerialPort($Port, $Baud, "None", 8, "One")
$sp.ReadTimeout = 500
# DTR/RTS matter on ESP32 boards: some designs use them for reset/boot strapping.
# Asserting DTR is the common configuration for CDC consoles.
$sp.DtrEnable = $true
$sp.RtsEnable = $true

try {
    $sp.Open()
} catch {
    Write-Error "Could not open ${Port}: $($_.Exception.Message). Close any serial monitor using it."
    exit 1
}

Write-Host "Capturing $Seconds seconds from $Port at $Baud baud..."
Write-Host "  -> flip the antenna switch / change modes on the device during this window"

$bytes = New-Object System.Collections.Generic.List[byte]
$deadline = (Get-Date).AddSeconds($Seconds)
$buffer = New-Object byte[] 4096

while ((Get-Date) -lt $deadline) {
    try {
        $n = $sp.Read($buffer, 0, $buffer.Length)
        if ($n -gt 0) {
            $bytes.AddRange([byte[]]$buffer[0..($n-1)])
            Write-Host -NoNewline "."
        }
    } catch [TimeoutException] {
        # Quiet interval; keep waiting out the window.
    }
}
$sp.Close()
Write-Host ""

[System.IO.File]::WriteAllBytes($binPath, $bytes.ToArray())
# Text view: decode as UTF-8 with replacement, purely for human reading.
$text = [System.Text.Encoding]::UTF8.GetString($bytes.ToArray())
Set-Content -Path $txtPath -Value $text -Encoding UTF8 -NoNewline

Write-Host "wrote $binPath ($($bytes.Count) bytes)"
Write-Host "wrote $txtPath"
if ($bytes.Count -eq 0) {
    Write-Warning "Zero bytes captured. Either the firmware is silent in this mode, the baud is wrong, or this is not the board's port."
}
