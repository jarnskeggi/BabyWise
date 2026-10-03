# Convert the four unmodified LMS columns from official WHO expanded tables.
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root=Split-Path $PSScriptRoot -Parent
$destination=Join-Path $root 'app\src\main\assets\who'
New-Item -ItemType Directory -Force $destination | Out-Null
foreach($file in Get-ChildItem (Join-Path $root '.tools\who') -Filter '*.xlsx') {
    $zip=[IO.Compression.ZipFile]::OpenRead($file.FullName)
    try {
        $reader=[IO.StreamReader]::new($zip.GetEntry('xl/worksheets/sheet1.xml').Open())
        [xml]$sheet=$reader.ReadToEnd()
        $reader.Dispose()
        $lines=[Collections.Generic.List[string]]::new()
        $lines.Add('day,l,m,s')
        foreach($row in $sheet.worksheet.sheetData.row) {
            if([int]$row.r -eq 1) {continue}
            $cells=@($row.c | Select-Object -First 4)
            if($cells.Count -ne 4 -or $cells[0].t -eq 's') {continue}
            $values=@($cells | ForEach-Object {$_.v})
            if($values[0] -match '^\d+$') {$lines.Add(($values -join ','))}
        }
        $name=$file.BaseName.Replace('-zscore-expanded-tables','')+'.csv'
        [IO.File]::WriteAllLines((Join-Path $destination $name),$lines,[Text.UTF8Encoding]::new($false))
        "$name : $($lines.Count-1) LMS rows"
    } finally {$zip.Dispose()}
}
