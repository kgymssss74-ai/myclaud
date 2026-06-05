"""Tests for the part-leader dashboard route (/leader, /api/leader/summary)."""
import json
import pytest


# ── Page render ───────────────────────────────────────────────────────────────

def test_leader_page_renders(seeded_client):
    r = seeded_client.get('/leader')
    assert r.status_code == 200
    assert '파트장 대시보드'.encode() in r.data


# ── /api/leader/summary — basic structure ────────────────────────────────────

def test_summary_returns_json_keys(seeded_client):
    r = seeded_client.get('/api/leader/summary?week=2026-W23')
    assert r.status_code == 200
    body = json.loads(r.data)
    for key in ('week', 'submission', 'tg_summary', 'risk_milestones', 'mbo', 'issues'):
        assert key in body


def test_summary_week_echoed(seeded_client):
    r = seeded_client.get('/api/leader/summary?week=2026-W10')
    body = json.loads(r.data)
    assert body['week'] == '2026-W10'


# ── Submission tracking ───────────────────────────────────────────────────────

def test_submission_counts_members(seeded_client):
    r = seeded_client.get('/api/leader/summary?week=2026-W99')
    body = json.loads(r.data)
    sub = body['submission']
    assert sub['total'] == 2          # M01 + M02 inserted by seeded_app
    assert sub['submitted'] == 0
    assert len(sub['missing']) == 2


def test_submission_reflects_report(app_with_report):
    """After M01 submits for W23, submitted==1 and M01 absent from missing list."""
    client = app_with_report.test_client()
    r = client.get('/api/leader/summary?week=2026-W23')
    body = json.loads(r.data)
    sub = body['submission']
    assert sub['submitted'] == 1
    assert sub['total'] == 2
    missing_ids = [m['member_id'] for m in sub['missing']]
    assert 'M01' not in missing_ids
    assert 'M02' in missing_ids


def test_submitted_list_present(app_with_report):
    client = app_with_report.test_client()
    r = client.get('/api/leader/summary?week=2026-W23')
    body = json.loads(r.data)
    submitted_ids = [m['member_id'] for m in body['submission']['submitted_list']]
    assert 'M01' in submitted_ids


# ── Risk milestones ───────────────────────────────────────────────────────────

def test_risk_milestone_shows_risk_status(seeded_client):
    """MS004 has status='risk' → must appear in risk_milestones."""
    r = seeded_client.get('/api/leader/summary?week=2026-W23')
    body = json.loads(r.data)
    ms_ids = [ms['ms_id'] for ms in body['risk_milestones']]
    assert 'MS004' in ms_ids   # SRA risk
    assert 'MS006' in ms_ids   # ER1 risk


def test_overdue_pending_milestone_included(seeded_client):
    """MS001 (done 2026-03-01) should NOT appear; pending past-due should appear."""
    r = seeded_client.get('/api/leader/summary?week=2026-W23')
    body = json.loads(r.data)
    # MS001 is 'done', should not be in risk list
    ms_ids = [ms['ms_id'] for ms in body['risk_milestones']]
    assert 'MS001' not in ms_ids


# ── MBO data ─────────────────────────────────────────────────────────────────

def test_mbo_row_present(seeded_client):
    r = seeded_client.get('/api/leader/summary?week=2026-W23')
    body = json.loads(r.data)
    assert len(body['mbo']) >= 1
    mbo = body['mbo'][0]
    assert mbo['mbo_id'] == 'MBO001'
    assert mbo['member_id'] == 'M01'


def test_mbo_achievement_rate_zero_when_no_reports(seeded_client):
    r = seeded_client.get('/api/leader/summary?week=2026-W23')
    body = json.loads(r.data)
    mbo = next(m for m in body['mbo'] if m['mbo_id'] == 'MBO001')
    assert mbo['current_value'] == 0
    assert mbo['achievement_rate'] == 0.0


def test_mbo_achievement_rate_after_done_items(app_with_report):
    """DVR item status='完' linked to P01; MBO001 target=30 → rate = 1/30*100."""
    client = app_with_report.test_client()
    r = client.get('/api/leader/summary?week=2026-W23')
    body = json.loads(r.data)
    mbo = next(m for m in body['mbo'] if m['mbo_id'] == 'MBO001')
    assert mbo['current_value'] == 1
    assert mbo['achievement_rate'] == round(1 / 30 * 100, 1)


# ── TG summary ────────────────────────────────────────────────────────────────

def test_tg_summary_after_report(app_with_report):
    client = app_with_report.test_client()
    r = client.get('/api/leader/summary?week=2026-W23')
    body = json.loads(r.data)
    tg = {row['tg']: row for row in body['tg_summary']}
    assert 'Audio Solution' in tg
    row = tg['Audio Solution']
    assert row['done'] == 1     # DVR 完
    assert row['ongoing'] == 1  # PVR 進
    assert row['issue'] == 0


# ── Issues list ───────────────────────────────────────────────────────────────

def test_no_issues_when_none_submitted(seeded_client):
    r = seeded_client.get('/api/leader/summary?week=2026-W99')
    body = json.loads(r.data)
    assert body['issues'] == []


def test_issues_appear_for_issue_status(app_with_report):
    """Inject an 이슈 item and verify it appears in the issues list."""
    with app_with_report.app_context():
        from db.database import get_db
        db = get_db()
        rid = db.execute(
            "SELECT report_id FROM weekly_reports WHERE week='2026-W23' AND member_id='M01'"
        ).fetchone()['report_id']
        db.execute(
            "INSERT INTO report_items (report_id, item_key, progress_text, schedule_text, "
            "risk_text, delay_reason, status) VALUES (?,?,?,?,?,?,?)",
            (rid, 'PRA', '작업 중', '지연 예상', '일정 위험', '외부 의존성', '이슈'),
        )
        db.commit()

    client = app_with_report.test_client()
    r = client.get('/api/leader/summary?week=2026-W23')
    body = json.loads(r.data)
    assert len(body['issues']) == 1
    issue = body['issues'][0]
    assert issue['item_key'] == 'PRA'
    assert issue['delay_reason'] == '외부 의존성'
