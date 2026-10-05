# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""How each entity in spec/sync.yaml maps onto this database.

The contract talks in entities, uids and fields; Postgres has tables, integer
ids and columns. An adapter per entity translates between them, and nearly all
of them are the generic one: a field is the column of the same name, and a
`*_uid` reference is the `*_id` foreign key next to it. The exceptions are
where the schema predates the contract:

- `settings.name` lives on `users`, not `user_settings`.
- `device` is a user's claim on a watch (`user_devices`); the watch's own
  facts are on the shared `devices` row.
- `activity` and `trip` share `activities`, told apart by `is_merged`.
- `trip` holds lists of references, kept in `extra` as the maps page already
  does.

Nothing here decides whether a write wins — that is app.sync.merge — or when
things are written — that is app.sync.store.
"""
from __future__ import annotations

from datetime import date, datetime, time, timezone
from decimal import Decimal

from sqlalchemy import Date, DateTime, Numeric, Time, select
from sqlalchemy.orm import Session

from app.models.activity import Activity, Device, User, UserDevice
from app.models.coaching import CoachingRecommendation, RacePlan, TrainingGoal
from app.models.custom_track import CustomTrack, CustomTrackFolder
from app.models.flexibility import (
    FlowStretch, UserCustomStretch, UserFlexibilityFlow, UserFlexibilityPreference,
)
from app.models.fuel import FuelProduct, GutTrainingLog
from app.models.health import Injury
from app.models.meals import Meal, MealLog
from app.models.medications import Medication, MedicationLog, MedicationSchedule
from app.models.metrics import DailyMetric
from app.models.strength import (
    AnimationConfirmation, UserCustomExercise, UserExercisePreference, UserExerciseStrength,
)
from app.models.sync import FitFile
from app.models.training_plan import PlannedWorkout, TrainingPlan
from app.models.user_settings import UserSettings
from app.models.waypoint import Waypoint
from app.models.workout import UserWorkout, UserWorkoutExercise, UserWorkoutSession
from app.spec.sync import ENTITIES
from app.sync import uids


class NotYet(Exception):
    """A row that cannot exist in this database yet: a required field or a
    required parent has not arrived. The change is staged, not refused."""


# ── Values ───────────────────────────────────────────────────────────────────

def to_wire(value):
    """A column value as the JSON a phone expects."""
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    if isinstance(value, (date, time)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return float(value)
    return value


def _from_wire(column, value):
    if value is None:
        return None
    t = column.type
    try:
        if isinstance(t, DateTime):
            return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if isinstance(t, Date):
            return date.fromisoformat(str(value)[:10])
        if isinstance(t, Time):
            return time.fromisoformat(str(value))
    except ValueError as exc:
        raise ValueError(f"{column.name}: {exc}") from exc
    if isinstance(t, Numeric) and not isinstance(value, (int, float)):
        raise ValueError(f"{column.name}: expected a number")
    return value


# ── Adapters ─────────────────────────────────────────────────────────────────

class Adapter:
    """The generic mapping: field == column, `x_uid` == the `x_id` foreign key."""

    def __init__(self, entity: str, model):
        self.entity = entity
        self.spec = ENTITIES[entity]
        self.model = model
        self.table = model.__table__
        self.kind = self.spec["kind"]
        self.fields = list(self.spec["fields"])
        self.refs = dict(self.spec["refs"])
        # field -> column key, for the plain (non-list) fields.
        self.columns: dict[str, str] = {}
        for f in self.fields:
            col = self.column_for(f)
            if col is not None:
                self.columns[f] = col

    # Field ⇄ column ------------------------------------------------------

    def column_for(self, field: str) -> str | None:
        if field in self.refs and field.endswith("_uid"):
            return field[:-4] + "_id"
        return field

    def fields_for_column(self, key: str) -> list[str]:
        return [f for f, c in self.columns.items() if c == key]

    def ref_target(self, field: str) -> "Adapter":
        return ADAPTERS[self.refs[field]]

    # Rows -----------------------------------------------------------------

    def owns(self, obj) -> bool:
        return isinstance(obj, self.model)

    def scope(self, stmt, user_id: int):
        return stmt.where(self.model.user_id == user_id)

    def find(self, db: Session, user_id: int, uid: str):
        stmt = self.scope(select(self.model), user_id).where(self.model.uid == uid)
        return db.execute(stmt).scalars().first()

    def uid_for_id(self, db: Session, row_id: int) -> str | None:
        return db.execute(select(self.model.uid).where(self.model.id == row_id)).scalar()

    def id_for_uid(self, db: Session, user_id: int, uid: str) -> int | None:
        stmt = self.scope(select(self.model.id), user_id).where(self.model.uid == uid)
        return db.execute(stmt).scalar()

    # Reading --------------------------------------------------------------

    def get(self, db: Session, obj, field: str):
        if field in self.refs:
            pending = (obj.pending_refs or {}).get(field)
            if pending is not None:
                return pending
            row_id = getattr(obj, self.columns[field])
            if row_id is None:
                return None
            return self.ref_target(field).uid_for_id(db, row_id)
        return to_wire(getattr(obj, self.columns[field]))

    # Writing --------------------------------------------------------------

    def validate(self, field: str, value) -> None:
        """Raise ValueError if `value` cannot be written to `field`."""
        if field in self.refs:
            if value is not None and not isinstance(value, str):
                raise ValueError(f"{field}: expected a uid")
            return
        col = self.columns.get(field)
        if col is not None and col in self.table.c:
            _from_wire(self.table.c[col], value)

    def set(self, db: Session, user_id: int, obj, field: str, value) -> None:
        col = self.columns[field]
        if field in self.refs:
            pending = dict(obj.pending_refs or {})
            pending.pop(field, None)
            target_id = None
            if value is not None:
                target_id = self.ref_target(field).id_for_uid(db, user_id, value)
                if target_id is None:
                    if not self.table.c[col].nullable:
                        raise NotYet(f"{self.entity}.{field} → {value} has not arrived")
                    pending[field] = value
            setattr(obj, col, target_id)
            obj.pending_refs = pending or None
            return
        setattr(obj, col, _from_wire(self.table.c[col], value))

    def new(self, db: Session, user_id: int, uid: str, fields: dict):
        """A new row from a complete set of field values, or NotYet."""
        obj = self.model()
        obj.user_id = user_id
        obj.uid = uid
        for field, value in fields.items():
            if field in self.columns:
                self.set(db, user_id, obj, field, value)
        self.require_complete(obj)
        return obj

    def require_complete(self, obj) -> None:
        for col in self.table.columns:
            if col.nullable or col.primary_key or col.default is not None \
                    or col.server_default is not None:
                continue
            if getattr(obj, col.key, None) is None:
                raise NotYet(f"{self.entity} needs {col.key}")

    # Natural keys ---------------------------------------------------------

    def natural_values(self, db: Session, obj) -> dict | None:
        """Values for this entity's uid_key template, read from a row."""
        template = self.spec.get("uid_key")
        if not template:
            return None
        values = {}
        for name in _template_names(template):
            v = getattr(obj, name, None)
            if v is None:
                return None
            values[name] = to_wire(v)
        return values

    def natural_uid(self, db: Session, obj) -> str | None:
        template = self.spec.get("uid_key")
        if not template:
            return None
        values = self.natural_values(db, obj)
        if values is None:
            return None
        return uids.natural_uid(template, **values)

    def natural_uid_from_fields(self, fields: dict) -> str | None:
        template = self.spec.get("uid_key")
        if not template:
            return None
        names = _template_names(template)
        if not all(fields.get(n) is not None for n in names):
            return None
        return uids.natural_uid(template, **{n: fields[n] for n in names})


