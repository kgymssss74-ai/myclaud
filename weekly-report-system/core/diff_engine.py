from difflib import SequenceMatcher


def compute_delta(prev_text: str, curr_text: str) -> list[tuple[int, int]]:
    """Return character-offset spans in curr_text where content is new or replaced.

    Uses difflib.SequenceMatcher opcodes; collects 'insert' and 'replace' spans
    (j1, j2) in curr_text.  'equal' and 'delete' opcodes are ignored.
    Identical inputs return [].  Empty prev_text yields spans covering all of curr_text.
    """
    spans: list[tuple[int, int]] = []
    matcher = SequenceMatcher(None, prev_text, curr_text)
    for tag, _i1, _i2, j1, j2 in matcher.get_opcodes():
        if tag in ('insert', 'replace'):
            spans.append((j1, j2))
    return spans
