import sys
from pathlib import Path
from datetime import date, timedelta

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.validators import validate_delay_reason

TODAY = date.today()
YESTERDAY = TODAY - timedelta(days=1)
TOMORROW = TODAY + timedelta(days=1)
WEEK_AGO = TODAY - timedelta(days=7)
WEEK_AHEAD = TODAY + timedelta(days=7)


def test_done_milestone_no_delay_check():
    """Completed on time — no delay reason needed."""
    item = {'delay_reason': None}
    validate_delay_reason(item, 'done', YESTERDAY, YESTERDAY)


def test_risk_status_requires_reason():
    """risk status always triggers regardless of dates."""
    item = {'delay_reason': ''}
    with pytest.raises(ValueError):
        validate_delay_reason(item, 'risk', WEEK_AHEAD, None)


def test_pending_on_future_date_ok():
    """pending with planned date still in the future — no reason needed."""
    item = {'delay_reason': None}
    validate_delay_reason(item, 'pending', WEEK_AHEAD, None)


def test_pending_past_planned_requires_reason():
    """pending with planned date already past and no actual — overdue."""
    item = {'delay_reason': None}
    with pytest.raises(ValueError):
        validate_delay_reason(item, 'pending', WEEK_AGO, None)
