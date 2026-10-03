$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$gradleFile=Join-Path $root 'app\build.gradle.kts'
$versionName=([regex]::Match((Get-Content -LiteralPath $gradleFile -Raw),'versionName\s*=\s*"([^"]+)"')).Groups[1].Value
if([string]::IsNullOrWhiteSpace($versionName)) {throw 'Could not read versionName from app/build.gradle.kts'}
$apkName="BabyWise-$versionName.apk"
$signing=Join-Path $root '.signing'
New-Item -ItemType Directory -Force $signing | Out-Null
$passwordFile=Join-Path $signing 'password.txt'
$key=Join-Path $signing 'babywise.jks'
if(-not (Test-Path $key)) {
    if(-not (Test-Path $passwordFile)) {
        $bytes=New-Object byte[] 32
        $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes)
        $rng.Dispose()
        [IO.File]::WriteAllText($passwordFile,[Convert]::ToBase64String($bytes))
    }
    $env:BABYWISE_STORE_PASSWORD=[IO.File]::ReadAllText($passwordFile).Trim()
    & '.tools\jdk\jdk-17.0.15+6\bin\keytool.exe' -genkeypair -keystore $key -storepass:env BABYWISE_STORE_PASSWORD -keypass:env BABYWISE_STORE_PASSWORD -alias babywise -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=BabyWise Family, OU=Private Android App' -storetype JKS
    if($LASTEXITCODE -ne 0) {throw 'Signing key generation failed'}
}
$env:BABYWISE_STORE_PASSWORD=[IO.File]::ReadAllText($passwordFile).Trim()
$env:JAVA_HOME=Join-Path $root '.tools\jdk\jdk-17.0.15+6'
$env:ANDROID_HOME=Join-Path $root '.tools\sdk'
$env:GRADLE_USER_HOME=Join-Path $root '.gradle'
& '.tools\gradle-8.11.1\bin\gradle.bat' assembleRelease --console=plain
if($LASTEXITCODE -ne 0) {throw 'Release build failed'}
New-Item -ItemType Directory -Force artifacts | Out-Null
Copy-Item 'app\build\outputs\apk\release\app-release.apk' (Join-Path artifacts $apkName) -Force
& '.tools\sdk\build-tools\35.0.0\apksigner.bat' verify --verbose (Join-Path artifacts $apkName)
if($LASTEXITCODE -ne 0) {throw 'APK signature verification failed'}
Get-FileHash (Join-Path artifacts $apkName) -Algorithm SHA256 | Format-List
Remove-Item Env:\BABYWISE_STORE_PASSWORD
