from flask import Blueprint, jsonify, render_template, request
from db.database import get_db

bp = Blueprint('query', __name__)


@bp.route('/query')
def query_page():
    db = get_db()
    members = db.execute(
        'SELECT member_id, display_name, name, tg FROM members ORDER BY tg_order, member_id'
    ).fetchall()
    projects = db.execute(
        'SELECT project_id, name, track FROM projects ORDER BY track, name'
    ).fetchall()
    tags = db.execute('SELECT tag_id, name FROM tags ORDER BY name').fetchall()
    return render_template('dashboard.html', members=members, projects=projects, tags=tags)


# ── API ────────────────────────────────────────────────────────────────────────

@bp.route('/api/query/member')
def query_by_member():
    member_id = request.args.get('member_id', '')
    from_week  = request.args.get('from_week', '')
    to_week    = request.args.get('to_week', '')
    db = get_db()
    rows = db.execute(
        '''SELECT r.week, p.name project_name, p.track,
                  i.item_key, i.progress_text, i.schedule_text, i.risk_text, i.status
           FROM weekly_reports r
           JOIN projects p ON r.project_id = p.project_id
           JOIN report_items i ON i.report_id = r.report_id
           WHERE r.member_id = ?
             AND (? = '' OR r.week >= ?)
             AND (? = '' OR r.week <= ?)
           ORDER BY r.week DESC, p.name, i.item_key''',
        (member_id, from_week, from_week, to_week, to_week),
    ).fetchall()
    return jsonify([dict(r) for r in rows])


@bp.route('/api/query/project')
def query_by_project():
    project_id = request.args.get('project_id', '')
    from_week  = request.args.get('from_week', '')
    to_week    = request.args.get('to_week', '')
    db = get_db()
    rows = db.execute(
        '''SELECT r.week, m.display_name member_name, i.item_key,
                  i.progress_text, i.schedule_text, i.risk_text, i.status
           FROM weekly_reports r
           JOIN members m ON r.member_id = m.member_id
           JOIN report_items i ON i.report_id = r.report_id
           WHERE r.project_id = ?
             AND (? = '' OR r.week >= ?)
             AND (? = '' OR r.week <= ?)
           ORDER BY r.week DESC, m.tg_order, i.item_key''',
        (project_id, from_week, from_week, to_week, to_week),
    ).fetchall()
    return jsonify([dict(r) for r in rows])


@bp.route('/api/query/tag')
def query_by_tag():
    tag_name  = request.args.get('tag', '')
    from_week = request.args.get('from_week', '')
    to_week   = request.args.get('to_week', '')
    db = get_db()
    rows = db.execute(
        '''SELECT r.week, m.display_name member_name, p.name project_name,
                  i.item_key, i.progress_text, i.status
           FROM item_tags it
           JOIN tags t ON it.tag_id = t.tag_id
           JOIN report_items i ON it.item_id = i.item_id
           JOIN weekly_reports r ON i.report_id = r.report_id
           JOIN members m ON r.member_id = m.member_id
           JOIN projects p ON r.project_id = p.project_id
           WHERE t.name = ?
             AND (? = '' OR r.week >= ?)
             AND (? = '' OR r.week <= ?)
           ORDER BY r.week DESC''',
        (tag_name, from_week, from_week, to_week, to_week),
    ).fetchall()
    return jsonify([dict(r) for r in rows])


@bp.route('/api/query/milestone-status')
def milestone_status():
    """Dashboard: milestones for current/upcoming week at-risk or done."""
    db = get_db()
    rows = db.execute(
        '''SELECT p.name project_name, p.track, m.type, m.planned_date, m.actual_date, m.status
           FROM milestones m
           JOIN projects p ON m.project_id = p.project_id
           ORDER BY p.name, m.ms_id'''
    ).fetchall()
    return jsonify([dict(r) for r in rows])
