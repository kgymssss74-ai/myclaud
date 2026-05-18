# FileScope 버그 수정 적용 요청

## 프로젝트 개요

FileScope는 PyQt6 + Whoosh FTS + SQLite + watchdog 기반의 PC 문서 전문 검색 데스크탑 앱입니다.

**기술 스택:**
- UI: PyQt6
- FTS 엔진: Whoosh 2.7
- 메타데이터 DB: SQLite3
- 파일 감시: watchdog
- 패키징: PyInstaller

**프로젝트 구조:**
```
filescope/
├── main.py
├── core/
│   ├── indexer.py
│   ├── searcher.py
│   ├── watcher.py
│   └── parsers/
│       ├── factory.py
│       ├── base.py
│       ├── pdf_parser.py
│       ├── docx_parser.py
│       ├── pptx_parser.py
│       └── xlsx_parser.py
├── storage/
│   └── db.py
└── requirements.txt
```

---

## 첨부 파일 설명

`filescope_fixes.zip` 안에 5개 버그에 대한 **수정 참조 코드**가 들어 있습니다.

```
filescope_fixes/
├── core/
│   ├── writer_queue.py      ← BUG-1 핵심 클래스
│   ├── indexer_safe.py      ← BUG-1 + BUG-2 적용 예시
│   └── parsers/
│       ├── pdf_parser.py    ← BUG-4 수정본
│       ├── xlsx_parser.py   ← BUG-5 수정본
│       └── docx_parser.py   ← BUG-5 수정본
└── storage/
    └── db.py                ← BUG-3 수정본
```

---

## 수정 작업 요청

아래 5개 버그를 수정해 주세요. 각 버그마다 **무엇을 바꿔야 하는지** 상세히 설명합니다.

---

### BUG-1: Whoosh LockError (최우선)

**원인:** watcher 스레드와 bulk_scan이 동시에 `ix.writer()`를 호출해 충돌

**수정 방법:**

1. `filescope_fixes/core/writer_queue.py`를 `core/writer_queue.py`로 복사합니다.

2. `core/indexer.py`에서 `ix.writer()`를 직접 호출하는 모든 코드를 `WriterQueue.submit()`으로 교체합니다.

   **변경 전 (예시):**
   ```python
   def add_document(self, path, content):
       writer = self._ix.writer()
       writer.update_document(path=str(path), content=content)
       writer.commit()
   ```

   **변경 후:**
   ```python
   # __init__ 에서:
   from core.writer_queue import WriterQueue
   self._wq = WriterQueue(self._ix)

   # add_document:
   def add_document(self, path, content):
       def _write(writer):
           writer.update_document(path=str(path), content=content)
       self._wq.submit(_write)
   ```

3. `bulk_scan`은 `submit_batch(fns)` 메서드로 묶어서 1회 commit 처리:
   ```python
   def bulk_scan(self, file_list):
       fns = []
       for path, content, ext, mtime in file_list:
           def _write(writer, p=path, c=content, e=ext, m=mtime):
               writer.update_document(path=str(p), content=c, ext=e, mtime=m)
           fns.append(_write)
       self._wq.submit_batch(fns)
   ```

4. watcher의 `on_modified`, `on_created`, `on_deleted` 콜백이 indexer의 `add_document`/`remove`를 호출하도록 유지 (WriterQueue가 내부에서 직렬화하므로 watcher 코드는 그대로 사용 가능)

5. 앱 종료 시 `self._wq.stop()` 호출 추가 (main_window.py의 closeEvent 또는 QApplication.aboutToQuit)

---

### BUG-2: 인덱스 초기화 시 파일 잠금

**원인:** 백그라운드 스레드가 Whoosh 파일을 열고 있는 상태에서 인덱스 폴더를 삭제 시도

**수정 방법:**

`core/indexer.py`의 `clear_index()` 메서드(또는 이에 해당하는 인덱스 초기화 함수)를 아래 순서로 수정합니다:

```python
def clear_index(self):
    # 1단계: 모든 백그라운드 작업에 중지 신호
    self._stop_event.set()
    self._watcher.stop()           # watchdog Observer 중지
    self._wq.stop(timeout=10.0)    # WriterQueue 워커 종료 (진행 중 커밋 완료 후 멈춤)
    if hasattr(self, '_maintenance_timer'):
        self._maintenance_timer.cancel()

    # 2단계: 스레드 완전 종료 대기
    self._watcher.join(timeout=5.0)

    # 3단계: 이제 안전하게 파일 삭제
    import shutil
    shutil.rmtree(INDEX_DIR, ignore_errors=True)
    INDEX_DIR.mkdir(parents=True)

    # 4단계: 새 인덱스 + 새 WriterQueue로 재시작
    from whoosh.index import create_in
    ix = create_in(str(INDEX_DIR), self._schema)
    self._wq.reload_index(ix)
    self._stop_event.clear()
    self._watcher = ... # watchdog Observer 새로 생성 후 start()
```

