param([double]$PerMinute=12,[long]$Seed=42,[string]$PostgresBin='C:\Program Files\PostgreSQL\18\bin',[int]$Port=55432,
 [ValidatePattern('^amhs_soak[a-z0-9_]*$')][string]$DatabaseName='amhs_soak',[int]$ServerPort=8080)
# A soak run on its own database in the project's isolated cluster: the large layout, five OHT and
# five AGV, simulated, with work written at a steady rate. The database is made fresh every run so a
# run can be repeated; the development and operating databases are never touched.
#
# The server keeps running afterwards. Read the run at GET /api/v1/sites/1/simulation/load, open the
# screens through the web dev server, and stop the server process when done.
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$testRoot=Join-Path $projectRoot '.local/postgres-test'
$dataPath=Join-Path $testRoot 'data'
$credentialPath=Join-Path $testRoot 'credential.xml'
$jar=Join-Path $projectRoot 'server/target/amhs-server-0.1.0.jar'
$java=Get-ChildItem (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-21*' | Select-Object -First 1
if(!$java){throw 'JDK 21 not found under .tools; run scripts/setup-tools.ps1'}
$java=Join-Path $java.FullName 'bin/java.exe'
if(!(Test-Path $jar)){throw 'Server jar not built; run the test or package step first'}
if(!(Test-Path $credentialPath)){throw 'Isolated cluster not set up; run scripts/test-postgres.ps1 once'}
if(Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue){throw "Port $ServerPort already in use"}
if(!(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)){
 $startArgs=@('-D',('"'+$dataPath+'"'),'-l',('"'+$testRoot+'/postgres.log"'),'-o',('"-h 127.0.0.1 -p '+$Port+'"'),'-w','start')
 $start=Start-Process -FilePath "$PostgresBin/pg_ctl.exe" -ArgumentList $startArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$testRoot/soak-start.out" -RedirectStandardError "$testRoot/soak-start.err"
 $null=$start.Handle; $start.WaitForExit()
 if($start.ExitCode -ne 0){throw "Isolated cluster start failed; see $testRoot/soak-start.err"}
}
$credential=Import-Clixml -LiteralPath $credentialPath
$env:PGPASSWORD=$credential.GetNetworkCredential().Password
try{
 & "$PostgresBin/dropdb.exe" -w -h 127.0.0.1 -p $Port -U amhs_test_admin --if-exists $DatabaseName
 & "$PostgresBin/createdb.exe" -w -h 127.0.0.1 -p $Port -U amhs_test_admin $DatabaseName
 if($LASTEXITCODE -ne 0){throw 'Soak database creation failed'}
 $env:AMHS_DB_URL="jdbc:postgresql://127.0.0.1:$Port/$DatabaseName"
 $env:AMHS_DB_USER='amhs_test_admin'
 $env:AMHS_DB_PASSWORD=$env:PGPASSWORD
 function Start-Server([string[]]$extra){
  $arguments=@('-jar',$jar,"--server.port=$ServerPort")+$extra
  $p=Start-Process -FilePath $java -ArgumentList $arguments -WindowStyle Hidden -PassThru `
   -RedirectStandardOutput (Join-Path $projectRoot '.tools/soak-server.out') -RedirectStandardError (Join-Path $projectRoot '.tools/soak-server.err')
  for($i=0;$i -lt 90;$i++){
   Start-Sleep -Milliseconds 1000
   if(Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue){ return $p }
   if($p.HasExited){ throw "Server exited ($($p.ExitCode)); see .tools/soak-server.out" }
  }
  throw 'Server did not start listening'
 }
 # First start migrates the fresh database; nothing runs yet.
 $migrate=Start-Server @('--amhs.simulation.auto-run=false')
 Stop-Process -Id $migrate.Id -Force
 Start-Sleep -Seconds 2
 & "$PostgresBin/psql.exe" -X -w -h 127.0.0.1 -p $Port -U amhs_test_admin -d $DatabaseName -v ON_ERROR_STOP=1 -f (Join-Path $PSScriptRoot 'soak-fixture.sql')
 if($LASTEXITCODE -ne 0){throw 'Soak fixture failed'}
 $run=Start-Server @("--amhs.simulation.load.per-minute=$PerMinute","--amhs.simulation.load.seed=$Seed")
 "soak server pid $($run.Id): $PerMinute orders/min, seed $Seed, database $DatabaseName, port $ServerPort"
}finally{
 $env:PGPASSWORD=$null; $env:AMHS_DB_PASSWORD=$null
}
