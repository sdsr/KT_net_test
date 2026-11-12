$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
New-Item -ItemType Directory -Force -Path '.tools/jdk','.tools/android-sdk/cmdline-tools','.tools/android-user','.tools/gradle-cache','.downloads' | Out-Null
if (!(Test-Path '.downloads/jdk17.json')) {
    & curl.exe -L --fail --retry 2 -o .downloads/jdk17.json 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse'
    if ($LASTEXITCODE -ne 0) { throw 'JDK metadata download failed' }
}
$jdkMetadata = Get-Content -Raw '.downloads/jdk17.json' | ConvertFrom-Json
$jdkArchive = $jdkMetadata[0].binary.package
if (!(Test-Path '.downloads/jdk17.zip')) {
    & curl.exe -L --fail --retry 2 -o .downloads/jdk17.zip $jdkArchive.link
    if ($LASTEXITCODE -ne 0) { throw 'JDK download failed' }
}
if ((Get-FileHash '.downloads/jdk17.zip' -Algorithm SHA256).Hash -ne $jdkArchive.checksum) { throw 'JDK checksum mismatch' }
if (!(Get-ChildItem '.tools/jdk' -Directory)) { & tar.exe -xf .downloads/jdk17.zip -C .tools/jdk; if ($LASTEXITCODE -ne 0) { throw 'JDK extraction failed' } }
$env:JAVA_HOME = (Get-ChildItem '.tools/jdk' -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-cache'
$env:PATH = $env:JAVA_HOME + '\bin;' + $env:PATH
if (!(Test-Path '.downloads/android-repository.xml')) {
    & curl.exe -L --fail --retry 2 -o .downloads/android-repository.xml https://dl.google.com/android/repository/repository2-1.xml
    if ($LASTEXITCODE -ne 0) { throw 'Android repository download failed' }
}
[xml]$repoXml = Get-Content -Raw '.downloads/android-repository.xml'
$cmdPackage = $repoXml.SelectSingleNode("//*[local-name()='remotePackage' and @path='cmdline-tools;19.0']")
if (!$cmdPackage) { throw 'Command-line tools 19.0 not listed in official repository' }
$cmdArchive = $cmdPackage.SelectSingleNode("archives/archive[host-os='windows']/complete")
if (!(Test-Path '.downloads/android-commandline.zip')) {
    & curl.exe -L --fail --retry 2 -o .downloads/android-commandline.zip ('https://dl.google.com/android/repository/' + $cmdArchive.url)
    if ($LASTEXITCODE -ne 0) { throw 'Android tools download failed' }
}
if ((Get-FileHash '.downloads/android-commandline.zip' -Algorithm SHA1).Hash -ne $cmdArchive.checksum) { throw 'Android command-line tools checksum mismatch' }
if (!(Test-Path '.tools/android-sdk/cmdline-tools/cmdline-tools/bin/sdkmanager.bat')) {
    & tar.exe -xf .downloads/android-commandline.zip -C .tools/android-sdk/cmdline-tools
    if ($LASTEXITCODE -ne 0) { throw 'Android tools extraction failed' }
}
$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools/cmdline-tools/bin/sdkmanager.bat'
(1..50 | ForEach-Object { 'y' }) | & $sdkManager --sdk_root=$env:ANDROID_HOME --licenses
& $sdkManager --sdk_root=$env:ANDROID_HOME 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK install failed' }
$sdkProperty = 'sdk.dir=' + (($env:ANDROID_HOME -replace '\\','/') -replace ':','\:')
[System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), $sdkProperty + "`n", [System.Text.UTF8Encoding]::new($false))
& "$env:JAVA_HOME/bin/java.exe" -version
Write-Output 'Build toolchain ready.'
