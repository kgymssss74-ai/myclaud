from datetime import date
from flask import Blueprint, jsonify, render_template, request
from db.database import get_db

bp = Blueprint('leader', __name__)


def _current_week() -> str:
    y, w, _ = date.today().isocalendar()
    return f'{y}-W{w:02d}'


@bp.route('/leader')
def leader_dashboard():
    return render_template('leader.html', current_week=_current_week())


@bp.route('/api/leader/summary')
def leader_summary():
    week = request.args.get('week', _current_week())
    db   = get_db()

    # ── 1. 입력 현황 ────────────────────────────────────────────────────────
    all_members = db.execute(
        'SELECT member_id, display_name, name, tg, tg_order, cl_level, is_part_leader '
        'FROM members ORDER BY tg_order, member_id'
    ).fetchall()

    submitted_ids = {
        r['member_id']
        for r in db.execute(
            'SELECT DISTINCT member_id FROM weekly_reports WHERE week=?', (week,)
        ).fetchall()
    }

    submission = {
        'total':     len(all_members),
        'submitted': len(submitted_ids),
        'missing': [
            {'member_id': m['member_id'],
             'display_name': m['display_name'] or m['name'],
             'tg': m['tg']}
            for m in all_members if m['member_id'] not in submitted_ids
        ],
        'submitted_list': [
            {'member_id': m['member_id'],
             'display_name': m['display_name'] or m['name']}
            for m in all_members if m['member_id'] in submitted_ids
        ],
    }

    # ── 2. TG별 현황 ─────────────────────────────────────────────────────────
    tg_rows = db.execute(
        '''SELECT m.tg, i.status, COUNT(*) n
           FROM report_items i
           JOIN weekly_reports r ON i.report_id = r.report_id
           JOIN members m ON r.member_id = m.member_id
           WHERE r.week = ?
           GROUP BY m.tg, i.status
           ORDER BY m.tg_order''',
        (week,),
    ).fetchall()

    # Pivot: {tg: {完:n, 進:n, 이슈:n}}
    tg_pivot: dict = {}
    for row in tg_rows:
        tg = row['tg']
        if tg not in tg_pivot:
            tg_pivot[tg] = {'完': 0, '進': 0, '이슈': 0}
        tg_pivot[tg][row['status']] = row['n']

    tg_summary = [
        {'tg': tg, 'done': v['完'], 'ongoing': v['進'], 'issue': v['이슈']}
        for tg, v in tg_pivot.items()
    ]

    # ── 3. 위험 마일스톤 ──────────────────────────────────────────────────────
    today = date.today().isoformat()
    risk_milestones = db.execute(
        '''SELECT p.name project_name, p.track, p.project_id,
                  ms.ms_id, ms.type, ms.planned_date, ms.actual_date, ms.status,
                  m.display_name owner_name
           FROM milestones ms
           JOIN projects p ON ms.project_id = p.project_id
           LEFT JOIN members m ON p.owner_member_id = m.member_id
           WHERE ms.status = 'risk'
              OR (ms.status = 'pending'
                  AND ms.planned_date IS NOT NULL
                  AND ms.planned_date < ?)
           ORDER BY ms.planned_date''',
        (today,),
    ).fetchall()

    # ── 4. MBO 달성률 현황 ────────────────────────────────────────────────────
    year = week.split('-W')[0]
    mbo_rows = db.execute(
        '''SELECT m.member_id, m.display_name member_name, m.tg,
                  o.mbo_id, o.title, o.weight, o.target_metric,
                  o.target_value, o.linked_project_id
           FROM mbo_objectives o
           JOIN members m ON o.member_id = m.member_id
           WHERE o.year = ?
           ORDER BY m.tg_order, m.member_id, o.mbo_id''',
        (year,),
    ).fetchall()

    # Count 完 items per linked project
    mbo_list = []
    for r in mbo_rows:
        current_n = 0
        if r['linked_project_id']:
            res = db.execute(
                '''SELECT COUNT(*) n FROM report_items ri
                   JOIN weekly_reports wr ON ri.report_id = wr.report_id
                   WHERE wr.project_id = ? AND ri.status = '完' ''',
                (r['linked_project_id'],),
            ).fetchone()
            current_n = res['n'] if res else 0

        target = r['target_value']
        rate = None
        try:
            rate = round(current_n / float(target) * 100, 1) if target else None
        except (TypeError, ZeroDivisionError):
            rate = None

        mbo_list.append({
            'member_id':  r['member_id'],
            'member_name': r['member_name'],
            'tg':          r['tg'],
            'mbo_id':      r['mbo_id'],
            'title':       r['title'],
            'weight':      r['weight'],
            'target_metric': r['target_metric'],
            'target_value':  target,
            'current_value': current_n,
            'achievement_rate': rate,
        })

    # ── 5. 이번 주 이슈 항목 목록 ─────────────────────────────────────────────
    issues = db.execute(
        '''SELECT m.display_name member_name, p.name project_name,
                  i.item_key, i.risk_text, i.delay_reason
           FROM report_items i
           JOIN weekly_reports r ON i.report_id = r.report_id
           JOIN members m ON r.member_id = m.member_id
           JOIN projects p ON r.project_id = p.project_id
           WHERE r.week = ? AND i.status = '이슈'
           ORDER BY m.tg_order, m.member_id''',
        (week,),
    ).fetchall()

    return jsonify({
        'week':             week,
        'submission':       submission,
        'tg_summary':       tg_summary,
        'risk_milestones':  [dict(r) for r in risk_milestones],
        'mbo':              mbo_list,
        'issues':           [dict(r) for r in issues],
    })
