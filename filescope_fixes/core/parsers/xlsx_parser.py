"""
BUG-5 FIX: 대용량 xlsx 파일 처리
----------------------------------
수정 사항:
  1. read_only=True + data_only=True 스트리밍 모드 (기존 동일)
  2. max_cells 한계 도달 시 조기 종료 (메모리 보호)
  3. Windows 호환 타임아웃 (threading.Timer 기반)
  4. 셀 변환 오류는 건너뜀

Windows에서 signal.SIGALRM 사용 불가이므로
threading.Timer + Event 조합으로 타임아웃 구현.
"""

from __future__ import annotations

import threading
from pathlib import Path

from .base import ParseFailedException

_MAX_CELLS = 100_000   # 이 이상이면 앞부분만 인덱싱
_TIMEOUT_SEC = 30      # 파싱 타임아웃 (초)


def parse(path: str, max_cells: int = _MAX_CELLS, timeout: float = _TIMEOUT_SEC) -> str:
    """xlsx/xls/csv 파일 텍스트 추출.

    max_cells 개 이상의 셀이 있으면 앞부분만 반환한다.
    timeout 초 내에 완료되지 않으면 그때까지 수집된 텍스트를 반환한다.
    """
    ext = Path(path).suffix.lower()
    if ext == ".csv":
        return _parse_csv(path)
    return _parse_excel(path, max_cells=max_cells, timeout=timeout)


# ------------------------------------------------------------------
# xlsx / xls
# ------------------------------------------------------------------

def _parse_excel(path: str, max_cells: int, timeout: float) -> str:
    result: list[str] = []
    stop_event = threading.Event()

    def _do_parse() -> None:
        try:
            from openpyxl import load_workbook
            wb = load_workbook(path, read_only=True, data_only=True)
            cell_count = 0
            try:
                for ws in wb.worksheets:
                    if stop_event.is_set():
                        break
                    for row in ws.iter_rows(values_only=True):
                        if stop_event.is_set():
                            break
                        for cell in row:
                            if cell is not None:
                                try:
                                    result.append(str(cell))
                                except Exception:
                                    continue
                                cell_count += 1
                                if cell_count >= max_cells:
                                    stop_event.set()
                                    break
            finally:
                wb.close()
        except Exception as exc:
            result.append(f"__PARSE_ERROR__{exc}")

    worker = threading.Thread(target=_do_parse, daemon=True)
    worker.start()

    # 타임아웃 타이머: 시간 초과 시 stop_event 세팅 (강제 중단)
    timer = threading.Timer(timeout, stop_event.set)
    timer.start()
    try:
        worker.join(timeout=timeout + 2)  # 워커가 stop_event 확인할 여유
    finally:
        timer.cancel()

    # 파싱 오류가 유일한 결과인 경우 예외 발생
    if len(result) == 1 and result[0].startswith("__PARSE_ERROR__"):
        raise ParseFailedException(path, result[0].removeprefix("__PARSE_ERROR__"))

    return " ".join(result)


# ------------------------------------------------------------------
# csv (openpyxl 불필요, 표준 라이브러리 사용)
# ------------------------------------------------------------------

def _parse_csv(path: str, max_rows: int = 50_000) -> str:
    import csv
    import chardet

    # 인코딩 자동 감지 (앞 64KB만 샘플링)
    with open(path, "rb") as f:
        raw = f.read(65536)
    encoding = chardet.detect(raw).get("encoding") or "utf-8"

    texts: list[str] = []
    try:
        with open(path, encoding=encoding, errors="replace", newline="") as f:
            reader = csv.reader(f)
            for i, row in enumerate(reader):
                if i >= max_rows:
                    break
                texts.extend(cell for cell in row if cell.strip())
    except Exception as exc:
        raise ParseFailedException(path, str(exc))

    return " ".join(texts)