def _template_names(template: str) -> list[str]:
    import string
    return [name for _, name, _, _ in string.Formatter().parse(template) if name]


class SettingsAdapter(Adapter):
    """`name` is the user's own; everything else is a user_settings column."""

    def column_for(self, field):
        return None if field == "name" else field

    def scope(self, stmt, user_id):
        return stmt.where(UserSettings.user_id == user_id)

    def get(self, db, obj, field):
        if field == "name":
            return db.execute(select(User.name).where(User.id == obj.user_id)).scalar()
        return super().get(db, obj, field)

    def set(self, db, user_id, obj, field, value):
        if field == "name":
            user = db.get(User, user_id)
            if value is not None:
                user.name = value
            return
        super().set(db, user_id, obj, field, value)

    def uid_for_id(self, db, row_id):
        return db.execute(select(UserSettings.uid).where(UserSettings.user_id == row_id)).scalar()


class DeviceAdapter(Adapter):
    """A claim (`user_devices`) plus the watch's facts (`devices`)."""

    _DEVICE_FIELDS = ("serial_number", "manufacturer", "manufacturer_id", "product_name",
                      "product_id", "software_version")

    def column_for(self, field):
        return None if field in self._DEVICE_FIELDS else field

    def get(self, db, obj, field):
        if field in self._DEVICE_FIELDS:
            device = db.get(Device, obj.device_id)
            return to_wire(getattr(device, field)) if device else None
        return super().get(db, obj, field)

    def set(self, db, user_id, obj, field, value):
        if field in self._DEVICE_FIELDS:
            if field == "serial_number" or obj.device_id is None:
                return  # identity; resolved in new()
            device = db.get(Device, obj.device_id)
            if value is not None:
                setattr(device, field, value)
            return
        super().set(db, user_id, obj, field, value)

    def new(self, db, user_id, uid, fields):
        serial = fields.get("serial_number")
        if not serial:
            raise NotYet("device needs serial_number")
        device = db.execute(select(Device).where(Device.serial_number == serial)).scalars().first()
        if device is None:
            device = Device(serial_number=serial)
            db.add(device)
        for f in self._DEVICE_FIELDS[1:]:
            if fields.get(f) is not None:
                setattr(device, f, fields[f])
        db.flush()
        claim = UserDevice(user_id=user_id, device_id=device.id, uid=uid)
        for f in ("label", "is_primary"):
            if f in fields:
                super().set(db, user_id, claim, f, fields[f])
        return claim

    def uid_for_id(self, db, row_id):
        raise LookupError("devices are never referenced by id")

    def natural_values(self, db, obj):
        serial = db.execute(select(Device.serial_number).where(Device.id == obj.device_id)).scalar()
        return {"serial_number": serial} if serial else None


