# 설치 가이드 — SW Solution Part 1 주간보고 시스템

## 1. 사전 요구사항

| 항목 | 버전 | 확인 명령 |
|------|------|-----------|
| Python | 3.11 이상 | `python --version` |
| pip | 최신 권장 | `pip --version` |
| Windows | 10 / 11 | 서버 PC 기준 |

### Python 설치 (미설치 시)
1. https://www.python.org/downloads/ 접속
2. **Python 3.11** 다운로드 → 설치
3. 설치 시 **"Add Python to PATH"** 반드시 체크

---

## 2. 압축 해제

```
weekly-report-system.zip 압축 해제
→ C:\weekly-report-system\ 폴더 생성 권장
```

---

## 3. 자동 설치 (Windows)

`setup.bat` 더블클릭 (또는 CMD에서 실행):

```bat
cd C:\weekly-report-system
setup.bat
```

설치 과정:
1. Python 패키지 설치 (Flask, waitress, python-docx, openpyxl)
2. SQLite DB 초기화 (`weekly.db` 생성)
3. 시드 데이터 임포트 (members, projects, milestones, MBO, tags)
4. Alpine.js / Chart.js 다운로드 시도 (인터넷 연결 시 자동, 실패 시 스텁 유지)

---

## 4. 자동 설치 (Linux / Mac)

```bash
cd weekly-report-system
chmod +x setup.sh start.sh
bash setup.sh
```

---

## 5. 수동 설치 (인터넷 없는 폐쇄망)

### 패키지 설치
```bat
pip install flask waitress python-docx openpyxl
```

폐쇄망 환경에서는 wheel 파일을 인터넷 PC에서 미리 다운로드하여 반입:
```bat
pip download flask waitress python-docx openpyxl -d wheels\
# → wheels\ 폴더를 USB로 반입 후:
pip install --no-index --find-links=wheels\ flask waitress python-docx openpyxl
```

### DB 초기화 및 시드 임포트
```bat
python db\seed_import.py --csv-dir seed_csv\
```

### Alpine.js / Chart.js 반입
인터넷 PC에서 다운로드 후 복사:
```bat
curl -o static\vendor\alpine.js https://cdn.jsdelivr.net/npm/alpinejs@3.14.1/dist/cdn.min.js
curl -o static\vendor\chart.min.js https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js
```

---

## 6. 서버 시작

```bat
start.bat          ← 더블클릭 또는 CMD 실행
```

브라우저 접속: **http://localhost:5000**

파트원 PC에서 접속: **http://서버IP주소:5000**
(서버 IP 확인: `ipconfig` → IPv4 주소)

---

## 7. Windows 서비스 등록 (상시 가동, 선택사항)

PC 재부팅 후에도 자동 시작되도록 NSSM 서비스 등록:

```bat
nssm_install.bat
```

상세 절차: `ops\nssm_setup.md` 참조

---

## 8. 방화벽 설정 (파트원 PC 접속 허용)

서버 PC 방화벽에서 5000번 포트 허용:
```bat
netsh advfirewall firewall add rule name="WeeklyReport" ^
  dir=in action=allow protocol=TCP localport=5000
```

---

## 9. 설치 확인

```bat
python -m pytest tests\ -v
```
65개 테스트 전원 PASS 확인.

---

## 디렉터리 구조

```
weekly-report-system\
├── app.py              Flask 앱 엔트리
├── server.py           waitress 서버
├── weekly.db           SQLite DB (자동 생성)
├── seed_csv\           시드 CSV 파일
├── db\                 스키마 및 임포트 스크립트
├── core\               diff 엔진, 유효성 검사
├── export\             DOC / XLS 생성
├── routes\             Flask API 라우트
├── templates\          HTML 템플릿
├── static\vendor\      Alpine.js, Chart.js (로컬)
├── uploads\            이미지 업로드 저장소
├── backup\             백업 스크립트
├── tests\              pytest 테스트
├── docs\               설치·사용 가이드
└── ops\                NSSM 설정 문서
```
