# Probes two facts about the Tab5 firmware that decide the automation design:
#
#   1. Can we open the port WITHOUT resetting the board?  (DTR held clear)
#      If yes, connecting stops kicking the device back to its menu.
#   2. Does the firmware read serial COMMANDS?  We send a handful of candidate
#      lines and watch the log for any reaction ([ANT-SW], echo, "unknown cmd").
#      If yes, the app can switch antennas itself and experiments run hands-free.
#
# This is observation, not guesswork: we conclude only from what the device
# actually prints back. Candidates are benign single tokens; the firmware either
# reacts or ignores them.

param(
    [string]$Port = "COM6",
    [int]$Baud = 115200
)

$sp = New-Object System.IO.Ports.SerialPort($Port, $Baud, "None", 8, "One")
$sp.ReadTimeout = 250
$sp.NewLine = "`n"
# THE TEST: do not assert DTR/RTS. If the ESP32 auto-reset circuit is DTR-driven,
# this open should leave the firmware running undisturbed.
$sp.DtrEnable = $false
$sp.RtsEnable = $false

try { $sp.Open() } catch {
    Write-Error "Could not open ${Port}: $($_.Exception.Message)"
    exit 1
}

function Read-Window([int]$ms) {
    $end = (Get-Date).AddMilliseconds($ms)
    $sb = New-Object System.Text.StringBuilder
    while ((Get-Date) -lt $end) {
        try { [void]$sb.Append($sp.ReadExisting()) } catch {}
        Start-Sleep -Milliseconds 50
    }
    return $sb.ToString()
}

Write-Host "=== Phase 1: reset check (listening 5s after DTR-clear open) ==="
$baseline = Read-Window 5000
$resetSeen = $baseline -match "ESP-ROM:|rst:0x"
$aliveSeen = $baseline -match "\[LOOP|\[hb]|\[SAMPLE]|\[SCAN"
Write-Host "reset banner seen : $resetSeen"
Write-Host "firmware chatter  : $aliveSeen"
Write-Host "--- baseline sample ---"
($baseline -split "`n" | Select-Object -First 8) -join "`n" | Write-Host

Write-Host ""
Write-Host "=== Phase 2: command candidates (2.5s listen after each) ==="
$candidates = @("help", "?", "status", "EXT", "ANT EXT", "INT", "ANT INT")
foreach ($cmd in $candidates) {
    try { $sp.WriteLine($cmd) } catch { Write-Host "write failed: $cmd"; continue }
    $resp = Read-Window 2500
    # A "reaction" is any line that is not routine chatter appearing right after
    # our send -- especially [ANT-SW], an echo, or an unknown-command message.
    $interesting = ($resp -split "`n") | Where-Object {
        $_ -match "\[ANT|unknown|invalid|cmd|CMD|echo|$([regex]::Escape($cmd))" -and
        $_ -notmatch "\[LOOP|\[hb]|\[SCAN|\[SAMPLE]"
    }
    Write-Host ("cmd '{0,-8}' -> {1}" -f $cmd, $(if ($interesting) { ($interesting -join " | ").Trim() } else { "(no reaction)" }))
}

Write-Host ""
Write-Host "=== Phase 3: post-probe health (is it still sampling?) ==="
$after = Read-Window 4000
Write-Host "still alive: $($after -match '\[LOOP|\[hb]|\[SAMPLE]')"
($after -split "`n" | Where-Object { $_ -match "\[SAMPLE]|\[ANT|\[hb]" } | Select-Object -First 6) -join "`n" | Write-Host

$sp.Close()
Write-Host "probe complete"
