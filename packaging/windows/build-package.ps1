# Build and assemble the verified Windows runtime into a new output directory.
[CmdletBinding()]
param([string]$OutputDirectory = "$PSScriptRoot\..\..\build\package\windows\ChunkDaddy")
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
& "$root\scripts\setup-windows.ps1"
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw "Output already exists: $output. Choose a new directory." }
New-Item -ItemType Directory -Path "$output\LICENSES" -Force | Out-Null
$build = "$root\build\windows-release"
Copy-Item -LiteralPath "$build\chunkdaddy.exe" -Destination $output
Get-ChildItem -LiteralPath $build -Filter '*.dll' | Copy-Item -Destination $output
foreach ($folder in @('worker', 'runtime', 'platforms', 'styles', 'imageformats', 'iconengines', 'generic', 'networkinformation', 'tls', 'translations')) {
    if (Test-Path -LiteralPath "$build\$folder") {
        Copy-Item -LiteralPath "$build\$folder" -Destination $output -Recurse
    }
}
Copy-Item -LiteralPath "$root\third_party\NOTICES.md" -Destination "$output\LICENSES"
Copy-Item -LiteralPath "$root\third_party\chunker\LICENSE" -Destination "$output\LICENSES\chunker-LICENSE"
Write-Host "Package: $output"
