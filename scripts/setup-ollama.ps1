<#
.SYNOPSIS
    One-shot Ollama optimization for the Arena Bot on an RTX 5060 (8 GB VRAM).

.DESCRIPTION
    Persists the Ollama server tuning below as USER environment variables and
    restarts the local Ollama instance so they take effect:

      OLLAMA_NUM_PARALLEL     = 4      parallel request slots per model; the bot
                                       fires chest + embedding + commander +
                                       move-oracle calls concurrently.
      OLLAMA_KEEP_ALIVE       = -1     models stay resident in (V)RAM forever —
                                       no reload latency between ticks.
      OLLAMA_MAX_LOADED_MODELS= 4      all four role models stay loaded:
                                       gemma4-e2b (~3.1GB) + lfm2.5-350m (~0.4GB)
                                       + lfm2-350m-extract (~0.3GB)
                                       + lfm2.5-embedding-350m (~0.2GB) ≈ 4GB
                                       weights, comfortably inside 8GB with KV.
      OLLAMA_FLASH_ATTENTION  = 1      flash attention for faster prompt
                                       processing on the Blackwell GPU.
      OLLAMA_KV_CACHE_TYPE    = q8_0   8-bit KV cache — halves context VRAM at
                                       negligible quality loss for these models.

    Idempotent: safe to re-run any time (e.g. after an Ollama update).

.NOTES
    Temperature strategy lives in config/config.json per role, NOT here:
      chest 0.1 (deterministic unlock codes), embedding 0.0,
      bot_move 0.3 and commander 0.3 (medium-low, structured tactics).
#>

[CmdletBinding()]
param(
    [switch]$NoRestart
)

$ErrorActionPreference = 'Stop'

$settings = [ordered]@{
    'OLLAMA_NUM_PARALLEL'      = '4'
    'OLLAMA_KEEP_ALIVE'        = '-1'
    'OLLAMA_MAX_LOADED_MODELS' = '4'
    'OLLAMA_FLASH_ATTENTION'   = '1'
    'OLLAMA_KV_CACHE_TYPE'     = 'q8_0'
}

Write-Host 'Ollama optimization (RTX 5060 / 8GB VRAM profile)' -ForegroundColor Cyan
foreach ($k in $settings.Keys) {
    $v = $settings[$k]
    $old = [Environment]::GetEnvironmentVariable($k, 'User')
    [Environment]::SetEnvironmentVariable($k, $v, 'User')
    Set-Item -Path "Env:$k" -Value $v   # current session too
    if ($old -eq $v) {
        Write-Host ("  {0,-26} = {1}  (unchanged)" -f $k, $v) -ForegroundColor DarkGray
    } else {
        $was = if ($null -eq $old) { '<unset>' } else { $old }
        Write-Host ("  {0,-26} = {1}  (was: {2})" -f $k, $v, $was) -ForegroundColor Green
    }
}

if ($NoRestart) {
    Write-Host 'Skipping Ollama restart (-NoRestart). New vars apply on next Ollama start.' -ForegroundColor Yellow
    return
}

# --- Restart Ollama so the server picks up the new environment -----------
Write-Host 'Restarting Ollama ...' -ForegroundColor Cyan
$procs = Get-Process -Name 'ollama', 'ollama app' -ErrorAction SilentlyContinue
if ($procs) {
    $procs | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

$appExe = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama app.exe'
if (Test-Path $appExe) {
    Start-Process -FilePath $appExe -WindowStyle Hidden
    Write-Host "  relaunched tray app: $appExe" -ForegroundColor Green
} else {
    $cli = Get-Command ollama -ErrorAction SilentlyContinue
    if ($cli) {
        Start-Process -FilePath $cli.Source -ArgumentList 'serve' -WindowStyle Hidden
        Write-Host "  started 'ollama serve' via $($cli.Source)" -ForegroundColor Green
    } else {
        Write-Host '  Ollama binary not found — start it manually.' -ForegroundColor Yellow
        return
    }
}

# --- Wait for the API to come back, then verify --------------------------
$base = 'http://127.0.0.1:11434'
for ($i = 0; $i -lt 20; $i++) {
    try {
        $null = Invoke-RestMethod -Uri "$base/api/version" -TimeoutSec 2 -ErrorAction Stop
        Write-Host "Ollama is back up at $base" -ForegroundColor Green
        break
    } catch {
        Start-Sleep -Milliseconds 500
    }
}

try {
    $tags = Invoke-RestMethod -Uri "$base/api/tags" -TimeoutSec 5 -ErrorAction Stop
    $names = @($tags.models | ForEach-Object { $_.name })
    $required = @('lfm2-350m-extract:latest', 'lfm2.5-embedding-350m:latest',
                  'lfm2.5-350m:latest', 'gemma4-e2b:latest')
    foreach ($r in $required) {
        if ($names -contains $r) {
            Write-Host "  model OK      : $r" -ForegroundColor Green
        } else {
            Write-Host "  model MISSING : $r  (ollama pull $r)" -ForegroundColor Red
        }
    }
} catch {
    Write-Host "Could not verify models: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host 'Done. Models will be pinned in VRAM on first use (keep_alive=-1).' -ForegroundColor Cyan
