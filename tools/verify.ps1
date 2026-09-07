param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$GradleExecutable = '',
    [string]$NeoVersion = '21.1.219',
    [switch]$Mobile,
    [switch]$Couriers,
    [string]$JeiVersion = '',
    [switch]$RequirePriorPersistence
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../../../.fmp-reference-build-safe'))
$reportRoot = Join-Path $outputRoot 'reports/reference'
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
if (-not $GradleExecutable) { $GradleExecutable = Join-Path $projectRoot 'gradlew.bat' }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Java executable is missing' }
if (-not (Test-Path -LiteralPath $GradleExecutable)) { throw 'Gradle executable is missing' }
$env:JAVA_HOME = $JavaHome
$env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"
$gradleArgs = @('test', 'runGameTestServer', '-PfmpGameTests=true', "-Pneo_version=$NeoVersion", '--no-daemon', '--no-configuration-cache', '--console=plain')
$profile = "neo$NeoVersion"
if ($Couriers) { $gradleArgs += '-PwithCouriers'; $profile += '-paper' }
if ($Mobile) { $gradleArgs += '-PwithMobile'; $profile += '-mobile' }
if ($JeiVersion) { $gradleArgs += @('-PwithJei', "-Pjei_runtime_version=$JeiVersion"); $profile += "-jei$JeiVersion" }
$profile += '-model2'
if ($RequirePriorPersistence) { $gradleArgs += '-PfmpRequirePriorPersistence=true' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$transcript = Join-Path $reportRoot "$stamp-$profile.log"
Push-Location $projectRoot
try {
    $priorErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $GradleExecutable @gradleArgs 2>&1 | Tee-Object -FilePath $transcript | Select-String -Pattern '^> Task', 'BUILD ', 'required tests', 'failed at', 'GAME TESTS COMPLETE', 'FMP optional adapter', '^FAILURE:'
    $resultCode = $LASTEXITCODE
} finally { $ErrorActionPreference = $priorErrorPreference; Pop-Location }
$gameLog = Join-Path $outputRoot "run/gametest/$profile/logs/latest.log"
$gameSucceeded = $false
if (Test-Path -LiteralPath $gameLog) {
    $gameSucceeded = [bool](Select-String -LiteralPath $gameLog -Pattern 'All [0-9]+ required tests passed') -and
        [bool](Select-String -LiteralPath $transcript -Pattern 'All [0-9]+ required tests passed')
    Copy-Item -LiteralPath $gameLog -Destination (Join-Path $reportRoot "$stamp-$profile-game.log")
}
$buildSucceeded = [bool](Select-String -LiteralPath $transcript -Pattern '^BUILD SUCCESSFUL')
Write-Output "PROFILE=$profile EXIT=$resultCode BUILD_OK=$buildSucceeded GAME_OK=$gameSucceeded LOG=$transcript"
if ($resultCode -ne 0 -or -not $buildSucceeded -or -not $gameSucceeded) { exit 1 }
exit 0
