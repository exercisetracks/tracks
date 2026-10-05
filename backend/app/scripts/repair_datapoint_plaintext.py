# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""One-off repair for a real incident on the secure-data-pipeline branch.

alembic/versions/20260813_e3a7c15f9b02_encrypt_datapoint_gps.py assumed a
fresh, empty database — the branch's stated plan for this whole rearchitecture
was "no migrations, fresh database only." It converts data_points.lat/lng
from float to bytea with:

    ALTER COLUMN lat TYPE bytea USING convert_to(lat::text, 'UTF8')

That's a correct type change on an EMPTY column, but on a database that
already held real GPS data (this instance's re-imported activity history —
see the memory note "fresh-machine Postgres volume is empty; activities
reimported from FIT files"), it just wrapped each existing plaintext float's
text representation in a bytea column. It never touched
app.services.user_crypto's AES-256-GCM at all. Every data_points row that
existed before that migration ran is sitting in the database as recoverable
plaintext GPS coordinates, not ciphertext — confirmed by decoding stored
bytes and finding literal ASCII like "12.345678901234567", and by the
stored length (12-18 bytes) never matching real ciphertext's fixed ~28-byte
nonce+tag overhead on top of the plaintext length.

This script repairs it in place, one column at a time: for every
data_points row, try to decrypt lat/lng under the current DEK. Anything
that decrypts cleanly is already correct ciphertext (skip). Anything that
fails is decoded as UTF-8 text and parsed as a float — if that succeeds,
it's the original plaintext value, still fully recoverable; re-encrypt it
properly and write it back. Anything that's neither is reported and left
untouched rather than guessed at.

Requires the account's password, entered interactively — never accepted as
a CLI argument or environment variable, which would leave it sitting in
shell history or `docker compose exec`'s process listing.

Usage (from the repo root):

    docker compose exec backend python -m app.scripts.repair_datapoint_plaintext
    docker compose exec backend python -m app.scripts.repair_datapoint_plaintext --apply

Defaults to a dry run (reports counts, writes nothing). Pass --apply to
actually update the rows. Back up the data_points table first regardless:

    docker compose exec -T db pg_dump -U tracks -d tracks -t data_points \
        > data_points_backup_$(date +%s).sql
"""

import argparse
import getpass
import sys

from psycopg2.extras import execute_values

from app.database import SessionLocal
from app.models.user_keys import UserKey
from app.services import user_crypto

_BATCH_SIZE = 5000


def _plaintext_float(raw: bytes) -> float | None:
    try:
        return float(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None


def _repair_column(cursor, material, column: str, apply: bool, user_id: int) -> tuple[int, int, int]:
    # Only this user's rows: anyone else's ciphertext fails this key and would
    # be reported as corrupt.
    cursor.execute(
        f"SELECT d.id, d.{column} FROM data_points d JOIN activities a ON a.id = d.activity_id "
        f"WHERE d.{column} IS NOT NULL AND a.user_id = %s", (user_id,))
    rows = cursor.fetchall()

    repaired = already_ok = unrecoverable = 0
    to_update: list[tuple[int, bytes]] = []

    for row_id, raw in rows:
        raw = bytes(raw)
        try:
            user_crypto.decrypt_bytes(material.dek, raw)
            already_ok += 1
            continue
        except user_crypto.WrongSecret:
            pass

        value = _plaintext_float(raw)
        if value is None:
            unrecoverable += 1
            print(f"  UNRECOVERABLE: data_points.id={row_id} {column} is neither valid "
                  f"ciphertext nor a plaintext float — left untouched", file=sys.stderr)
            continue

        to_update.append((row_id, user_crypto.encrypt_bytes(material.dek, repr(value).encode("utf-8"))))
        repaired += 1

    if apply:
        for i in range(0, len(to_update), _BATCH_SIZE):
            batch = to_update[i:i + _BATCH_SIZE]
            execute_values(
                cursor,
                f"UPDATE data_points AS d SET {column} = v.val FROM (VALUES %s) AS v(id, val) "
                f"WHERE d.id = v.id",
                batch,
                template="(%s, %s::bytea)",
            )

    return repaired, already_ok, unrecoverable


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true",
                        help="Actually write the repaired ciphertext. Without this, only reports counts.")
    parser.add_argument("--user-id", type=int, required=True,
                        help="Whose data to repair. Required: with several accounts, "
                             "\"the first key\" is somebody arbitrary.")
    args = parser.parse_args()

    db = SessionLocal()
    try:
        uk = db.query(UserKey).filter_by(user_id=args.user_id).first()
        if uk is None:
            print(f"No UserKey row for user {args.user_id} — nothing to repair.")
            return

        password = getpass.getpass(f"Password for user_id={uk.user_id}: ")
        try:
            material = user_crypto.unwrap_with_password(password, uk)
        except user_crypto.WrongSecret:
            print("Wrong password.", file=sys.stderr)
            sys.exit(1)

        cursor = db.connection().connection.cursor()
        mode = "APPLYING CHANGES" if args.apply else "DRY RUN — pass --apply to write changes"
        print(f"{mode} — scanning data_points.lat/lng under user_id={uk.user_id}'s key...\n")

        totals = {"repaired": 0, "already_ok": 0, "unrecoverable": 0}
        for column in ("lat", "lng"):
            repaired, ok, bad = _repair_column(cursor, material, column, args.apply, args.user_id)
            print(f"{column}: repaired={repaired} already_ok={ok} unrecoverable={bad}")
            totals["repaired"] += repaired
            totals["already_ok"] += ok
            totals["unrecoverable"] += bad

        if args.apply:
            db.connection().connection.commit()
            print(f"\nCommitted. Repaired {totals['repaired']} values.")
        else:
            db.connection().connection.rollback()
            print(f"\nDry run only — would repair {totals['repaired']} values. Re-run with --apply to write them.")

        if totals["unrecoverable"]:
            print(f"\n{totals['unrecoverable']} value(s) could not be classified as either "
                  f"ciphertext or plaintext — see UNRECOVERABLE lines above for their ids.",
                  file=sys.stderr)
    finally:
        db.close()


if __name__ == "__main__":
    main()
