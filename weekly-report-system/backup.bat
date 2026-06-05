@echo off
chcp 65001 > nul
echo 백업 실행 중...
python backup\backup.py --db weekly.db --uploads uploads\ --out backup\
echo 완료.
pause
