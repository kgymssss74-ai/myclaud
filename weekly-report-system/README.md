# SW Solution Part 1 — 팀 주간보고 시스템

Flask + SQLite 기반 사내 폐쇄망 주간보고 입력·취합·산출 시스템.

## 빠른 시작

```bat
pip install flask waitress python-docx openpyxl pytest
python db\seed_import.py --csv-dir <CSV_디렉터리>
python server.py
```
브라우저: `http://localhost:5000`

## 디렉터리 구조

```
weekly-report-system/
├── app.py               Flask 앱 팩토리
├── server.py            waitress WSGI 기동
├── db/
│   ├── schema.sql       SQLite 스키마 (WAL, FK)
│   └── seed_import.py   CSV → DB 시드 임포트
├── core/
│   ├── diff_engine.py   difflib 델타 엔진
│   └── validators.py    입력 유효성 검사
├── export/
│   ├── docx_builder.py  주간보고 DOC 생성
│   └── xlsx_builder.py  MBO/피드백/평가 XLS 생성
├── routes/              Flask Blueprint (register, weekly, diff, query, export)
├── templates/           Jinja2 HTML (base, register, weekly, dashboard)
├── static/vendor/       Alpine.js, Chart.js 로컬 번들 (→ 아래 주의)
├── backup/backup.py     일 1회 VACUUM + ZIP 백업
├── tests/               pytest 테스트 65개
└── ops/nssm_setup.md    Windows 서비스 등록 절차
```

## Alpine.js / Chart.js 실제 파일 반입

`static/vendor/` 에는 동작 확인용 스텁이 들어 있습니다.
인터넷 접속 가능한 PC에서 실제 파일을 다운로드한 후 서버에 복사하세요.

```bash
curl -o static/vendor/alpine.js \
  https://cdn.jsdelivr.net/npm/alpinejs@3.14.1/dist/cdn.min.js

curl -o static/vendor/chart.min.js \
  https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js
```

## 시드 임포트

```bat
python db\seed_import.py --csv-dir C:\seed_csv
```

CSV 파일: `members.csv`, `member_display_names.csv`, `projects.csv`,
`milestones.csv`, `mbo_template.csv`, `tags.csv`

## 주간 입력 순서

1. `/register` → 신규 과제 등록 (최초 1회, track 선택 시 필드 동적 표시)
2. `/weekly` → 파트원·과제·주차 선택 → 좌(전주)/우(금주) 분할 입력
3. 텍스트 입력 시 500ms 디바운스 후 `/preview-diff` AJAX → 파란색 delta 미리보기
4. 저장 → delta_spans DB 기록

## 산출물 다운로드

| URL | 설명 |
|-----|------|
| `/export/doc?week=2026-W23` | 주간보고 DOCX (TG 순서, 파란 delta) |
| `/export/xls/mbo?year=2026` | MBO 달성률 XLS |
| `/export/xls/feedback` | 피드백 XLS (전체 기간) |
| `/export/xls/eval?year=2026` | 연간 평가 XLS |

## 백업

```bat
python backup\backup.py --db weekly.db --out backup\
```
`backup/backup_YYYYMMDD.zip` 생성 (VACUUM DB + uploads 디렉터리 포함).

## 검증

```bat
python -m pytest tests\ -v
# 65개 테스트 전원 PASS 확인
```

## NSSM 서비스 등록

`ops/nssm_setup.md` 참조.
