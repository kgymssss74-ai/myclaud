# NSSM Windows 서비스 등록 절차

## 전제 조건
- Python 3.11+ 설치 (PATH 등록)
- NSSM 2.24+ 다운로드: https://nssm.cc/download → nssm.exe 를 `C:\tools\nssm\` 에 배치
- `weekly-report-system/` 배포 위치: `C:\weekly-report\`

## 의존 패키지 설치 (폐쇄망 시 wheel 파일 반입)

```
pip install flask waitress python-docx openpyxl
```

### 폐쇄망 wheel 반입 목록
| 패키지 | wheel 파일 |
|--------|-----------|
| Flask 3.x | flask-3.x-py3-none-any.whl |
| waitress 3.x | waitress-3.x-py3-none-any.whl |
| python-docx 1.x | python_docx-1.x-py3-none-any.whl |
| openpyxl 3.x | openpyxl-3.x-py3-none-any.whl |
| lxml (docx 의존) | lxml-*.whl |

## DB 초기화

```bat
cd C:\weekly-report
python db\seed_import.py --csv-dir <CSV_디렉터리>
```

## NSSM 서비스 등록

```bat
nssm install WeeklyReport "C:\Python311\python.exe"
nssm set WeeklyReport AppDirectory "C:\weekly-report"
nssm set WeeklyReport AppParameters "server.py"
nssm set WeeklyReport AppStdout "C:\weekly-report\logs\out.log"
nssm set WeeklyReport AppStderr "C:\weekly-report\logs\err.log"
nssm set WeeklyReport AppRotateFiles 1
nssm set WeeklyReport AppRotateBytes 10485760
nssm set WeeklyReport Start SERVICE_AUTO_START
nssm start WeeklyReport
```

## 서비스 상태 확인

```bat
nssm status WeeklyReport
sc query WeeklyReport
```

## 접속 URL

`http://<서버IP>:5000`

## 주 1회 무결성 체크

```bat
python -c "import sqlite3; c=sqlite3.connect('weekly.db'); print(c.execute('PRAGMA integrity_check').fetchone())"
```

## 일 1회 자동 백업 (Windows 작업 스케줄러)

```bat
C:\Python311\python.exe C:\weekly-report\backup\backup.py --db C:\weekly-report\weekly.db --out C:\weekly-report\backup
```

작업 스케줄러 → 새 작업 → 트리거: 매일 오전 2시 → 동작: 위 명령어

## Alpine.js / Chart.js 실제 파일 반입

인터넷 접속 가능한 PC에서:
```bat
curl -o static\vendor\alpine.js https://cdn.jsdelivr.net/npm/alpinejs@3.14.1/dist/cdn.min.js
curl -o static\vendor\chart.min.js https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js
```
이후 폐쇄망 서버로 파일 복사.
