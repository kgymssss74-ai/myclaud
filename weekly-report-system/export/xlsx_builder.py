"""
xlsx_builder.py — Generate MBO / Feedback / Evaluation XLS.

MBO sheet: formula =current/target*100 for achievement rate.
"""
import io
import sqlite3

import openpyxl
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

HEADER_FILL = PatternFill('solid', fgColor='4472C4')
HEADER_FONT = Font(bold=True, color='FFFFFF')


def _conn(db_path: str) -> sqlite3.Connection:
    c = sqlite3.connect(db_path)
    c.row_factory = sqlite3.Row
    return c


def _style_header(ws, row: int, cols: int) -> None:
    for col in range(1, cols + 1):
        cell = ws.cell(row=row, column=col)
        cell.fill = HEADER_FILL
        cell.font = HEADER_FONT
        cell.alignment = Alignment(horizontal='center')


def _autofit(ws) -> None:
    for col in ws.columns:
        max_len = max((len(str(c.value or '')) for c in col), default=8)
        ws.column_dimensions[get_column_letter(col[0].column)].width = min(max_len + 2, 50)


# ── MBO XLS ────────────────────────────────────────────────────────────────────

def build_mbo_xls(year: int, db_path: str) -> bytes:
    conn = _conn(db_path)
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = f'MBO_{year}'

    headers = ['파트원', '목표명', '가중치(%)', '목표지표', '목표값', '현재값', '달성률(%)', '상태']
    ws.append(headers)
    _style_header(ws, 1, len(headers))

    rows = conn.execute(
        '''SELECT m.display_name member_name, o.mbo_id, o.title, o.weight,
                  o.target_metric, o.target_value, o.linked_project_id
           FROM mbo_objectives o
           JOIN members m ON o.member_id = m.member_id
           WHERE o.year = ?
           ORDER BY m.tg_order, m.member_id, o.mbo_id''',
        (year,),
    ).fetchall()

    data_start = 2
    for i, r in enumerate(rows):
        row_num = data_start + i
        # Count completed items for linked project as "current value"
        current_val = 0
        if r['linked_project_id']:
            res = conn.execute(
                '''SELECT COUNT(*) n FROM report_items ri
                   JOIN weekly_reports wr ON ri.report_id = wr.report_id
                   WHERE wr.project_id = ? AND ri.status = '完' ''',
                (r['linked_project_id'],),
            ).fetchone()
            current_val = res['n'] if res else 0

        target_val_str = str(r['target_value'] or '')
        ws.append([
            r['member_name'],
            r['title'],
            r['weight'],
            r['target_metric'],
            target_val_str,       # E: 목표값
            current_val,          # F: 현재값
            None,                 # G: 달성률 — formula below
            '',                   # H: 상태
        ])
        # Achievement rate formula: =F{row}/E{row}*100  (only if target is numeric)
        g_cell = ws.cell(row=row_num, column=7)
        try:
            float(target_val_str)
            g_cell.value = f'=IF(E{row_num}="",0,F{row_num}/VALUE(E{row_num})*100)'
        except (ValueError, TypeError):
            g_cell.value = ''
        g_cell.number_format = '0.0'

    _style_header(ws, 1, len(headers))
    _autofit(ws)
    conn.close()

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


# ── Feedback XLS ───────────────────────────────────────────────────────────────

def build_feedback_xls(from_week: str, to_week: str, db_path: str) -> bytes:
    conn = _conn(db_path)
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = 'Feedback'

    headers = ['주차', '파트원', 'TG', '과제명', 'MS', '진행', '일정', 'Risk', '상태', '지연사유']
    ws.append(headers)
    _style_header(ws, 1, len(headers))

    params = [from_week, from_week, to_week, to_week]
    rows = conn.execute(
        '''SELECT r.week, m.display_name member_name, m.tg, p.name project_name,
                  i.item_key, i.progress_text, i.schedule_text, i.risk_text, i.status, i.delay_reason
           FROM report_items i
           JOIN weekly_reports r ON i.report_id = r.report_id
           JOIN members m ON r.member_id = m.member_id
           JOIN projects p ON r.project_id = p.project_id
           WHERE (? = '' OR r.week >= ?)
             AND (? = '' OR r.week <= ?)
           ORDER BY m.tg_order, m.member_id, r.week, p.name, i.item_key''',
        params,
    ).fetchall()

    for r in rows:
        ws.append([
            r['week'], r['member_name'], r['tg'], r['project_name'],
            r['item_key'], r['progress_text'], r['schedule_text'],
            r['risk_text'], r['status'], r['delay_reason'],
        ])
        # Color status column
        status_cell = ws.cell(row=ws.max_row, column=9)
        if r['status'] == '완':
            status_cell.fill = PatternFill('solid', fgColor='C6EFCE')
        elif r['status'] == '이슈':
            status_cell.fill = PatternFill('solid', fgColor='FFC7CE')

    _autofit(ws)
    conn.close()

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


# ── Evaluation XLS ─────────────────────────────────────────────────────────────

def build_eval_xls(year: int, db_path: str) -> bytes:
    conn = _conn(db_path)
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = f'Eval_{year}'

    headers = ['파트원', 'TG', 'CL', '완료 항목', '진행 항목', '이슈 항목', '총 항목', '완료율(%)']
    ws.append(headers)
    _style_header(ws, 1, len(headers))

    members = conn.execute(
        'SELECT member_id, display_name, tg, cl_level FROM members ORDER BY tg_order, member_id'
    ).fetchall()

    data_start = 2
    for i, m in enumerate(members):
        row_num = data_start + i
        counts = conn.execute(
            '''SELECT i.status, COUNT(*) n
               FROM report_items i
               JOIN weekly_reports r ON i.report_id = r.report_id
               WHERE r.member_id = ?
                 AND substr(r.week, 1, 4) = ?
               GROUP BY i.status''',
            (m['member_id'], str(year)),
        ).fetchall()
        cnt = {r['status']: r['n'] for r in counts}
        done    = cnt.get('完', 0)
        ongoing = cnt.get('進', 0)
        issue   = cnt.get('이슈', 0)
        total   = done + ongoing + issue

        ws.append([m['display_name'], m['tg'], m['cl_level'],
                   done, ongoing, issue, total, None])
        # Completion rate formula
        rate_cell = ws.cell(row=row_num, column=8)
        rate_cell.value = f'=IF(G{row_num}=0,0,D{row_num}/G{row_num}*100)'
        rate_cell.number_format = '0.0'

    _autofit(ws)
    conn.close()

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()
