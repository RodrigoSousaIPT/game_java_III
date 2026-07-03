<#
.SYNOPSIS
    Interactive launcher for the Arena Bot with a text menu and built-in
    telemetry support (Swing dashboard + tail-able log).

.DESCRIPTION
    Starts the bot with the bundled Maven + shaded jar
    (target/arena-bot.jar), preferring the local Maven at
    tools/apache-maven-3.9.9/bin/mvn.cmd.

    The Swing dashboard opened by com.arenabot.Main IS the telemetry:
      - RoomCodePanel     (start/stop button + status)
      - GridPanel         (walls in grey, chests purple, energy yellow,
                           vaults red, bot cyan, opponents violet)
      - AnalyticsPanel    (live role / tok in-out / elapsed + tail of lines)
    This script does not duplicate that UI; it just launches the bot, lets
    the user pre-flight the endpoints, edit config, and inspect/tail the
    slf4j-simple log stream this script writes to data/logs/bot-*.log.

    All edits to config/config.json are JSON-safe (idempotent key writes
    converted via ConvertFrom-Json / ConvertTo-Json) so the bot can keep
    reloading it on next start.

.PARAMETER BuildFirst
    Force `mvn package` before launching, even if the jar timestamp
    already matches the source tree.

.PARAMETER Room
    Override the room code for this single launch — does NOT rewrite
    config/config.json. Useful for quick reconnect to a new arena.

.PARAMETER Config
    Path to an alternate config.json to feed the bot. Falls back to
    config/config.json at the project root.

.EXAMPLE
    .\run-bot.ps1
    # Default — opens the menu.

.EXAMPLE
    .\run-bot.ps1 -BuildFirst -Headless
    # Headless run with a clean rebuild, menu still shown.

.EXAMPLE
    .\run-bot.ps1 -Room C31233
    # Launch the bot once against room C31233 using whatever the menu
    # chose for start mode (default = with Swing dashboard).
#>

[CmdletBinding()]
param(
    [switch]$BuildFirst,
    [switch]$Headless,
    [string]$Room,
    [string]$Config
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# --- Paths --------------------------------------------------------------
$ProjectRoot = $PSScriptRoot
if (-not $ProjectRoot) { $ProjectRoot = (Get-Location).Path }

$LocalMvn      = Join-Path $ProjectRoot 'tools/apache-maven-3.9.9/bin/mvn.cmd'
$MvnCmd        = if (Test-Path $LocalMvn) { $LocalMvn } else { 'mvn' }
$JarPath       = Join-Path $ProjectRoot 'target/arena-bot.jar'
$MainClass     = 'com.arenabot.Main'
$DefaultConfig = Join-Path $ProjectRoot 'config/config.json'
$Template      = Join-Path $ProjectRoot 'src/main/resources/config.json.template'
$ConfigPath    = if ($Config -and (Test-Path $Config)) { (Resolve-Path $Config).Path } else { $DefaultConfig }
$DataDir       = Join-Path $ProjectRoot 'data'
$LogDir        = Join-Path $DataDir 'logs'
$PidFile       = Join-Path $DataDir 'bot.pid'
New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot 'config') | Out-Null
New-Item -ItemType Directory -Force -Path $LogDir                                | Out-Null

# --- Logging helpers ----------------------------------------------------
function Write-Banner  { param([string]$Text) Write-Host $Text -ForegroundColor Cyan }
function Write-Info   { param([string]$Text) Write-Host $Text -ForegroundColor Green }
function Write-Warn   { param([string]$Text) Write-Host $Text -ForegroundColor Yellow }
function Write-Err    { param([string]$Text) Write-Host $Text -ForegroundColor Red }
function Write-Cmd    { param([string]$Text) Write-Host $Text -ForegroundColor DarkGray }

# --- Menu read with input validation ------------------------------------
function Read-Choice {
    param([Parameter(Mandatory)][string]$Prompt, [string[]]$Valid)
    while ($true) {
        $raw = (Read-Host $Prompt).Trim()
        if ($null -eq $raw) { continue }
        if ($Valid -contains $raw) { return $raw }
        Write-Warn "Invalid choice '$raw' — expected one of: $($Valid -join ', ')"
    }
}

# --- Config helpers (uses PowerShell's native JSON, no Jackson needed) ---
function Read-ConfigObject {
    if (-not (Test-Path $ConfigPath)) { return $null }
    Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
}

function Ensure-Config {
    if (Test-Path $ConfigPath) { return }
    if (-not (Test-Path $Template)) {
        throw "config.json missing AND no template at $Template — cannot bootstrap."
    }
    Write-Warn "Config $ConfigPath not found — copying from bundled template."
    Copy-Item -LiteralPath $Template -Destination $ConfigPath
}

