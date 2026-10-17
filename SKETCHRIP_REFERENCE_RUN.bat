@echo off
setlocal
cd /d "%~dp0"
if "%~1"=="" (
    echo Usage: SKETCHRIP_REFERENCE_RUN.bat "C:\path\to\Mineways.exe"
    echo The reference GLB and schematic are already included in fixtures\reference-all.
    echo An explicit Mineways executable is required for the real exporter comparison.
    exit /b 2
)
python scripts\chunkdaddy_cli.py --sketchrip --reference evaluate --out fixtures\reference-all
if errorlevel 1 exit /b 1
python scripts\chunkdaddy_cli.py --sketchrip --reference mineways --out fixtures\reference-all --mineways-exe "%~1" --mineways-mode both
if errorlevel 1 exit /b 1
python scripts\chunkdaddy_cli.py --sketchrip --reference evaluate-mineways --out fixtures\reference-all --mesh fixtures\reference-all\mineways-reference-individual.glb
if errorlevel 1 exit /b 1
copy /y fixtures\reference-all\mineways-evaluation.json fixtures\reference-all\mineways-evaluation-individual.json >nul
python scripts\chunkdaddy_cli.py --sketchrip --reference evaluate-mineways --out fixtures\reference-all --mesh fixtures\reference-all\mineways-reference-merged.glb
if errorlevel 1 exit /b 1
copy /y fixtures\reference-all\mineways-evaluation.json fixtures\reference-all\mineways-evaluation-merged.json >nul
echo Finished. Open fixtures\reference-all\mineways-evaluation-*.json and state_conformance.csv
