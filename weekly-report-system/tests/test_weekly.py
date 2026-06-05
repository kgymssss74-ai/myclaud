"""Gate ⑤⑥ — 주간 입력 저장 API 검증"""
import pytest
from db.database import get_db


def _base_data(extra=None):
    data = {
        'member_id':  'M01',
        'project_id': 'P01',
        'week':       '2026-W23',
        # device project has DVR/PVR/PRA/SRA keys
        'progress_text_DVR':  'DVR 완료',
        'schedule_text_DVR':  '일정 정상',
        'risk_text_DVR':      '없음',
        'status_DVR':         '完',
        'progress_text_PVR':  'PVR 준비 중',
        'schedule_text_PVR':  '일정 정상',
        'risk_text_PVR':      '없음',
        'status_PVR':         '進',
        'progress_text_PRA':  '미시작',
        'schedule_text_PRA':  '일정 정상',
        'risk_text_PRA':      '없음',
        'status_PRA':         '進',
        'progress_text_SRA':  '미시작',
        'schedule_text_SRA':  '일정 정상',
        'risk_text_SRA':      '있음 — SRA 위험',
        'delay_reason_SRA':   '일정 조율 중',  # SRA status=risk → required
        'status_SRA':         '이슈',
    }
    if extra:
        data.update(extra)
    return data


# ── 정상 저장 ─────────────────────────────────────────────────────────────────

def test_weekly_save_valid_redirects(seeded_client):
    resp = seeded_client.post('/weekly', data=_base_data())
    assert resp.status_code in (200, 302)

    with seeded_client.application.app_context():
        db = get_db()
        r = db.execute(
            "SELECT report_id FROM weekly_reports WHERE week='2026-W23' AND member_id='M01' AND project_id='P01'"
        ).fetchone()
        assert r is not None
        items = db.execute("SELECT item_key FROM report_items WHERE report_id=?", (r['report_id'],)).fetchall()
        keys = {i['item_key'] for i in items}
        assert {'DVR', 'PVR', 'PRA', 'SRA'} == keys


def test_weekly_save_creates_delta_spans_on_second_submit(seeded_client):
    """Second submission triggers delta computation vs. previous week."""
    # First: save W22
    data_w22 = _base_data({'week': '2026-W22', 'progress_text_DVR': 'DVR 진행 중'})
    seeded_client.post('/weekly', data=data_w22)

    # Second: save W23 with modified text
    data_w23 = _base_data({'week': '2026-W23', 'progress_text_DVR': 'DVR 완료 — 신규 추가 내용'})
    seeded_client.post('/weekly', data=data_w23)

    with seeded_client.application.app_context():
        db = get_db()
        r = db.execute(
            "SELECT report_id FROM weekly_reports WHERE week='2026-W23' AND member_id='M01' AND project_id='P01'"
        ).fetchone()
        dvr_item = db.execute(
            "SELECT item_id FROM report_items WHERE report_id=? AND item_key='DVR'", (r['report_id'],)
        ).fetchone()
        spans = db.execute(
            "SELECT * FROM delta_spans WHERE item_id=?", (dvr_item['item_id'],)
        ).fetchall()
        # There should be delta spans because text changed
        assert len(spans) > 0


# ── 필수 필드 누락 → 422 ──────────────────────────────────────────────────────

def test_weekly_missing_progress_returns_422(seeded_client):
    data = _base_data()
    del data['progress_text_DVR']
    resp = seeded_client.post('/weekly', data=data)
    assert resp.status_code == 422
    body = resp.get_json()
    assert 'DVR_required' in body.get('errors', {})


def test_weekly_missing_schedule_returns_422(seeded_client):
    data = _base_data()
    del data['schedule_text_PVR']
    resp = seeded_client.post('/weekly', data=data)
    assert resp.status_code == 422


def test_weekly_missing_risk_returns_422(seeded_client):
    data = _base_data()
    del data['risk_text_PRA']
    resp = seeded_client.post('/weekly', data=data)
    assert resp.status_code == 422


def test_weekly_empty_string_fields_returns_422(seeded_client):
    data = _base_data({'progress_text_DVR': '', 'schedule_text_DVR': '', 'risk_text_DVR': ''})
    resp = seeded_client.post('/weekly', data=data)
    assert resp.status_code == 422


# ── 지연사유 조건부 필수 ────────────────────────────────────────────────────────

def test_weekly_risk_ms_without_delay_reason_returns_422(seeded_client):
    """SRA milestone has status=risk → delay_reason required."""
    data = _base_data()
    data.pop('delay_reason_SRA', None)  # remove delay reason
    data['delay_reason_SRA'] = ''       # empty
    resp = seeded_client.post('/weekly', data=data)
    assert resp.status_code == 422
    body = resp.get_json()
    assert 'SRA_delay' in body.get('errors', {})


def test_weekly_ontime_ms_without_delay_reason_ok(seeded_client):
    """PVR milestone status=pending with future date → no delay reason needed."""
    data = _base_data()
    # PVR planned 2026-08-01 (future) → no delay
    resp = seeded_client.post('/weekly', data=data)
    assert resp.status_code in (200, 302)


# ── 한글 파일명 → 422 ────────────────────────────────────────────────────────

def test_weekly_korean_filename_returns_422(seeded_client):
    from io import BytesIO
    data = _base_data()
    resp = seeded_client.post(
        '/weekly',
        data={**data, 'images_DVR': (BytesIO(b'fake'), '보고서이미지.png')},
        content_type='multipart/form-data',
    )
    assert resp.status_code == 422


# ── API endpoints ─────────────────────────────────────────────────────────────

def test_api_projects_returns_list(seeded_client):
    resp = seeded_client.get('/api/projects?member_id=M01')
    assert resp.status_code == 200
    data = resp.get_json()
    assert isinstance(data, list)
    assert any(p['project_id'] == 'P01' for p in data)


def test_api_milestones_returns_list(seeded_client):
    resp = seeded_client.get('/api/milestones?project_id=P01')
    assert resp.status_code == 200
    data = resp.get_json()
    assert len(data) == 4
    types = [m['type'] for m in data]
    assert 'DVR' in types and 'SRA' in types


def test_api_prev_report_empty_initially(seeded_client):
    resp = seeded_client.get(
        '/api/prev-report?member_id=M01&project_id=P01&week=2026-W22'
    )
    assert resp.status_code == 200
    assert resp.get_json()['items'] == []
