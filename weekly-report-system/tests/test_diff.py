import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.diff_engine import compute_delta


def test_no_delta_for_identical_text():
    assert compute_delta("hello", "hello") == []


def test_full_insert_when_prev_empty():
    spans = compute_delta("", "hello")
    assert spans == [(0, 5)]


def test_replace_middle_word():
    prev = "ABC DEF GHI"
    curr = "ABC XYZ GHI"
    spans = compute_delta(prev, curr)
    assert len(spans) == 1
    assert curr[spans[0][0]:spans[0][1]] == "XYZ"


def test_insert_at_end():
    prev = "foo"
    curr = "foo bar"
    spans = compute_delta(prev, curr)
    assert len(spans) == 1
    assert curr[spans[0][0]:spans[0][1]] == " bar"


def test_insert_at_start():
    prev = "world"
    curr = "hello world"
    spans = compute_delta(prev, curr)
    assert len(spans) == 1
    assert curr[spans[0][0]:spans[0][1]] == "hello "


def test_no_false_positive_golden_case():
    text = "진행: SSC 포팅 완료"
    assert compute_delta(text, text) == []


def test_new_sentence_only_highlighted():
    prev = "A. B."
    curr = "A. B. C."
    spans = compute_delta(prev, curr)
    assert len(spans) == 1
    assert curr[spans[0][0]:spans[0][1]] == " C."
