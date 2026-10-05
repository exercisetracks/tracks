# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared helpers for all FIT file parsers."""

# FIT protocol manufacturer_id for common vendors.
# fitdecode sometimes returns manufacturer as a plain string (e.g. "garmin")
# rather than an enum object, so we can't always extract the numeric ID via
# the enum's .value attribute — fall back to this table.
_MANUFACTURER_IDS: dict[str, int] = {
    "garmin":          1,
    "dynastream":      15,
    "dynastream_oem":  18,
    "wahoo_fitness":   32,
    "polar":           14,
    "suunto":          8,
    "tacx":            89,
    "specialized":     63,
}


def get(frame, field):
    """Safely get a field value from a FIT frame, returning None if absent."""
    try:
        return frame.get_value(field)
    except KeyError:
        return None


def get_by_iter(frame, field_name):
    """Get a field value by iterating over the frame.

    fitdecode's get_value() and has_field() do not index unknown_* fields by
    name, so standard lookups silently fail for them.  Iterating the frame
    object always works and is the only reliable way to read those fields.
    """
    for field in frame:
        if field.name == field_name:
            return field.value
    return None


def get_enhanced(frame, field):
    """Try the enhanced_ version of a field first, fall back to the plain version.

    Garmin devices from ~2015 onward write enhanced_speed and enhanced_altitude
    instead of (or in addition to) the original lower-precision fields.
    Must use 'is not None' — not 'or' — because 0.0 is a valid value.
    """
    value = get(frame, f"enhanced_{field}")
    if value is not None:
        return value
    return get(frame, field)


def as_str(value) -> str | None:
    """Convert a fitdecode enum or raw value to a plain string."""
    if value is None:
        return None
    if hasattr(value, "name"):
        return value.name
    return str(value)


def as_int(value) -> int | None:
    if value is None:
        return None
    if hasattr(value, "value"):
        return int(value.value)
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def parse_file_id(frame) -> dict:
    """Extract device identification fields from a file_id message."""
    serial = get(frame, "serial_number")
    mfr_raw = get(frame, "manufacturer")
    mfr_str = as_str(mfr_raw)
    # as_int handles enum objects; fall back to the lookup table for plain strings
    mfr_id = as_int(mfr_raw) or _MANUFACTURER_IDS.get(mfr_str)
    return {
        "serial_number":   str(serial) if serial is not None else None,
        "manufacturer":    mfr_str,
        "manufacturer_id": mfr_id,
        # Garmin uses garmin_product; other vendors use product_name
        "product_name":    get(frame, "garmin_product") or get(frame, "product_name"),
        "product_id":      as_int(get(frame, "product")),
    }