class ActivityAdapter(Adapter):
    """What a person says about an activity; never the activity itself.

    An activity exists once its FIT file is parsed, on every device, from the
    same bytes. A push can only edit one, so `new` is always NotYet and the
    edit waits in sync_pending_rows for the import to create the row.
    """

    def scope(self, stmt, user_id):
        return stmt.where(Activity.user_id == user_id, Activity.is_merged.is_(False))

    def owns(self, obj):
        return isinstance(obj, Activity) and not obj.is_merged

    def new(self, db, user_id, uid, fields):
        raise NotYet("an activity is created by importing its file")

    def natural_uid(self, db, obj):
        # Not derived here: the importer sets it (activity_uid), knowing which
        # file the row came from. A row created any other way has no file to
        # be the same activity as, and gets a random uid.
        return None


def activity_uid(device_serial: str | None, started_at: datetime | None, sha256: str | None) -> str:
    """Same watch, same start second: the same activity, whichever bytes it came in."""
    if device_serial and started_at is not None:
        if started_at.tzinfo is None:
            started_at = started_at.replace(tzinfo=timezone.utc)
        return uids.natural_uid(ENTITIES["activity"]["uid_key"],
                                device_serial=device_serial,
                                start_epoch_s=int(started_at.timestamp()))
    return uids.natural_uid(ENTITIES["activity"]["uid_key_fallback"], sha256=sha256)


class TripAdapter(Adapter):
    """A merged multi-day summary. Its references are lists, kept in `extra`."""

    def validate(self, field, value):
        if field in self._LISTS:
            if value is not None and not (isinstance(value, list)
                                          and all(isinstance(u, str) for u in value)):
                raise ValueError(f"{field}: expected a list of uids")
            return
        super().validate(field, value)

    _LISTS = {"activity_uids": ("source_activity_ids", "activity"),
              "track_uids": ("source_track_ids", "track")}

    def column_for(self, field):
        return None if field in self._LISTS else field

    def scope(self, stmt, user_id):
        return stmt.where(Activity.user_id == user_id, Activity.is_merged.is_(True))

    def owns(self, obj):
        return isinstance(obj, Activity) and bool(obj.is_merged)

    def get(self, db, obj, field):
        if field in self._LISTS:
            key, target = self._LISTS[field]
            extra = obj.extra or {}
            out = [ADAPTERS[target].uid_for_id(db, i) for i in extra.get(key) or []]
            return [u for u in out if u] + list((obj.pending_refs or {}).get(field) or [])
        return super().get(db, obj, field)

    def set(self, db, user_id, obj, field, value):
        if field in self._LISTS:
            key, target = self._LISTS[field]
            ids, missing = [], []
            for u in value or []:
                i = ADAPTERS[target].id_for_uid(db, user_id, u)
                (ids.append(i) if i is not None else missing.append(u))
            extra = dict(obj.extra or {})
            extra[key] = ids
            obj.extra = extra
            pending = dict(obj.pending_refs or {})
            pending.pop(field, None)
            if missing:
                pending[field] = missing
            obj.pending_refs = pending or None
            return
        super().set(db, user_id, obj, field, value)

    def new(self, db, user_id, uid, fields):
        obj = Activity(user_id=user_id, uid=uid, is_merged=True, extra={})
        for field, value in fields.items():
            if field in self.fields:
                self.set(db, user_id, obj, field, value)
        return obj


