from datetime import date
from pathlib import Path

from flask import Blueprint, current_app, jsonify, redirect, render_template, request

from core.diff_engine import compute_delta
from core.validators import validate_delay_reason, validate_filename, validate_required_fields
from db.database import get_db
from routes.register import MILESTONE_TYPES

bp = Blueprint('weekly', __name__)


def _current_week() -> str:
    y, w, _ = date.today().isocalendar()
    return f'{y}-W{w:02d}'


def _prev_week(week: str) -> str:
    year, w = week.split('-W')
    year, w = int(year), int(w)
    return f'{year}-W{w-1:02d}' if w > 1 else f'{year-1}-W52'


# ── API helpers ────────────────────────────────────────────────────────────────

@bp.route('/api/projects')
def api_projects():
    member_id = request.args.get('member_id', '')
    db = get_db()
    if member_id:
        m = db.execute('SELECT tg FROM members WHERE member_id=?', (member_id,)).fetchone()
        tg = m['tg'] if m else None
        rows = db.execute(
            'SELECT project_id, name, track FROM projects WHERE tg=? ORDER BY track, name',
            (tg,),
        ).fetchall() if tg else []
    else:
        rows = db.execute(
            'SELECT project_id, name, track FROM projects ORDER BY track, name'
        ).fetchall()
    return jsonify([dict(r) for r in rows])


@bp.route('/api/milestones')
def api_milestones():
    project_id = request.args.get('project_id', '')
    db = get_db()
    rows = db.execute(
        'SELECT ms_id, type, planned_date, actual_date, status '
        'FROM milestones WHERE project_id=? ORDER BY ms_id',
        (project_id,),
    ).fetchall()
    return jsonify([dict(r) for r in rows])


@bp.route('/api/prev-report')
def api_prev_report():
    member_id  = request.args.get('member_id', '')
    project_id = request.args.get('project_id', '')
    week       = request.args.get('week', '')
    db = get_db()
    report = db.execute(
        'SELECT report_id FROM weekly_reports WHERE member_id=? AND project_id=? AND week=?',
        (member_id, project_id, week),
    ).fetchone()
    if not report:
        return jsonify({'items': []})
    items = db.execute(
        'SELECT item_key, progress_text, schedule_text, risk_text, delay_reason, status '
        'FROM report_items WHERE report_id=? ORDER BY item_id',
        (report['report_id'],),
    ).fetchall()
    return jsonify({'items': [dict(i) for i in items]})


# ── Weekly form ────────────────────────────────────────────────────────────────

@bp.route('/weekly', methods=['GET'])
def weekly_form():
    db = get_db()
    members = db.execute(
        'SELECT member_id, display_name, name, tg FROM members ORDER BY tg_order, member_id'
    ).fetchall()
    saved      = request.args.get('saved', '')
    member_id  = request.args.get('member_id', '')
    project_id = request.args.get('project_id', '')
    week       = request.args.get('week', _current_week())
    return render_template(
        'weekly.html',
        members=members,
        current_week=week,
        saved=saved,
        sel_member=member_id,
        sel_project=project_id,
    )


