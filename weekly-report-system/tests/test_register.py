"""Gate ④ — 신규 과제 등록 검증"""
import pytest
from db.database import get_db


# ── 유형별 과제 등록 및 마일스톤 생성 ────────────────────────────────────────

def test_register_device_creates_4_milestones(seeded_client):
    resp = seeded_client.post('/register', data={
        'track': 'device',
        'name': 'New Device Project',
        'owner_member_id': 'M01',
        'created_week': '2026-W24',
        'platform': 'QC SM8850',
        'date_DVR': '2026-03-01',
        'date_PVR': '2026-04-01',
        'date_PRA': '2026-05-01',
        'date_SRA': '2026-06-01',
    }, follow_redirects=True)
    assert resp.status_code == 200

    with seeded_client.application.app_context():
        db = get_db()
        proj = db.execute("SELECT * FROM projects WHERE name='New Device Project'").fetchone()
        assert proj is not None
        assert proj['track'] == 'device'
        assert proj['platform'] == 'QC SM8850'
        ms = db.execute("SELECT type FROM milestones WHERE project_id=? ORDER BY ms_id", (proj['project_id'],)).fetchall()
        assert [r['type'] for r in ms] == ['DVR', 'PVR', 'PRA', 'SRA']


def test_register_advance_creates_4_milestones(seeded_client):
    resp = seeded_client.post('/register', data={
        'track': 'advance',
        'name': 'New Advance Project',
        'owner_member_id': 'M01',
        'background': 'AI NLC 선행 배경',
        'date_EA': '2026-04-01',
        'date_ER1': '2026-05-01',
        'date_ER2': '2026-06-01',
        'date_CA': '2026-07-01',
    }, follow_redirects=True)
    assert resp.status_code == 200

    with seeded_client.application.app_context():
        db = get_db()
        proj = db.execute("SELECT * FROM projects WHERE name='New Advance Project'").fetchone()
        assert proj is not None
        assert proj['track'] == 'advance'
        assert proj['background'] == 'AI NLC 선행 배경'
        ms = db.execute("SELECT type FROM milestones WHERE project_id=? ORDER BY ms_id", (proj['project_id'],)).fetchall()
        assert [r['type'] for r in ms] == ['EA', 'ER1', 'ER2', 'CA']


def test_register_review_creates_0_milestones(seeded_client):
    resp = seeded_client.post('/register', data={
        'track': 'review',
        'name': 'New Review Project',
        'owner_member_id': 'M02',
        'background': '경쟁사 검토 배경',
    }, follow_redirects=True)
    assert resp.status_code == 200

    with seeded_client.application.app_context():
        db = get_db()
        proj = db.execute("SELECT * FROM projects WHERE name='New Review Project'").fetchone()
        assert proj is not None
        ms = db.execute("SELECT COUNT(*) n FROM milestones WHERE project_id=?", (proj['project_id'],)).fetchone()
        assert ms['n'] == 0


# ── 필수 필드 누락 → 422 ──────────────────────────────────────────────────────

def test_register_missing_name_returns_422(seeded_client):
    resp = seeded_client.post('/register', data={
        'track': 'device',
        'owner_member_id': 'M01',
        'platform': 'QC',
    })
    assert resp.status_code == 422
    data = resp.get_json()
    assert any('과제명' in e for e in data['errors'])


def test_register_device_missing_platform_returns_422(seeded_client):
    resp = seeded_client.post('/register', data={
        'track': 'device',
        'name': 'Missing Platform',
        'owner_member_id': 'M01',
    })
    assert resp.status_code == 422
    data = resp.get_json()
    assert any('플랫폼' in e for e in data['errors'])


def test_register_advance_missing_background_returns_422(seeded_client):
    resp = seeded_client.post('/register', data={
        'track': 'advance',
        'name': 'Missing Background',
        'owner_member_id': 'M01',
    })
    assert resp.status_code == 422
    data = resp.get_json()
    assert any('배경' in e for e in data['errors'])


def test_register_missing_track_returns_422(seeded_client):
    resp = seeded_client.post('/register', data={
        'name': 'No Track',
        'owner_member_id': 'M01',
    })
    assert resp.status_code == 422


# ── GET renders form with Alpine.js x-show directives ─────────────────────────

def test_register_form_has_alpine_track_toggle(seeded_client):
    resp = seeded_client.get('/register')
    assert resp.status_code == 200
    html = resp.get_data(as_text=True)
    assert "x-show" in html
    assert "track === 'device'" in html
    assert "track === 'advance'" in html
    assert "track === 'review'" in html
