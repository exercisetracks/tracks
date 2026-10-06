# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import zoneinfo
from datetime import datetime
from typing import Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, computed_field, field_validator

from app.api.strength.schemas import VALID_EQUIPMENT
from app.calculators.zones import hr_zones, power_zones


class ZoneOut(BaseModel):
    number: int
    name: str
    description: str


class HRZoneOut(ZoneOut):
    min_bpm: int
    max_bpm: int | None = None


class PowerZoneOut(ZoneOut):
    min_watts: int
    max_watts: int | None = None


class UserSettingsOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    units: str
    timezone: str
    accent_color: str | None = None
    theme_mode: str = "system"
    avatar_url: str | None = None
    weight_kg: float | None = None
    height_cm: float | None = None
    sex: str | None = None
    birth_year: int | None = None

    # Max HR — auto derived from activity history, overridable manually
    max_hr_mode: str
    max_hr_manual: int | None = None
    max_hr_auto: int | None = None

    # Threshold HR — best 20-min rolling avg from steady efforts
    threshold_hr_mode: str
    threshold_hr_manual: int | None = None
    threshold_hr_auto: int | None = None

    # FTP — 95% of best 20-min power from cycling
    ftp_mode: str
    ftp_manual: int | None = None
    ftp_auto: int | None = None

    # Sports excluded from all metric views and charts
    hidden_sports: list[str] = []

    updated_at: datetime | None = None

    # Chart resolution for activity detail graphs
    chart_resolution: str = "high"

    # Garmin watch pace coaching
    pace_coaching: bool = False

    # AGPS (Assisted GPS) — defaults off to keep the app fully offline by default
    agps_enabled: bool = False
    agps_source: str = "garmin"
    agps_custom_url: str | None = None
    agps_epo_path: str = "GARMIN/REMOTESW/CPE.bin"
    agps_max_age_hours: int = 24
    agps_last_synced_at: datetime | None = None

    # Strength training equipment available at the user's gym / home
    equipment_available: list[str] = ["bodyweight", "dumbbell"]

    # Strength-training experience (brand_new|returning|regular|advanced),
    # asked once when strength is first enabled; None = never asked
    strength_experience: str | None = None
    # {sport family: never|occasional|1_2|3_4|5_plus}; see the model.
    activity_frequency: dict[str, str] | None = None
    # History-derived "coach note" suggestion (never auto-applied).
    experience_suggestion: str | None = None
    experience_suggestion_reason: str | None = None
    experience_suggestion_dismissed: bool = False

    # Onboarding — the user hasn't completed the setup wizard yet
    setup_complete: bool = False

    # In-app guided tutorial state. tour_seen maps {tourId: true} for tours the
    # user has finished/dismissed; tour_enabled is the master "show tips" switch.
    tour_seen: dict = {}
    tour_enabled: bool = True

    @field_validator("tour_seen", mode="before")
    @classmethod
    def _tour_seen_default(cls, v):
        # Rows created before the tour columns existed store NULL — treat the
        # absence of any saved progress as an empty map.
        return v or {}

    # Weather everywhere it is fetched: races, map points, watch (Open-Meteo)
    weather_enabled: bool = True

    # Map tiles — downloads global basemap and DEM tiles from third-party servers
    map_enabled: bool = False

    # Live wildfire + smoke overlays (NIFC / NOAA feeds, fetched server-side)
    wildfire_enabled: bool = False

    # AI coaching provider config (api_key is write-only — never returned)
    ai_provider: str | None = None
    ai_endpoint: str | None = None
    ai_model: str | None = None
    # Populated from ORM but excluded from serialised output — used only for ai_configured.
    ai_api_key_enc: str | None = Field(None, exclude=True)

    @computed_field
    @property
    def ai_configured(self) -> bool:
        """True when an encrypted AI API key is stored."""
        return bool(self.ai_api_key_enc)

    # ── Effective values (mode selects manual vs auto) ──────────────────────

    @computed_field
    @property
    def max_hr(self) -> int | None:
        if self.max_hr_mode == "manual":
            return self.max_hr_manual
        return self.max_hr_auto

    @computed_field
    @property
    def threshold_hr(self) -> int | None:
        if self.threshold_hr_mode == "manual":
            return self.threshold_hr_manual
        return self.threshold_hr_auto

    @computed_field
    @property
    def ftp(self) -> int | None:
        if self.ftp_mode == "manual":
            return self.ftp_manual
        return self.ftp_auto

    # ── Training zones (null when the base metric is unavailable) ────────────

    @computed_field
    @property
    def hr_zones_running(self) -> list[HRZoneOut] | None:
        """Friel 7-zone HR boundaries for running (based on LTHR)."""
        if self.threshold_hr is None:
            return None
        return [HRZoneOut(**z) for z in hr_zones(self.threshold_hr, sport="running")]

    @computed_field
    @property
    def hr_zones_cycling(self) -> list[HRZoneOut] | None:
        """Friel 7-zone HR boundaries for cycling (based on LTHR)."""
        if self.threshold_hr is None:
            return None
        return [HRZoneOut(**z) for z in hr_zones(self.threshold_hr, sport="cycling")]

    @computed_field
    @property
    def power_zones(self) -> list[PowerZoneOut] | None:
        """Coggan 7-zone power boundaries (based on FTP)."""
        if self.ftp is None:
            return None
        return [PowerZoneOut(**z) for z in power_zones(self.ftp)]


