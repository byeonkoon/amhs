# Read-only checkout verification. Does not fetch, switch branches or modify files.
[CmdletBinding()]
param(
    [ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedCommit,
    [switch]$CheckRemote
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
function Read-Git([string[]]$GitArgs) {
    $result = & git -C $projectRoot @GitArgs
    if ($LASTEXITCODE -ne 0) { throw 'Git inspection failed; synchronization is not verified.' }
    return ($result -join "`n").Trim()
}
$head = Read-Git @('rev-parse', 'HEAD')
$branch = Read-Git @('branch', '--show-current')
$status = Read-Git @('status', '--porcelain=v1', '--untracked-files=all')
$expectedMatches = $null
if ($ExpectedCommit) { $expectedMatches = $head -eq $ExpectedCommit }
$remoteMatches = $null
if ($CheckRemote) {
    if (!$branch) { throw 'Detached HEAD: remote branch cannot be inferred.' }
    $remoteRow = Read-Git @('ls-remote', '--exit-code', 'origin', "refs/heads/$branch")
    $remoteMatches = ($remoteRow -split '\s+')[0] -eq $head
}
# Detect a shared checkout being switched or edited during inspection.
$stable = (Read-Git @('rev-parse', 'HEAD')) -eq $head -and
    (Read-Git @('branch', '--show-current')) -eq $branch -and
    (Read-Git @('status', '--porcelain=v1', '--untracked-files=all')) -eq $status
$ready = !$status -and $stable -and $expectedMatches -ne $false -and $remoteMatches -ne $false
[ordered]@{
    branch = $branch
    commit = $head
    dirty = [bool]$status
    expectedCommitMatches = $expectedMatches
    remoteCommitMatches = $remoteMatches
    checkoutStableDuringCheck = $stable
    checksPassed = [bool]$ready
    note = 'Null means not checked. No build, database, device or other-PC verification is implied.'
} | ConvertTo-Json
if (!$ready) { exit 1 }
