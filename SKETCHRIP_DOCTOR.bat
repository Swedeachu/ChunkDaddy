@echo off
setlocal
cd /d "%~dp0"
python scripts\chunkdaddy_cli.py --sketchrip --doctor
pause
