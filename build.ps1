param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'))
$ErrorActionPreference='Stop'
$env:JAVA_HOME=Join-Path $PSScriptRoot '.tools\jdk\jdk-17.0.15+6'
$env:ANDROID_HOME=Join-Path $PSScriptRoot '.tools\sdk'
$env:GRADLE_USER_HOME=Join-Path $PSScriptRoot '.gradle'
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
$gradle=Join-Path $PSScriptRoot '.tools\gradle-8.11.1\bin\gradle.bat'
if(-not (Test-Path $gradle)) {throw 'Run scripts/setup.ps1 first.'}
& $gradle @Tasks --console=plain
exit $LASTEXITCODE
