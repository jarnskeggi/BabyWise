param([string]$Api='35',[string]$Port='5554',[string]$Gpu='software',[switch]$WipeData)
$root=Split-Path $PSScriptRoot -Parent
$env:ANDROID_HOME=Join-Path $root '.tools\sdk'
$env:ANDROID_AVD_HOME=Join-Path $root '.tools\avd'
$emulator=Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
$memoryMb=if($Api -eq '37.0') {4096} else {2048}
$arguments=@('-avd',"babywise-$Api",'-port',$Port,'-no-window','-no-audio','-no-boot-anim','-no-snapshot','-gpu',$Gpu,'-accel','auto','-memory',$memoryMb,'-cores','4')
if($WipeData) {$arguments+='-wipe-data'}
$process=Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root ".tools\emulator-$Api.log") -RedirectStandardError (Join-Path $root ".tools\emulator-$Api.err.log") -PassThru
Wait-Process -Id $process.Id
