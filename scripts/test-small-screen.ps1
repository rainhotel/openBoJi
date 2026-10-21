param([string]$Serial='emulator-5554')
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') {throw 'Use the dedicated Boji emulator.'}
Push-Location $projectRoot
try {
    & $adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if($LASTEXITCODE -ne 0){throw 'App install failed'}
    & $adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if($LASTEXITCODE -ne 0){throw 'Test install failed'}
    & $adb -s $Serial shell wm size 720x1280
    & $adb -s $Serial shell wm density 320
    & $adb -s $Serial shell settings put system font_scale 1.3
    & $adb -s $Serial shell settings put system user_rotation 0
    & $adb -s $Serial shell settings put system accelerometer_rotation 1
    $output=& $adb -s $Serial shell am instrument -w -e class 'com.boji.app.CoreUserFlowTest#sevenTimesAndPhotoLinkToTaskThenAppearInBothViews' com.boji.app.test/com.boji.app.BojiTestRunner
    ($output -join "`n").TrimEnd() | Set-Content artifacts/small-screen-results.txt -Encoding utf8
    $output | Write-Output
    if(($output -join "`n") -notmatch 'OK \(1 test\)'){throw 'Small-screen acceptance failed'}
    & $adb -s $Serial pull /sdcard/Android/data/com.boji.app/files/screenshots/12-landscape-task-row.png artifacts/screenshots/12-landscape-task-row.png
    if($LASTEXITCODE -ne 0){throw 'Landscape screenshot retrieval failed'}
} finally {
    & $adb -s $Serial shell wm size 1080x2340
    & $adb -s $Serial shell wm density 420
    & $adb -s $Serial shell settings put system font_scale 1.0
    & $adb -s $Serial shell settings put system user_rotation 0
    & $adb -s $Serial shell settings put system accelerometer_rotation 1
    Pop-Location
}
