@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0gradle\wrapper\bootstrap-gradle-wrapper.ps1" %*
set "exit_code=%ERRORLEVEL%"
endlocal & exit /b %exit_code%
