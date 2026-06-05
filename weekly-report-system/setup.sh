#!/usr/bin/env bash
set -e
echo "============================================================"
echo " SW Solution Part 1 주간보고 시스템 설치 (Linux/Mac)"
echo "============================================================"

echo "[1/4] Python 패키지 설치..."
pip install flask waitress python-docx openpyxl --quiet
echo "      완료."

echo "[2/4] Alpine.js / Chart.js 다운로드 시도..."
curl -sf --max-time 10 -o static/vendor/alpine.js \
  https://cdn.jsdelivr.net/npm/alpinejs@3.14.1/dist/cdn.min.js \
  && echo "      Alpine.js 완료." || echo "      Alpine.js 실패 - 스텁 유지."

curl -sf --max-time 10 -o static/vendor/chart.min.js \
  https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js \
  && echo "      Chart.js 완료." || echo "      Chart.js 실패 - 스텁 유지."

echo "[3/4] 폴더 생성..."
mkdir -p uploads backup
echo "      완료."

echo "[4/4] DB 초기화 및 시드 임포트..."
if [ -f seed_csv/members.csv ]; then
    python db/seed_import.py --csv-dir seed_csv/
else
    echo "      seed_csv/members.csv 없음 - DB만 초기화."
    python -c "
import sqlite3, pathlib
sql = pathlib.Path('db/schema.sql').read_text()
c = sqlite3.connect('weekly.db')
c.execute('PRAGMA foreign_keys=ON')
c.executescript(sql)
c.commit(); c.close()
print('DB 초기화 완료.')
"
fi

echo ""
echo "============================================================"
echo " 설치 완료! 서버 시작: python server.py"
echo " 브라우저: http://localhost:5000"
echo "============================================================"
