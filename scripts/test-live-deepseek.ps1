param([string]$Serial='emulator-5554')
$ErrorActionPreference='Stop'
if (-not $env:BOJI_DEEPSEEK_KEY) { throw 'Provide BOJI_DEEPSEEK_KEY in the process environment; never pass a key as an argument.' }
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $projectRoot
try {
    $env:BOJI_LIVE_SERIAL=$Serial
    @'
import os,subprocess,pathlib,re
adb=str(pathlib.Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platform-tools/adb.exe')
base=[adb,'-s',os.environ['BOJI_LIVE_SERIAL']]
assert subprocess.check_output(base+['shell','getprop','ro.kernel.qemu'],text=True).strip()=='1','Dedicated emulator required'
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
    subprocess.run(base+['install','-r',apk],check=True,capture_output=True)
try:
    subprocess.run(base+['shell',"run-as com.boji.app sh -c 'cat > files/live-deepseek-key'"],input=os.environ['BOJI_DEEPSEEK_KEY'].encode(),check=True,capture_output=True)
    r=subprocess.run(base+['shell','am','instrument','-w','-e','liveDeepSeek','true','-e','class','com.boji.app.LiveAssistantFlowTest','com.boji.app.test/com.boji.app.BojiTestRunner'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=900)
    output=(r.stdout+r.stderr).replace(os.environ['BOJI_DEEPSEEK_KEY'],'[REDACTED]')
    pathlib.Path('artifacts/live-deepseek-results.txt').write_text(output.rstrip()+'\n',encoding='utf-8')
    print(output)
    if not re.search(r'OK \(1 test\)',output): raise SystemExit('Live acceptance failed; inspect sanitized result')
    subprocess.run(base+['pull','/sdcard/Android/data/com.boji.app/files/screenshots/','artifacts/'],check=True,capture_output=True)
finally:
    subprocess.run(base+['shell','run-as','com.boji.app','rm','-f','files/live-deepseek-key'],capture_output=True)
'@ | python -
    if ($LASTEXITCODE -ne 0) { throw 'Live DeepSeek acceptance failed.' }
} finally {Remove-Item Env:\BOJI_LIVE_SERIAL -ErrorAction SilentlyContinue;Pop-Location}