@bp.route('/weekly', methods=['POST'])
def weekly_save():
    db   = get_db()
    data = request.form

    member_id  = data.get('member_id', '').strip()
    project_id = data.get('project_id', '').strip()
    week       = data.get('week', '').strip()

    if not member_id or not project_id or not week:
        return jsonify({'error': 'member_id, project_id, week 는 필수입니다'}), 422

    project = db.execute('SELECT track FROM projects WHERE project_id=?', (project_id,)).fetchone()
    if not project:
        return jsonify({'error': '존재하지 않는 과제입니다'}), 422

    milestones = {
        ms['type']: ms
        for ms in db.execute(
            'SELECT * FROM milestones WHERE project_id=?', (project_id,)
        ).fetchall()
    }

    ms_types  = MILESTONE_TYPES.get(project['track'], [])
    item_keys = ms_types if ms_types else ['general']

    # ── Validate all items before writing ─────────────────────────────────────
    errors: dict = {}
    for key in item_keys:
        item = {
            'progress_text': data.get(f'progress_text_{key}', '').strip(),
            'schedule_text': data.get(f'schedule_text_{key}', '').strip(),
            'risk_text':     data.get(f'risk_text_{key}', '').strip(),
            'delay_reason':  data.get(f'delay_reason_{key}', '').strip(),
        }
        try:
            validate_required_fields(item)
        except ValueError as e:
            errors[f'{key}_required'] = str(e)
            continue

        ms = milestones.get(key)
        if ms:
            try:
                validate_delay_reason(item, ms['status'], ms['planned_date'], ms['actual_date'])
            except ValueError as e:
                errors[f'{key}_delay'] = str(e)

    # Validate image filenames
    for key in item_keys:
        for f in request.files.getlist(f'images_{key}'):
            if f.filename:
                try:
                    validate_filename(f.filename)
                except ValueError as e:
                    errors[f'{key}_filename'] = str(e)

    if errors:
        return jsonify({'errors': errors}), 422

    # ── Upsert report ──────────────────────────────────────────────────────────
    existing = db.execute(
        'SELECT report_id FROM weekly_reports WHERE week=? AND member_id=? AND project_id=?',
        (week, member_id, project_id),
    ).fetchone()

    if existing:
        report_id = existing['report_id']
        db.execute('DELETE FROM report_items WHERE report_id=?', (report_id,))
    else:
        db.execute(
            'INSERT INTO weekly_reports (week, member_id, project_id) VALUES (?,?,?)',
            (week, member_id, project_id),
        )
        report_id = db.execute('SELECT last_insert_rowid() r').fetchone()['r']

    # Fetch previous week's items for delta
    prev_week = _prev_week(week)
    prev_report = db.execute(
        'SELECT report_id FROM weekly_reports WHERE week=? AND member_id=? AND project_id=?',
        (prev_week, member_id, project_id),
    ).fetchone()

    prev_items: dict = {}
    if prev_report:
        for row in db.execute(
            'SELECT item_key, progress_text, schedule_text, risk_text '
            'FROM report_items WHERE report_id=?',
            (prev_report['report_id'],),
        ).fetchall():
            prev_items[row['item_key']] = dict(row)

    # ── Write items ────────────────────────────────────────────────────────────
    year = week.split('-W')[0]
    upload_dir = Path(current_app.config['UPLOAD_FOLDER']) / year / week / member_id

    for key in item_keys:
        ms = milestones.get(key)
        ms_id = ms['ms_id'] if ms else None

        progress_text = data.get(f'progress_text_{key}', '').strip()
        schedule_text = data.get(f'schedule_text_{key}', '').strip()
        risk_text     = data.get(f'risk_text_{key}', '').strip()
        delay_reason  = data.get(f'delay_reason_{key}', '').strip() or None
        status        = data.get(f'status_{key}', '進').strip()

        prev = prev_items.get(key, {})
        prev_item_row = (
            db.execute(
                'SELECT item_id FROM report_items WHERE report_id=? AND item_key=?',
                (prev_report['report_id'], key),
            ).fetchone()
            if prev_report
            else None
        )
        prev_item_id = prev_item_row['item_id'] if prev_item_row else None

        db.execute(
            '''INSERT INTO report_items
               (report_id, item_key, progress_text, schedule_text, risk_text,
                delay_reason, ms_id, status, prev_item_id)
               VALUES (?,?,?,?,?,?,?,?,?)''',
            (report_id, key, progress_text, schedule_text, risk_text,
             delay_reason, ms_id, status, prev_item_id),
        )
        item_id = db.execute('SELECT last_insert_rowid() r').fetchone()['r']

        # Compute delta spans
        field_map = {
            'progress_text': progress_text,
            'schedule_text': schedule_text,
            'risk_text':     risk_text,
        }
        for field, curr_val in field_map.items():
            prev_val = prev.get(field, '') or ''
            for s, e in compute_delta(prev_val, curr_val):
                db.execute(
                    'INSERT INTO delta_spans (item_id, field, start_pos, end_pos) VALUES (?,?,?,?)',
                    (item_id, field, s, e),
                )

        # Save image attachments
        files = request.files.getlist(f'images_{key}')
        if files and any(f.filename for f in files):
            upload_dir.mkdir(parents=True, exist_ok=True)
        for f in files:
            if f.filename:
                dest = upload_dir / f'{item_id}_{f.filename}'
                f.save(str(dest))
                rel = str(dest.relative_to(Path(current_app.config['UPLOAD_FOLDER']).parent))
                db.execute(
                    'INSERT INTO item_attachments (item_id, kind, path) VALUES (?,?,?)',
                    (item_id, 'image', rel),
                )

    db.commit()
    return redirect(f'/weekly?saved=1&member_id={member_id}&project_id={project_id}&week={week}')
