param(
    [Parameter(Mandatory = $true)][string]$ModJar,
    [Parameter(Mandatory = $true)][string]$ServerDirectory,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25.0.3'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$artifact = (Resolve-Path -LiteralPath $ModJar).Path
$server = (Resolve-Path -LiteralPath $ServerDirectory).Path
$serverJar = Get-ChildItem (Join-Path $server 'versions') -Recurse -Filter 'server-*.jar' |
    Select-Object -First 1
if ($null -eq $serverJar) { throw 'Run the exact-version server startup smoke first to extract its runtime.' }
$libraries = Get-ChildItem (Join-Path $server 'libraries') -Recurse -Filter '*.jar'
$output = Join-Path $root ('.tmp/sidebar-flight-test-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $output | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -d $output `
    (Join-Path $root 'tests/java/cn/bamgdam/rankboard/SidebarFlightFormatTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Sidebar regression test compilation failed.' }
$classpath = (@($output, $artifact, $serverJar.FullName) + @($libraries.FullName)) -join ';'
& (Join-Path $JavaHome 'bin/java.exe') -cp $classpath cn.bamgdam.rankboard.SidebarFlightFormatTest
if ($LASTEXITCODE -ne 0) { throw 'Packaged sidebar km regression failed.' }
& (Join-Path $JavaHome 'bin/javac.exe') -cp $classpath -d $output `
    (Join-Path $root 'tests/java/cn/bamgdam/rankboard/SidebarUpdateStateTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Sidebar packet regression compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $classpath cn.bamgdam.rankboard.SidebarUpdateStateTest
if ($LASTEXITCODE -ne 0) { throw 'Sidebar packet regression failed.' }
& (Join-Path $JavaHome 'bin/javac.exe') -d $output `
    (Join-Path $root 'tests/java/cn/bamgdam/rankboard/JoinMessageConfigTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Join config regression compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $classpath cn.bamgdam.rankboard.JoinMessageConfigTest
if ($LASTEXITCODE -ne 0) { throw 'Join config regression failed.' }
