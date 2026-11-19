[CmdletBinding()]
param([switch]$Run, [string]$BuildDir)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$env:UV_PYTHON_INSTALL_DIR = "$root\local\python"
$env:UV_CACHE_DIR = "$root\local\uv-cache"
# Astral's release mirror: GitHub answers anonymous release downloads from busy shared IPs
# (CI runners) with 403 now and then. uv fetches its Python builds from here too.
$astralMirror = 'https://releases.astral.sh/github'
$env:UV_PYTHON_INSTALL_MIRROR = "$astralMirror/python-build-standalone/releases/download"
New-Item -ItemType Directory -Force "$root\local" | Out-Null

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

if (-not [Environment]::Is64BitOperatingSystem -or $env:PROCESSOR_ARCHITECTURE -eq 'ARM64') {
    throw 'The Windows setup currently supports x64 PCs.'
}

# Install the compiler only if no suitable existing Visual Studio instance is found.
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vs = $null
if (Test-Path $vswhere) {
    $vs = & $vswhere -latest -products '*' -version '[17.0,)' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
}
if (-not $vs) {
    Write-Host 'Installing Visual Studio C++ Build Tools (Windows may request administrator approval)...'
    $installer = "$root\local\vs-buildtools.exe"
    Invoke-WebRequest -UseBasicParsing 'https://aka.ms/vs/17/release/vs_buildtools.exe' -OutFile $installer
    $signature = Get-AuthenticodeSignature $installer
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Microsoft Corporation') {
        throw 'Visual Studio installer signature verification failed.'
    }
    $process = Start-Process $installer -Wait -PassThru -WindowStyle Hidden -ArgumentList '--passive --wait --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended'
    if ($process.ExitCode -eq 3010) { throw 'Visual Studio requires a reboot. Restart Windows, then run scripts\build-windows.bat again.' }
    if ($process.ExitCode -ne 0) { throw "Visual Studio installation failed: $($process.ExitCode)" }
    $vs = & $vswhere -latest -products '*' -version '[17.0,)' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $vs) { throw 'Visual Studio C++ tools were not found after installation.' }
}
Import-Module "$vs\Common7\Tools\Microsoft.VisualStudio.DevShell.dll"
Enter-VsDevShell -VsInstallPath $vs -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64'

if (-not (Test-Path "$root\local\uv\uv.exe")) {
    Write-Host 'Downloading the project-local Python bootstrap tool...'
    $uvAsset = 'uv/releases/download/0.8.22/uv-x86_64-pc-windows-msvc.zip'
    $uvSha256 = '5049375aa2a5162f132b2c1cb992e25d42d47d934cab8c174dbe6f60973dcc12'
    $downloaded = $false
    foreach ($base in @($astralMirror, 'https://github.com/astral-sh')) {
        for ($attempt = 1; $attempt -le 5 -and -not $downloaded; $attempt++) {
            try {
                Invoke-WebRequest -UseBasicParsing "$base/$uvAsset" -OutFile "$root\local\uv.zip"
                $downloaded = (Get-FileHash -Algorithm SHA256 "$root\local\uv.zip").Hash -eq $uvSha256
                if (-not $downloaded) { Write-Host "uv from $base failed its checksum." }
            } catch {
                Write-Host "uv download from $base failed (attempt $attempt): $($_.Exception.Message)"
                Start-Sleep -Seconds (3 * $attempt)
            }
        }
        if ($downloaded) { break }
    }
    if (-not $downloaded) { throw 'uv download failed from every source.' }
    Expand-Archive "$root\local\uv.zip" -DestinationPath "$root\local\uv" -Force
}
$uv = "$root\local\uv\uv.exe"
$python = "$root\local\tools\Scripts\python.exe"
if (-not (Test-Path $python)) {
    Invoke-Checked $uv @('venv', '--python', '3.12.11', '--managed-python', "$root\local\tools")
}
Invoke-Checked $uv @('pip', 'install', '--python', $python, '-r', "$root\scripts\build-requirements.txt")
$env:PATH = "$root\local\tools\Scripts;$env:PATH"
$arguments = @("$root\scripts\build.py", '--prepared')
if ($Run) { $arguments += '--run' }
if ($BuildDir) { $arguments += @('--build-dir', $BuildDir) }
Invoke-Checked $python $arguments
