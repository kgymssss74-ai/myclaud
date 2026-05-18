"""
BUG-3 FIX: SQLite CHECK 제약조건 — 데이터 손실 없는 마이그레이션
-----------------------------------------------------------------
SQLite는 ALTER TABLE로 CHECK 제약조건을 변경할 수 없다.
표준 해법: RENAME → CREATE NEW → INSERT SELECT → DROP OLD

schema_version 테이블로 버전 관리하므로 앱을 재시작해도
이미 마이그레이션된 DB는 다시 건드리지 않는다.

추가: WAL 모드 활성화로 읽기/쓰기 동시성 향상.
"""

import sqlite3
import threading
from pathlib import Path

DB_PATH = Path("storage/filescope.db")

# 스레드별 독립 연결 (SQLite connection은 스레드 간 공유 불가)
_local = threading.local()


def get_conn() -> sqlite3.Connection:
    """현재 스레드의 DB 연결 반환 (없으면 생성)."""
    if not hasattr(_local, "conn") or _local.conn is None:
        _local.conn = _open_conn()
    return _local.conn


def _open_conn() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(DB_PATH), check_same_thread=False)
    conn.execute("PRAGMA journal_mode=WAL")   # 읽기/쓰기 동시 허용
    conn.execute("PRAGMA synchronous=NORMAL") # WAL에서 안전하고 빠름
    conn.row_factory = sqlite3.Row
    _init_or_migrate(conn)
    return conn


# ------------------------------------------------------------------
# 스키마 초기화 + 마이그레이션
# ------------------------------------------------------------------

_CURRENT_VERSION = 2

def _init_or_migrate(conn: sqlite3.Connection) -> None:
    conn.execute("""
        CREATE TABLE IF NOT EXISTS schema_version (
            version INTEGER NOT NULL
        )
    """)
    row = conn.execute("SELECT MAX(version) FROM schema_version").fetchone()
    version = row[0] if row[0] is not None else 0

    if version == 0:
        _create_fresh(conn)
        conn.execute(f"INSERT INTO schema_version VALUES ({_CURRENT_VERSION})")
        conn.commit()
    elif version < _CURRENT_VERSION:
        _migrate(conn, from_version=version)


def _create_fresh(conn: sqlite3.Connection) -> None:
    """신규 설치용 최신 스키마."""
    conn.execute("""
        CREATE TABLE IF NOT EXISTS files (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            path       TEXT    UNIQUE NOT NULL,
            size       INTEGER,
            mtime      REAL,
            status     TEXT    CHECK(status IN ('ok','error','password_required','skipped')),
            indexed_at REAL
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_files_status ON files(status)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_files_mtime  ON files(mtime)")


def _migrate(conn: sqlite3.Connection, from_version: int) -> None:
    if from_version < 2:
        _migrate_v1_to_v2(conn)
        conn.execute("DELETE FROM schema_version")
        conn.execute(f"INSERT INTO schema_version VALUES ({_CURRENT_VERSION})")
        conn.commit()


def _migrate_v1_to_v2(conn: sqlite3.Connection) -> None:
    """CHECK 제약조건에 'skipped' 추가. 기존 rows 전량 보존.

    미지의 status 값은 'error'로 안전하게 변환한다.
    """
    conn.execute("PRAGMA foreign_keys=OFF")

    # 1) 새 테이블 생성
    conn.execute("""
        CREATE TABLE files_new (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            path       TEXT    UNIQUE NOT NULL,
            size       INTEGER,
            mtime      REAL,
            status     TEXT    CHECK(status IN ('ok','error','password_required','skipped')),
            indexed_at REAL
        )
    """)

    # 2) 기존 데이터 복사 — 알 수 없는 status는 'error'로 변환
    conn.execute("""
        INSERT INTO files_new (id, path, size, mtime, status, indexed_at)
        SELECT
            id, path, size, mtime,
            CASE
                WHEN status IN ('ok','error','password_required','skipped') THEN status
                ELSE 'error'
            END,
            indexed_at
        FROM files
    """)

    # 3) 교체
    conn.execute("DROP TABLE files")
    conn.execute("ALTER TABLE files_new RENAME TO files")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_files_status ON files(status)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_files_mtime  ON files(mtime)")
    conn.execute("PRAGMA foreign_keys=ON")


# ------------------------------------------------------------------
# 편의 헬퍼
# ------------------------------------------------------------------

def upsert_file(path: str, size: int, mtime: float,
                status: str, indexed_at: float) -> None:
    conn = get_conn()
    conn.execute("""
        INSERT INTO files (path, size, mtime, status, indexed_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(path) DO UPDATE SET
            size=excluded.size,
            mtime=excluded.mtime,
            status=excluded.status,
            indexed_at=excluded.indexed_at
    """, (path, size, mtime, status, indexed_at))
    conn.commit()


def delete_file(path: str) -> None:
    conn = get_conn()
    conn.execute("DELETE FROM files WHERE path=?", (path,))
    conn.commit()


def get_file(path: str) -> sqlite3.Row | None:
    conn = get_conn()
    return conn.execute("SELECT * FROM files WHERE path=?", (path,)).fetchone()
