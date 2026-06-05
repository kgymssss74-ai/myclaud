from flask import Blueprint, request, jsonify, render_template, redirect
from db.database import get_db

bp = Blueprint('register', __name__)

MILESTONE_TYPES = {
    'device':  ['DVR', 'PVR', 'PRA', 'SRA'],
    'advance': ['EA', 'ER1', 'ER2', 'CA'],
    'review':  [],
}


def _next_project_id(db):
    row = db.execute("SELECT project_id FROM projects ORDER BY project_id DESC LIMIT 1").fetchone()
    return 'P01' if not row else f'P{int(row["project_id"][1:])+1:02d}'


def _insert_milestones(db, project_id, track, data):
    ms_types = MILESTONE_TYPES.get(track, [])
    if not ms_types:
        return
    row = db.execute("SELECT ms_id FROM milestones ORDER BY ms_id DESC LIMIT 1").fetchone()
    counter = int(row['ms_id'][2:]) + 1 if row else 1
    for ms_type in ms_types:
        ms_id = f'MS{counter:03d}'
        date_val = data.get(f'date_{ms_type}', '').strip() or None
        db.execute(
            'INSERT INTO milestones (ms_id, project_id, type, planned_date, status) VALUES (?,?,?,?,?)',
            (ms_id, project_id, ms_type, date_val, 'pending'),
        )
        counter += 1


@bp.route('/register', methods=['GET', 'POST'])
def register():
    db = get_db()
    members = db.execute(
        'SELECT member_id, display_name, name FROM members ORDER BY tg_order, member_id'
    ).fetchall()

    if request.method == 'GET':
        return render_template('register.html', members=members)

    data = request.form
    track   = data.get('track', '').strip()
    name    = data.get('name', '').strip()
    owner   = data.get('owner_member_id', '').strip()
    week    = data.get('created_week', '').strip() or None
    platform   = data.get('platform', '').strip() or None
    background = data.get('background', '').strip() or None

    errors = []
    if track not in MILESTONE_TYPES:
        errors.append('track 을 선택하세요 (device / advance / review)')
    if not name:
        errors.append('과제명을 입력하세요')
    if not owner:
        errors.append('담당자를 선택하세요')
    if track == 'device' and not platform:
        errors.append('device 과제는 플랫폼을 입력하세요')
    if track in ('advance', 'review') and not background:
        errors.append('advance/review 과제는 배경을 입력하세요')
    if errors:
        return jsonify({'errors': errors}), 422

    member = db.execute('SELECT tg FROM members WHERE member_id=?', (owner,)).fetchone()
    tg = member['tg'] if member else None

    project_id = _next_project_id(db)
    db.execute(
        '''INSERT INTO projects
           (project_id, name, track, platform, background, owner_member_id, created_week, tg)
           VALUES (?,?,?,?,?,?,?,?)''',
        (project_id, name, track, platform, background, owner, week, tg),
    )
    _insert_milestones(db, project_id, track, data)
    db.commit()

    return redirect(f'/register/done?project_id={project_id}')


@bp.route('/register/done')
def register_done():
    project_id = request.args.get('project_id', '')
    db = get_db()
    project    = db.execute('SELECT * FROM projects WHERE project_id=?', (project_id,)).fetchone()
    milestones = db.execute(
        'SELECT * FROM milestones WHERE project_id=? ORDER BY ms_id', (project_id,)
    ).fetchall()
    return render_template('register_done.html', project=project, milestones=milestones)