class DailyEntryAdapter(Adapter):
    """The hand-entered part of a day; device-measured columns are derived."""


class OneRepMaxAdapter(Adapter):
    pass


class PlanAdapter(Adapter):
    def natural_values(self, db, obj):
        goal_uid = db.execute(select(TrainingGoal.uid).where(TrainingGoal.id == obj.goal_id)).scalar()
        return {"goal_uid": goal_uid} if goal_uid else None


def _generic(entity, model):
    return Adapter(entity, model)


ADAPTERS: dict[str, Adapter] = {
    "settings": SettingsAdapter("settings", UserSettings),
    "device": DeviceAdapter("device", UserDevice),
    "fit_file": _generic("fit_file", FitFile),
    "activity": ActivityAdapter("activity", Activity),
    "trip": TripAdapter("trip", Activity),
    "daily_entry": DailyEntryAdapter("daily_entry", DailyMetric),
    "goal": _generic("goal", TrainingGoal),
    "race_plan": _generic("race_plan", RacePlan),
    "plan": PlanAdapter("plan", TrainingPlan),
    "planned_workout": _generic("planned_workout", PlannedWorkout),
    "workout": _generic("workout", UserWorkout),
    "workout_exercise": _generic("workout_exercise", UserWorkoutExercise),
    "workout_session": _generic("workout_session", UserWorkoutSession),
    "exercise_preference": _generic("exercise_preference", UserExercisePreference),
    "custom_exercise": _generic("custom_exercise", UserCustomExercise),
    "one_rep_max": OneRepMaxAdapter("one_rep_max", UserExerciseStrength),
    "animation_confirmation": _generic("animation_confirmation", AnimationConfirmation),
    "flow": _generic("flow", UserFlexibilityFlow),
    "flow_stretch": _generic("flow_stretch", FlowStretch),
    "stretch_preference": _generic("stretch_preference", UserFlexibilityPreference),
    "custom_stretch": _generic("custom_stretch", UserCustomStretch),
    "medication": _generic("medication", Medication),
    "medication_schedule": _generic("medication_schedule", MedicationSchedule),
    "medication_log": _generic("medication_log", MedicationLog),
    "injury": _generic("injury", Injury),
    "meal": _generic("meal", Meal),
    "meal_log": _generic("meal_log", MealLog),
    "fuel_product": _generic("fuel_product", FuelProduct),
    "gut_training_log": _generic("gut_training_log", GutTrainingLog),
    "track_folder": _generic("track_folder", CustomTrackFolder),
    "track": _generic("track", CustomTrack),
    "waypoint": _generic("waypoint", Waypoint),
    "coaching_note": _generic("coaching_note", CoachingRecommendation),
}

# Ordering keys: the contract calls it `order`, the tables `order_index`.
for _name in ("flow_stretch", "workout_exercise"):
    ADAPTERS[_name].columns["order"] = "order_index"

assert set(ADAPTERS) == set(ENTITIES), set(ENTITIES) ^ set(ADAPTERS)

SYNCED_MODELS = {a.model for a in ADAPTERS.values()}
SYNCED_TABLES = sorted({a.table.name for a in ADAPTERS.values()})


def adapter_for(obj) -> Adapter | None:
    """The entity a row belongs to, or None if it does not sync."""
    for a in _BY_MODEL.get(type(obj), ()):
        if a.owns(obj):
            return a
    return None


_BY_MODEL: dict[type, list[Adapter]] = {}
for _a in ADAPTERS.values():
    _BY_MODEL.setdefault(_a.model, []).append(_a)
