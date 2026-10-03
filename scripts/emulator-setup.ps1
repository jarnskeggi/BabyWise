param([string]$Api='35')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME=Join-Path $root '.tools\jdk\jdk-17.0.15+6'
$env:ANDROID_HOME=Join-Path $root '.tools\sdk'
$env:ANDROID_AVD_HOME=Join-Path $root '.tools\avd'
New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
$image="system-images;android-$Api;google_apis;x86_64"
& (Join-Path $root '.tools\android\cmdline-tools\bin\sdkmanager.bat') --sdk_root=$env:ANDROID_HOME 'emulator' $image
if($LASTEXITCODE -ne 0) {throw 'SDK image installation failed'}
'no' | & (Join-Path $root '.tools\android\cmdline-tools\bin\avdmanager.bat') create avd --name "babywise-$Api" --package $image --path (Join-Path $env:ANDROID_AVD_HOME "babywise-$Api.avd") --force
$config=Join-Path $env:ANDROID_AVD_HOME "babywise-$Api.avd\config.ini"
$text=[IO.File]::ReadAllText($config)
$text=$text -replace '(?m)^image.sysdir.1=.*$', "image.sysdir.1=system-images\android-$Api\google_apis\x86_64\"
$text=$text -replace '(?m)^hw.lcd.width=.*$', 'hw.lcd.width=480'
$text=$text -replace '(?m)^hw.lcd.height=.*$', 'hw.lcd.height=960'
$text=$text -replace '(?m)^hw.lcd.density=.*$', 'hw.lcd.density=200'
$text=$text -replace '(?m)^hw.keyboard=.*$', 'hw.keyboard=yes'
if($Api -eq '37.0') {$text=$text -replace '(?m)^hw.ramSize=.*$', 'hw.ramSize=4096'}
[IO.File]::WriteAllText($config,$text)
