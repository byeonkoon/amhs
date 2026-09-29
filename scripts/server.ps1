param([switch]$Test,[switch]$SqlTest)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$connectionFile=Join-Path $projectRoot '.local/postgres-connection.json'
if((Test-Path $connectionFile) -and !$env:AMHS_DB_USER -and !$env:AMHS_DB_PASSWORD){
 $connection=Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json
 $credential=Import-Clixml -LiteralPath (Join-Path $projectRoot '.local/postgres-credential.xml')
 $env:AMHS_DB_USER=$credential.UserName
 $env:AMHS_DB_PASSWORD=$credential.GetNetworkCredential().Password
 if(!$env:AMHS_DB_URL){$env:AMHS_DB_URL="jdbc:postgresql://$($connection.Host):$($connection.Port)/$($connection.DevDatabase)"}
 if(!$env:AMHS_TEST_DB_URL){$env:AMHS_TEST_DB_URL="jdbc:postgresql://$($connection.Host):$($connection.Port)/$($connection.TestDatabase)"}
}
$localJdk=Get-ChildItem "$projectRoot/.tools" -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
if($localJdk){$env:JAVA_HOME=$localJdk.FullName}
if($env:JAVA_HOME){$env:PATH="$env:JAVA_HOME/bin;$env:PATH"}
$java=(Get-Command java -ErrorAction Stop).Source
$maven="$projectRoot/.tools/apache-maven-3.9.9/bin/mvn.cmd"
if(!(Test-Path $maven)){$maven=(Get-Command mvn.cmd -ErrorAction Stop).Source}
if($Test -or $SqlTest){
 $previousSqlTest=$env:AMHS_SQL_TEST
 try {
  if($SqlTest){$env:AMHS_SQL_TEST='true'}else{$env:AMHS_SQL_TEST='false'}
  & $maven -B -f "$projectRoot/server/pom.xml" verify
  $testExit=$LASTEXITCODE
 } finally { $env:AMHS_SQL_TEST=$previousSqlTest }
 exit $testExit
}
& $java -jar "$projectRoot/server/target/amhs-server-0.1.0.jar"
