# The judged load run, as something a schedule can start and nobody has to watch.
#
# Windows Task Scheduler keeps the exit code as the task's Last Run Result, which is what makes
# this a regression rather than a reading: 0 the floor held, 1 it did not, 2 the run could not be
# judged (see scripts/load-run.ps1). Everything the run said goes to a log beside the report,
# because a scheduled run has no screen and the verdict alone does not say why.
#
# Registered with scripts/load-regression-schedule.ps1. Run it by hand the same way:
#
#   ./scripts/load-regression-task.ps1
#
# It runs as the person who registered it, and it has to: the isolated cluster's password is
# stored with that account's own key (Export-Clixml), so another account cannot read it.
param(
 [int]$Minutes=12,
 [double]$PerMinute=240,
 [int]$PerVehicle=2,
 # How many of these logs to keep. A nightly run leaves a month of them otherwise, and the reports
 # in .local/load-runs are the record that matters.
 [int]$KeepLogs=14,
 [string]$PostgresBin='C:\Program Files\PostgreSQL\18\bin'
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root '.local/load-runs'
New-Item -ItemType Directory -Force $out | Out-Null
$log=Join-Path $out ("scheduled-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
# The run is captured whole and written once, in one encoding. Redirecting a child's streams
# straight into the file (`*>>`) writes UTF-16 in this PowerShell while everything else here
# writes UTF-8, and a log that is half one and half the other is a log nobody can read.
#
# It does mean nothing appears here until the run ends. The harness's own report in
# .local/load-runs is written line by line while it runs, which is where to look meanwhile.
$said=& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'load-run.ps1') `
  -Minutes $Minutes -PerMinute $PerMinute -PerVehicle $PerVehicle -PostgresBin $PostgresBin `
  *>&1
$verdict=$LASTEXITCODE

$lines=@("started -- $Minutes minute(s) at $PerMinute/min, $PerVehicle per vehicle")
$lines+=$said | ForEach-Object { $_.ToString() }
$lines+=''
$lines+="finished $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') with exit code $verdict ($(switch($verdict){0{'PASS'}1{'FAIL'}2{'UNJUDGED'}default{'the run did not get as far as a verdict'}}))"
$lines | Out-File -LiteralPath $log -Append -Encoding utf8

# Said to the host as well, so a task started by hand shows its verdict without opening the log.
$lines | Select-Object -Last 14 | ForEach-Object { Write-Host $_ }
Write-Host "log: $log"

Get-ChildItem $out -Filter 'scheduled-*.log' | Sort-Object LastWriteTime -Descending |
 Select-Object -Skip $KeepLogs | Remove-Item -Force -ErrorAction SilentlyContinue

exit $verdict
