@echo off
setlocal
cd /d "%~dp0"
echo Starting VARDHANI verified Windows build...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0BUILD_VARDHANI_WINDOWS.ps1"
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (
  echo VARDHANI build FAILED with exit code %RC%.
) else (
  echo VARDHANI build completed successfully.
)
echo.
pause
exit /b %RC%
