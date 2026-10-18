param(
    [string]$ModVersion = '',
    [string]$Java21Home = '',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (-not $ModVersion) {
    $line = Get-Content (Join-Path $project 'gradle.properties') |
        Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
    if (-not $line) { throw 'mod_version is missing from gradle.properties' }
    $ModVersion = ($line -split '=', 2)[1].Trim()
}
if (-not $Java21Home -and $env:JAVA_HOME_21_X64) { $Java21Home = $env:JAVA_HOME_21_X64 }
if (-not $Java21Home) {
    $local21 = Join-Path $project '.tmp/toolchain-temurin21/jdk-21.0.12.1+1'
    if (Test-Path -LiteralPath $local21) { $Java21Home = $local21 }
}
$gradle = Join-Path $project $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
# Leading ':' is essential: do not select child assemble/JAR tasks.
$arguments = @('-p', (Join-Path $project 'unified'), ':assemble', '--no-daemon', '--console=plain', "-Pmod_version=$ModVersion")
if ($Offline) { $arguments += '--offline' }
if ($Java21Home) {
    $arguments += "-Dorg.gradle.java.installations.paths=$((Resolve-Path -LiteralPath $Java21Home).Path)"
}
& $gradle @arguments
if ($LASTEXITCODE -ne 0) { throw "Unified build failed with exit code $LASTEXITCODE" }
$artifact = Get-ChildItem (Join-Path $project 'unified/build/libs') -File -Filter "rankboard-$ModVersion+mc1.21-26.3+*.jar" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $artifact) { throw 'The unified distributable was not produced' }
Write-Output "Unified artifact: $($artifact.FullName)"
