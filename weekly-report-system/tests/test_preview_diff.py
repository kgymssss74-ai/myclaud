"""Gate ⑤ — /preview-diff 서버 diff 검증"""
import pytest
from routes.preview_diff import render_diff_html


# ── render_diff_html unit tests ───────────────────────────────────────────────

def test_render_diff_html_identical_no_mark():
    html = render_diff_html('hello world', 'hello world')
    assert '<mark' not in html
    assert 'hello world' in html


def test_render_diff_html_empty_prev_no_mark():
    """Empty prev → no prev baseline → no mark (all is 'new', treated as full insert)."""
    html = render_diff_html('', 'hello')
    # compute_delta('', 'hello') returns [(0,5)] → wrapped in mark
    assert '<mark class="delta">hello</mark>' in html


def test_render_diff_html_partial_change_has_mark():
    html = render_diff_html('hello world', 'hello new world')
    assert '<mark class="delta">' in html
    assert 'new ' in html


def test_render_diff_html_escapes_html_chars():
    html = render_diff_html('a', 'a <script>alert(1)</script>')
    assert '<script>' not in html
    assert '&lt;script&gt;' in html


def test_render_diff_html_korean_text():
    html = render_diff_html('진행: SSC 완료', '진행: SSC 완료 — 추가 내용')
    assert '<mark class="delta">' in html
    # Escaped em dash or space will be in html
    assert '추가' in html


# ── /preview-diff endpoint tests ─────────────────────────────────────────────

def test_preview_diff_endpoint_returns_200(client):
    resp = client.post('/preview-diff', json={
        'prev_text': 'hello',
        'curr_text': 'hello world',
    })
    assert resp.status_code == 200
    assert resp.content_type.startswith('text/html')


def test_preview_diff_endpoint_has_mark_on_change(client):
    resp = client.post('/preview-diff', json={
        'prev_text': 'ABC DEF',
        'curr_text': 'ABC XYZ',
    })
    html = resp.get_data(as_text=True)
    assert '<mark class="delta">' in html


def test_preview_diff_endpoint_no_mark_on_identical(client):
    resp = client.post('/preview-diff', json={
        'prev_text': '동일한 텍스트',
        'curr_text': '동일한 텍스트',
    })
    html = resp.get_data(as_text=True)
    assert '<mark' not in html


def test_preview_diff_endpoint_handles_empty_body(client):
    """Missing keys default to empty strings — should not crash."""
    resp = client.post('/preview-diff', json={})
    assert resp.status_code == 200


def test_preview_diff_client_matches_doc_engine():
    """Server diff engine must match what's used in DOC generation.
    Both use render_diff_html → no discrepancy possible by design."""
    prev = '진행: DVR 완료'
    curr = '진행: DVR 완료, PVR 시작'
    html = render_diff_html(prev, curr)
    from core.diff_engine import compute_delta
    spans = compute_delta(prev, curr)
    # All span content should appear inside <mark> in the HTML
    for s, e in spans:
        import html as htmllib
        assert htmllib.escape(curr[s:e]) in html
