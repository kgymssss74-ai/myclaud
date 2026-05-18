"""
BUG-5 FIX: 대용량 docx 파일 처리 (이미지 포함 시 느림 문제)
-------------------------------------------------------------
수정 사항:
  1. 임베디드 이미지/OLE 객체가 많아도 텍스트만 빠르게 추출
  2. threading.Timer 기반 타임아웃 (Windows 호환)
  3. 텍스트 10MB 초과 시 조기 종료
"""

from __future__ import annotations

import threading
from pathlib import Path

from .base import PasswordRequiredException, ParseFailedException

_MAX_TEXT_BYTES = 10 * 1024 * 1024  # 10 MB
_TIMEOUT_SEC = 60


def parse(path: str, timeout: float = _TIMEOUT_SEC) -> str:
    result: list[str] = []
    stop_event = threading.Event()
    error_box: list[Exception | None] = [None]

    def _do_parse() -> None:
        try:
            from docx import Document
            doc = Document(path)
            total = 0

            # 단락
            for para in doc.paragraphs:
                if stop_event.is_set():
                    return
                text = para.text.strip()
                if text:
                    result.append(text)
                    total += len(text.encode())
                    if total >= _MAX_TEXT_BYTES:
                        return

            # 표
            for table in doc.tables:
                if stop_event.is_set():
                    return
                for row in table.rows:
                    for cell in row.cells:
                        text = cell.text.strip()
                        if text:
                            result.append(text)
                            total += len(text.encode())
                            if total >= _MAX_TEXT_BYTES:
                                return

            # 머릿글/바닥글 (선택적)
            for section in doc.sections:
                for para in (section.header.paragraphs + section.footer.paragraphs):
                    if stop_event.is_set():
                        return
                    text = para.text.strip()
                    if text:
                        result.append(text)

        except Exception as exc:
            msg = str(exc).lower()
            if "password" in msg or "encrypted" in msg:
                error_box[0] = PasswordRequiredException(path)
            else:
                error_box[0] = exc

    worker = threading.Thread(target=_do_parse, daemon=True)
    worker.start()

    timer = threading.Timer(timeout, stop_event.set)
    timer.start()
    try:
        worker.join(timeout=timeout + 2)
    finally:
        timer.cancel()

    if error_box[0] is not None:
        if isinstance(error_box[0], PasswordRequiredException):
            raise error_box[0]
        raise ParseFailedException(path, str(error_box[0]))

    return "\n".join(result)
