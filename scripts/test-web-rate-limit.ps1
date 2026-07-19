param(
    [string]$Version = "1.21.11",
    [string]$WrapperJar = "",
    [int]$WebPort = 28611
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$directory = Join-Path $projectRoot "wrapper-smoke/$Version"
$wrapperPath = if ([string]::IsNullOrWhiteSpace($WrapperJar)) {
    Get-ChildItem (Join-Path $projectRoot "build/libs") -Filter "rankboard-*+mc1.21.x+*.jar" |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
} else {
    Join-Path $projectRoot $WrapperJar
}
if ([string]::IsNullOrWhiteSpace($wrapperPath)) { throw "No RankBoard 1.21.x Wrapper JAR was found." }
$wrapper = (Resolve-Path -LiteralPath $wrapperPath).Path
$java = "C:\Program Files\Java\jdk-21.0.11\bin\java.exe"
$before = @(Get-Process java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)

Copy-Item -LiteralPath $wrapper -Destination (Join-Path $directory "mods/rankboard-wrapper.jar") -Force
$process = Start-Process -FilePath $java `
    -ArgumentList "-Xms512M", "-Xmx1G", "-jar", "fabric-server-launch.jar", "nogui" `
    -WorkingDirectory $directory `
    -RedirectStandardOutput (Join-Path $directory "stdout-rate.log") `
    -RedirectStandardError (Join-Path $directory "stderr-rate.log") `
    -WindowStyle Hidden -PassThru

try {
    $site = "http://127.0.0.1:$WebPort/api/site"
    $icon = "http://127.0.0.1:$WebPort/site-icon"
    $deadline = (Get-Date).AddSeconds(90)
    do {
        Start-Sleep -Seconds 2
        $ready = $false
        try { $ready = (Invoke-WebRequest -UseBasicParsing $site -TimeoutSec 2).StatusCode -eq 200 }
        catch { }
    } until ($ready -or (Get-Date) -ge $deadline)
    if (-not $ready) { throw "Web dashboard did not become ready." }

    1..31 | ForEach-Object { & curl.exe -s -o NUL $site }
    Start-Sleep -Seconds 1
    $dataHeaders = & curl.exe -s -D - -o NUL $site

    1..7 | ForEach-Object { & curl.exe -s -o NUL $icon }
    Start-Sleep -Seconds 3
    $iconHeaders = & curl.exe -s -D - -o NUL $icon

    [pscustomobject]@{
        Kind = "data"
        Status = ($dataHeaders | Select-String "^HTTP/").Line
        RetryAfter = ($dataHeaders | Select-String "^Retry-After:").Line
        Window = ($dataHeaders | Select-String "^X-RateLimit-Window:").Line
        Penalty = ($dataHeaders | Select-String "^X-RateLimit-Penalty-Active:").Line
    }
    [pscustomobject]@{
        Kind = "icon"
        Status = ($iconHeaders | Select-String "^HTTP/").Line
        RetryAfter = ($iconHeaders | Select-String "^Retry-After:").Line
        Window = ($iconHeaders | Select-String "^X-RateLimit-Window:").Line
        Penalty = ($iconHeaders | Select-String "^X-RateLimit-Penalty-Active:").Line
    }
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.Id -notin $before } |
        Stop-Process -Force -ErrorAction SilentlyContinue
}
