@echo off
setlocal
title ChunkDaddy Setup and Build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-windows.ps1" %*
set "result=%errorlevel%"
echo.
if "%result%"=="0" (
    echo ChunkDaddy setup and build completed successfully.
) else (
    echo ChunkDaddy setup or build failed. See the error above.
)
if not defined CI pause
exit /b %result%