function Format-Config {
    param($Cfg)
    if (-not $Cfg) { return '(no config)' }
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("room_code           : $($Cfg.room_code)")
    $lines.Add("arena_base_url      : $($Cfg.arena_base_url)")
    $lines.Add("ollama_base_url     : $($Cfg.ollama_base_url)")
    $lines.Add("tick_ms             : $($Cfg.tick_ms)")
    $lines.Add("commander_period_ms : $($Cfg.commander_period_ms)")
    $lines.Add("energy_critical     : $($Cfg.energy_critical_threshold)")
    $lines.Add("energy_low          : $($Cfg.energy_low_threshold)")
    if ($Cfg.models) {
        $lines.Add("models              :")
        foreach ($p in $Cfg.models.PSObject.Properties) {
            $name = "$($p.Name)".PadRight(11)
            $lines.Add("  $name -> $($p.Value.model_name)  (temp=$($p.Value.temperature))")
        }
    }
    $lines.Add("ui.grid_pixel_size  : $($Cfg.ui.grid_pixel_size)")
    $lines.Add("ui.grid_x_max       : $($Cfg.ui.grid_x_max)")
    $lines.Add("ui.grid_y_max       : $($Cfg.ui.grid_y_max)")
    $lines -join "`n"
}

function Update-ConfigKey {
    param([Parameter(Mandatory)][string]$Key, [Parameter(Mandatory)]$Value)
    $cfg = Read-ConfigObject
    if (-not $cfg) { throw "No config at $ConfigPath" }
    $cfg.$Key = $Value
    ($cfg | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $ConfigPath -Encoding UTF8
    Write-Info "  updated $Key -> $Value (saved to $ConfigPath)"
}

# --- Maven wrapper ------------------------------------------------------
function Invoke-Maven {
    param([Parameter(Mandatory)][scriptblock]$Block)
    Push-Location -LiteralPath $ProjectRoot
    try {
        & $Block
    } finally {
        Pop-Location
    }
}

# --- Rebuild trigger ----------------------------------------------------
function Get-LatestSourceMtime {
    $sources = Get-ChildItem -Recurse -Path (Join-Path $ProjectRoot 'src') -Filter *.java -ErrorAction SilentlyContinue
    if (-not $sources) { return [datetime]::MinValue }
    ($sources | Measure-Object LastWriteTime -Maximum).Maximum
}

function Ensure-Jar {
    if ($BuildFirst) {
        Write-Banner "Building per -BuildFirst (rebuild from clean) ..."
        Invoke-Maven { & $MvnCmd -f (Join-Path $ProjectRoot 'pom.xml') clean package -B -DskipTests }
        return
    }
    $jarExists = Test-Path $JarPath
    $srcMtime  = Get-LatestSourceMtime
    $jarMtime  = if ($jarExists) { (Get-Item $JarPath).LastWriteTime } else { [datetime]::MinValue }
    if ($jarExists -and $jarMtime -ge $srcMtime) {
        Write-Info  "Jar up to date ($([math]::Round(((Get-Date) - $jarMtime).TotalMinutes,1)) min old)."
        return
    }
    Write-Banner "Jar missing or stale (jar=$jarMtime, src=$srcMtime) — building ..."
    Invoke-Maven { & $MvnCmd -f (Join-Path $ProjectRoot 'pom.xml') package -B -DskipTests }
}

# --- Pre-flight probes --------------------------------------------------
function Get-ConfigValue {
    param([string]$Key, [string]$Default)
    $cfg = Read-ConfigObject
    if (-not $cfg -or -not $cfg.PSObject.Properties.Name -contains $Key) { return $Default }
    return "$($cfg.$Key)"
}

function Test-Arena {
    $base = Get-ConfigValue -Key 'arena_base_url' -Default 'https://arena.pmonteiro.ovh'
    $room = Get-ConfigValue -Key 'room_code' -Default '7A1071'
    Write-Banner "Pre-flight: arena  $base  (room $room)"
    try {
        # /openapi.json is small and always served
        $url = "$base/openapi.json"
        Write-Cmd "HEAD $url"
        $r = Invoke-WebRequest -Uri $url -Method Head -UseBasicParsing -TimeoutSec 8 -ErrorAction Stop
        Write-Info ("  HTTP {0}  {1}" -f $r.StatusCode, $url)
    } catch {
        Write-Err "  unreachable -> $($_.Exception.Message)"
    }
}

function Test-Ollama {
    $base = Get-ConfigValue -Key 'ollama_base_url' -Default 'http://127.0.0.1:11434'
    Write-Banner "Pre-flight: ollama $base"
    try {
        $url = "$base/api/tags"
        Write-Cmd "GET  $url"
        $resp = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 5 -ErrorAction Stop
        $names = @($resp.models | ForEach-Object { $_.name })
        if ($names.Count -eq 0) { Write-Warn "  reachable but no models loaded." }
        else { Write-Info ("  {0} models: {1}" -f $names.Count, ($names -join ', ')) }
    } catch {
        Write-Err "  unreachable -> $($_.Exception.Message)"
    }
}

# --- Launch / stop bot --------------------------------------------------
function Get-StoredPid {
    if (-not (Test-Path $PidFile)) { return $null }
    $raw = (Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $raw) { return $null }
    $pid = 0
    if ([int]::TryParse($raw, [ref]$pid) -and (Get-Process -Id $pid -ErrorAction SilentlyContinue)) {
        return $pid
    }
    return $null
}

function Start-Bot {
    param([switch]$HeadlessMode, [string]$RoomOverride)

    $existing = Get-StoredPid
    if ($existing) {
        Write-Warn "Bot already running as PID $existing. Stop it first (option 9) or use -Force."
        return
    }

    Ensure-Jar
    $stamp   = Get-Date -Format 'yyyyMMdd-HHmmss'
    $logFile = Join-Path $LogDir "bot-$stamp.log"
    "" | Set-Content -LiteralPath $logFile -Encoding UTF8   # truncate

    # JVM args. -Djava.awt.headless=true keeps the bot running without the
    # Swing frame (CI / SSH). Otherwise the TelemetryDashboard opens.
    $jvmArgs = @()
    if ($HeadlessMode) {
        $jvmArgs += '-Djava.awt.headless=true'
        Write-Banner "Starting bot (HEADLESS) ..."
    } else {
        Write-Banner "Starting bot (Swing telemetry dashboard) ..."
    }
    # slf4j-simple controls — single-line INFO so tail -f is readable.
    $jvmArgs += @(
        '-Dorg.slf4j.simpleLogger.defaultLogLevel=info'
        '-Dorg.slf4j.simpleLogger.showThreadName=true'
        '-Dorg.slf4j.simpleLogger.showShortLogName=true'
        '-Dorg.slf4j.simpleLogger.showDateTime=true'
        '-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS'
    )
    if ($RoomOverride) {
        $jvmArgs += "-Darenabot.room.code=$RoomOverride"
    }

    $args = @('-jar', $JarPath, $ConfigPath) + $jvmArgs
    Write-Cmd "java $($args -join ' ')"

    $proc = Start-Process -FilePath 'java' `
        -ArgumentList $args `
        -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError  "$logFile.err" `
        -PassThru -WindowStyle Normal
    $proc.Id | Set-Content -LiteralPath $PidFile -Encoding ASCII
    Start-Sleep -Milliseconds 300

    if ((Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
        Write-Info ("Bot started (PID {0}) — log: {1}" -f $proc.Id, $logFile)
        if (-not $HeadlessMode) {
            Write-Info "Telemetry dashboard will open in a few seconds (RoomCodePanel / GridPanel / AnalyticsPanel)."
        }
    } else {
        Write-Err "Bot process exited immediately. Tail of $logFile :"
        Get-Content -LiteralPath $logFile -Tail 25 | ForEach-Object { Write-Host "  $_" }
    }
}

function Stop-Bot {
    $pid = Get-StoredPid
    if (-not $pid) { Write-Warn "No running bot (pid file empty or stale)." ; return }
    Write-Banner "Stopping bot PID $pid ..."
    try {
        Stop-Process -Id $pid -Force
        Start-Sleep -Milliseconds 400
        if (Get-Process -Id $pid -ErrorAction SilentlyContinue) {
            Write-Warn "Process still alive — issuing `taskkill /F /T /PID $pid`."
            & taskkill /F /T /PID $pid | Out-Null
        }
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        Write-Info "Bot stopped."
    } catch {
        Write-Err "Stop failed: $($_.Exception.Message)"
    }
}

# --- Menu actions -------------------------------------------------------
function Set-Room-InConfig {
    $cfg = Read-ConfigObject
    $current = if ($cfg) { "$($cfg.room_code)" } else { '' }
    $newCode = (Read-Host "  New room code (current='$current')").Trim()
    if (-not $newCode) { Write-Warn "  Aborted — no change." ; return }
    Update-ConfigKey -Key 'room_code' -Value $newCode
}

function Edit-Config {
    if (-not (Test-Path $ConfigPath)) { Write-Err "No config at $ConfigPath"; return }
    $editor = $env:VISUAL
    if (-not $editor) {
        if (Get-Command code -ErrorAction SilentlyContinue) { $editor = 'code --wait' }
        elseif (Get-Command notepad -ErrorAction SilentlyContinue) { $editor = 'notepad' }
    }
    if ($editor) {
        Write-Info "Opening $ConfigPath in [$editor] ..."
        Invoke-Expression "$editor `"$ConfigPath`""
    } else {
        Write-Warn "No editor found. Use option 6 (View config) or open the file manually:"
        Write-Host "  $ConfigPath"
    }
}

