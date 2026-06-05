import sqlite3
from pathlib import Path

import pytest


@pytest.fixture(scope='session')
def schema_sql():
    schema_path = Path(__file__).parent.parent / 'db' / 'schema.sql'
    return schema_path.read_text(encoding='utf-8')


@pytest.fixture
def db_conn(tmp_path, schema_sql):
    db_path = tmp_path / 'test_weekly.db'
    conn = sqlite3.connect(str(db_path))
    conn.execute('PRAGMA foreign_keys = ON')
    conn.execute('PRAGMA journal_mode = WAL')
    conn.executescript(schema_sql)
    yield conn
    conn.close()
