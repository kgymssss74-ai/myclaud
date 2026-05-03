@echo off
title YouTube Collector Server (Port 8001)
echo ========================================================
echo Starting S25 YouTube Collector Backend Server...
echo ========================================================
echo.
echo The server will run on http://0.0.0.0:8001
echo To stop the server, run stop_server.bat or close this window.
echo.

python -m uvicorn server:app --host 0.0.0.0 --port 8001

pause