function View-Config {
    Write-Banner "--- current config ($ConfigPath) ---"
    $cfg = Read-ConfigObject
    Write-Host (Format-Config $cfg)
    Write-Banner "--- raw JSON ---"
    Get-Content -Raw -LiteralPath $ConfigPath
}

function Tail-BotLog {
    param([int]$Lines = 30)
    $latest = Get-ChildItem -Path $LogDir -Filter 'bot-*.log' -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) { Write-Warn "No log files yet under $LogDir"; return }
    Write-Banner "Last $Lines lines of $($latest.Name)"
    Get-Content -LiteralPath $latest.FullName -Tail $Lines -Wait -ErrorAction SilentlyContinue
    # ^ -Wait blocks. Caller can Ctrl+C and we return to the menu.
}

function Run-Tests {
    Write-Banner "Running JUnit (Phase 3: resilience + strategy) ..."
    Invoke-Maven { & $MvnCmd -f (Join-Path $ProjectRoot 'pom.xml') test -B }
}

# --- Menu banner --------------------------------------------------------
function Show-Menu {
    Clear-Host
    $cfg = Read-ConfigObject
    $room = if ($cfg) { "$($cfg.room_code)" } else { '(none)' }
    $ollama = if ($cfg) { "$($cfg.ollama_base_url)" } else { '(none)' }
    $pid = Get-StoredPid
    $status = if ($pid) { "RUNNING (PID $pid)" } else { 'stopped' }
    Write-Host ''
    Write-Host '====================================================================' -ForegroundColor Cyan
    Write-Host "              ARENA BOT  ::  room=$room   ollama=$ollama"               -ForegroundColor Cyan
    Write-Host "              $status"                                                  -ForegroundColor Cyan
    Write-Host '====================================================================' -ForegroundColor Cyan
    Write-Host '  1) Start bot WITH telemetry (Swing dashboard)'
    Write-Host '  2) Start bot HEADLESS (no UI, console/SSH/CI)'
    Write-Host '  3) Set room code  (writes config/config.json)'
    Write-Host '  4) Open config in editor'
    Write-Host '  5) Pre-flight: ping arena + Ollama'
    Write-Host '  6) View current config'
    Write-Host '  7) Run JUnit (Phase 3 resilience + strategy)'
    Write-Host '  8) Tail latest bot log (Ctrl+C to leave tail)'
    Write-Host '  9) Stop running bot'
    Write-Host '  0) Exit'
    Write-Host '====================================================================' -ForegroundColor Cyan
    if ($Room)          { Write-Warn "  -Room override active for this session: $Room" }
    if ($Headless)      { Write-Warn "  -Headless default-start active for this session." }
    if ($BuildFirst)    { Write-Warn "  -BuildFirst active for this session." }
}

