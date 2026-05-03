@echo off
chcp 65001 >nul
echo Starting YouTube Link Collector...
cd /d "%~dp0"
python main.py
pause
