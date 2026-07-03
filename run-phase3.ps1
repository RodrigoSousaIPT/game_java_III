<#
.SYNOPSIS
    Run and commit "Phase 3 — Final Bug Checking & Advanced Brainstorming"
    of the Java Arena Bot project.

.DESCRIPTION
    1. Builds the project with the bundled Maven (clean + package).
    2. Runs the full JUnit suite, with focus on Phase 3 packages
       (resilience: ApiRetry / CircuitBreaker / StuckTileDetector /
       PromptRingBuffer — strategy: AdaptiveStrategy / OpponentAwareness /
       VaultAttemptLedger).
    3. Stages every change .gitignore allows (target/, *.class, *.jar,
       IDE junk, etc. are auto-excluded) and creates a Phase 3 commit.

    The script aborts on the first failing step so a broken build never
    reaches git.

.PARAMETER SkipTests
    Build only — skip `mvn test`. Useful for a quick sanity build.

.PARAMETER DryRun
    Print the steps (build / test / git) without executing them.

.PARAMETER CommitMessage
    Override the default Phase 3 commit subject. The fixed body is
    always appended.

.PARAMETER NoCommit
    Run build + tests but stop before staging / committing. Handy when
    you want a manual review first.

.EXAMPLE
    .\run-phase3.ps1
    # Standard flow: clean package -> test -> commit

.EXAMPLE
    .\run-phase3.ps1 -DryRun
    # Show the steps without running them

.EXAMPLE
    .\run-phase3.ps1 -SkipTests -NoCommit
    # Quick build only, no test, no commit

.NOTES
    Uses the bundled Maven at tools\apache-maven-3.9.9\bin\mvn.cmd when
    present, falling back to `mvn` on PATH. Requires git in PATH and a
    user.name / user.email configured (sets a throw-away identity for
    this run only if missing).
#>

[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$DryRun,
    [switch]$NoCommit,
    [string]$CommitMessage = 'phase 3: final bug checking + advanced brainstorming'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# --- Resolve project root + Maven ---------------------------------------
$ProjectRoot = $PSScriptRoot
if (-not $ProjectRoot) { $ProjectRoot = (Get-Location).Path }

$LocalMvn = Join-Path $ProjectRoot 'tools\apache-maven-3.9.9\bin\mvn.cmd'
$MvnCmd   = if (Test-Path $LocalMvn) { $LocalMvn } else { 'mvn' }

Write-Host ""
Write-Host "=== Phase 3: Final Bug Check + Advanced Brainstorming ===" -ForegroundColor Cyan
Write-Host "Project root : $ProjectRoot"                              -ForegroundColor DarkGray
Write-Host "Maven        : $MvnCmd"                                    -ForegroundColor DarkGray
Write-Host "DryRun       : $DryRun"                                    -ForegroundColor DarkGray
Write-Host "SkipTests    : $SkipTests"                                 -ForegroundColor DarkGray
Write-Host "NoCommit     : $NoCommit"                                  -ForegroundColor DarkGray

# --- Helpers ------------------------------------------------------------
# Invoke via [scriptblock] rather than Invoke-Expression so a single quote
# in $MvnCmd / $ProjectRoot cannot silently break the command. The block is
# evaluated under Push-Location so callers can reference paths relatively.
function Invoke-ProjectShell {
    param(
        [Parameter(Mandatory)] [string]$Label,
        [Parameter(Mandatory)] [scriptblock]$Command
    )
    Write-Host ""
    Write-Host "--- $Label ---" -ForegroundColor Yellow
    if ($DryRun) {
        Write-Host "[dry-run] $($Command.ToString().Trim())" -ForegroundColor DarkGray
        return
    }
    Write-Host "Running: $($Command.ToString().Trim())" -ForegroundColor DarkGray
    Push-Location -LiteralPath $ProjectRoot
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Step '$Label' failed (exit $LASTEXITCODE)." -ForegroundColor Red
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
}

# --- 1. Build -----------------------------------------------------------
# Step 1 always skips tests: Step 2's `mvn test` is the canonical run and
# surface for JUnit failures (publishes surefire reports under target/).
Invoke-ProjectShell -Label '1/3  Maven clean package (-DskipTests; tests run in step 2)' `
    -Command { & $MvnCmd -f (Join-Path $ProjectRoot 'pom.xml') clean package -B -DskipTests }

# --- 2. Tests -----------------------------------------------------------
if (-not $SkipTests) {
    Invoke-ProjectShell -Label '2/3  Maven test (Phase 3: resilience + strategy)' `
        -Command { & $MvnCmd -f (Join-Path $ProjectRoot 'pom.xml') test -B }
} else {
    Write-Host ""
    Write-Host "--- 2/3  Maven test SKIPPED (-SkipTests) ---" -ForegroundColor DarkYellow
}

