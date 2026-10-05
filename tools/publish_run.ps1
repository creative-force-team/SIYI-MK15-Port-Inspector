param(
    [Parameter(Mandatory = $true)]
    [string]$RunDir,
    [int]$ResultCode = 1,
    [string]$RunType = 'unknown'
)

$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$RunDir = [System.IO.Path]::GetFullPath($RunDir)
$RootFull = [System.IO.Path]::GetFullPath($Root)

if (-not $RunDir.StartsWith($RootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
    Write-Host 'Refusing to publish a run directory outside this repository.' -ForegroundColor Red
    exit 2
}

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

$PublishLog = Join-Path $RunDir 'publish.log'
$SummaryPath = Join-Path $RunDir 'RUN_SUMMARY.txt'
$ArtifactDir = Join-Path $RunDir 'artifacts'
$RunId = Split-Path -Leaf $RunDir
$RunRel = $RunDir.Substring($RootFull.Length).TrimStart('\', '/').Replace('\', '/')

function Log-Line([string]$Message) {
    $Line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + $Message
    Add-Content -Path $PublishLog -Value $Line -Encoding UTF8
    Write-Host $Line
}

Log-Line ('Publishing diagnostics for ' + $RunId + ' type=' + $RunType + ' result=' + $ResultCode)

New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null

$ApkCandidates = @(
    (Join-Path $Root 'out\MK15PortInspector-1.5.2-debug.apk'),
    (Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk')
)

$AarCandidates = @(
    (Join-Path $Root 'out\MK15-CD-SDK-1.0.0.aar'),
    (Join-Path $Root 'mk15-sdk\build\outputs\aar\mk15-sdk-release.aar')
)

$CopiedApk = @()
foreach ($Apk in $ApkCandidates) {
    if (Test-Path $Apk) {
        $Name = Split-Path -Leaf $Apk
        if ($Name -eq 'app-debug.apk') {
            $Name = 'MK15PortInspector-1.5.2-debug.apk'
        }
        $Destination = Join-Path $ArtifactDir $Name
        Copy-Item -Force $Apk $Destination
        if ($CopiedApk -notcontains $Destination) {
            $CopiedApk += $Destination
        }
    }
}

$CopiedAar = @()
foreach ($Aar in $AarCandidates) {
    if (Test-Path $Aar) {
        $Name = Split-Path -Leaf $Aar
        if ($Name -eq 'mk15-sdk-release.aar') {
            $Name = 'MK15-CD-SDK-1.0.0.aar'
        }
        $Destination = Join-Path $ArtifactDir $Name
        Copy-Item -Force $Aar $Destination
        if ($CopiedAar -notcontains $Destination) {
            $CopiedAar += $Destination
        }
    }
}

$HashLines = @()
foreach ($Artifact in @($CopiedApk + $CopiedAar)) {
    try {
        $Hash = Get-FileHash -Algorithm SHA256 $Artifact
        $HashLines += ($Hash.Hash + '  ' + (Split-Path -Leaf $Artifact))
    }
    catch {
        $HashLines += ('HASH_ERROR  ' + (Split-Path -Leaf $Artifact) + '  ' + $_.Exception.Message)
    }
}

if ($HashLines.Count -gt 0) {
    $HashLines | Out-File -FilePath (Join-Path $ArtifactDir 'SHA256.txt') -Encoding UTF8
}

$GitHead = (& git -C $Root rev-parse HEAD 2>&1 | Out-String).Trim()
$GitBranch = (& git -C $Root rev-parse --abbrev-ref HEAD 2>&1 | Out-String).Trim()
$GitRemote = (& git -C $Root remote get-url origin 2>&1 | Out-String).Trim()
$OsText = [Environment]::OSVersion.VersionString

$Summary = @(
    ('run_id=' + $RunId),
    ('run_type=' + $RunType),
    ('result_code=' + $ResultCode),
    ('finished_at=' + (Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')),
    ('os=' + $OsText),
    ('git_head_before_publish=' + $GitHead),
    ('git_branch=' + $GitBranch),
    ('git_remote=' + $GitRemote),
    ('run_path=' + $RunRel),
    ('apk_count=' + $CopiedApk.Count),
    ('aar_count=' + $CopiedAar.Count)
)

$Summary | Out-File -FilePath $SummaryPath -Encoding UTF8
(& git -C $Root status --short 2>&1) | Out-File -FilePath (Join-Path $RunDir 'git_status_before_publish.txt') -Encoding UTF8

& git -C $Root rev-parse --is-inside-work-tree *> $null
if ($LASTEXITCODE -ne 0) {
    Log-Line 'Git repository was not detected. Run data remains local.'
    exit 3
}

$DirtyBefore = @(& git -C $Root status --porcelain 2>&1)
$RunRelRegex = [regex]::Escape($RunRel)
$UnrelatedDirty = @($DirtyBefore | Where-Object { $_ -and ($_ -notmatch $RunRelRegex) })

& git -C $Root add -- $RunRel
if ($LASTEXITCODE -ne 0) {
    Log-Line 'git add failed. Run data remains local.'
    exit 4
}

& git -C $Root diff --cached --quiet
$HasStagedChanges = ($LASTEXITCODE -ne 0)

if ($HasStagedChanges) {
    $Message = '[run] ' + $RunType + ' ' + $RunId + ' rc=' + $ResultCode
    & git -C $Root -c user.name='MK15 Diagnostic Runner' -c user.email='mk15-diagnostics@local.invalid' commit -m $Message
    if ($LASTEXITCODE -ne 0) {
        Log-Line 'git commit failed. Run data remains local or staged.'
        exit 5
    }
    Write-Host ('Created diagnostic commit: ' + $Message)
}
else {
    Write-Host 'No new run files to commit. A push will still be attempted.'
}

& git -C $Root push origin HEAD:main
if ($LASTEXITCODE -eq 0) {
    Write-Host 'Diagnostic run was pushed to GitHub successfully.' -ForegroundColor Green
    exit 0
}

Write-Host 'Initial git push failed.' -ForegroundColor Yellow

if ($UnrelatedDirty.Count -gt 0) {
    Write-Host 'Automatic rebase retry was skipped because unrelated local changes are present.' -ForegroundColor Yellow
    Write-Host 'Run PUBLISH_LAST_RUN.bat later after resolving local changes, network or authentication.' -ForegroundColor Yellow
    exit 6
}

Write-Host 'No unrelated local changes were detected. Trying fetch/rebase and one more push.'
& git -C $Root fetch origin main
if ($LASTEXITCODE -ne 0) {
    Write-Host 'git fetch failed.' -ForegroundColor Yellow
    exit 7
}

& git -C $Root rebase origin/main
if ($LASTEXITCODE -ne 0) {
    Write-Host 'git rebase failed. Resolve the repository state manually, then run PUBLISH_LAST_RUN.bat.' -ForegroundColor Yellow
    exit 8
}

& git -C $Root push origin HEAD:main
if ($LASTEXITCODE -eq 0) {
    Write-Host 'Diagnostic run was pushed to GitHub after rebase.' -ForegroundColor Green
    exit 0
}

Write-Host 'Second git push failed. Run data and its local commit are preserved.' -ForegroundColor Yellow
exit 9
