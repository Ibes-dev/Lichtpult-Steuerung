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

    # protoc-Java-Konflikt: CURRENT_PROPERTYVALUE erzeugt die int-Konstante CURRENT_PROPERTYVALUE_VALUE,
    # die mit dem gleichnamigen Enum-Wert (=3) kollidiert. Umbenennung ist wire-kompatibel.
    $dev = Join-Path $dst "Common\Types\Device\DeviceServiceTypes.proto"
    $txt = [IO.File]::ReadAllText($dev)
    $txt = $txt -replace '\bCURRENT_PROPERTYVALUE_VALUE = 3;', 'CURRENT_PROPERTYVALUE_VAL = 3;'
    [IO.File]::WriteAllText($dev, $txt)
    if (-not ($txt -match 'CURRENT_PROPERTYVALUE_VAL = 3;')) { throw "Patch EValueType fehlgeschlagen" }
    $n = (Get-ChildItem $dst -Recurse -Filter *.proto).Count
    Write-Host "Protos $Version -> $dst ($n Dateien)"
}
finally {
    Remove-Item $tmp -Recurse -Force
}
