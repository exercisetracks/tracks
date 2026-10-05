#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
# One-time setup of a source checkout: creates .env from .env.example with
# generated secrets, for docker-compose.yml (development, or running your own
# build). The released image needs none of this — it generates its secrets on
# first start (deploy/rootfs/usr/local/bin/tracks-init). Only ever writes
# ./.env (mode 600), and is safe to read before running.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then
    echo "ERROR: .env already exists — refusing to overwrite it." >&2
    echo "Delete or move it first if you really want to regenerate secrets" >&2
    echo "(rotating ENCRYPTION_KEY invalidates stored AI API keys; a new" >&2
    echo "POSTGRES_PASSWORD won't match an existing database volume)." >&2
    exit 1
fi
[ -f .env.example ] || { echo "ERROR: .env.example is missing next to setup.sh" >&2; exit 1; }

# Secrets from python3 where there is one, openssl otherwise. Both draw from the OS CSPRNG. Every value is URL-safe base64
# so it can sit in DATABASE_URL and in sed below without escaping.
if command -v python3 >/dev/null 2>&1; then
    gen() { python3 -c "import secrets; print(secrets.token_urlsafe($1))"; }
    fernet() { python3 -c 'import base64, os; print(base64.urlsafe_b64encode(os.urandom(32)).decode())'; }
elif command -v openssl >/dev/null 2>&1; then
    gen() { openssl rand -base64 "$1" | tr -d '\n=' | tr '+/' '-_'; echo; }
    # A Fernet key is the padded URL-safe base64 of exactly 32 random bytes.
    fernet() { openssl rand -base64 32 | tr '+/' '-_'; }
else
    echo "ERROR: python3 or openssl is required to generate secrets" >&2
    exit 1
fi

JWT_SECRET="$(gen 48)"
SESSION_CACHE_KEY="$(gen 32)"
GARMIN_SYNC_BOOTSTRAP_TOKEN="$(gen 32)"
POSTGRES_PASSWORD="$(gen 24)"
ENCRYPTION_KEY="$(fernet)"

sed -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${POSTGRES_PASSWORD}|" \
    -e "s|^DATABASE_URL=.*|DATABASE_URL=postgresql://tracks:${POSTGRES_PASSWORD}@db:5432/tracks|" \
    -e "s|^JWT_SECRET=.*|JWT_SECRET=${JWT_SECRET}|" \
    -e "s|^ENCRYPTION_KEY=.*|ENCRYPTION_KEY=${ENCRYPTION_KEY}|" \
    -e "s|^SESSION_CACHE_KEY=.*|SESSION_CACHE_KEY=${SESSION_CACHE_KEY}|" \
    -e "s|^GARMIN_SYNC_BOOTSTRAP_TOKEN=.*|GARMIN_SYNC_BOOTSTRAP_TOKEN=${GARMIN_SYNC_BOOTSTRAP_TOKEN}|" \
    -e "s|^HOST_UID=.*|HOST_UID=$(id -u)|" \
    -e "s|^HOST_GID=.*|HOST_GID=$(id -g)|" \
    .env.example > .env
chmod 600 .env

up="docker compose up -d --build"

echo "Wrote .env with generated secrets (JWT, encryption key, DB password)."
echo
echo "Next steps:"
echo "  1. Review .env — set HOST_MAPDATA_DIR to a disk with ~10 GB+ free."
echo "  2. Start Tracks:   ${up}"
echo "  3. Open http://localhost:4080 and create your admin account"
echo "     before exposing the port to anyone else."
echo "  4. Have a Garmin watch on this machine's USB? Uncomment"
echo "     COMPOSE_PROFILES=garmin in .env, then: ${up}"
