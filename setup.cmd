@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup-windows.ps1" %*
set "result=%errorlevel%"
if not defined CI pause
exit /b %result%
