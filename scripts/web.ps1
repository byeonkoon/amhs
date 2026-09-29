$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$localNode="$projectRoot/.tools/node-v22.22.0-win-x64"
if(Test-Path "$localNode/node.exe"){$env:PATH="$localNode;$env:PATH"}
$npm=(Get-Command npm.cmd -ErrorAction Stop).Source
Push-Location "$projectRoot/web"
try { & $npm run dev } finally { Pop-Location }
