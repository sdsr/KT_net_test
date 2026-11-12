param([string[]]$Tasks = @('testDebugUnitTest','lintDebug','assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$jdkFolder = Get-ChildItem '.tools/jdk' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if (!$jdkFolder) { throw 'Run tools/bootstrap.ps1 first.' }
$env:JAVA_HOME = $jdkFolder.FullName
$env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-cache'
$env:PATH = $env:JAVA_HOME + '\bin;' + (Join-Path $env:ANDROID_HOME 'platform-tools') + ';' + $env:PATH
& .\gradlew.bat @Tasks --console=plain --no-daemon
exit $LASTEXITCODE
