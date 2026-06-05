import sys
from pathlib import Path
from datetime import date, timedelta

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.validators import (
    validate_required_fields,
    validate_delay_reason,
    validate_filename,
)

# ---------------------------------------------------------------------------
# validate_required_fields
# ---------------------------------------------------------------------------

def test_missing_progress_raises():
    with pytest.raises(ValueError):
        validate_required_fields({'schedule_text': 'x', 'risk_text': 'x'})


def test_missing_schedule_raises():
    with pytest.raises(ValueError):
        validate_required_fields({'progress_text': 'x', 'risk_text': 'x'})


def test_missing_risk_raises():
    with pytest.raises(ValueError):
        validate_required_fields({'progress_text': 'x', 'schedule_text': 'x'})


def test_empty_string_fields_raise():
    with pytest.raises(ValueError):
        validate_required_fields({'progress_text': '', 'schedule_text': '', 'risk_text': ''})


def test_valid_item_passes():
    validate_required_fields({
        'progress_text': 'done',
        'schedule_text': 'on track',
        'risk_text': 'none',
    })


# ---------------------------------------------------------------------------
# validate_delay_reason
# ---------------------------------------------------------------------------

def test_delay_no_reason_raises():
    item = {'delay_reason': ''}
    with pytest.raises(ValueError):
        validate_delay_reason(item, 'risk', date.today(), None)


def test_on_time_no_reason_ok():
    today = date.today()
    item = {'delay_reason': None}
    validate_delay_reason(item, 'done', today, today)


def test_actual_past_planned_requires_reason():
    planned = date.today() - timedelta(days=7)
    actual = date.today()
    item = {'delay_reason': None}
    with pytest.raises(ValueError):
        validate_delay_reason(item, 'done', planned, actual)


# ---------------------------------------------------------------------------
# validate_filename
# ---------------------------------------------------------------------------

def test_ascii_filename_ok():
    validate_filename('report_2026_W23.docx')


def test_korean_filename_raises():
    with pytest.raises(ValueError):
        validate_filename('보고서.docx')
