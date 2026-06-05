from datetime import date


def _to_date(v) -> date | None:
    if v is None:
        return None
    if isinstance(v, str):
        return date.fromisoformat(v)
    return v


def validate_required_fields(item: dict) -> None:
    """Raise ValueError if progress_text, schedule_text, or risk_text is absent or blank."""
    for field in ('progress_text', 'schedule_text', 'risk_text'):
        value = item.get(field)
        if value is None or not str(value).strip():
            raise ValueError(f"{field} is required")


def validate_delay_reason(
    item: dict,
    milestone_status: str,
    planned_date,
    actual_date,
) -> None:
    """Raise ValueError when a delay is detected but delay_reason is absent or blank.

    Delay is detected if any of the following is true:
    - milestone_status == 'risk'
    - actual_date > planned_date (both present)
    - planned_date is in the past with no actual_date (pending overdue)
    """
    needs_reason = False

    if milestone_status == 'risk':
        needs_reason = True

    pd = _to_date(planned_date)
    ad = _to_date(actual_date)

    if pd is not None and ad is not None and ad > pd:
        needs_reason = True

    if pd is not None and ad is None and pd < date.today():
        needs_reason = True

    if needs_reason:
        reason = item.get('delay_reason')
        if not reason or not str(reason).strip():
            raise ValueError("delay_reason is required when a delay is detected")


def validate_filename(filename: str) -> None:
    """Raise ValueError if filename contains any non-ASCII character."""
    if not filename.isascii():
        raise ValueError(
            f"Filename must contain only ASCII characters: {filename!r}"
        )
