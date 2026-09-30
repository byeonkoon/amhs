param([string]$PostgresBin='C:\Program Files\PostgreSQL\18\bin',[int]$Port=55432)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$testRoot=Join-Path $projectRoot '.local/postgres-test'
$dataPath=Join-Path $testRoot 'data'
$credentialPath=Join-Path $testRoot 'credential.xml'
if(!(Test-Path "$PostgresBin/initdb.exe")){throw 'PostgreSQL binaries not found'}
if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue){throw "Port $Port already in use; refusing to use an unknown server"}
New-Item -ItemType Directory -Force $testRoot | Out-Null
if(!(Test-Path $dataPath)){
 $password=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
 $credential=[pscredential]::new('amhs_test_admin',(ConvertTo-SecureString $password -AsPlainText -Force))
 $credential | Export-Clixml -LiteralPath $credentialPath
 $passwordPath=Join-Path $testRoot 'init-password.tmp'
 try {
  Set-Content -LiteralPath $passwordPath -Value $password -Encoding ascii
  & "$PostgresBin/initdb.exe" -D $dataPath -U amhs_test_admin --encoding=UTF8 --locale=C --auth=scram-sha-256 "--pwfile=$passwordPath"
  if($LASTEXITCODE -ne 0){throw 'initdb failed; inspect the test directory before retrying'}
 } finally {Remove-Item -LiteralPath $passwordPath -ErrorAction SilentlyContinue; $password=$null}
}
if(!(Test-Path $credentialPath)){throw 'Test cluster credential missing; refusing to change authentication'}
$credential=Import-Clixml -LiteralPath $credentialPath
$previous=@{}
foreach($name in @('AMHS_DB_USER','AMHS_DB_PASSWORD','AMHS_TEST_DB_URL','PGPASSWORD')){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$started=$false
try {
 # This isolated cluster uses the installed PostgreSQL binaries, not the user's Windows service.
 $startArgs=@('-D',('"'+$dataPath+'"'),'-l',('"'+$testRoot+'/postgres.log"'),'-o',('"-h 127.0.0.1 -p '+$Port+'"'),'-w','start')
 $start=Start-Process -FilePath "$PostgresBin/pg_ctl.exe" -ArgumentList $startArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$testRoot/start.out" -RedirectStandardError "$testRoot/start.err"
 # Touch the handle before exit; otherwise Windows PowerShell can report a null ExitCode for a successful start.
 $null=$start.Handle
 $start.WaitForExit()
 if($start.ExitCode -ne 0){throw "Test PostgreSQL start failed; see $testRoot/start.err"}
 $started=$true
 $env:PGPASSWORD=$credential.GetNetworkCredential().Password
 $exists=& "$PostgresBin/psql.exe" -X -w -h 127.0.0.1 -p $Port -U amhs_test_admin -d postgres -At -c "SELECT 1 FROM pg_database WHERE datname='amhs_test'"
 if($LASTEXITCODE -ne 0){throw 'Test cluster connection failed'}
 if($exists -ne '1'){
  & "$PostgresBin/createdb.exe" -w -h 127.0.0.1 -p $Port -U amhs_test_admin amhs_test
  if($LASTEXITCODE -ne 0){throw 'Test database creation failed'}
 }
 $env:AMHS_DB_USER='amhs_test_admin'
 $env:AMHS_DB_PASSWORD=$env:PGPASSWORD
 $env:AMHS_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$Port/amhs_test"
 # Separate PowerShell process prevents server.ps1's exit from bypassing this cleanup.
 & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot/server.ps1" -SqlTest
 $testExit=$LASTEXITCODE
} finally {
 if($started){& "$PostgresBin/pg_ctl.exe" -D $dataPath -m fast -w stop}
 foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
}
exit $testExit
