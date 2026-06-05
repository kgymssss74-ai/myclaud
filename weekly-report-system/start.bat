@echo off
chcp 65001 > nul
echo ============================================================
echo  주간보고 시스템 시작 중...
echo  브라우저: http://localhost:5000
echo  종료: Ctrl+C
echo ============================================================
python server.py
pause