_VALID_TIMEZONES = zoneinfo.available_timezones()


class UserSettingsUpdate(BaseModel):
    units: Literal["metric", "imperial"] | None = None
    timezone: str | None = None
    # A preset name or a "#rrggbb" literal; the clients agree on the presets.
    accent_color: str | None = Field(None, max_length=32)
    theme_mode: Literal["system", "light", "dark"] | None = None
    avatar_url: str | None = None
    weight_kg: float | None = Field(None, ge=0, le=500)
    height_cm: float | None = Field(None, ge=50, le=300)
    sex: Literal["male", "female"] | None = None
    # Upper bound checked against today's year below, so it never goes stale.
    birth_year: int | None = Field(None, ge=1900)
    max_hr_mode: Literal["auto", "manual"] | None = None
    max_hr_manual: int | None = Field(None, ge=100, le=250)
    threshold_hr_mode: Literal["auto", "manual"] | None = None
    threshold_hr_manual: int | None = Field(None, ge=80, le=250)
    ftp_mode: Literal["auto", "manual"] | None = None
    ftp_manual: int | None = Field(None, ge=1, le=2000)
    hidden_sports: list[str] | None = None
    chart_resolution: Literal["low", "medium", "high", "raw"] | None = None
    pace_coaching: bool | None = None
    # AGPS settings
    agps_enabled: bool | None = None
    agps_source: Literal["garmin", "custom"] | None = None
    agps_custom_url: str | None = None
    agps_epo_path: str | None = None
    agps_max_age_hours: int | None = Field(None, ge=1, le=8760)
    # AI provider config — api_key is write-only (stored encrypted, never returned)
    equipment_available: list[str] | None = None
    strength_experience: Literal["brand_new", "returning", "regular", "advanced"] | None = None
    activity_frequency: dict[str, Literal["never", "occasional", "1_2", "3_4", "5_plus"]] | None = None
    experience_suggestion_dismissed: bool | None = None
    setup_complete: bool | None = None
    tour_seen: dict | None = None
    tour_enabled: bool | None = None
    weather_enabled: bool | None = None
    map_enabled: bool | None = None
    wildfire_enabled: bool | None = None
    ai_provider: Literal["ollama", "anthropic", "openai"] | None = None
    ai_endpoint: str | None = None
    ai_model: str | None = None
    ai_api_key: str | None = None  # plaintext on input; stored encrypted

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, v: str | None) -> str | None:
        if v is not None and v not in _VALID_TIMEZONES:
            raise ValueError(f"Unknown timezone: {v!r}")
        return v

    @field_validator("birth_year")
    @classmethod
    def validate_birth_year(cls, v: int | None) -> int | None:
        if v is not None and v > datetime.now().year:
            raise ValueError("birth_year cannot be in the future")
        return v

    @field_validator("avatar_url")
    @classmethod
    def validate_avatar_url(cls, v: str | None) -> str | None:
        if v is not None and not v.startswith(("http://", "https://")):
            raise ValueError("avatar_url must be an http or https URL")
        return v

    @field_validator("agps_custom_url")
    @classmethod
    def validate_agps_custom_url(cls, v: str | None) -> str | None:
        # The server downloads this and returns the bytes, so anything but
        # http(s) — file:// above all — is a way to read the server's own
        # files. Refused here so it is never stored; public_fetch refuses it
        # again at download time, along with private addresses, for any row
        # written before this check existed.
        if v:
            parts = urlsplit(v.strip())
            if parts.scheme not in ("http", "https") or not parts.hostname:
                raise ValueError("agps_custom_url must be an http or https URL")
            return v.strip()
        return v

    @field_validator("equipment_available")
    @classmethod
    def validate_equipment(cls, v: list[str] | None) -> list[str] | None:
        # PUT /strength/equipment already enforces this; PATCH /users/me/settings
        # is the other write path to the same column (used by both the Settings
        # EquipmentSection and the onboarding Strength step) and previously had
        # no validation at all here — unknown values would have been stored as-is.
        if v is not None:
            invalid = set(v) - VALID_EQUIPMENT
            if invalid:
                raise ValueError(f"Unknown equipment: {', '.join(sorted(invalid))}")
        return v
