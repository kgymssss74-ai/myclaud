@echo off
chcp 65001 > nul
echo ============================================================
echo  SW Solution Part 1 주간보고 시스템 설치
echo ============================================================
echo.

:: Python 확인
python --version > nul 2>&1
if %errorlevel% neq 0 (
    echo [오류] Python 이 설치되어 있지 않습니다.
    echo        https://www.python.org/downloads/ 에서 Python 3.11+ 를 설치하세요.
    echo        설치 시 "Add Python to PATH" 를 반드시 체크하세요.
    pause
    exit /b 1
)

echo [1/4] Python 패키지 설치 중...
pip install flask waitress python-docx openpyxl --quiet
if %errorlevel% neq 0 (
    echo [오류] 패키지 설치 실패. 인터넷 연결을 확인하거나 wheels\ 폴더를 준비하세요.
    echo        폐쇄망 설치: pip install --no-index --find-links=wheels\ flask waitress python-docx openpyxl
    pause
    exit /b 1
)
echo       완료.

echo.
echo [2/4] Alpine.js / Chart.js 다운로드 시도 중...
curl -s --max-time 10 -o static\vendor\alpine.js https://cdn.jsdelivr.net/npm/alpinejs@3.14.1/dist/cdn.min.js 2>nul
if %errorlevel% equ 0 (
    echo       Alpine.js 다운로드 완료.
) else (
    echo       Alpine.js 다운로드 실패 - 스텁 유지 (UI 기능 제한됨).
)
curl -s --max-time 10 -o static\vendor\chart.min.js https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js 2>nul
if %errorlevel% equ 0 (
    echo       Chart.js 다운로드 완료.
) else (
    echo       Chart.js 다운로드 실패 - 스텁 유지 (차트 미표시).
)

echo.
echo [3/4] 폴더 생성 중...
if not exist uploads mkdir uploads
if not exist backup  mkdir backup
echo       완료.

echo.
echo [4/4] DB 초기화 및 시드 데이터 임포트 중...
if exist seed_csv\members.csv (
    python db\seed_import.py --csv-dir seed_csv\
    if %errorlevel% neq 0 (
        echo [경고] 시드 임포트 실패. seed_csv\ 폴더의 CSV 파일을 확인하세요.
    ) else (
        echo       시드 임포트 완료.
    )
) else (
    echo [경고] seed_csv\members.csv 파일이 없습니다.
    echo        시드 없이 DB만 초기화합니다.
    python -c "import sqlite3,pathlib; sql=pathlib.Path('db/schema.sql').read_text(); c=sqlite3.connect('weekly.db'); c.execute('PRAGMA foreign_keys=ON'); c.executescript(sql); c.commit(); c.close(); print('DB 초기화 완료.')"
)

echo.
echo ============================================================
echo  설치 완료!
echo  start.bat 을 실행하여 서버를 시작하세요.
echo  브라우저: http://localhost:5000
echo ============================================================
pause
