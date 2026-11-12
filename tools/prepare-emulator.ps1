$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME = (Get-ChildItem '.tools/jdk' -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools/avds'
$env:PATH = $env:JAVA_HOME + '\bin;' + $env:PATH
New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME | Out-Null
$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools/cmdline-tools/bin/sdkmanager.bat'
& $sdkManager --sdk_root=$env:ANDROID_HOME 'emulator' 'system-images;android-35;google_apis;x86_64'
if ($LASTEXITCODE -ne 0) { throw 'Emulator package installation failed' }
$avdManager = Join-Path $env:ANDROID_HOME 'cmdline-tools/cmdline-tools/bin/avdmanager.bat'
if (!(Test-Path (Join-Path $env:ANDROID_AVD_HOME 'NetCheck.ini'))) {
    'no' | & $avdManager create avd --name NetCheck --package 'system-images;android-35;google_apis;x86_64' --device pixel_6 --path (Join-Path $env:ANDROID_AVD_HOME 'NetCheck.avd')
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed' }
}
Write-Output 'Headless test emulator ready.'
