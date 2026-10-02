<#
.SYNOPSIS
    Void clean every world archive in a folder.

.DESCRIPTION
    Runs the ChunkDaddy void cleaner over every .zip (and .mcworld) in a folder, writing a
    cleaned archive per world into an output folder. Sources are never modified.

    Each world is a separate JVM, so one bad world cannot take the batch down with it; the
    script reports the failure and carries on.

.EXAMPLE
    scripts\void-clean-all.ps1 -Folder C:\Users\camde\source\repos\Swim.gg-2.0\savedWorlds -DryRun
    scripts\void-clean-all.ps1 -Folder C:\Users\camde\source\repos\Swim.gg-2.0\savedWorlds
#>
[CmdletBinding()]
param(
    # Folder holding the world archives.
    [Parameter(Mandatory = $true)][string]$Folder,

    # Where the cleaned archives go. Defaults to a "cleaned" folder beside the sources,
    # which keeps the originals and stops a second run eating its own output.
    [string]$OutputFolder,

    # Report what would go without writing anything. Do this first.
    [switch]$DryRun,

    # Also drop columns whose only sub-chunks are a single air palette entry.
    [switch]$AirSubChunks,

    # Heap for each world. Raise it if a very large world runs out.
    [string]$Heap = "6g"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

$jar = Join-Path $root 'worker\build\dist\chunkdaddy-worker.jar'
if (-not (Test-Path $jar)) {
    throw "No worker jar at $jar - run scripts\build-windows.bat first."
}

$java = 'java'
$bundled = Get-ChildItem (Join-Path $root 'local\jdk') -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($bundled) { $java = $bundled }

if (-not (Test-Path $Folder)) { throw "No such folder: $Folder" }
if (-not $OutputFolder) { $OutputFolder = Join-Path $Folder 'cleaned' }
if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $OutputFolder | Out-Null }

# Anything already produced by this script is skipped, so re-running is safe.
$worlds = Get-ChildItem -Path $Folder -File |
    Where-Object { $_.Extension -in '.zip', '.mcworld' } |
    Where-Object { $_.BaseName -notlike '*-cleaned' } |
    Sort-Object Name

if ($worlds.Count -eq 0) { Write-Host "No .zip or .mcworld files in $Folder"; return }

Write-Host ""
Write-Host "Void cleaning $($worlds.Count) world(s) from $Folder"
if ($DryRun) { Write-Host "DRY RUN - nothing will be written" } else { Write-Host "Output: $OutputFolder" }
Write-Host ("-" * 72)

$totalBefore = 0L
$totalAfter = 0L
$failed = @()

foreach ($world in $worlds) {
    $destination = Join-Path $OutputFolder ($world.BaseName + '-cleaned.zip')
    Write-Host ""
    Write-Host "== $($world.Name)  ($('{0:N1}' -f ($world.Length / 1MB)) MB)"

    $javaArgs = @("-Xmx$Heap", '-cp', $jar, 'gg.swim.chunkdaddy.worker.VoidCleanerMain', $world.FullName)
    if (-not $DryRun) { $javaArgs += @($destination, '--zip') }
    if ($DryRun) { $javaArgs += '--dry-run' }
    if ($AirSubChunks) { $javaArgs += '--air-sub-chunks' }
    $javaArgs += '--quiet'

    & $java @javaArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "FAILED: $($world.Name)"
        $failed += $world.Name
        continue
    }

    $totalBefore += $world.Length
    if (-not $DryRun -and (Test-Path $destination)) {
        $after = (Get-Item $destination).Length
        $totalAfter += $after
        $ratio = if ($after -gt 0) { $world.Length / $after } else { 0 }
        Write-Host ("   {0:N1} MB -> {1:N1} MB  ({2:N1}x smaller)" -f ($world.Length / 1MB), ($after / 1MB), $ratio)
    }
}

Write-Host ""
Write-Host ("-" * 72)
if (-not $DryRun -and $totalAfter -gt 0) {
    Write-Host ("Total: {0:N1} MB -> {1:N1} MB   saved {2:N1} MB ({3:N1}x smaller)" -f `
        ($totalBefore / 1MB), ($totalAfter / 1MB), (($totalBefore - $totalAfter) / 1MB), ($totalBefore / $totalAfter))
}
if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Warning "$($failed.Count) world(s) failed: $($failed -join ', ')"
    exit 1
}
Write-Host "Done."