# --- 3. Commit (unless -NoCommit) ---------------------------------------
if ($NoCommit) {
    Write-Host ""
    Write-Host "--- 3/3  Git commit SKIPPED (-NoCommit) ---" -ForegroundColor DarkYellow
    Write-Host "Phase 3 build OK (no commit performed)." -ForegroundColor Green
    return
}

Write-Host ""
Write-Host "--- 3/3  Git commit Phase 3 ---" -ForegroundColor Yellow

if ($DryRun) {
    Write-Host "[dry-run] would run: git add + git diff --cached --stat + git commit" -ForegroundColor DarkGray
    Write-Host ""
    Write-Host "=== Phase 3 dry-run complete ===" -ForegroundColor Cyan
    exit 0
}

# Make sure git user is configured. Don't write to global config — use
# -c only for this invocation so we leave the user's identity alone.
Push-Location -LiteralPath $ProjectRoot
try {
    $gitArgsCheck = @('--version')
    & git @gitArgsCheck | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "git executable not found on PATH." -ForegroundColor Red
        exit 1
    }

    $userName  = (& git config --get user.name  2>$null).Trim()
    $userEmail = (& git config --get user.email 2>$null).Trim()
    $identityArgs = @()
    if (-not $userName)  { $identityArgs += '-c'; $identityArgs += 'user.name=Arena Bot' }
    if (-not $userEmail) { $identityArgs += '-c'; $identityArgs += 'user.email=bot@arena.local' }
    if ($identityArgs.Count -gt 0) {
        Write-Host "No git identity configured — using throw-away (this commit only)." -ForegroundColor Yellow
    }

    # Stage everything .gitignore allows (target/, *.jar, *.class auto-excluded).
    & git @identityArgs add -A
    if ($LASTEXITCODE -ne 0) {
        Write-Host "git add failed (exit $LASTEXITCODE)." -ForegroundColor Red
        exit $LASTEXITCODE
    }

    $stagedStat = & git diff --cached --stat
    if (-not $stagedStat) {
        Write-Host "Nothing to commit — working tree clean for tracked paths." -ForegroundColor Green
        exit 0
    }

    Write-Host "Staged change summary:" -ForegroundColor DarkGray
    Write-Host $stagedStat

    $commitBody = @'
- resilience: ApiRetry (timeouts/rate-limit), CircuitBreaker (sustained outage),
  StuckTileDetector (oscillation loop guard), PromptRingBuffer (LLM memory cap).
- strategy:   AdaptiveStrategy (low-energy ECO-MARCH + opponent crowd override),
  OpponentAwareness (Manhattan proximity scoring),
  VaultAttemptLedger (2 attempts/vault then abandon).
- tests:      JUnit 5 coverage for every Phase 3 class.
- build:      pom.xml wiring (shaded jar, JVM 21, main class).
- ops:        run-phase3.ps1 — build + test + commit gate for Phase 3.
'@

    $commitArgs = @('commit', '-m', $CommitMessage, '-m', $commitBody.TrimEnd())
    & git @identityArgs @commitArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host "git commit failed (exit $LASTEXITCODE)." -ForegroundColor Red
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Phase 3 committed." -ForegroundColor Green
& git log --oneline -n 1
Write-Host ""
Write-Host "=== Phase 3 done ===" -ForegroundColor Cyan
