param(
    [Parameter(Mandatory = $true)][string]$ModJar,
    [string]$Version = '26.3',
    [int]$WebPort = 19876
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$artifact = (Resolve-Path -LiteralPath $ModJar).Path
$fixture = Join-Path $projectRoot "wrapper-smoke/$Version"
$testRoot = Join-Path $projectRoot ('.tmp/shared-web-port-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$registry = Join-Path $testRoot 'registry'
$java = 'C:\Program Files\Java\jdk-25.0.3\bin\java.exe'
$processes = @()
New-Item -ItemType Directory -Path $registry -Force | Out-Null
try {
    for ($index = 0; $index -lt 2; $index++) {
        $directory = Join-Path $testRoot "server-$index"
        New-Item -ItemType Directory -Path "$directory/mods", "$directory/config/rankboard" -Force | Out-Null
        foreach ($file in @('server.jar', 'fabric-server-launch.jar')) {
            New-Item -ItemType HardLink -Path "$directory/$file" -Target "$fixture/$file" | Out-Null
        }
        New-Item -ItemType HardLink -Path "$directory/mods/fabric-api.jar" -Target "$fixture/mods/fabric-api.jar" | Out-Null
        Copy-Item -LiteralPath $artifact -Destination "$directory/mods/rankboard.jar"
        foreach ($name in @('libraries', 'versions', '.fabric')) {
            if (Test-Path -LiteralPath "$fixture/$name") {
                New-Item -ItemType Junction -Path "$directory/$name" -Target "$fixture/$name" | Out-Null
            }
        }
        Set-Content -LiteralPath "$directory/eula.txt" -Value 'eula=true' -Encoding utf8
        Set-Content -LiteralPath "$directory/server.properties" -Encoding utf8 -Value @"
server-port=$(27880+$index)
online-mode=false
enable-rcon=true
rcon.port=$(29880+$index)
rcon.password=rankboard-smoke-only
view-distance=2
simulation-distance=2
pause-when-empty-seconds=0
motd=FeatureSync-$index
"@
        Set-Content -LiteralPath "$directory/config/rankboard/rankboard-web.properties" -Encoding utf8 -Value @"
host=127.0.0.1
port=$WebPort
web-switcher-name=FeatureSync-$index
"@
        $process = Start-Process -FilePath $java -WorkingDirectory $directory -WindowStyle Hidden -PassThru `
            -ArgumentList @('-Xms512M', '-Xmx1G', "-Djava.io.tmpdir=$registry", '-jar', 'fabric-server-launch.jar', 'nogui') `
            -RedirectStandardOutput "$directory/stdout.log" -RedirectStandardError "$directory/stderr.log"
        $processes += $process
        $ready = $false
        $deadline = (Get-Date).AddSeconds(180)
        while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
            Start-Sleep -Seconds 2
            $log = "$directory/logs/latest.log"
            if ((Test-Path -LiteralPath $log) -and (Get-Content -Raw -LiteralPath $log).Contains('Done (')) {
                $ready = $true
                break
            }
        }
        if (-not $ready) { throw "Server $index did not start. Logs: $directory" }
        $env:RANKBOARD_TEST_RCON_PORT = [string](29880+$index)
        & node -e "const {connect}=require('./scripts/rcon-smoke.cjs'); (async()=>{const r=await connect(process.env.RANKBOARD_TEST_RCON_PORT); for(const c of ['leaderboard namecolor scoreboard-only','leaderboard bedrock true','leaderboard help config general']) await r.command(c);r.socket.end()})().catch(e=>{console.error(e);process.exit(1)})"
        if ($LASTEXITCODE -ne 0) { throw "Server $index command regression failed" }
    }
    $sites = (Invoke-RestMethod "http://127.0.0.1:$WebPort/api/sites" -TimeoutSec 10).sites
    if ($sites.Count -ne 2) { throw "Expected two servers on one public web port: $($sites | ConvertTo-Json -Compress)" }
    foreach ($site in $sites) {
        $uri = [uri]$site.url
        if ($uri.Port -ne $WebPort) { throw "Server switch exposes a different port: $uri" }
        $base = $uri.GetLeftPart([System.UriPartial]::Authority)
        $metadata = Invoke-RestMethod "$base/api/site$($uri.Query)" -TimeoutSec 10
        if ($metadata.switcherName -ne $site.name) { throw "Server query routed to the wrong server: $uri" }
        foreach ($metric in @('breeding', 'afk', 'bedrock')) {
            if ($metadata.metrics.id -notcontains $metric) { throw "$metric missing from $uri" }
        }
        Write-Host "PASS: $($site.name) routes through $uri with breeding/AFK/bedrock enabled"
    }
    $sites | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$testRoot/test-results.json" -Encoding utf8
    Write-Host "SHARED_WEB_PORT_OK ($Version): $testRoot"
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force; $process.WaitForExit() }
    }
    Remove-Item Env:RANKBOARD_TEST_RCON_PORT -ErrorAction SilentlyContinue
}
