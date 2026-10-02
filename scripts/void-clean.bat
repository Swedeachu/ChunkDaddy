@echo off
rem Remove columns that hold nothing from a Bedrock world, leaving every other record alone.
rem
rem   scripts\void-clean.bat <world-or-archive> [output] [--dry-run] [--air-sub-chunks] [--folder]
rem
rem Uses the worker jar from the last build. Run scripts\build-windows.bat first if missing.
setlocal
set "root=%~dp0.."
set "jar=%root%\worker\build\dist\chunkdaddy-worker.jar"
if not exist "%jar%" (
    echo No worker jar at %jar% - run scripts\build-windows.bat first. 1>&2
    exit /b 1
)
set "java=java"
for /d %%d in ("%root%\local\jdk\*") do if exist "%%d\bin\java.exe" set "java=%%d\bin\java.exe"
"%java%" -Xmx6g -cp "%jar%" gg.swim.chunkdaddy.worker.VoidCleanerMain %*
exit /b %errorlevel%
