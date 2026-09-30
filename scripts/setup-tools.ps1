# Downloads project-local tools only; does not change machine PATH or install services.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsPath = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force -Path $toolsPath | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-VerifiedArchive([string]$Uri, [string]$Path, [string]$Hash, [string]$Algorithm) {
    if (!(Test-Path -LiteralPath $Path) -or (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash -ne $Hash) {
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $Path
    }
    if ((Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash -ne $Hash) {
        throw "Checksum mismatch: $([IO.Path]::GetFileName($Path)); archive was not extracted."
    }
}

$jdk = Get-ChildItem -LiteralPath $toolsPath -Directory -Filter 'jdk-*' |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } |
    Select-Object -First 1
if (!$jdk) {
    $assets = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $asset = @($assets)[0]
    if (!$asset.binary.package.link -or !$asset.binary.package.checksum) { throw 'JDK release metadata is incomplete.' }
    $archive = Join-Path $toolsPath 'temurin-21-windows-x64.zip'
    Get-VerifiedArchive $asset.binary.package.link $archive $asset.binary.package.checksum 'SHA256'
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsPath
    $jdk = Get-ChildItem -LiteralPath $toolsPath -Directory -Filter 'jdk-*' |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } |
        Select-Object -First 1
}
if (!$jdk) { throw 'JDK extraction did not produce a compiler.' }
$javaOutput = & (Join-Path $jdk.FullName 'bin/java.exe') --version
if ($LASTEXITCODE -ne 0 -or ($javaOutput -join ' ') -notmatch 'openjdk 21\.') { throw 'Project requires JDK 21.' }

$mavenPath = Join-Path $toolsPath 'apache-maven-3.9.9'
if (!(Test-Path -LiteralPath (Join-Path $mavenPath 'bin/mvn.cmd'))) {
    $base = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
    $expected = ((Invoke-WebRequest -UseBasicParsing -Uri "$base.sha512").Content).Trim()
    $archive = Join-Path $toolsPath 'apache-maven-3.9.9-bin.zip'
    Get-VerifiedArchive $base $archive $expected 'SHA512'
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsPath
}
Write-Output "Project tools ready: JDK 21, Maven 3.9.9 in .tools. Run scripts/server.ps1 -Test."

