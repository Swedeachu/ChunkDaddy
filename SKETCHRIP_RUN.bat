@echo off
cd /d "%~dp0"
python scripts\chunkdaddy_cli.py --sketchrip %*
if errorlevel 1 pause
