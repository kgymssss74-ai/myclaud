@echo off
:: Check for Admin Privileges
net session >nul 2>&1
if %errorLevel% == 0 (
    goto :run_stop
) else (
    echo Requesting Administrative Privileges...
    powershell -Command "Start-Process '%~dpnx0' -Verb RunAs"
    exit /b
)

:run_stop
echo ========================================================
echo Stopping S25 YouTube Collector Backend Server (Port 8001)
echo ========================================================

for /f "tokens=5" %%a in ('netstat -aon ^| find "LISTENING" ^| find ":8001"') do (
    echo Found process %%a on port 8001. Stopping...
    taskkill /F /PID %%a
)

echo.
echo Server stopped.
pause
