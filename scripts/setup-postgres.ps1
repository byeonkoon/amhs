param([string]$DbHost='127.0.0.1',[int]$Port=5432,[string]$AdminUser='postgres',
 [string]$PostgresBin='C:\Program Files\PostgreSQL\18\bin')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$localPath=Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Force $localPath | Out-Null
$credentialPath=Join-Path $localPath 'postgres-credential.xml'
$adminSecret=if($env:PGPASSWORD){ConvertTo-SecureString $env:PGPASSWORD -AsPlainText -Force}else{Read-Host "PostgreSQL administrator password for $AdminUser (not displayed)" -AsSecureString}
$admin=[pscredential]::new($AdminUser,$adminSecret)
$previousPassword=$env:PGPASSWORD
function Invoke-Pg([string]$sql){
 $result=$sql | & "$PostgresBin/psql.exe" -X -w -h $DbHost -p $Port -U $AdminUser -d postgres -v ON_ERROR_STOP=1 -At
 if($LASTEXITCODE -ne 0){throw 'PostgreSQL setup command failed'}
 return $result
}
try {
 $env:PGPASSWORD=$admin.GetNetworkCredential().Password
 $roleExists=Invoke-Pg "SELECT 1 FROM pg_roles WHERE rolname='amhs';"
 if($roleExists -eq '1'){
  if(!(Test-Path $credentialPath)){throw 'Role amhs exists without saved credentials. Use configure-postgres.ps1 with its existing password; no role was changed.'}
  $credential=Import-Clixml -LiteralPath $credentialPath
  $env:PGPASSWORD=$credential.GetNetworkCredential().Password
  & "$PostgresBin/psql.exe" -X -w -h $DbHost -p $Port -U amhs -d postgres -c 'SELECT current_user;'
  if($LASTEXITCODE -ne 0){throw 'Saved amhs credential does not match this server'}
  $env:PGPASSWORD=$admin.GetNetworkCredential().Password
 } else {
  $password=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
  $credential=[pscredential]::new('amhs',(ConvertTo-SecureString $password -AsPlainText -Force))
  Invoke-Pg "CREATE ROLE amhs LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$password';" | Out-Null
  $credential | Export-Clixml -LiteralPath $credentialPath
  $password=$null
 }
 foreach($database in @('amhs_dev','amhs_test')){
  $owner=Invoke-Pg "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$database';"
  if(!$owner){Invoke-Pg "CREATE DATABASE $database OWNER amhs ENCODING 'UTF8' TEMPLATE template0;" | Out-Null}
  elseif($owner -ne 'amhs'){throw "$database exists with another owner; refusing to modify it"}
 }
 @{Host=$DbHost;Port=$Port;DevDatabase='amhs_dev';TestDatabase='amhs_test'} |
  ConvertTo-Json | Set-Content -LiteralPath (Join-Path $localPath 'postgres-connection.json') -Encoding utf8
 Write-Output 'Ready: amhs_dev, amhs_test. Application credential saved with Windows DPAPI; administrator password not saved.'
} finally {$env:PGPASSWORD=$previousPassword}
