param([string]$DbHost='127.0.0.1',[int]$Port=5432,[string]$DbUser='amhs',
 [string]$DevDatabase='amhs_dev',[string]$TestDatabase='amhs_test',
 [string]$PostgresBin='C:\Program Files\PostgreSQL\18\bin')
$ErrorActionPreference='Stop'
foreach($identifier in @($DevDatabase,$TestDatabase)){
 if($identifier -notmatch '^[a-z][a-z0-9_]*$'){throw 'Database names must be lowercase SQL identifiers'}
}
if($DevDatabase -eq $TestDatabase){throw 'Development and test databases must be separate'}
$secret=Read-Host "PostgreSQL password for $DbUser (not displayed)" -AsSecureString
$credential=[pscredential]::new($DbUser,$secret)
$previousPassword=$env:PGPASSWORD
try {
 $env:PGPASSWORD=$credential.GetNetworkCredential().Password
 foreach($database in @($DevDatabase,$TestDatabase)){
  & "$PostgresBin/psql.exe" -X -w -h $DbHost -p $Port -U $DbUser -d $database -v ON_ERROR_STOP=1 -c 'SELECT current_database(), current_user;'
  if($LASTEXITCODE -ne 0){throw "Cannot connect to $database; connection settings not saved"}
 }
 $localPath=Join-Path (Split-Path $PSScriptRoot -Parent) '.local'
 New-Item -ItemType Directory -Force $localPath | Out-Null
 $credential | Export-Clixml -LiteralPath (Join-Path $localPath 'postgres-credential.xml')
 @{Host=$DbHost;Port=$Port;DevDatabase=$DevDatabase;TestDatabase=$TestDatabase} |
  ConvertTo-Json | Set-Content -LiteralPath (Join-Path $localPath 'postgres-connection.json') -Encoding utf8
 Write-Output 'Connection saved for this Windows user using DPAPI. No password was written in plaintext.'
} finally {$env:PGPASSWORD=$previousPassword}
