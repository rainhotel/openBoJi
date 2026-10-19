param(
    [switch]$Verify,
    [switch]$Install,
    [string]$Serial = ""
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
$sdk = $sdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platforms\android-36')) } | Select-Object -First 1
if (-not $sdk) { throw 'Install Android SDK platform 36 and build tools first.' }
$jdkCandidates = @((Join-Path $env:USERPROFILE '.jdks\liberica-full-17.0.17'), $env:JAVA_HOME)
$jdk = $jdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
if (-not $jdk) { throw 'Set JAVA_HOME to JDK 17 or 21.' }

$previousJavaHome = $env:JAVA_HOME
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $jdk
    # This workstation's Java UDS transport fails; a nonexistent socket directory triggers TCP fallback.
    $socketFallback = (Join-Path $projectRoot ('.gradle\tcp-fallback-' + [Guid]::NewGuid().ToString('N'))).Replace('\','/')
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djdk.net.unixdomain.tmpdir=$socketFallback".Trim()
    $escapedSdk = $sdk.Replace('\','/').Replace(':','\:')
    Set-Content -LiteralPath 'local.properties' -Value "sdk.dir=$escapedSdk" -Encoding ascii
    Push-Location 'agent-runtime'
    try {
        if (-not (Test-Path -LiteralPath 'node_modules\esbuild')) {
            & cmd /c npm ci --ignore-scripts
            if ($LASTEXITCODE -ne 0) { throw 'Agent dependency install failed.' }
        }
        & cmd /c npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Agent bundle failed.' }
        if ($Verify) {
            & cmd /c npm test
            if ($LASTEXITCODE -ne 0) { throw 'Agent runtime tests failed.' }
        }
    } finally { Pop-Location }
    $tasks = @(':app:assembleDebug')
    if ($Verify) { $tasks += @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest') }
    & .\gradlew.bat @tasks --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
    New-Item -ItemType Directory -Force -Path 'artifacts' | Out-Null
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination 'artifacts\boji-debug.apk'
    Write-Output (Join-Path $projectRoot 'artifacts\boji-debug.apk')
    if ($Install) {
        $adb = Join-Path $sdk 'platform-tools\adb.exe'
        $adbArgs = @()
        if ($Serial) { $adbArgs += @('-s',$Serial) }
        & $adb @adbArgs install -r 'artifacts\boji-debug.apk'
        if ($LASTEXITCODE -ne 0) { throw 'APK install failed. Check adb devices and pass -Serial if needed.' }
        & $adb @adbArgs shell am start -n 'com.boji.app/.MainActivity'
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Pop-Location
}
