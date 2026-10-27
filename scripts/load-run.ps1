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
# Against the isolated test cluster by default, so a run cannot disturb the development database.
# -Database dev runs it against the development database instead, which is where a site with a
# real-scale map usually lives.
param(
 [int]$Minutes=10,
 [double]$PerMinute=240,
 [int]$PerVehicle=2,
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
 throw "another application ($others connection(s)) is attached to $dbName; stop it first -- two servers drive the same site and the run would measure both"
}

# --- the floor the previous run left ------------------------------------------------------------
# Before the server, not after it. A revision cannot be put into use over open work, and rightly
# so -- swapping the ground under a job is the one change that cannot be undone by editing it
# back -- so the previous run's work is ended first; on a layout about to be replaced it means
# nothing anyway, and a harness that cannot be run twice is not a harness. Doing it with the
# server already up deadlocked against its own sweep, which was working on those very rows.
if($Map -and (Sql "SELECT to_regclass('transport.order') IS NOT NULL") -eq 't'){
 $ending=Sql @"
UPDATE transport.order SET status='CANCELLED',terminal_at=now(),cancel_reason='load-run: layout replaced'
 WHERE site_id=$Site AND status NOT IN ('COMPLETED','CANCELLED','FAILED') RETURNING id
"@
 $null=Sql "UPDATE transport.assignment SET released_at=now(),release_reason='LOAD_RUN_RESET' WHERE released_at IS NULL"
 # The controller's own moves too -- charging, giving way, going home. They are not orders and they
 # hold the activation just as firmly (MOVES_STILL_OPEN).
 $null=Sql "UPDATE transport.relocation SET finished_at=now(),finish_reason='LOAD_RUN_RESET' WHERE site_id=$Site AND finished_at IS NULL"
 $null=Sql "UPDATE traffic.node_reservation SET released_at=now(),release_reason='LOAD_RUN_RESET' WHERE site_id=$Site AND released_at IS NULL"
 $null=Sql "DELETE FROM traffic.reservation_wait WHERE site_id=$Site"
 if($ending){ Write-Host "ended $(($ending | Measure-Object).Count) order(s) left by an earlier run" }
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
 if($interrupted){ $summary+="INTERRUPTED: $interrupted" }
 $summary+=($load | Format-List | Out-String).Trim()
 $summary+='--- control loops (ms) ---'
 $summary+=($timing | Select-Object name,passes,targetMs,meanMs,maxMs,meanGapMs,maxGapMs | Format-Table -AutoSize | Out-String).Trim()
 $summary | ForEach-Object { Write-Host $_ }
 Add-Content -LiteralPath $report -Value ($summary -join "`n") -Encoding utf8
}finally{
 if($server -and -not $server.HasExited){ $server.CloseMainWindow() | Out-Null; Start-Sleep -Seconds 2 }
 if($server -and -not $server.HasExited){ Stop-Process -Id $server.Id -Force }
 # The invariants are asked after the server has stopped: nothing is moving, so a leftover is a
 # leftover rather than a vehicle mid-stride.
 Start-Sleep -Seconds 2
 Write-Host '--- invariants ---'
 $invariants=& "$PostgresBin/psql.exe" -X -w -h 127.0.0.1 -p $pgPort -U $dbUser -d $dbName `
  -v "site=$Site" -v 'quiet_minutes=3' -v "since=$runStarted" -f "$root/scripts/load-invariants.sql"
 $invariants | ForEach-Object { Write-Host $_ }
 Add-Content -LiteralPath $report -Value ("--- invariants ---`n"+($invariants -join "`n")) -Encoding utf8

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
 $env:PGPASSWORD=''
 Write-Host "report: $report"
}
