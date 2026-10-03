$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$destination=Join-Path $root 'artifacts\BabyWise-source.zip'
New-Item -ItemType Directory -Force (Split-Path $destination -Parent) | Out-Null
$files=@('.gitignore','LICENSE','README.md','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','build.ps1','app\build.gradle.kts') | ForEach-Object {Get-Item -LiteralPath (Join-Path $root $_)}
foreach($folder in @('gradle','app\src','app\schemas','scripts','docs')) {
    $files+=Get-ChildItem -LiteralPath (Join-Path $root $folder) -File -Recurse
}
$stream=[IO.File]::Open($destination,[IO.FileMode]::Create)
$zip=New-Object IO.Compression.ZipArchive($stream,[IO.Compression.ZipArchiveMode]::Create)
try {
    foreach($file in $files) {
        $relative=$file.FullName.Substring($root.Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$relative,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {$zip.Dispose();$stream.Dispose()}
Get-Item -LiteralPath $destination | Select-Object FullName,Length
