# Lädt die offiziellen DMXControl-3-Protobuf-Dateien (Client + Common) in app\src\main\proto.
# Version MUSS zur installierten DMXControl-Version passen (Tags: v3.3.0, v3.3.1, v3.3.2, ...).
param([string]$Version = "v3.3.2")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dst  = Join-Path $root "app\src\main\proto"
$tmp  = Join-Path ([IO.Path]::GetTempPath()) ("dmxc3-ni-" + [Guid]::NewGuid())
New-Item -ItemType Directory $tmp | Out-Null
try {
    $zip = Join-Path $tmp "ni.zip"
    Invoke-WebRequest "https://github.com/DMXControl/DMXControl3-Network-Interface/archive/refs/tags/$Version.zip" -OutFile $zip
    Expand-Archive $zip -DestinationPath $tmp
    $src = Get-ChildItem $tmp -Directory | Select-Object -First 1
    Get-ChildItem $dst -Force | Where-Object { $_.Name -ne ".gitkeep" } | Remove-Item -Recurse -Force
    Copy-Item (Join-Path $src.FullName "proto\Common") $dst -Recurse
    Copy-Item (Join-Path $src.FullName "proto\Client") $dst -Recurse
    $n = (Get-ChildItem $dst -Recurse -Filter *.proto).Count
    Write-Host "Protos $Version -> $dst ($n Dateien)"
}
finally {
    Remove-Item $tmp -Recurse -Force
}
