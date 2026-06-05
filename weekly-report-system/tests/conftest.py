import sqlite3
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(ROOT))


# ── Schema fixture (session-scoped) ───────────────────────────────────────────

@pytest.fixture(scope='session')
def schema_sql():
    return (ROOT / 'db' / 'schema.sql').read_text(encoding='utf-8')


# ── Standalone DB fixture (unit tests) ────────────────────────────────────────

@pytest.fixture
def db_conn(tmp_path, schema_sql):
    db_path = tmp_path / 'test_weekly.db'
    conn = sqlite3.connect(str(db_path))
    conn.execute('PRAGMA foreign_keys = ON')
    conn.execute('PRAGMA journal_mode = WAL')
    conn.executescript(schema_sql)
    yield conn
    conn.close()


# ── Flask app fixture ─────────────────────────────────────────────────────────

@pytest.fixture
def app(tmp_path, schema_sql):
    db_path = str(tmp_path / 'app_test.db')
    conn = sqlite3.connect(db_path)
    conn.execute('PRAGMA foreign_keys = ON')
    conn.executescript(schema_sql)
    conn.commit()
    conn.close()

    from app import create_app
    flask_app = create_app(db_path=db_path, upload_folder=str(tmp_path / 'uploads'))
    flask_app.config['TESTING'] = True
    yield flask_app


@pytest.fixture
def client(app):
    return app.test_client()


# ── Seeded app (member + projects + milestones + MBO) ─────────────────────────

@pytest.fixture
def seeded_app(app):
    with app.app_context():
        from db.database import get_db
        db = get_db()
        db.execute("INSERT INTO members VALUES ('M01','Kim Gangyeol','김강열','Audio Solution',0,'Part Leader',NULL,1)")
        db.execute("INSERT INTO members VALUES ('M02','Son Baekgwon','손백권','Audio Solution',1,'CL4',NULL,0)")
        # device project
        db.execute("INSERT INTO projects VALUES ('P01','Test SoundBooster','device','QC SM8850',NULL,'M01','2026-W22','Audio Solution')")
        db.execute("INSERT INTO milestones VALUES ('MS001','P01','DVR','2026-03-01','2026-03-01','done')")
        db.execute("INSERT INTO milestones VALUES ('MS002','P01','PVR','2026-08-01',NULL,'pending')")
        db.execute("INSERT INTO milestones VALUES ('MS003','P01','PRA','2026-09-01',NULL,'pending')")
        db.execute("INSERT INTO milestones VALUES ('MS004','P01','SRA','2026-10-01',NULL,'risk')")
        # advance project
        db.execute("INSERT INTO projects VALUES ('P02','Test Advance','advance',NULL,'AI model','M01','2026-W22','Audio Solution')")
        db.execute("INSERT INTO milestones VALUES ('MS005','P02','EA','2026-04-01','2026-04-01','done')")
        db.execute("INSERT INTO milestones VALUES ('MS006','P02','ER1','2026-06-01',NULL,'risk')")
        db.execute("INSERT INTO milestones VALUES ('MS007','P02','ER2','2026-07-01',NULL,'pending')")
        db.execute("INSERT INTO milestones VALUES ('MS008','P02','CA','2026-08-01',NULL,'pending')")
        # review project
        db.execute("INSERT INTO projects VALUES ('P03','Test Review','review',NULL,'Review bg','M02','2026-W22','Audio Solution')")
        # MBO
        db.execute("INSERT INTO mbo_objectives VALUES ('MBO001','M01',2026,'SoundBooster Opt',15,'MCPS','30','P01')")
        db.commit()
    yield app


@pytest.fixture
def seeded_client(seeded_app):
    return seeded_app.test_client()


# ── App with a weekly report entry ────────────────────────────────────────────

@pytest.fixture
def app_with_report(seeded_app):
    with seeded_app.app_context():
        from db.database import get_db
        db = get_db()
        db.execute(
            "INSERT INTO weekly_reports (week, member_id, project_id) VALUES ('2026-W23','M01','P01')"
        )
        rid = db.execute('SELECT last_insert_rowid() r').fetchone()['r']
        db.execute(
            "INSERT INTO report_items (report_id, item_key, progress_text, schedule_text, risk_text, status) "
            "VALUES (?,?,?,?,?,?)",
            (rid, 'DVR', 'DVR 완료 보고', '일정 정상', '이슈 없음', '完'),
        )
        iid1 = db.execute('SELECT last_insert_rowid() r').fetchone()['r']
        db.execute(
            "INSERT INTO report_items (report_id, item_key, progress_text, schedule_text, risk_text, status, ms_id) "
            "VALUES (?,?,?,?,?,?,?)",
            (rid, 'PVR', 'PVR 준비 중', '일정 정상', '없음', '進', 'MS002'),
        )
        iid2 = db.execute('SELECT last_insert_rowid() r').fetchone()['r']
        # Add a delta span for the PVR item
        db.execute(
            "INSERT INTO delta_spans (item_id, field, start_pos, end_pos) VALUES (?,?,?,?)",
            (iid2, 'progress_text', 4, 8),
        )
        db.commit()
    yield seeded_app