# --- Bootstrap ----------------------------------------------------------
Ensure-Config

Write-Banner 'run-bot.ps1 — Arena Bot launcher'
Write-Info ("Project root : $ProjectRoot")
Write-Info ("Maven        : $MvnCmd")
Write-Info ("Config       : $ConfigPath")
Write-Info ("Jar          : $JarPath")

# If the user invoked us with -Room / -Headless / -BuildFirst and no menu
# required, the menu is still useful, so we always drop into it.

while ($true) {
    Show-Menu
    $pick = Read-Choice -Prompt '> pick [0-9, q] ' -Valid @('1','2','3','4','5','6','7','8','9','0','q','Q')

    switch ($pick) {
        '1'  { Start-Bot -HeadlessMode:$false -RoomOverride:$Room }
        '2'  { Start-Bot -HeadlessMode:$true  -RoomOverride:$Room }
        '3'  { Set-Room-InConfig }
        '4'  { Edit-Config }
        '5'  { Test-Arena ; Write-Host '' ; Test-Ollama }
        '6'  { View-Config }
        '7'  { Run-Tests }
        '8'  { Tail-BotLog -Lines 30 }
        '9'  { Stop-Bot }
        '0'  { Write-Info 'Goodbye.' ; return }
        'q'  { Write-Info 'Goodbye.' ; return }
        'Q'  { Write-Info 'Goodbye.' ; return }
    }

    if ($pick -notin '0','q','Q') {
        Read-Host "`nPress Enter to return to menu"
    } else {
        return
    }
}
