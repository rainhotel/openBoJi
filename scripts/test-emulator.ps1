param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$adb = if (Test-Path -LiteralPath $sdkAdb) { $sdkAdb } else { 'adb' }
Push-Location $projectRoot
try {
    $emulator = & $adb -s $Serial shell getprop ro.kernel.qemu
    if (($emulator -join '').Trim() -ne '1') { throw 'Use an isolated Android emulator for instrumentation tests.' }
    & $adb -s $Serial install -r 'app\build\outputs\apk\debug\app-debug.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Install application failed. Run scripts/build.ps1 -Verify first.' }
    & $adb -s $Serial install -r 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Install test APK failed.' }
    $output = & $adb -s $Serial shell am instrument -w -e notClass 'com.boji.app.LiveAssistantFlowTest' 'com.boji.app.test/com.boji.app.BojiTestRunner'
    ($output -join "`n").TrimEnd() | Set-Content -LiteralPath 'artifacts\instrumentation-results.txt' -Encoding utf8
    $output | Write-Output
    if (($output -join "`n") -notmatch 'OK \([0-9]+ tests\)') { throw 'Instrumentation did not pass. See artifacts/instrumentation-results.txt.' }
    & $adb -s $Serial pull '/sdcard/Android/data/com.boji.app/files/screenshots/' 'artifacts/'
} finally { Pop-Location }
