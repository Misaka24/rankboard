param(
    [string]$Artifact = '',
    [string]$Output = '.tmp/unified-matrix-20261018.json'
)
$ErrorActionPreference = 'Stop'
$versionLine = Get-Content gradle.properties | Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
if (-not $versionLine) { throw 'mod_version is missing from gradle.properties.' }
$modVersion = ($versionLine -split '=', 2)[1].Trim()
if (-not $Artifact) {
    $candidate = Get-ChildItem mod-builds -Directory | Sort-Object Name -Descending |
        ForEach-Object { Get-ChildItem $_.FullName -File -Filter "rankboard-$modVersion+mc1.21-26.3+*.jar" } |
        Select-Object -First 1
    if (-not $candidate) { throw "No archived unified artifact for $modVersion was found." }
    $Artifact = $candidate.FullName
}
$hash = (Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash
$roots = @(
    'fabric-direct-smoke-20261018-123857', 'neoforge-direct-smoke-20261018-123859',
    'fabric-direct-smoke-20261018-124024', 'neoforge-direct-smoke-20261018-124026',
    'fabric-direct-smoke-20261018-124245', 'neoforge-direct-smoke-20261018-124248',
    'fabric-direct-smoke-20261018-124913', 'fabric-direct-smoke-20261018-125133',
    'neoforge-direct-smoke-20261018-125318'
)
$rows = foreach ($root in $roots) {
    $report = Join-Path ".tmp/$root" 'test-results.json'
    foreach ($row in @(Get-Content -Raw -LiteralPath $report | ConvertFrom-Json)) {
        if ($row.Result -notlike 'PASS*' -or -not $row.ServerReady -or -not $row.DashboardHttp) { throw "Failed test: $report $($row.Minecraft)" }
        $copy = Join-Path $row.TestDirectory 'mods/rankboard.jar'
        if ((Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash -ne $hash) { throw "Wrong tested binary: $copy" }
        if ($row.Minecraft.StartsWith('1.21') -and -not $row.PlayerJoined) { throw "Missing real login: $report" }
        [pscustomobject]@{
            Loader = if ($root.StartsWith('fabric-')) { 'Fabric' } else { 'NeoForge' }
            Minecraft = $row.Minecraft
            Result = $row.Result
            NetworkLogin = [bool]$row.PlayerJoined
            HeadlessComparison = [bool]$row.HeadlessComparison
            Sha256 = $hash
            Evidence = $report
        }
    }
}
$versions = @('1.21') + @(1..11 | ForEach-Object { "1.21.$_" }) + @('26.1','26.1.1','26.1.2','26.2','26.3')
foreach ($loader in 'Fabric','NeoForge') {
    foreach ($version in $versions) {
        if (@($rows | Where-Object { $_.Loader -eq $loader -and $_.Minecraft -eq $version }).Count -ne 1) { throw "Missing/duplicate $loader $version" }
    }
}
$rows | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Output -Encoding utf8
$synthetic = foreach ($root in 'neoforge-direct-smoke-20261018-125553','neoforge-direct-smoke-20261018-125646') {
    foreach ($row in @(Get-Content -Raw -LiteralPath ".tmp/$root/test-results.json" | ConvertFrom-Json)) {
        if ($row.Result -notlike 'PASS*') { throw "Synthetic test failed: $root" }
        $log = Join-Path $row.TestDirectory 'synthetic-player-smoke.log'
        if ((Get-Content -Raw -LiteralPath $log) -notmatch 'SYNTHETIC_PLAYER_OK') { throw "Missing synthetic player evidence: $log" }
        if ((Get-FileHash -LiteralPath (Join-Path $row.TestDirectory 'mods/rankboard.jar')).Hash -ne $hash) { throw 'Synthetic tests used a different binary' }
        [pscustomobject]@{ Loader='NeoForge'; Minecraft=$row.Minecraft; SyntheticJoins=5; NetworkLogin=$false; Sha256=$hash; Evidence=$log }
    }
}
if (@($synthetic).Count -ne 5) { throw 'Synthetic matrix incomplete' }
$synthetic | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath '.tmp/unified-synthetic-20261018.json' -Encoding utf8
Write-Output "UNIFIED_MATRIX_OK: $($rows.Count) combinations, same SHA-256; report=$Output"
Write-Output 'SYNTHETIC_MATRIX_OK: five NeoForge 26.x versions, five join/sidebar/leave cycles each; no TCP login'
