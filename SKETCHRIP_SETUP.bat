@echo off
setlocal
cd /d "%~dp0"
python scripts\sketchrip_setup.py
if errorlevel 1 (
    echo.
    echo SketchRip setup failed. See the messages above.
    pause
    exit /b 1
)
echo.
echo Done. Build ChunkDaddy using python scripts\build.py before exporting mcworld files.
pause
