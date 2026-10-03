$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
New-Item -ItemType Directory -Force .tools | Out-Null
$downloads=@(
    @('https://api.adoptium.net/v3/binary/version/jdk-17.0.15%2B6/windows/x64/jdk/hotspot/normal/eclipse','jdk.zip','jdk'),
    @('https://services.gradle.org/distributions/gradle-8.11.1-bin.zip','gradle.zip','.'),
    @('https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip','android-tools.zip','android')
)
foreach($item in $downloads) {
    $zip=Join-Path '.tools' $item[1]
    if(-not (Test-Path $zip)) {Invoke-WebRequest $item[0] -OutFile $zip -UseBasicParsing}
    Expand-Archive $zip (Join-Path '.tools' $item[2]) -Force
}
$env:JAVA_HOME=Join-Path $root '.tools\jdk\jdk-17.0.15+6'
$env:ANDROID_HOME=Join-Path $root '.tools\sdk'
& .tools\android\cmdline-tools\bin\sdkmanager.bat --sdk_root=$env:ANDROID_HOME --licenses
& .tools\android\cmdline-tools\bin\sdkmanager.bat --sdk_root=$env:ANDROID_HOME 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
