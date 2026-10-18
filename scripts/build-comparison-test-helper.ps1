param([string]$FixtureRoot='.tmp/fabric-direct-smoke-20261015-095425')
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$fixture=Join-Path $project "$FixtureRoot/26.3"
$run=Join-Path $project ('.tmp/comparison-test-helper-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$jdk='C:/Program Files/Java/jdk-25.0.3/bin'
New-Item -ItemType Directory -Path "$run/classes/META-INF","$run/deps" | Out-Null
$api=[IO.Compression.ZipFile]::OpenRead("$fixture/mods/fabric-api.jar")
try { foreach($entry in $api.Entries) { if($entry.FullName -like 'META-INF/jars/*.jar') {
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path "$run/deps" $entry.Name))
} } } finally { $api.Dispose() }
$cache=Join-Path $HOME '.gradle/caches/modules-2/files-2.1'
$jars=@(Get-ChildItem "$fixture/libraries" -Recurse -Filter '*.jar' | ForEach-Object FullName)
$jars+=@(Get-ChildItem "$run/deps" -Filter '*.jar' | ForEach-Object FullName)
$jars+=@(Get-ChildItem "$cache/net.neoforged/neoforge/26.3.0.1-beta" -Recurse -Filter '*-universal.jar' | ForEach-Object FullName)
$jars+=@(Get-ChildItem "$cache/net.neoforged/bus","$cache/net.neoforged.fancymodloader/loader" -Recurse -Filter '*.jar' |
    Where-Object {$_.Name -notmatch 'sources|javadoc'} | ForEach-Object FullName)
$jars+=@(Get-ChildItem "$cache/net.neoforged/mergetool" -Recurse -Filter '*-api.jar' | ForEach-Object FullName)
$jars+="$fixture/versions/26.3/server-26.3.jar"
$source=Join-Path $project 'tests/integration/helper'
$java=@(Get-ChildItem $source -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp ($jars -join ';') -d "$run/classes" @java
if($LASTEXITCODE -ne 0) { throw 'Comparison helper compilation failed' }
Copy-Item "$source/fabric.mod.json" "$run/classes/fabric.mod.json"
Copy-Item "$source/META-INF/neoforge.mods.toml" "$run/classes/META-INF/neoforge.mods.toml"
Copy-Item "$source/rankboard-probe.mixins.json" "$run/classes/rankboard-probe.mixins.json"
& "$jdk/jar.exe" --create --file "$run/comparison-test-helper.jar" -C "$run/classes" .
if($LASTEXITCODE -ne 0) { throw 'Comparison helper packaging failed' }
& 'C:/Users/28656/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' `
    'C:/Users/28656/.codex/skills/build-game-mods/scripts/archive_mod_build.py' `
    --artifact "$run/comparison-test-helper.jar" --output-root "$project/mod-builds" `
    --mod-name RankBoardTestProbe --game-version '26.x test-only' --build-command 'scripts/build-comparison-test-helper.ps1'
if($LASTEXITCODE -ne 0) { throw 'Comparison helper archive failed' }
Write-Output "TEST_HELPER=$run/comparison-test-helper.jar"
