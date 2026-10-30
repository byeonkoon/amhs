# Puts the judged load run on a schedule, and takes it off again.
#
#   ./scripts/load-regression-schedule.ps1                 # every day at 03:30
#   ./scripts/load-regression-schedule.ps1 -At 02:00 -Weekly Sunday
#   ./scripts/load-regression-schedule.ps1 -Show
#   ./scripts/load-regression-schedule.ps1 -Remove
#
# What it registers is scripts/load-regression-task.ps1, whose exit code becomes the task's Last
# Run Result: 0 the floor held, 1 it did not, 2 the run could not be judged.
#
# Three things are deliberate.
#
# It runs as the person who registers it, when they are logged on, and asks for no password. The
# isolated cluster's password is stored with that account's own key, so another account could not
# read it anyway -- and a scheduled task that needs a stored password is a password in one more
# place.
#
# It does not wake the machine. A missed run is started at the next opportunity instead
# (-StartWhenAvailable), because a regression is worth a machine's morning, not its night. Add
# waking with -WakeToRun if the schedule matters more than that.
#
# It will not start on battery, and will not be killed if the power goes while it is running. A
# run cut in half leaves open work and a cluster behind; the next run tidies that, but a verdict
# from a run that was stopped means nothing either way. On a laptop this means the run waits for
# the mains -- the task sits "Queued" until then and starts when the power comes back, which is a
# saturating twelve minutes not spent on somebody's battery. -OnBatteryToo says otherwise.
param(
 [string]$At='03:30',
 # A day name makes it weekly; left out, it is daily.
 [ValidateSet('Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday')][string]$Weekly,
 [int]$Minutes=12,
 [switch]$WakeToRun,
 # Start even on battery. Off, because this run saturates a floor for twelve minutes.
 [switch]$OnBatteryToo,
 [switch]$Show,
 [switch]$Remove,
 [string]$TaskName='AMHS load regression',
 [string]$TaskPath='\AMHS\'
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent

$existing=Get-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -ErrorAction SilentlyContinue
if($Show){
 if(-not $existing){ Write-Host "not registered"; exit 1 }
 $info=Get-ScheduledTaskInfo -InputObject $existing
 Write-Host "$($existing.TaskPath)$($existing.TaskName) -- $($existing.State)"
 $existing.Triggers | ForEach-Object { Write-Host "  trigger: $($_.CimClass.CimClassName) at $($_.StartBoundary)" }
 Write-Host "  action : $($existing.Actions[0].Execute) $($existing.Actions[0].Arguments)"
 # The scheduler's own codes come through here too, and 267011 ("has not run yet") next to a
 # legend that only explains 0, 1 and 2 reads as a failure nobody can look up.
 $said=switch($info.LastTaskResult){
  0 {'PASS -- the floor held'}
  1 {'FAIL -- something was wrong with the floor'}
  2 {'UNJUDGED -- the run could not be judged'}
  267011 {'has not run yet'}
  267009 {'running now'}
  267014 {'stopped before it finished'}
  default {"exit code $($info.LastTaskResult) -- the run did not get as far as a verdict"}
 }
 Write-Host "  last   : $($info.LastRunTime) -> $said"
 $recent=Get-ChildItem (Join-Path $root '.local/load-runs') -Filter 'scheduled-*.log' -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
 if($recent){ Write-Host "  log    : $($recent.FullName)" }
 Write-Host "  next   : $($info.NextRunTime)"
 exit 0
}
if($Remove){
 if(-not $existing){ Write-Host "not registered; nothing to remove"; exit 0 }
 Unregister-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -Confirm:$false
 Write-Host "removed $TaskPath$TaskName"
 exit 0
}

$when=[datetime]::ParseExact($At,'HH:mm',$null)
$trigger=$(if($Weekly){
 New-ScheduledTaskTrigger -Weekly -DaysOfWeek $Weekly -At $when
}else{
 New-ScheduledTaskTrigger -Daily -At $when
})
$action=New-ScheduledTaskAction -Execute 'powershell.exe' `
 -Argument ("-NoProfile -ExecutionPolicy Bypass -File `"$root\scripts\load-regression-task.ps1`" -Minutes $Minutes") `
 -WorkingDirectory $root
$settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries `
 -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 1) `
 -WakeToRun:$WakeToRun -AllowStartIfOnBatteries:$OnBatteryToo
# The account that registers it, when it is logged on, with no password asked for or stored.
$principal=New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive

if($existing){ Unregister-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -Confirm:$false }
$null=Register-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -Trigger $trigger `
 -Action $action -Settings $settings -Principal $principal `
 -Description ("The judged load run (scripts/load-regression-task.ps1), $Minutes minute(s). " +
   "Last Run Result is the verdict: 0 the floor held, 1 it did not, 2 the run could not be judged. " +
   "Logs and reports in .local/load-runs.")

$info=Get-ScheduledTaskInfo -TaskPath $TaskPath -TaskName $TaskName
Write-Host "registered $TaskPath$TaskName -- $(if($Weekly){"every $Weekly"}else{"every day"}) at $At, $Minutes minute(s)"
if(-not $OnBatteryToo){ Write-Host "it waits for mains power before starting (-OnBatteryToo to change that)" }
Write-Host "next run  $($info.NextRunTime)"
Write-Host "run it now:  Start-ScheduledTask -TaskPath '$TaskPath' -TaskName '$TaskName'"
Write-Host "see it:      ./scripts/load-regression-schedule.ps1 -Show"
Write-Host "take it off: ./scripts/load-regression-schedule.ps1 -Remove"
