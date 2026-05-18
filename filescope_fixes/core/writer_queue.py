"""
BUG-1 FIX: Whoosh LockError 근본 해결
--------------------------------------
모든 Whoosh 쓰기 작업을 단일 워커 스레드로 직렬화.
watcher, bulk_scan, maintenance 모두 이 큐만 사용한다.
ix.writer()를 직접 호출하는 코드는 모두 제거해야 한다.
"""

import threading
import queue
from typing import Callable, Any
from whoosh.index import FileIndex


class WriterQueue:
    """Whoosh writer 직렬화 큐.

    Whoosh는 동시 writer를 허용하지 않는다. 이 클래스는 모든 쓰기
    요청을 내부 큐에 넣고, 전용 워커 스레드 1개가 순서대로 처리한다.
    """

    def __init__(self, ix: FileIndex) -> None:
        self._ix = ix
        self._q: queue.Queue = queue.Queue()
        self._thread = threading.Thread(target=self._worker, daemon=True, name="WhooshWriter")
        self._thread.start()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def submit(self, fn: Callable) -> Any:
        """fn(writer) 콜백을 큐에 넣고 완료까지 블로킹 대기.

        fn은 단일 인자 `writer`를 받는 callable.
        fn 내부에서 commit/cancel을 호출하지 말 것 — 여기서 처리한다.

        Example::

            def _write(writer):
                writer.update_document(path=str(path), content=text)
            wq.submit(_write)
        """
        done = threading.Event()
        result_box: list = [None, None]  # [return_value, exception]
        self._q.put((fn, done, result_box))
        done.wait()
        if result_box[1] is not None:
            raise result_box[1]
        return result_box[0]

    def submit_batch(self, fns: list) -> None:
        """여러 쓰기 작업을 단일 writer 트랜잭션으로 묶어 처리.

        bulk_scan처럼 수백 개 파일을 한꺼번에 인덱싱할 때 사용.
        commit이 1회만 발생하므로 성능이 훨씬 빠르다.
        """
        def _batch(writer):
            for fn in fns:
                fn(writer)
        self.submit(_batch)

    def stop(self, timeout: float = 10.0) -> None:
        """큐를 비우고 워커 스레드를 종료한다.

        clear_index() 전에 반드시 호출해야 한다 (BUG-2 연계).
        """
        self._q.put(None)  # sentinel
        self._thread.join(timeout=timeout)

    def reload_index(self, new_ix: FileIndex) -> None:
        """인덱스 재초기화 후 새 인덱스 객체로 교체.

        stop() → 인덱스 삭제/재생성 → reload_index() 순서로 사용.
        """
        self._ix = new_ix
        self._q = queue.Queue()
        self._thread = threading.Thread(target=self._worker, daemon=True, name="WhooshWriter")
        self._thread.start()

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _worker(self) -> None:
        while True:
            item = self._q.get()
            if item is None:
                break

            fn, done, result_box = item
            writer = None
            try:
                writer = self._ix.writer()
                result_box[0] = fn(writer)
                writer.commit()
            except Exception as exc:
                if writer is not None:
                    try:
                        writer.cancel()
                    except Exception:
                        pass
                result_box[1] = exc
            finally:
                done.set()
