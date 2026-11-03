# Runs the plant under load and says afterwards what happened.
#
# The site is driven by the server's own load generator (`amhs.simulation.load.*`): work written
# between ports the way a host would write it, with a watch kept over the floor. This script starts
# a server for the run, sets the site to a state that can actually work, polls the report while it
# runs, and ends with the three things worth knowing -- what got through, how the control loops kept
# their periods, and whether anything was left behind (scripts/load-invariants.sql).
#
# It uses SQL for the setup rather than the screens, because what it sets is the shape of the run:
# every vehicle in automatic work, dispatch on, the restart check closed. The flags it changes are
# put back at the end.
#
#   ./scripts/load-run.ps1 -Minutes 20 -PerMinute 240 -PerVehicle 2
#
# It ends with a verdict and an exit code, so the same run can be a regression rather than a
# reading: 0 when the floor held, 1 when something was wrong with it, 2 when the run cannot be
# judged (the site stopped progressing, or this machine slept through part of it). The last one is
# deliberately not a failure -- a measurement that did not happen is not evidence of a defect.
#
# Against the isolated test cluster by default, so a run cannot disturb the development database.
# -Database dev runs it against the development database instead, which is where a site with a
# real-scale map usually lives.
param(
 [int]$Minutes=10,
 [double]$PerMinute=240,
 [int]$PerVehicle=2,
 # What makes this a regression rather than a reading. The run ends with a verdict and this script
 # exits non-zero when the floor broke, so it can be put in front of a release instead of being
 # read by somebody who already knows what the numbers should look like.
 #
 # -1 decides from the length of the run: a run too short for anything to finish cannot be judged
 # on what finished. On the shipped large-site map the median order takes minutes, so under eight
 # minutes this asks only that nothing went wrong, not that something went right.
 [int]$MinCompleted=-1,
 # How far a control loop's longest gap may exceed its period before it counts as a failure. The
 # simulation's own loops are not judged: the sweep's work grows with the traffic and the motion
 # step shares its thread by design, and neither loop exists in an operating plant.
 [double]$MaxGapFactor=4,
 # load: a database of this harness's own on the isolated cluster, made if it is not there and
 # migrated by the server as it starts. The default, because it is the only one nothing else writes
 # to -- the suite leaves work in `test`, and a development database has somebody's own site in it.
 [ValidateSet('load','test','dev')][string]$Database='load',
 [int]$Port=8099,
 [long]$Site=1,
 [int]$EverySeconds=30,
 # Releases road and assignments left behind by work that finished before this run. Off by
 # default: measuring a floor that is already wrong tells you nothing about the run.
 [switch]$ClearStale,
 # A map to put into use before the run, which is how a run is repeated on a known layout.
 # Needs an administrator, because changing the track is an administrator's act.
 [string]$Map,
 [string]$User,
 [string]$Password,
 [string]$PostgresBin='C:\Program Files\PostgreSQL\18\bin'
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root '.local/load-runs'
New-Item -ItemType Directory -Force $out | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$log="$out/server-$stamp.log"
$report="$out/report-$stamp.txt"

# --- where the run happens -----------------------------------------------------------------------
if($Database -ne 'dev'){
 $testRoot=Join-Path $root '.local/postgres-test'
 $dataPath=Join-Path $testRoot 'data'
 $pgPort=55432
 $credential=Import-Clixml -LiteralPath (Join-Path $testRoot 'credential.xml')
 $dbUser='amhs_test_admin'
 $dbPassword=$credential.GetNetworkCredential().Password
 $dbName=$(if($Database -eq 'load'){'amhs_load'}else{'amhs_test'})
 $startedCluster=$false
 if(-not (Get-NetTCPConnection -LocalPort $pgPort -State Listen -ErrorAction SilentlyContinue)){
  $startArgs=@('-D',('"'+$dataPath+'"'),'-l',('"'+$testRoot+'/postgres.log"'),'-o',('"-h 127.0.0.1 -p '+$pgPort+'"'),'-w','start')
  $p=Start-Process -FilePath "$PostgresBin/pg_ctl.exe" -ArgumentList $startArgs -WindowStyle Hidden -PassThru `
   -RedirectStandardOutput "$testRoot/start.out" -RedirectStandardError "$testRoot/start.err"
  $null=$p.Handle; $p.WaitForExit()
  if($p.ExitCode -ne 0){ Get-Content "$testRoot/start.err"; throw 'test cluster start failed' }
  $startedCluster=$true
 }
 $env:PGPASSWORD=$dbPassword
 $exists=& "$PostgresBin/psql.exe" -X -w -h 127.0.0.1 -p $pgPort -U $dbUser -d postgres -At `
  -c "SELECT 1 FROM pg_database WHERE datname='$dbName'"
 # A run that brings its own layout starts on an empty database, not merely a clear floor.
 #
 # Importing a map makes a revision and a fleet to go with it, and nothing ever removed the old
 # ones. After twenty runs this database held twenty revisions, 9,844 nodes and 906 vehicle
 # profiles where 75 were in use -- and the simulation sweep reads every profile of the site on
 # every pass, so a pass went from 1.5s to 19.6s and five long runs in a row measured nothing but
 # that. Dropping costs a migration at startup, which is seconds.
 #
 # Only with -Map: a run without one uses the layout already in place, and dropping would take it.
 if($exists -eq '1' -and $Map){
  & "$PostgresBin/psql.exe" -X -w -h 127.0.0.1 -p $pgPort -U $dbUser -d postgres `
   -c "DROP DATABASE $dbName" | Out-Null
  if($LASTEXITCODE -ne 0){ throw "could not drop $dbName; something is still attached to it" }
  Write-Host "dropped $dbName so this run starts on an empty database"
  $exists=$null
 }
 if($exists -ne '1'){
  & "$PostgresBin/createdb.exe" -w -h 127.0.0.1 -p $pgPort -U $dbUser $dbName
  Write-Host "made $dbName; the server will migrate it as it starts"
 }
}else{
 $connection=Get-Content -LiteralPath (Join-Path $root '.local/postgres-connection.json') -Raw | ConvertFrom-Json
 $credential=Import-Clixml -LiteralPath (Join-Path $root '.local/postgres-credential.xml')
 $pgPort=$connection.Port
 $dbUser=$credential.UserName
 $dbPassword=$credential.GetNetworkCredential().Password
 $dbName=$connection.DevDatabase
 $startedCluster=$false
}
$env:PGPASSWORD=$dbPassword
function Sql([string]$statement){
 $answer=& "$PostgresBin/psql.exe" -X -w -q -t -A -h 127.0.0.1 -p $pgPort -U $dbUser -d $dbName -c $statement
 if($LASTEXITCODE -ne 0){ throw "psql failed: $statement" }
 return $answer
}

# --- nobody else driving this floor --------------------------------------------------------------
# Another server on the same database drives the same site: its sweep moves the vehicles this run is
# measuring, and -- the way this was found -- its start opens a restart check, which stops the site
# under test. Half an hour of the first real run was that, and the numbers looked like the control's.
$others=[int](Sql @"
SELECT count(*) FROM pg_stat_activity
 WHERE datname=current_database() AND application_name LIKE 'PostgreSQL JDBC%'
"@)
if($others -gt 0){
 # Unjudged, not failed, and this is not a quibble about exit codes. A scheduled run catching up a
 # missed one lands on whatever the machine is doing at the time -- it happened, against a run
 # started by hand -- and the one that loses the race did not measure anything. Reporting that as
 # a failure of the floor would put a red result against a floor nobody looked at.
 Write-Host "UNJUDGED  another application ($others connection(s)) is attached to $dbName; stop it first -- two servers drive the same site and the run would measure both"
 if($startedCluster){ & "$PostgresBin/pg_ctl.exe" -D $dataPath -m fast -w stop | Out-Null }
 $env:PGPASSWORD=''
 exit 2
}

# --- the floor the previous run left ------------------------------------------------------------
# Before the server, not after it. A revision cannot be put into use over open work, and rightly
# so -- swapping the ground under a job is the one change that cannot be undone by editing it
# back -- so the previous run's work is ended first; on a layout about to be replaced it means
# nothing anyway, and a harness that cannot be run twice is not a harness. Doing it with the
# server already up deadlocked against its own sweep, which was working on those very rows.
#
# On this harness's own database it happens whether or not the layout is being replaced, because
# the previous run's work does not just sit there: it fills the open-work cap, so the generator
# writes almost nothing, and then the old orders finish during this run and are counted as its
# own -- with durations that began in the run before. The second run of this measured exactly
# that (one order written in the first minute against 240 a minute asked for), which is the
# measuring device being wrong again rather than the floor.
#
# A development or suite database is left alone: the work in it is somebody's, not this run's.
if((($Database -eq 'load') -or $Map) -and (Sql "SELECT to_regclass('transport.order') IS NOT NULL") -eq 't'){
 $ending=Sql @"
UPDATE transport.order SET status='CANCELLED',terminal_at=now(),cancel_reason='load-run: a run starts on a clear floor'
 WHERE site_id=$Site AND status NOT IN ('COMPLETED','CANCELLED','FAILED') RETURNING id
"@
 $null=Sql "UPDATE transport.assignment SET released_at=now(),release_reason='LOAD_RUN_RESET' WHERE released_at IS NULL"
 # The controller's own moves too -- charging, giving way, going home. They are not orders and they
 # hold the activation just as firmly (MOVES_STILL_OPEN).
 $null=Sql "UPDATE transport.relocation SET finished_at=now(),finish_reason='LOAD_RUN_RESET' WHERE site_id=$Site AND finished_at IS NULL"
 $null=Sql "UPDATE traffic.node_reservation SET released_at=now(),release_reason='LOAD_RUN_RESET' WHERE site_id=$Site AND released_at IS NULL"
 $null=Sql "DELETE FROM traffic.reservation_wait WHERE site_id=$Site"
 if($ending){ Write-Host "ended $(($ending | Measure-Object).Count) order(s) left by an earlier run; a run starts on a clear floor" }
}

# --- the server for this run ---------------------------------------------------------------------
$jdk=Get-ChildItem "$root/.tools" -Directory -Filter 'jdk-*' | Select-Object -First 1
$env:JAVA_HOME=$jdk.FullName
$env:AMHS_DB_USER=$dbUser
$env:AMHS_DB_PASSWORD=$dbPassword
$env:AMHS_DB_URL="jdbc:postgresql://127.0.0.1:$pgPort/$dbName"
$arguments=@(
 "-Damhs.simulation.load.per-minute=$PerMinute",
 "-Damhs.simulation.load.per-vehicle=$PerVehicle",
 # A server that starts while work is in progress opens the restart check, and a site waiting for
 # that comparison progresses nothing -- which is right, and which would make every run of this
 # ten quiet minutes. The comparison is a question for a person about a real floor; this floor is
 # the one this run is about to make.
 '-Damhs.restart-check.on-start=false',
 "-Dserver.port=$Port",
 "-Damhs.device.mappings=$root/docs/sample-mappings",
 '-jar',"$root/server/target/amhs-server-0.1.0.jar")
# --- stay awake for the measurement ---------------------------------------------------------------
# A measurement a sleeping machine can ruin should ask the machine to stay up, and it has to ask in
# the way this machine listens to.
#
# `SetThreadExecutionState` was the first attempt and it is not enough. On a laptop with S0 low
# power idle -- Modern Standby, which is most of them now -- that call holds off the old S3 sleep
# that such a machine no longer has, and the system goes to standby underneath it anyway. Two of
# three two-hour runs proved it: one lost an hour, the next lost two hours and fifteen minutes,
# and the report said NOT AWAKE THROUGHOUT both times.
#
# What such a machine listens to is a power request. `PowerRequestExecutionRequired` is the
# documented way to say "there is work running here". Whether it was granted is said in the line
# this prints, and `powercfg /requests` lists it by name for anybody with an administrator prompt.
#
# Both are asked for: the request for machines that have Modern Standby, the old call for machines
# that do not. Both are requests for the life of this script -- released in the `finally` below,
# with the machine's own power configuration untouched. Neither stops a closed lid or somebody
# choosing Sleep, and the report says so when that happens.
Add-Type -Namespace Amhs -Name Power -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)]
public static extern uint SetThreadExecutionState(uint esFlags);

[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
public struct ReasonContext { public uint Version; public uint Flags; public IntPtr Reason; }

[DllImport("kernel32.dll", SetLastError = true)]
public static extern IntPtr PowerCreateRequest(ref ReasonContext context);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool PowerSetRequest(IntPtr request, int type);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool PowerClearRequest(IntPtr request, int type);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool CloseHandle(IntPtr handle);

/** POWER_REQUEST_CONTEXT_VERSION 0, _SIMPLE_STRING 1, PowerRequestExecutionRequired 3. */
public static IntPtr KeepRunning(string why) {
 var context = new ReasonContext { Version = 0, Flags = 1, Reason = Marshal.StringToHGlobalUni(why) };
 var request = PowerCreateRequest(ref context);
 if (request == IntPtr.Zero || request == new IntPtr(-1)) return IntPtr.Zero;
 if (!PowerSetRequest(request, 3)) { CloseHandle(request); return IntPtr.Zero; }
 return request;
}
public static void LetGo(IntPtr request) {
 if (request == IntPtr.Zero) return;
 PowerClearRequest(request, 3);
 CloseHandle(request);
}
'@
# 0x80000000 and 0x00000001 written as numbers: PowerShell reads a literal 0x80000000 as a
# negative Int32, and the call refuses it.
$ES_CONTINUOUS=[uint32]2147483648; $ES_SYSTEM_REQUIRED=[uint32]1
$awake=[Amhs.Power]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED)
$running=[Amhs.Power]::KeepRunning("AMHS load run: $Minutes minute(s) of measurement")
if($running -eq [IntPtr]::Zero -and $awake -eq 0){
 Write-Host 'could not ask this machine to stay awake; it may sleep mid-run'
}elseif($running -eq [IntPtr]::Zero){
 Write-Host 'asked this machine to stay awake (old call only; a Modern Standby machine may sleep anyway)'
}else{
 Write-Host 'asked this machine to keep running until the run ends (an execution power request)'
}

# On battery is worth saying out loud, because it is not a detail of the machine -- it is the
# measurement. A laptop on battery runs its processor at base clock with no turbo, and what that
# costs is the simulator: the same floor and the same code took 1.9s a sweep pass on mains and
# 19.9s on battery, which is a floor stepped thirteen times slower than it is modelled to be.
# Nothing above it is wrong, and nothing above it can be measured either.
#
# Said rather than refused: somebody may want a short run on battery, and the scheduled task
# already declines to start one by itself.
$onBattery=$null -ne (Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue |
 Where-Object { $_.BatteryStatus -eq 1 } | Select-Object -First 1)
if($onBattery){
 Write-Host "this machine is on battery; the simulator will run well behind its period, so a run of $Minutes minute(s) is unlikely to measure anything -- plug it in"
}

$runStarted=(Sql 'SELECT clock_timestamp()').Trim()
$changedVehicles=@()
$server=Start-Process -FilePath "$($jdk.FullName)/bin/java.exe" -ArgumentList $arguments -PassThru `
 -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err"
try{
 $base="http://127.0.0.1:$Port/api/v1/sites/$Site"
 $ready=$false
 foreach($i in 1..60){
  Start-Sleep -Seconds 2
  try{ $null=Invoke-RestMethod "$base/overview" -TimeoutSec 3; $ready=$true; break }catch{ }
 }
 if(-not $ready){ Get-Content $log -Tail 30; throw 'the server did not come up' }

 # A layout to run on, put into use through the screens' own path rather than by writing rows.
 # First, because everything after this is about the site the layout describes: its vehicles are
 # the ones to put into automatic work, and its ports are what the work is written between.
 if($Map){
  if(-not $User){ throw 'a map needs -User and -Password: putting a revision into use is an administrator act' }
  $token=(Invoke-RestMethod "$base/session" -Method Post -ContentType 'application/json' `
   -Body (@{ code=$User; password=$Password } | ConvertTo-Json)).token
  $head=@{ Authorization="Bearer $token" }
  $revision=Invoke-RestMethod "$base/configuration/topology/import" -Method Post -Headers $head `
   -ContentType 'application/json' -Body (Get-Content -LiteralPath $Map -Raw -Encoding utf8)
  Write-Host "imported $Map as revision $($revision.id): $($revision.nodes) nodes, $($revision.ports) ports, $($revision.vehicles) vehicles"
  $null=Invoke-RestMethod "$base/configuration/system/mode" -Method Put -Headers $head `
   -ContentType 'application/json' -Body '{"mode":"MAINTENANCE"}'
  # The refusal carries the reason and nothing else does, so it is read out rather than left as a
  # bare 409: ORDERS_STILL_OPEN, MOVES_STILL_OPEN and ROADS_STILL_GRANTED each send you somewhere
  # different.
  try{
   $null=Invoke-RestMethod "$base/configuration/topology/$($revision.id)/activate" -Method Post -Headers $head
  }catch{
   $said=''
   try{ $said=(New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() }catch{ }
   throw "the revision could not be put into use: $said"
  }
  $null=Invoke-RestMethod "$base/configuration/system/mode" -Method Put -Headers $head `
   -ContentType 'application/json' -Body '{"mode":"RUNNING"}'
  $token=$null; $head=$null
 }

 # Kept so it can be handed back: a load run is a thing done to a site, not a change to it.
 $before=Sql "SELECT run_mode||'|'||auto_dispatch||'|'||sequence_running FROM platform.site_operation WHERE site_id=$Site"
 if(-not $before){ throw "site $Site is not in this database" }
 $scale=Sql "SELECT (SELECT count(*) FROM topology.node n JOIN topology.map_revision m ON m.id=n.revision_id AND m.status='ACTIVE' WHERE n.site_id=$Site)||' nodes, '||(SELECT count(*) FROM topology.port p JOIN topology.map_revision m ON m.id=p.revision_id AND m.status='ACTIVE' WHERE p.site_id=$Site)||' ports, '||(SELECT count(*) FROM fleet.vehicle_profile v JOIN topology.map_revision m ON m.id=v.revision_id AND m.status='ACTIVE' WHERE v.site_id=$Site AND v.enabled)||' vehicles'"

 # Now, and not before: a server that finds work in progress reopens the restart check as it starts,
 # and a site waiting for it does nothing at all. The first run of this script measured ten minutes
 # of exactly that.
 $null=Sql "UPDATE transport.restart_check SET finished_at=now(),outcome='CONFIRMED',finished_by='load-run' WHERE finished_at IS NULL"
 $null=Sql "UPDATE platform.site_operation SET run_mode='SIMULATION',auto_dispatch=TRUE,sequence_running=TRUE,restart_check_since=NULL,mode='RUNNING' WHERE site_id=$Site"
 $changedVehicles=Sql "UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=TRUE WHERE site_id=$Site AND revision_id=(SELECT id FROM topology.map_revision WHERE site_id=$Site AND status='ACTIVE') AND NOT (enabled AND assignment_enabled) RETURNING id"

 # Road held for work that has finished is road nobody will hand back, and on a floor with enough of
 # it the run measures the leftovers rather than the control. A reservation is handed back by having
 # released_at set and the row stays as the history of who held what, so "still held" is that column
 # and nothing else -- the first version of this check counted the whole history and refused to run
 # on a floor that was perfectly clear. The node a vehicle stands on does not count either.
 $stale=[int](Sql @"
SELECT count(*) FROM traffic.node_reservation r JOIN transport.order o ON o.id=r.order_id
 WHERE r.site_id=$Site AND r.released_at IS NULL AND o.status IN ('COMPLETED','CANCELLED','FAILED')
   AND r.node_id <> COALESCE((SELECT v.node_id FROM fleet.vehicle_state v
                               WHERE v.site_id=r.site_id AND v.vehicle_id=r.vehicle_id),-1)
"@)
 if($stale -gt 0){
  Write-Host "$stale node reservation(s) are still held for work that finished:"
  (Sql @"
SELECT r.vehicle_id||' holds '||count(*)||' node(s) for order '||r.order_id||' ('||o.status||', '||min(r.granted_at)::date||')'
 FROM traffic.node_reservation r JOIN transport.order o ON o.id=r.order_id
 WHERE r.site_id=$Site AND r.released_at IS NULL AND o.status IN ('COMPLETED','CANCELLED','FAILED')
   AND r.node_id <> COALESCE((SELECT v.node_id FROM fleet.vehicle_state v
                               WHERE v.site_id=r.site_id AND v.vehicle_id=r.vehicle_id),-1)
 GROUP BY r.vehicle_id,r.order_id,o.status ORDER BY r.vehicle_id
"@) | ForEach-Object { Write-Host "  $_" }
  if(-not $ClearStale){ throw 'refusing to measure a floor that is already blocked; look at the rows above, then re-run with -ClearStale' }
  # Released, not deleted: handing road back is what an operator would do, and the row is the record
  # that it was held.
  $null=Sql @"
UPDATE traffic.node_reservation r SET released_at=now(),release_reason='LOAD_RUN_STALE'
 FROM transport.order o
 WHERE o.id=r.order_id AND r.site_id=$Site AND r.released_at IS NULL
   AND o.status IN ('COMPLETED','CANCELLED','FAILED')
"@
  $null=Sql "DELETE FROM traffic.reservation_wait WHERE site_id=$Site"
  Write-Host 'released them'
 }

 $running=Sql "SELECT run_mode||'|'||auto_dispatch||'|'||sequence_running||'|'||COALESCE(restart_check_since::text,'-') FROM platform.site_operation WHERE site_id=$Site"
 Write-Host "server up on $Port; site was $before, now $running"
 Write-Host "running on: $scale"
 Write-Host "running for $Minutes minute(s) at $PerMinute/min, $PerVehicle per vehicle"

 $deadline=(Get-Date).AddMinutes($Minutes)
 $interrupted=$null
 while((Get-Date) -lt $deadline){
  Start-Sleep -Seconds ([Math]::Min($EverySeconds,[int]((New-TimeSpan -End $deadline).TotalSeconds)+1))
  # A site that has stopped progressing is not a site under load. Said as soon as it happens and the
  # run ends: carrying on would fill the report with minutes of a stopped plant, which is what the
  # first real run of this did for six of its fifteen minutes.
  $why=Sql "SELECT CASE WHEN NOT sequence_running THEN 'SEQUENCE_STOPPED' WHEN restart_check_since IS NOT NULL THEN 'RESTART_CHECK_PENDING' ELSE '' END FROM platform.site_operation WHERE site_id=$Site"
  if($why){
   $interrupted="the site stopped progressing ($why) -- nothing after this point is a measurement of load"
   Write-Host $interrupted
   break
  }
  $load=Invoke-RestMethod "$base/simulation/load" -TimeoutSec 5
  $line="{0:HH:mm:ss}  written {1,5}  refused {2,5}  completed {3,5}  open-max {4,3}  median {5}s  p95 {6}s  same-node {7}  unheld {8}  deadlock {9}" -f `
   (Get-Date),$load.written,$load.refused,$load.completed,$load.maxQueued,$load.medianSeconds,$load.p95Seconds,`
   $load.sameNodeSamples,$load.unheldNodeSamples,$load.deadlockSamples
  Write-Host $line
  Add-Content -LiteralPath $report -Value $line -Encoding utf8
 }

 # --- what happened ------------------------------------------------------------------------------
 $load=Invoke-RestMethod "$base/simulation/load" -TimeoutSec 10
 $timing=Invoke-RestMethod "$base/diagnostics/timing" -TimeoutSec 10
 $summary=@()
 $summary+="load run $stamp  database=$Database  site=$Site  $scale"
 $summary+="asked for $PerMinute/min, up to $PerVehicle open per vehicle, for $Minutes minute(s)"
 # In the report as well as on the screen: somebody reading this file a week later cannot see what
 # the machine was plugged into, and it is the first thing that explains a slow simulator.
 if($onBattery){ $summary+='ON BATTERY: the processor ran at base clock, so the simulator ran behind its period.' }
 if($interrupted){ $summary+="INTERRUPTED: $interrupted" }
 $summary+=($load | Format-List | Out-String).Trim()
 $summary+='--- control loops (ms) ---'
 $summary+=($timing | Select-Object name,passes,targetMs,meanMs,maxMs,meanGapMs,maxGapMs | Format-Table -AutoSize | Out-String).Trim()
 # A machine that went to sleep says so here, in every loop at once.
 #
 # Nothing inside the server can tell a suspended machine from a very slow one, but a gap of
 # minutes in *every* loop is not a slow pass -- it is wall-clock that moved while nothing ran. The
 # first two-hour run spent an hour of it asleep, and without this line its loop figures looked
 # like the system's own (one sweep "pass" of 3,638,883ms, and a mean dragged up with it).
 $asleep=$timing | Where-Object { $_.maxGapMs -gt [Math]::Max(60000,$_.targetMs*30) }
 $slept=($asleep | Measure-Object).Count -ge 2
 if($slept){
  $longest=[int](($asleep | Measure-Object -Property maxGapMs -Maximum).Maximum/1000)
  $summary+="NOT AWAKE THROUGHOUT: every loop lost up to $longest s at once, so this machine was asleep or suspended."
  # It used to say the counts and the invariants still held, and that was wrong. The floor froze
  # and the clock did not: every question of the form "has this been still for a while" then reads
  # the frozen rows as hours old, and a run that slept for two hours reported sixty-five vehicles
  # waiting too long for road when not one of them had been given the chance to move. The work
  # written is wall-clock paced too, so even the counts are not of the run that was asked for.
  $summary+='  Nothing here is a measurement of this system: the floor stopped and the clock did not.'
 }
 $summary | ForEach-Object { Write-Host $_ }
 Add-Content -LiteralPath $report -Value ($summary -join "`n") -Encoding utf8
}finally{
 if($server -and -not $server.HasExited){ $server.CloseMainWindow() | Out-Null; Start-Sleep -Seconds 2 }
 if($server -and -not $server.HasExited){ Stop-Process -Id $server.Id -Force }
 # The invariants are asked after the server has stopped: nothing is moving, so a leftover is a
 # leftover rather than a vehicle mid-stride.
 Start-Sleep -Seconds 2
 Write-Host '--- invariants ---'
 # As of when the floor stopped, not as of when the question is asked. The rows froze when the
 # server did, and "has this been still for three minutes" measured against a running clock keeps
 # finding more of them the longer the asking takes -- the same floor showed 2 vehicles waiting too
 # long at the end of a run and 31 of them half an hour later, without a thing having moved.
 $runEnded=(Sql 'SELECT clock_timestamp()').Trim()
 $invariants=& "$PostgresBin/psql.exe" -X -w -h 127.0.0.1 -p $pgPort -U $dbUser -d $dbName `
  -v "site=$Site" -v 'quiet_minutes=3' -v "since=$runStarted" -v "until=$runEnded" `
  -f "$root/scripts/load-invariants.sql"
 $invariants | ForEach-Object { Write-Host $_ }
 Add-Content -LiteralPath $report -Value ("--- invariants ---`n"+($invariants -join "`n")) -Encoding utf8

 # --- the verdict --------------------------------------------------------------------------------
 # The reading above is only a regression if something decides. What decides is here, and it reads
 # the same output a person reads rather than asking the database a second set of questions -- two
 # sets drift, and the one nobody looks at drifts first.
 #
 # Which invariants must be empty is taken from their own headings: a check whose heading says
 # "(none expected)" has to come back with no rows. So a check added to load-invariants.sql is
 # enforced by having been written, and the two informational ones are left alone.
 $violations=@(); $section=$null
 foreach($line in $invariants){
  if($line -match '^==\s*(.+?)\s*$'){ $section=$Matches[1]; continue }
  if($section -and $line -match '^\((\d+) rows?\)$'){
   if($section -like '*(none expected)*' -and [int]$Matches[1] -gt 0){
    $violations+=("{0} -- {1} row(s)" -f ($section -replace '\s*\(none expected\)',''),$Matches[1])
   }
   $section=$null
  }
 }
 # Two of those questions ask how long something has been still, in wall-clock minutes, and a
 # simulated floor only moves as fast as the simulator steps it. When the simulation sweep cannot
 # finish a pass inside its own period, the floor is running slower than it is modelled to -- a
 # vehicle "waiting three minutes" then means the simulator took three minutes to give anybody a
 # turn, not that the control starved it.
 #
 # One run measured exactly this: the sweep took 11.1s per pass against a 1500ms period (1.4s in
 # every run before it), the motion step drifted to a 2.2s gap against 100ms, and the report came
 # back with seventeen vehicles waiting too long. The device-facing loops were on their periods
 # throughout, so there was nothing wrong with the control -- the machine could not run the model.
 #
 # In OPERATION there is no such loop and the clock on the wall is the floor's own, so this only
 # applies where the simulation loops are in the report at all.
 $sweep=$timing | Where-Object { $_.name -eq 'simulation-sweep' } | Select-Object -First 1
 # Twice its period, not merely over it: a sweep a quarter behind still steps the floor at close to
 # the modelled speed, and a three-minute threshold has room for that. Measured across the runs so
 # far, 1405ms and 1882ms are floors that moved; 11133ms is a floor that did not.
 $simulatorBehind=$sweep -and $sweep.meanMs -gt $sweep.targetMs*2
 # "Fewer than the N this run asks for" belongs on this list too, and the A/B that proved the merge
 # marking costs nothing is what showed it: both twelve-minute runs finished no orders at all and
 # both were called a failure for it, when the floor was simply being stepped thirteen times slower
 # than modelled. How much got through is a question about the simulator's speed in such a run.
 $byTheClock='*waiting on road*','*claimed and never settled*','*completed, fewer than*'
 $unjudgeable=@(); $notes=@()
 if($simulatorBehind){
  $held=$violations | Where-Object { $v=$_; -not ($byTheClock | Where-Object { $v -like $_ }) }
  $setAside=$violations | Where-Object { $v=$_; $byTheClock | Where-Object { $v -like $_ } }
  $violations=$held
  $notes+=("the simulator could not keep up ({0}ms a pass against a {1}ms period), so the floor ran " `
    -f [int]$sweep.meanMs,$sweep.targetMs)+'slower than it is modelled to; questions about how long something stood still are not judged'
  $notes+=$setAside | ForEach-Object { "  set aside: $_" }
 }
 if(-not $load){ $unjudgeable+='the run produced no reading at all' }
 elseif($interrupted){ $unjudgeable+='the site stopped progressing, so this is not a measurement of load' }
 # A machine that slept takes the whole run with it, not only the loop timings. The floor froze
 # and the clock carried on, so every "has this been still for a while" question answers about the
 # sleep rather than about the floor -- one such run reported sixty-five vehicles waiting too long
 # for road, none of which had been given a chance to move.
 elseif($slept){ $unjudgeable+='this machine slept through part of the run, so the floor stopped while the clock did not' }
 elseif($load.samples -lt 1){ $unjudgeable+='the floor was never looked at (no samples), so the three counters mean nothing' }
 if($load){
  if($load.sameNodeSamples -gt 0){ $violations+="two vehicles on one node, $($load.sameNodeSamples) time(s)" }
  if($load.unheldNodeSamples -gt 0){ $violations+="a vehicle on road it does not hold, $($load.unheldNodeSamples) time(s)" }
  if($load.deadlockSamples -gt 0){ $violations+="deadlock seen $($load.deadlockSamples) time(s)" }
  if($load.refused -gt 0){ $violations+="$($load.refused) order(s) refused -- the generator stays within the open-work cap, so a refusal is the control saying no" }
  if($load.failed -gt 0){ $violations+="$($load.failed) order(s) failed" }
  if($load.recovery -gt 0){ $violations+="$($load.recovery) order(s) left awaiting recovery" }
  # Long enough for something to finish? A short run is judged on what went wrong, not on what
  # went right, because on a floor this size nothing has had time to go right yet.
  $floor=$(if($MinCompleted -ge 0){$MinCompleted}elseif($Minutes -ge 8){1}else{0})
  if($load.completed -lt $floor){ $violations+="$($load.completed) order(s) completed, fewer than the $floor this run asks for" }
 }
 # The loops, except the simulator's own and except a run that slept through part of itself.
 #
 # Two questions, and the first is the one that matters. **Did the loop hold its period**, which is
 # the mean gap: a loop waiting behind another waits every time, and that is what the defect this
 # instrument was built for looked like -- a 100ms device read sitting behind a sweep, every pass.
 # A single late pass is the other question, and a weaker one.
 if($timing -and -not $slept){
  # One stall that every loop lost at once is this machine, not a starved loop. Nothing inside the
  # server can tell a busy machine from a slow pass, but a spike that appears in several loops
  # simultaneously is wall-clock that went somewhere else -- the smaller cousin of NOT AWAKE
  # THROUGHOUT. A loop starved by another loop is late *alone*, and late *on average*.
  #
  # Counted by thread and not by loop: the simulation's sweep and its motion step share one thread
  # on purpose, so they always lose time together, and a spike in both of them is that thread being
  # busy -- which is the simulator's own business. Two *independent* threads losing it at the same
  # moment is the machine.
  $spiked=$timing | Where-Object { $_.maxGapMs - $_.meanGapMs -ge 1000 }
  $threads=$spiked | ForEach-Object { if($_.name -like 'simulation*'){ 'simulation' }else{ $_.name } } |
   Select-Object -Unique
  $machineStalled=($threads | Measure-Object).Count -ge 2
  foreach($loop in $timing){
   if($loop.name -like 'simulation*'){ continue }
   # Half again, and at least 100ms over: a period is kept or it is not, and asking for exactness
   # from a general-purpose machine would fail every run on rounding.
   $period=[Math]::Max($loop.targetMs*1.5,$loop.targetMs+100)
   if($loop.meanGapMs -gt $period){
    $violations+=("{0} averaged {1}ms between passes against a {2}ms period -- it is waiting behind something" `
     -f $loop.name,[int]$loop.meanGapMs,$loop.targetMs)
   }
   # A floor of a second with it: the first pass of a run competes with everything else starting
   # up, and a 600ms gap on a 200ms loop at second zero is a server booting, not a loop starved.
   $allowed=[Math]::Max($loop.targetMs*$MaxGapFactor,1000)
   if($loop.maxGapMs -gt $allowed -and -not $machineStalled){
    $violations+=("{0} waited {1}ms for one pass against a {2}ms period" -f $loop.name,$loop.maxGapMs,$loop.targetMs)
   }
  }
  if($machineStalled){
   # A note, not a reason the run cannot be judged. Everything else here -- the invariants, the
   # counters, the periods the loops held on average -- is still this system's own answer, and
   # burying a real failure under "the machine was busy" is how an instrument stops being read.
   $worst=[int](($spiked | Measure-Object -Property maxGapMs -Maximum).Maximum)
   $notes+=("single-pass timings not judged: $(($threads | Measure-Object).Count) threads lost up to "+
     "${worst}ms at the same moment, so something else on this machine had the processor")
  }
 }
 # Unjudged comes before failed, and the order is the point. A run that was not a measurement of
 # load cannot be evidence of a defect in it -- and what a stopped site leaves behind looks like a
 # fault by these questions while being exactly the designed answer: road ahead of a move that was
 # already delivered stays with that vehicle (decision 72), and a site that stops cancels the work
 # it was for. Calling that a failure would train somebody to ignore the verdict.
 $verdict=@('--- verdict ---')
 if($unjudgeable){
  $verdict+=$unjudgeable | ForEach-Object { "UNJUDGED  $_" }
  $verdict+=$violations | ForEach-Object { "    also seen, not judged: $_" }
  $exitCode=2
 }elseif($violations){
  $verdict+=$violations | ForEach-Object { "FAIL  $_" }
  $exitCode=1
 }else{
  $verdict+="PASS  nothing was left behind, and nothing was seen that should not be"
  if($load.completed -lt 1){ $verdict+='      and nothing finished, which this run was too short to ask for' }
  $exitCode=0
 }
 $verdict+=$notes | ForEach-Object { "NOTE  $_" }
 $verdict | ForEach-Object { Write-Host $_ }
 Add-Content -LiteralPath $report -Value ($verdict -join "`n") -Encoding utf8

 # The site goes back the way it was found -- and a tidy-up that fails says so instead of throwing.
 # A cleanup step that throws replaces the error it was cleaning up after, which is how a run that
 # failed at the map import was reported as a malformed UPDATE and took a while to understand.
 try{
  if($before -and ($before -split '\|').Count -ge 3){
   $parts=$before -split '\|'
   $null=Sql "UPDATE platform.site_operation SET run_mode='$($parts[0])',auto_dispatch=$($parts[1]),sequence_running=$($parts[2]) WHERE site_id=$Site"
  }else{
   Write-Host 'the site state was never read, so it is left as it stands'
  }
  if($changedVehicles){
   foreach($id in $changedVehicles){ if($id){ $null=Sql "UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE id=$id" } }
   Write-Host "put $(($changedVehicles | Measure-Object).Count) vehicle(s) back out of automatic work"
  }
 }catch{
  Write-Host "the site could not be handed back: $($_.Exception.Message)"
 }
 if($startedCluster){ & "$PostgresBin/pg_ctl.exe" -D $dataPath -m fast -w stop | Out-Null }
 # The machine may go back to sleeping on its own schedule; nothing of its configuration was changed.
 try{ $null=[Amhs.Power]::SetThreadExecutionState([uint32]2147483648) }catch{ }
 try{ [Amhs.Power]::LetGo($running) }catch{ }
 $env:PGPASSWORD=''
 Write-Host "report: $report"
}
# After the tidy-up, not inside it: an exit from a finally block would leave the site half returned.
exit $(if($null -ne $exitCode){$exitCode}else{2})
