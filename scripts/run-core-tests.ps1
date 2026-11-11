$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    New-Item -ItemType Directory -Path '.test-classes' -Force | Out-Null
    $sources = @(Get-ChildItem 'src/main/java/dev/optimus/core' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    $sources += 'src/main/java/dev/optimus/client/ClientSettings.java'
    $sources += 'src/main/java/dev/optimus/client/CombatTrace.java'
    $suites = @(Get-Content -LiteralPath 'scripts/core-test-suites.txt' |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') })
    foreach ($suite in $suites) {
        $sources += 'src/test/java/' + $suite.Replace('.', '/') + '.java'
    }
    & javac -source 8 -target 8 -d .test-classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed.' }
    # Negative configuration cases intentionally write to stderr. Each suite's
    # process exit code below determines failure, rather than PowerShell stderr.
    Get-Command java -ErrorAction Stop | Out-Null
    $ErrorActionPreference = 'Continue'
    foreach ($suite in $suites) {
        & java -cp '.test-classes;src/test/resources' $suite
        if ($LASTEXITCODE -ne 0) { throw "$suite failed." }
    }
} finally { Pop-Location }