**핵심 원칙:** lock 파일 강제 삭제(`os.remove`) 워크어라운드를 제거하고 위 순서로 교체합니다.

---

### BUG-3: SQLite CHECK 제약조건 에러

**원인:** `status` 컬럼의 CHECK 제약조건에 `'skipped'`가 없어 INSERT 실패

**수정 방법:**

`storage/db.py`를 `filescope_fixes/storage/db.py`로 교체합니다. 주요 변경 내용:

1. `schema_version` 테이블 추가 (마이그레이션 추적)
2. `_migrate_v1_to_v2()` 함수: 기존 테이블을 `files_new`로 복사 후 교체
   - 기존 rows 전량 보존
   - 알 수 없는 status 값은 `'error'`로 변환
3. SQLite WAL 모드 활성화 (`PRAGMA journal_mode=WAL`) — 읽기/쓰기 동시성 향상
4. 스레드별 독립 connection (`threading.local()`) 사용

**기존 `init_db()` 호출부는 그대로 유지** — 시그니처 변경 없음.

---

### BUG-4: PDF 파싱 실패

**원인:** pdfplumber 최신 버전에서 `PDF.is_encrypted` 속성 제거됨

**수정 방법:**

`core/parsers/pdf_parser.py`를 `filescope_fixes/core/parsers/pdf_parser.py`로 교체합니다. 주요 변경 내용:

1. `is_encrypted` 속성 체크 코드 제거
2. 암호 보호는 `PDFPasswordIncorrect` 예외 + 메시지 키워드 감지로 처리
3. 개별 페이지 실패는 `continue`로 건너뜀 (손상 페이지 허용)
4. pdfplumber 전체 실패 시 **PyMuPDF 폴백**:
   ```python
   import fitz  # PyMuPDF
   with fitz.open(path) as doc:
       ...
   ```

**requirements.txt에 추가:**
```
PyMuPDF>=1.23.0
```

---

### BUG-5: 대용량 파일 처리 (메모리/시간 초과)

**원인:** openpyxl/python-docx가 대용량 파일 처리 시 메모리 과다 사용 또는 무한 대기

**수정 방법:**

`core/parsers/xlsx_parser.py`를 `filescope_fixes/core/parsers/xlsx_parser.py`로 교체합니다:
- `max_cells=100_000` 초과 시 조기 종료
- `threading.Timer` 기반 30초 타임아웃 (**Windows 호환**, `signal.SIGALRM` 미사용)

`core/parsers/docx_parser.py`를 `filescope_fixes/core/parsers/docx_parser.py`로 교체합니다:
- 텍스트 10MB 초과 시 조기 종료
- `threading.Timer` 기반 60초 타임아웃

**공통 패턴:**
```python
stop_event = threading.Event()
timer = threading.Timer(timeout, stop_event.set)
timer.start()
worker = threading.Thread(target=_do_parse, daemon=True)
worker.start()
worker.join(timeout=timeout + 2)
timer.cancel()
```

---

## 수정 완료 후 확인 체크리스트

- [ ] `ix.writer()` 직접 호출이 코드베이스 어디에도 없는지 grep으로 확인
  ```bash
  grep -r "ix\.writer()\|\.writer()" core/ --include="*.py"
  ```
- [ ] `watcher` + `bulk_scan` 동시 실행 시 LockError 미발생
- [ ] 인덱싱 중 인덱스 초기화 시 `[WinError 32]` 미발생
- [ ] `status='skipped'` INSERT 성공, 기존 DB rows 보존
- [ ] 암호 보호 PDF → `PasswordRequiredException` 발생 (앱 크래시 X)
- [ ] 20MB xlsx 파일 30초 내 처리 완료 또는 조기 종료

---

## 주의 사항

- `filescope_fixes/` 는 참조용 코드입니다. 기존 프로젝트의 import 경로, 클래스 이름, 메서드 시그니처에 맞게 조정해서 적용하세요.
- `base.py`의 `PasswordRequiredException`, `ParseFailedException` 클래스는 기존 것을 그대로 사용합니다.
- SQLite 마이그레이션은 앱 첫 실행 시 자동으로 한 번만 실행됩니다.
