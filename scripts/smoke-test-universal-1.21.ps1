param(
    [Parameter(Mandatory = $true)]
    [string]$WrapperJar,
    [string[]]$Versions = @(
        "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
        "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
    ),
    [string]$LoaderVersion = "0.18.6",
    [string]$InstallerVersion = "1.1.1",
    [int]$TimeoutSeconds = 180,
    [int]$BaseServerPort = 25600,
    [int]$BaseWebPort = 28600,
    [string]$Java = "",
    [switch]$Offline
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$wrapper = (Resolve-Path -LiteralPath $WrapperJar).Path
$testRoot = Join-Path $projectRoot "wrapper-smoke"
$apiMetadata = if ($Offline) { $null } else {
    [xml](Invoke-WebRequest -UseBasicParsing "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml").Content
}
$mojangManifest = if ($Offline) { $null } else {
    Invoke-RestMethod "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
}
$results = @()

foreach ($version in $Versions) {
    Write-Host "Smoke testing Minecraft $version..."
    $java = $Java
    if ([string]::IsNullOrWhiteSpace($java)) {
        $java = if ($version.StartsWith("26.")) {
            "C:\Program Files\Java\jdk-25.0.3\bin\java.exe"
        } else {
            "C:\Program Files\Java\jdk-21.0.11\bin\java.exe"
        }
    }
    if (-not (Test-Path -LiteralPath $java)) { $java = "java.exe" }
    $directory = Join-Path $testRoot $version
    $mods = Join-Path $directory "mods"
    New-Item -ItemType Directory -Force $mods | Out-Null

    $launcher = Join-Path $directory "fabric-server-launch.jar"
    if (-not (Test-Path -LiteralPath $launcher)) {
        if ($Offline) { throw "Cached Fabric launcher not found for Minecraft $version." }
        Invoke-WebRequest -UseBasicParsing `
            "https://meta.fabricmc.net/v2/versions/loader/$version/$LoaderVersion/$InstallerVersion/server/jar" `
            -OutFile $launcher
    }

    $apiJar = Join-Path $mods "fabric-api.jar"
    if ($Offline) {
        if (-not (Test-Path -LiteralPath $apiJar)) { throw "Cached Fabric API not found for Minecraft $version." }
        $apiVersion = "cached"
    } else {
        $apiVersion = $apiMetadata.metadata.versioning.versions.version |
            Where-Object { $_ -like "*+$version" } |
            Select-Object -Last 1
        if ([string]::IsNullOrWhiteSpace($apiVersion)) {
            throw "No Fabric API release found for Minecraft $version."
        }
    }
    if (-not $Offline -and -not (Test-Path -LiteralPath $apiJar)) {
        $apiUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$apiVersion/fabric-api-$apiVersion.jar"
        Invoke-WebRequest -UseBasicParsing $apiUrl -OutFile $apiJar
    }

    Copy-Item -LiteralPath $wrapper -Destination (Join-Path $mods "rankboard-wrapper.jar") -Force
    $versionParts = $version.Split(".")
    $patchVersion = if ($versionParts.Length -ge 3) { [int]$versionParts[2] } else { 0 }
    $serverPort = $BaseServerPort + $patchVersion
    $webPort = $BaseWebPort + $patchVersion
    [System.IO.File]::WriteAllText((Join-Path $directory "eula.txt"), "eula=true`n")
    [System.IO.File]::WriteAllText(
        (Join-Path $directory "server.properties"),
        "server-port=$serverPort`nonline-mode=false`nenable-query=false`nenable-rcon=false`nlevel-name=world`n"
    )
    $rankBoardConfig = Join-Path $directory "config/rankboard"
    New-Item -ItemType Directory -Force $rankBoardConfig | Out-Null
    [System.IO.File]::WriteAllText(
        (Join-Path $rankBoardConfig "rankboard-web.properties"),
        "host=127.0.0.1`nport=$webPort`n"
    )

    $serverJar = Join-Path $directory "server.jar"
    if (-not (Test-Path -LiteralPath $serverJar)) {
        if ($Offline) { throw "Cached Minecraft server not found for Minecraft $version." }
        $versionEntry = $mojangManifest.versions | Where-Object { $_.id -eq $version } | Select-Object -First 1
        if ($null -eq $versionEntry) { throw "Minecraft metadata not found for $version." }
        $versionMetadata = Invoke-RestMethod $versionEntry.url
        Invoke-WebRequest -UseBasicParsing $versionMetadata.downloads.server.url -OutFile $serverJar -TimeoutSec 180
    }

    foreach ($name in @("logs")) {
        $path = Join-Path $directory $name
        if (Test-Path -LiteralPath $path) {
            [System.IO.Directory]::Delete((Resolve-Path -LiteralPath $path).Path, $true)
        }
    }

    $stdout = Join-Path $directory "stdout-smoke.log"
    $stderr = Join-Path $directory "stderr-smoke.log"
    $existingJavaPids = @(Get-Process java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
    $startedAt = Get-Date
    $process = Start-Process -FilePath $java `
        -ArgumentList "-Xms512M", "-Xmx1G", "-jar", "fabric-server-launch.jar", "nogui" `
        -WorkingDirectory $directory `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $ready = $false
    $failure = $false
    $selected = ""
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $log = Join-Path $directory "logs/latest.log"
        if (-not (Test-Path -LiteralPath $log)) {
            if ($process.HasExited -and (Get-Date) -gt $startedAt.AddSeconds(15)) { $failure = $true; break }
            continue
        }
        $logText = Get-Content -Raw $log
        $match = [regex]::Match($logText, "rankboard ([^\r\n]+)")
        if ($match.Success) { $selected = $match.Groups[1].Value.Trim() }
        if ($logText -match "Done \(") { $ready = $true; break }
        if ($logText -match "Incompatible mods found|Mod discovery failed|Could not find required mod|Failed to start the minecraft server|Exception in server tick loop|FAILED TO BIND") {
            $failure = $true
            break
        }
    }
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        $process.WaitForExit()
    }
    Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.Id -notin $existingJavaPids } |
        Stop-Process -Force -ErrorAction SilentlyContinue
    $results += [pscustomobject]@{
        Minecraft = $version
        FabricApi = $apiVersion
        Ready = $ready
        Failed = $failure
        SelectedRankBoard = $selected
        Log = Join-Path $directory "logs/latest.log"
    }
}

$results | Format-Table -AutoSize
if ($results | Where-Object { -not $_.Ready }) { exit 1 }
