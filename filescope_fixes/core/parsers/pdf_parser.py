"""
BUG-4 FIX: PDF 파싱 실패
--------------------------
수정 사항:
  1. is_encrypted 속성 제거 (최신 pdfplumber에 없음)
  2. 암호 보호: 예외 메시지 기반 감지
  3. 개별 페이지 실패는 건너뜀 (손상된 페이지 허용)
  4. pdfplumber 전체 실패 시 PyMuPDF(fitz)로 폴백

requirements.txt에 추가 필요:
    PyMuPDF>=1.23.0
"""

from __future__ import annotations

import pdfplumber

from .base import PasswordRequiredException, ParseFailedException

# pdfplumber가 내부적으로 사용하는 pdfminer 예외
try:
    from pdfminer.pdfdocument import PDFPasswordIncorrect
    from pdfminer.pdfparser import PDFSyntaxError
except ImportError:
    PDFPasswordIncorrect = Exception
    PDFSyntaxError = Exception


def parse(path: str) -> str:
    """PDF 파일 텍스트 추출.

    Returns:
        추출된 텍스트 문자열
    Raises:
        PasswordRequiredException: 암호 보호된 PDF
        ParseFailedException: 손상 등 복구 불가 오류
    """
    try:
        return _parse_with_pdfplumber(path)
    except PasswordRequiredException:
        raise
    except Exception as primary_exc:
        # pdfplumber 실패 → PyMuPDF 폴백 시도
        try:
            return _parse_with_pymupdf(path)
        except PasswordRequiredException:
            raise
        except Exception:
            raise ParseFailedException(path, str(primary_exc))


# ------------------------------------------------------------------
# pdfplumber 파싱
# ------------------------------------------------------------------

def _parse_with_pdfplumber(path: str) -> str:
    texts: list[str] = []
    try:
        with pdfplumber.open(path) as pdf:
            for page in pdf.pages:
                try:
                    text = page.extract_text()
                    if text:
                        texts.append(text)
                except Exception:
                    # 개별 페이지 오류는 건너뜀 (손상된 페이지 허용)
                    continue
    except PDFPasswordIncorrect:
        raise PasswordRequiredException(path)
    except Exception as e:
        msg = str(e).lower()
        # 암호 보호 메시지 패턴 감지 (버전마다 다름)
        if any(kw in msg for kw in ("password", "encrypted", "incorrect")):
            raise PasswordRequiredException(path)
        raise  # ParseFailedException으로 상위에서 처리

    return "\n".join(texts)


# ------------------------------------------------------------------
# PyMuPDF 폴백 파싱
# ------------------------------------------------------------------

def _parse_with_pymupdf(path: str) -> str:
    try:
        import fitz  # PyMuPDF
    except ImportError:
        raise ParseFailedException(path, "PyMuPDF not installed")

    texts: list[str] = []
    try:
        with fitz.open(path) as doc:
            if doc.is_encrypted:
                # 빈 암호로 열기 시도
                if not doc.authenticate(""):
                    raise PasswordRequiredException(path)
            for page in doc:
                try:
                    texts.append(page.get_text())
                except Exception:
                    continue
    except PasswordRequiredException:
        raise
    except Exception as e:
        raise ParseFailedException(path, str(e))

    return "\n".join(texts)
