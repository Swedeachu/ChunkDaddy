@echo off
rem One click: build ChunkDaddy, then write the single zip to upload to a GitHub release.
setlocal
title ChunkDaddy Release Package
set "root=%~dp0.."

echo Building ChunkDaddy before packaging...
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-windows.ps1"
if errorlevel 1 goto :failed

rem setup-windows.ps1 bootstraps this Python; it exists by the time the build succeeds.
set "python=%root%\local\tools\Scripts\python.exe"
if not exist "%python%" (
    echo Could not find the build's Python at "%python%".
    goto :failed
)

echo.
echo Packaging the release archive...
"%python%" "%~dp0release.py" %*
if errorlevel 1 goto :failed

echo.
echo Done.
if not defined CI pause
exit /b 0

:failed
echo.
echo Release packaging failed. See the error above.
if not defined CI pause
exit /b 1
