param([Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2.0
$utf8=New-Object Text.UTF8Encoding($false,$true)
$path=Join-Path $Root 'app\src\main\java\com\mk15\portinspector\MainActivity.java'
$text=[IO.File]::ReadAllText($path,$utf8).Replace("`r`n","`n")
[byte[]]$bytes=$utf8.GetBytes($text)
[byte[]]$prefix=$utf8.GetBytes(('blob '+$bytes.Length+[char]0))
$hash=[Security.Cryptography.SHA1]::Create()
try { $actual=([BitConverter]::ToString($hash.ComputeHash([byte[]]($prefix+$bytes)))).Replace('-','').ToLowerInvariant() }
finally { $hash.Dispose() }
if($actual -ne '21a9b7e5151aab07ab38c973df99b99c39905be2'){ throw ('Unexpected MainActivity base: '+$actual) }
$start=$text.IndexOf('    private View buildUi() {',[StringComparison]::Ordinal)
$end=$text.IndexOf('    private TextView text(',[StringComparison]::Ordinal)
if($start -lt 0 -or $end -le $start){ throw 'Expected buildUi block is missing.' }
$block=$text.Substring($start,$end-$start)
$marker='        return root;'
if([regex]::Matches($block,[regex]::Escape($marker)).Count -ne 1){ throw 'Unexpected buildUi return count.' }
$replacement=@'
        return OperatorScreen.arrange(this, title, warning, statusLine, transportRow,
                commands, researchPanel, controlsResearchText, finderButtons, finderText,
                reportText, saText, channelsScroll, scanText, logText);
'@
$block=$block.Replace($marker,$replacement.Replace("`r`n","`n"))
$text=($text.Substring(0,$start)+$block+$text.Substring($end)).Replace('1.5.1','1.5.2')
[IO.File]::WriteAllText($path,$text,$utf8)
foreach($name in @('app/build.gradle','tools/build_windows.ps1','tools/publish_run.ps1','app/src/main/java/com/mk15/portinspector/ReportTools.java')){
    $path=Join-Path $Root $name
    $text=[IO.File]::ReadAllText($path,$utf8).Replace("`r`n","`n")
    if(-not $text.Contains('1.5.1')){ throw ('Unexpected version in '+$name) }
    $text=$text.Replace('1.5.1','1.5.2')
    if($name -eq 'app/build.gradle'){
        if(-not $text.Contains('versionCode 51')){ throw 'Unexpected versionCode.' }
        $text=$text.Replace('versionCode 51','versionCode 52')
    }
    if($name.EndsWith('.ps1') -and $text -match '[^\x00-\x7F]'){ throw ('Project PowerShell loader must remain ASCII: '+$name) }
    [IO.File]::WriteAllText($path,$text,$utf8)
}
Write-Host 'LAYOUT_PATCH_APPLIED=1.5.2'
