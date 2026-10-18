param(
    [Parameter(Mandatory=$true)][string]$BaselineJar,
    [Parameter(Mandatory=$true)][string]$FixedJar,
    [Parameter(Mandatory=$true)][string]$CarpetJar,
    [string]$FixtureRoot='wrapper-smoke',
    [string]$JavaHome='C:\Program Files\Java\jdk-25.0.3',
    [ValidatePattern('^cn\.bamgdam\.rankboard(?:\.impl\.mojang26)?$')][string]$ImplementationPackage='cn.bamgdam.rankboard',
    [ValidateRange(1,20)][int]$Viewers=3,
    [switch]$MixedMetrics,
    [ValidateSet('baseline','fixed')][string[]]$Variants=@('baseline','fixed'),
    [int]$TimeoutSeconds=300
)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$fixture=Join-Path (Join-Path $project $FixtureRoot) '26.3'
if(-not (Test-Path -LiteralPath "$fixture/versions/26.3/server-26.3.jar")) {
    throw 'The fixture must have already been started once to extract the Minecraft server classes.'
}
$run=Join-Path $project ('.tmp/fake-player-performance-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$jdk=Join-Path $JavaHome 'bin'
$baseline=(Resolve-Path -LiteralPath $BaselineJar).Path
$fixed=(Resolve-Path -LiteralPath $FixedJar).Path
$carpet=(Resolve-Path -LiteralPath $CarpetJar).Path
New-Item -ItemType Directory -Path "$run/deps","$run/classes" | Out-Null
$api=[IO.Compression.ZipFile]::OpenRead("$fixture/mods/fabric-api.jar")
try { foreach($entry in $api.Entries) { if($entry.FullName -like 'META-INF/jars/*.jar') {
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path "$run/deps" $entry.Name))
} } } finally { $api.Dispose() }
$jars=@(Get-ChildItem "$fixture/libraries" -Recurse -Filter '*.jar' | ForEach-Object FullName)
$jars+=@(Get-ChildItem "$run/deps" -Filter '*.jar' | ForEach-Object FullName)
$jars+=@("$fixture/versions/26.3/server-26.3.jar",$carpet,$fixed)
$sources=@("$project/tests/integration/cn/bamgdam/rankboard/FakePlayerPerformanceTest.java", "$project/tests/integration/cn/bamgdam/rankboardtest/mixin/PerformanceTickMixin.java")
if($ImplementationPackage -ne 'cn.bamgdam.rankboard') {
    New-Item -ItemType Directory "$run/src" | Out-Null
    $sources=@($sources | ForEach-Object {
        $target=Join-Path "$run/src" ([IO.Path]::GetFileName($_))
        $content=(Get-Content -Raw $_) -replace 'cn\.bamgdam\.rankboard(?=[.;"])',$ImplementationPackage
        [IO.File]::WriteAllText($target,$content,[Text.UTF8Encoding]::new($false))
        $target
    })
}
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp ($jars -join ';') -d "$run/classes" @sources
if($LASTEXITCODE -ne 0) { throw 'Performance test compilation failed' }
$metadata=(Get-Content -Raw "$project/tests/integration/fake-player-performance.fabric.mod.json") -replace 'cn\.bamgdam\.rankboard(?=[.;"])',$ImplementationPackage
[IO.File]::WriteAllText("$run/classes/fabric.mod.json",$metadata,[Text.UTF8Encoding]::new($false))
Copy-Item "$project/tests/integration/rankboard-performance.mixins.json" "$run/classes/rankboard-performance.mixins.json"
& "$jdk/jar.exe" --create --file "$run/performance-test.jar" -C "$run/classes" .
if($LASTEXITCODE -ne 0) { throw 'Performance test packaging failed' }
$encoding=[Text.UTF8Encoding]::new($false)
$results=@{}
foreach($label in $Variants) {
    $directory=Join-Path $run $label
    New-Item -ItemType Directory -Path "$directory/mods","$directory/config/rankboard" | Out-Null
    foreach($file in @('server.jar','fabric-server-launch.jar')) {
        New-Item -ItemType HardLink -Path "$directory/$file" -Target "$fixture/$file" | Out-Null
    }
    foreach($name in @('libraries','versions','.fabric')) {
        if(Test-Path -LiteralPath "$fixture/$name") {
            New-Item -ItemType Junction -Path "$directory/$name" -Target "$fixture/$name" | Out-Null
        }
    }
    Copy-Item "$fixture/mods/fabric-api.jar" "$directory/mods/fabric-api.jar"
    Copy-Item $carpet "$directory/mods/carpet.jar"
    Copy-Item "$run/performance-test.jar" "$directory/mods/performance-test.jar"
    Copy-Item $(if($label -eq 'baseline'){$baseline}else{$fixed}) "$directory/mods/rankboard.jar"
    [IO.File]::WriteAllText("$directory/eula.txt","eula=true`n",$encoding)
    [IO.File]::WriteAllText("$directory/server.properties",@"
server-port=0
online-mode=false
level-name=world
level-seed=123456789
level-type=minecraft:flat
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
max-tick-time=60000
pause-when-empty-seconds=0
"@,$encoding)
    [IO.File]::WriteAllText("$directory/config/rankboard/rankboard.properties",@"
read-legacy-config=false
scoreboard-recipient-filter=false
scoreboard-live-update-enabled=false
avatar-cache-enabled=false
welcome-enabled=false
join-menu-enabled=false
join-web-hint-enabled=false
"@,$encoding)
    [IO.File]::WriteAllText("$directory/config/rankboard/rankboard-web.properties","web-enabled=false`n",$encoding)
    $process=Start-Process -FilePath "$jdk/java.exe" -ArgumentList @('-Xms1G','-Xmx2G','-XX:+UseG1GC',"-Drankboard.test.viewers=$Viewers",("-Drankboard.test.mixed="+$MixedMetrics.ToString().ToLowerInvariant()),'-jar','fabric-server-launch.jar','nogui') -WorkingDirectory $directory -WindowStyle Hidden -PassThru -RedirectStandardOutput "$directory/stdout.log" -RedirectStandardError "$directory/stderr.log"
    try {
        $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
        Write-Host "Running $label in $directory (PID $($process.Id))"
        while(-not $process.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }
        if(-not $process.HasExited) { throw "$label timed out" }
        $log=Get-Content -Raw "$directory/stdout.log"
        if($log -match 'PERF_FAIL' -or $log -notmatch 'PERF_PASS') {
            if($label -eq 'baseline' -and $log -match 'A single server tick took 60') {
                $partial=@($log -split '\r?\n' | Where-Object {$_ -match 'PERF_BENCHMARK '})
                $results[$label]=[ordered]@{status='WATCHDOG';reason='A tick exceeded 60 seconds';benchmark=if($partial.Count){($partial[-1] -replace '^.*PERF_BENCHMARK ','') | ConvertFrom-Json}else{$null}}
                Write-Host 'baseline WATCHDOG: regression reproduced; its unfinished checks are not counted as passing.'
                continue
            }
            Get-Content "$directory/stdout.log" -Tail 35
            Get-Content "$directory/stderr.log" -Tail 20
            throw "$label failed"
        }
        $json=@($log -split '\r?\n' | Where-Object {$_ -match 'PERF_RESULT '})[-1] -replace '^.*PERF_RESULT ',''
        $results[$label]=$json | ConvertFrom-Json
        $cache=Get-Content -Raw "$directory/config/rankboard/rankboard-history-cache.json" | ConvertFrom-Json
        $vanilla=Get-Content -Raw "$directory/world/players/stats/$($results[$label].churnUuid).json" | ConvertFrom-Json
        if($vanilla.stats.'minecraft:custom'.'minecraft:jump' -ne 340) { throw "$label did not persist the final vanilla fake statistics" }
        $online=@($cache.players | Where-Object name -eq 'Perf00')
        $onlinePreserved=$online.Count -eq 1 -and $online[0].values.jumps -eq 9002
        $results[$label] | Add-Member -NotePropertyName finalOnlineCachePreserved -NotePropertyValue $onlinePreserved
        if($label -eq 'fixed' -and -not $onlinePreserved) { throw "$label did not flush the online player's final statistics at shutdown" }
        Write-Host "$label PASS $json"
    } finally {
        if(-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    }
}
$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$run/results.json" -Encoding utf8
Write-Host "Performance results: $run/results.json"
