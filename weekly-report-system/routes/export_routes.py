from flask import Blueprint, current_app, request, send_file
import io

bp = Blueprint('export', __name__, url_prefix='/export')


@bp.route('/doc')
def export_doc():
    week = request.args.get('week', '')
    if not week:
        return 'week 파라미터가 필요합니다', 400
    from export.docx_builder import build_doc
    data = build_doc(week, current_app.config['DB_PATH'])
    return send_file(
        io.BytesIO(data),
        mimetype='application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        as_attachment=True,
        download_name=f'weekly_report_{week}.docx',
    )


@bp.route('/xls/mbo')
def export_mbo():
    year = int(request.args.get('year', '2026'))
    from export.xlsx_builder import build_mbo_xls
    data = build_mbo_xls(year, current_app.config['DB_PATH'])
    return send_file(
        io.BytesIO(data),
        mimetype='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        as_attachment=True,
        download_name=f'mbo_{year}.xlsx',
    )


@bp.route('/xls/feedback')
def export_feedback():
    from_week = request.args.get('from_week', '')
    to_week   = request.args.get('to_week', '')
    from export.xlsx_builder import build_feedback_xls
    data = build_feedback_xls(from_week, to_week, current_app.config['DB_PATH'])
    return send_file(
        io.BytesIO(data),
        mimetype='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        as_attachment=True,
        download_name='feedback.xlsx',
    )


@bp.route('/xls/eval')
def export_eval():
    year = int(request.args.get('year', '2026'))
    from export.xlsx_builder import build_eval_xls
    data = build_eval_xls(year, current_app.config['DB_PATH'])
    return send_file(
        io.BytesIO(data),
        mimetype='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        as_attachment=True,
        download_name=f'eval_{year}.xlsx',
    )
