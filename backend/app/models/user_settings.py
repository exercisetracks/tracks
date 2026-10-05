# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Boolean, Column, Integer, String, Float, DateTime, ForeignKey, Text
from sqlalchemy.sql import func
from app.database import Base, PJson
from app.models.sync import Synced, sync_indexes


class UserSettings(Base, Synced):
    __tablename__ = "user_settings"

    user_id             = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    password_hash       = Column(Text)
    units               = Column(String, nullable=False, default="metric")
    # Accent colour, as a preset name ("emerald", "sky", …) or a "#rrggbb"
    # literal. Stored server-side rather than per-device because it is an
    # identity choice, not a device one: someone who picks violet on the laptop
    # means violet, and a phone that keeps its own answer makes the two look
    # like different products. The colour scheme (light/dark) is deliberately
    # NOT here — that follows the device it is being read on.
    accent_color        = Column(String, nullable=True)
    # "system" | "light" | "dark". Here rather than in each browser's
    # localStorage so that the phone and the web look the same; it used to be
    # per device, which is what "synced" had silently not meant.
    theme_mode          = Column(String, nullable=False, default="system", server_default="system")
    timezone            = Column(String, nullable=False, default="UTC")
    avatar_url          = Column(Text)
    weight_kg           = Column(Float)
    height_cm           = Column(Float)
    sex                 = Column(String)  # "male" | "female" | None
    # Optional. Read only by the running-fitness estimate for someone with no
    # runs yet (calculators/plan/running_fitness.py: age is the strongest
    # predictor of VO2max after sex). A year, not a date of birth: the model
    # needs age in years, and a full birth date is far more identifying than
    # it needs to be. Synced like height and weight; never sent anywhere else.
    birth_year          = Column(Integer)
    max_hr_mode         = Column(String, nullable=False, default="auto")
    max_hr_manual       = Column(Integer)
    max_hr_auto         = Column(Integer)
    threshold_hr_mode   = Column(String, nullable=False, default="auto")
    threshold_hr_manual = Column(Integer)
    threshold_hr_auto   = Column(Integer)
    ftp_mode            = Column(String, nullable=False, default="auto")
    ftp_manual          = Column(Integer)
    ftp_auto            = Column(Integer)
    hidden_sports       = Column(PJson, nullable=False, default=lambda: [])
    css_mode            = Column(String, nullable=False, default="auto")   # critical swim speed
    css_manual          = Column(Float)   # sec / 100 m, user-entered
    css_auto            = Column(Float)   # sec / 100 m, auto-derived from swim history
    updated_at          = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    # Chart resolution for activity detail graphs (controls LTTB downsampling)
    chart_resolution    = Column(String, nullable=False, default="high")

    # Garmin watch workout coaching
    pace_coaching       = Column(Boolean, nullable=False, default=True)

    # AI coaching provider (optional — enables AI-enhanced recommendations)
    ai_provider         = Column(String)           # 'ollama' | 'anthropic' | 'openai'
    ai_endpoint         = Column(String)           # base URL for the provider
    ai_model            = Column(String)           # e.g. 'llama3', 'claude-haiku-4-5'
    ai_api_key_enc      = Column(Text)             # Fernet-encrypted API key

    # AGPS (Assisted GPS) — push CPE.bin to watch on plug-in (requires internet)
    agps_enabled        = Column(Boolean, nullable=False, default=False)
    agps_source         = Column(String, nullable=False, default="garmin")  # 'garmin' | 'custom'
    agps_custom_url     = Column(Text)
    agps_epo_path       = Column(String, nullable=False, default="GARMIN/REMOTESW/CPE.bin")
    agps_max_age_hours  = Column(Integer, nullable=False, default=24)
    agps_last_synced_at  = Column(DateTime(timezone=True))
    watch_last_synced_at = Column(DateTime(timezone=True))

    # Music server (Subsonic API — Navidrome, Gonic, Airsonic…). Credentials are
    # Fernet-encrypted like the AI key rather than DEK-encrypted: the rotation
    # job runs in the background with no live crypto session, and a column it
    # cannot read is a column it cannot sync from.
    music_server_url        = Column(Text)
    music_server_username   = Column(String)
    music_server_password_enc = Column(Text)
    # Auto-rotation: refresh what the watch carries from listening history.
    music_auto_rotate       = Column(Boolean, nullable=False, default=False)
    music_rotate_count      = Column(Integer, nullable=False, default=40)
    music_rotate_last_run   = Column(DateTime(timezone=True))

    # Equipment available for strength training plan generation.
    # PJson array of: bodyweight, dumbbell, barbell, cable, machine, kettlebell, band.
    # Defaults to bodyweight + dumbbell so first-time users get sensible plans.
    equipment_available  = Column(PJson, nullable=False,
                                  default=lambda: ["bodyweight", "dumbbell"])

    # Strength-training experience, asked once when a goal first enables
    # strength (or set in Settings). Shapes default tier, difficulty ceiling,
    # and periodization starting stage — see calculators/strength_plan/leveling.py.
    # One of: brand_new | returning | regular | advanced. NULL = never asked.
    strength_experience  = Column(String(16), nullable=True)

    # Coach-note: a history-derived suggestion to change the experience level
    # (never auto-applied). Surfaced as a banner the user accepts or dismisses.
    # See calculators/strength_plan/leveling.infer_experience_suggestion.
    # How often the person does each endurance sport, by sport family:
    # {"running": "3_4", "cycling": "never", …}, each one of never |
    # occasional | 1_2 | 3_4 | 5_plus (sessions a week). Asked at setup and in
    # Settings. Read only when there is no history of that sport: it sets
    # where a first plan starts (calculators/plan/starting.py), so someone who
    # has never run is not handed the same first week as someone who runs
    # five times a week. NULL or a missing sport = never asked.
    activity_frequency   = Column(PJson, nullable=True)

    experience_suggestion            = Column(String(16), nullable=True)
    experience_suggestion_reason     = Column(Text, nullable=True)
    experience_suggestion_dismissed  = Column(Boolean, nullable=False,
                                              default=False, server_default="false")

    # Onboarding flag — set to True once the user completes the setup wizard.
    # First-time admin creation sets this to True automatically; new users
    # created after the fact must go through onboarding on first login.
    setup_complete       = Column(Boolean, nullable=False, default=False)

    # In-app guided tutorial (the per-page "tips" tour). tour_seen is an object
    # map of {tourId: true} for tours the user has finished or dismissed, so each
    # section's tips fire only on first visit. tour_enabled is the master switch
    # ("Show tutorial tips" in Settings); "Restart tutorial" clears tour_seen.
    tour_seen            = Column(PJson, nullable=True, default=lambda: {})
    tour_enabled         = Column(Boolean, nullable=False, default=True,
                                  server_default="true")

    # Weather data for race plans — fetches forecast/historical from Open-Meteo
    # when a race location pin is set. Defaults on so new users get weather-aware
    # pacing straight away. Set to false to keep location data fully local.
    weather_enabled      = Column(Boolean, nullable=False, default=True)

    # Map tiles — downloads global basemap and DEM tiles from third-party tile
    # servers (Protomaps / Mapterhorn). Off by default — the app is fully offline
    # until the user explicitly opts in.
    map_enabled          = Column(Boolean, nullable=False, default=False)

    # Live wildfire + smoke map overlays — queries NIFC (fire locations /
    # perimeters) and NOAA (smoke plumes) while the overlay is on. Off by
    # default; the feeds are nationwide, so no location/viewport is sent out.
    wildfire_enabled     = Column(Boolean, nullable=False, default=False)


sync_indexes(UserSettings)
