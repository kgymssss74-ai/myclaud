@echo off
chcp 65001 >nul
echo Starting YouTube Link Collector (Debug)...
cd /d "D:\Claud\youtube-collecting"
echo Python path:
where python 2>&1
echo.
echo Running app...
python main.py 2>&1
echo.
echo Exit code: %ERRORLEVEL%
pause
