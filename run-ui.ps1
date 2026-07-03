<#
.SYNOPSIS
    Opens the Arena Bot telemetry dashboard (Swing UI) directly — no menu.

.DESCRIPTION
    Straight-to-UI launcher: builds the shaded jar if it is stale (same
    freshness rule as run-bot.ps1), then starts com.arenabot.Main with the
    Swing ArenaDashboard + HeatMapPanel in the foreground of a new java
    process. Output is streamed to data/logs/ui-*.log so the console stays
    free. The room code field starts EMPTY by design — type it in the UI,
    or pass -Room to pre-fill it for this launch.

.PARAMETER Room
    Pre-fills the room code field via -Darenabot.room.code. Optional; does
    NOT rewrite config/config.json.

.PARAMETER Config
    Alternate config.json path. Defaults to config/config.json.

.PARAMETER BuildFirst
    Force `mvn clean package` before launching.

.EXAMPLE
    .\run-ui.ps1
    # Opens the dashboard; type the room code in the Sala field.

.EXAMPLE
    .\run-ui.ps1 -Room C31233
    # Opens the dashboard with the Sala field pre-filled.
#>

[CmdletBinding()]
param(
    [string]$Room,
    [string]$Config,
    [switch]$BuildFirst
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# --- Paths (mirrors run-bot.ps1) -----------------------------------------
$ProjectRoot = $PSScriptRoot
if (-not $ProjectRoot) { $ProjectRoot = (Get-Location).Path }

$LocalMvn      = Join-Path $ProjectRoot 'tools/apache-maven-3.9.9/bin/mvn.cmd'
$MvnCmd        = if (Test-Path $LocalMvn) { $LocalMvn } else { 'mvn' }
$JarPath       = Join-Path $ProjectRoot 'target/arena-bot.jar'
$DefaultConfig = Join-Path $ProjectRoot 'config/config.json'
$Template      = Join-Path $ProjectRoot 'src/main/resources/config.json.template'
$ConfigPath    = if ($Config -and (Test-Path $Config)) { (Resolve-Path $Config).Path } else { $DefaultConfig }
$LogDir        = Join-Path $ProjectRoot 'data/logs'
New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot 'config') | Out-Null
New-Item -ItemType Directory -Force -Path $LogDir                           | Out-Null

function Write-Info { param([string]$Text) Write-Host $Text -ForegroundColor Green }
function Write-Warn { param([string]$Text) Write-Host $Text -ForegroundColor Yellow }

# --- Bootstrap config from the template on first run ----------------------
if (-not (Test-Path $ConfigPath)) {
    if (-not (Test-Path $Template)) {
        throw "config.json missing AND no template at $Template — cannot bootstrap."
    }
    Write-Warn "Config $ConfigPath not found — copying from bundled template."
    Copy-Item -LiteralPath $Template -Destination $ConfigPath
}

# --- Build if the jar is missing or stale ---------------------------------
function Get-LatestSourceMtime {
    $sources = Get-ChildItem -Recurse -Path (Join-Path $ProjectRoot 'src') -Filter *.java -ErrorAction SilentlyContinue
    if (-not $sources) { return [datetime]::MinValue }
    ($sources | Measure-Object LastWriteTime -Maximum).Maximum
}

$needBuild = $BuildFirst -or -not (Test-Path $JarPath) -or
             ((Get-Item $JarPath).LastWriteTime -lt (Get-LatestSourceMtime))
if ($needBuild) {
    Write-Host 'Building arena-bot.jar ...' -ForegroundColor Cyan
    Push-Location -LiteralPath $ProjectRoot
    try {
        & $MvnCmd -f (Join-Path $ProjectRoot 'pom.xml') clean package -B -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
} else {
    Write-Info 'Jar up to date — skipping build.'
}

# --- Launch the dashboard --------------------------------------------------
$stamp   = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $LogDir "ui-$stamp.log"

# JVM flags MUST come before -jar (after the jar they become program args).
$javaArgs = @(
    '-Dorg.slf4j.simpleLogger.defaultLogLevel=info'
    '-Dorg.slf4j.simpleLogger.showShortLogName=true'
    '-Dorg.slf4j.simpleLogger.showDateTime=true'
    '-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS'
)
if ($Room) { $javaArgs += "-Darenabot.room.code=$Room" }
# Quote paths — Start-Process re-joins the argument list into a command
# line, and unquoted spaces (e.g. "D:\Projeto BOT") split the jar path.
$javaArgs += @('-jar', ('"{0}"' -f $JarPath), ('"{0}"' -f $ConfigPath))

Write-Host "java $($javaArgs -join ' ')" -ForegroundColor DarkGray
$proc = Start-Process -FilePath 'java' `
    -ArgumentList $javaArgs `
    -WorkingDirectory $ProjectRoot `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError  "$logFile.err" `
    -PassThru -WindowStyle Normal

Start-Sleep -Milliseconds 500
if (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue) {
    Write-Info ("Dashboard opening (PID {0}) — log: {1}" -f $proc.Id, $logFile)
    if ($Room) { Write-Info "Sala pre-filled: $Room" }
    else       { Write-Info 'Sala field is empty — type the room code in the UI.' }
} else {
    Write-Host 'Process exited immediately. Log tail:' -ForegroundColor Red
    Get-Content -LiteralPath "$logFile.err" -Tail 20 -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host "  $_" }
}
