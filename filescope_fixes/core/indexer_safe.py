"""
BUG-1 + BUG-2 FIX: 안전한 IndexerFacade 패턴
-----------------------------------------------
기존 indexer.py를 이 패턴으로 리팩터링하면:
  - BUG-1: WriterQueue로 동시 writer 충돌 제거
  - BUG-2: clear_index() 시 stop → wait → delete 순서 보장

watcher, UI, maintenance 모두 이 클래스의 메서드만 호출한다.
"""

import threading
import shutil
import sqlite3
from pathlib import Path

from whoosh.index import create_in, open_dir, FileIndex
from whoosh.fields import Schema, TEXT, ID, STORED, DATETIME

from .writer_queue import WriterQueue


INDEX_DIR = Path("storage/index")
DB_PATH = Path("storage/filescope.db")


def _build_schema() -> Schema:
    return Schema(
        path=ID(stored=True, unique=True),
        filename=TEXT(stored=True),
        content=TEXT,
        ext=STORED,
        mtime=STORED,
    )


class IndexerFacade:
    """스레드 안전한 인덱스 관리 파사드."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        INDEX_DIR.mkdir(parents=True, exist_ok=True)

        if open_dir.__module__ and INDEX_DIR.joinpath("MAIN_WRITELOCK").exists():
            INDEX_DIR.joinpath("MAIN_WRITELOCK").unlink(missing_ok=True)

        if not any(INDEX_DIR.iterdir()):
            ix = create_in(str(INDEX_DIR), _build_schema())
        else:
            ix = open_dir(str(INDEX_DIR))

        self._wq = WriterQueue(ix)

    # ------------------------------------------------------------------
    # 쓰기 API (모두 WriterQueue를 통해 처리)
    # ------------------------------------------------------------------

    def add_or_update(self, path: Path, content: str, ext: str, mtime: float) -> None:
        """파일 추가 또는 갱신."""
        def _write(writer):
            writer.update_document(
                path=str(path),
                filename=path.name,
                content=content,
                ext=ext,
                mtime=mtime,
            )
        self._wq.submit(_write)

    def remove(self, path: Path) -> None:
        """파일 삭제."""
        from whoosh.query import Term
        def _write(writer):
            writer.delete_by_term("path", str(path))
        self._wq.submit(_write)

    def bulk_add(self, items: list) -> None:
        """items: list of (path, content, ext, mtime).
        단일 트랜잭션으로 처리하므로 개별 add 반복보다 훨씬 빠름.
        """
        def _make_write(p, c, e, m):
            def _write(writer):
                writer.update_document(
                    path=str(p), filename=p.name, content=c, ext=e, mtime=m,
                )
            return _write
        fns = [_make_write(p, c, e, m) for p, c, e, m in items]
        self._wq.submit_batch(fns)

    # ------------------------------------------------------------------
    # BUG-2 FIX: 인덱스 초기화
    # ------------------------------------------------------------------

    def clear_index(self, watcher=None, maintenance_timer=None) -> None:
        """모든 백그라운드 작업을 완전히 종료한 후 인덱스를 삭제·재생성.

        watcher: watchdog Observer 인스턴스 (stop/join 호출됨)
        maintenance_timer: threading.Timer 인스턴스 (cancel 호출됨)
        """
        # 1단계: 중지 신호 전파
        self._stop_event.set()

        if maintenance_timer is not None:
            maintenance_timer.cancel()

        if watcher is not None:
            watcher.stop()

        # 2단계: WriterQueue 워커 종료 — 진행 중인 커밋까지 완료 후 멈춤
        self._wq.stop(timeout=10.0)

        if watcher is not None:
            watcher.join(timeout=5.0)

        # 3단계: 이제 어떤 스레드도 인덱스 파일을 열고 있지 않으므로 안전하게 삭제
        shutil.rmtree(INDEX_DIR, ignore_errors=True)
        INDEX_DIR.mkdir(parents=True)

        # 4단계: 새 인덱스 + 새 WriterQueue
        ix = create_in(str(INDEX_DIR), _build_schema())
        self._wq.reload_index(ix)
        self._stop_event.clear()

    def shutdown(self) -> None:
        """앱 종료 시 호출. 진행 중인 쓰기를 마치고 워커를 정리."""
        self._stop_event.set()
        self._wq.stop(timeout=15.0)
