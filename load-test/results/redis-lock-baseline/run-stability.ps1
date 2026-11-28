$ErrorActionPreference = 'Stop'
$paths = @(
    'src/test/java/com/hthien/flash_sale/integration/ConcurrencyIntegrationTest.java',
    'src/test/java/com/hthien/flash_sale/integration/SimulationServiceIntegrationTest.java'
)
$originals = @{}
try {
    foreach ($path in $paths) {
        $originals[$path] = [IO.File]::ReadAllBytes((Join-Path $PWD $path))
        $source = [IO.File]::ReadAllText((Join-Path $PWD $path))
        $pattern = if ($path.Contains('ConcurrencyIntegration')) {
            '@Test(\r?\n    @DisplayName\("Case 6b:)'
        } else {
            '@Test(\r?\n    @DisplayName\("\[STABILITY\] REDIS LOCK:)'
        }
        if ([regex]::Matches($source, $pattern).Count -ne 1) { throw "Expected one target in $path" }
        $source = [regex]::Replace($source, $pattern, '@org.junit.jupiter.api.RepeatedTest(30)$1')
        [IO.File]::WriteAllText((Join-Path $PWD $path), $source, [Text.UTF8Encoding]::new($false))
    }
    $ErrorActionPreference = 'Continue'
    & .\mvnw.cmd '-Dtest=ConcurrencyIntegrationTest#concurrent_withRedisLock_multiStock_shouldSucceedExactlyStockCount,SimulationServiceIntegrationTest#runSimulation_withRedisLock_stableAcrossMultipleRuns' test *> phase0-stability.log
    $result = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    Get-Content phase0-stability.log -Tail 25
    if ($result -ne 0) { throw "Stability run failed: $result" }
} finally {
    foreach ($path in $originals.Keys) {
        [IO.File]::WriteAllBytes((Join-Path $PWD $path), $originals[$path])
    }
}
