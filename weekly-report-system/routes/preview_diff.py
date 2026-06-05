import html
from flask import Blueprint, request, Response
from core.diff_engine import compute_delta

bp = Blueprint('diff', __name__)


def render_diff_html(prev_text: str, curr_text: str) -> str:
    """Return HTML string of curr_text with delta spans wrapped in <mark class="delta">."""
    spans = compute_delta(prev_text, curr_text)
    if not spans:
        return html.escape(curr_text)

    parts = []
    last = 0
    for start, end in spans:
        parts.append(html.escape(curr_text[last:start]))
        parts.append(f'<mark class="delta">{html.escape(curr_text[start:end])}</mark>')
        last = end
    parts.append(html.escape(curr_text[last:]))
    return ''.join(parts)


@bp.route('/preview-diff', methods=['POST'])
def preview_diff():
    body = request.get_json(silent=True) or {}
    prev_text = body.get('prev_text', '') or ''
    curr_text = body.get('curr_text', '') or ''
    result = render_diff_html(prev_text, curr_text)
    return Response(result, mimetype='text/html')
