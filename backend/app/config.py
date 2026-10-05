# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import logging

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

log = logging.getLogger(__name__)

_INSECURE_DEFAULT = "change-this-jwt-secret-in-production"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="allow")

    database_url: str
    fit_files_dir: str = "/fit-files"
    # Root directory for self-hosted map data — PMTiles archives, BRouter rd5
    # segments, fonts, and sprite assets. Shared via Docker volume.
    map_data_dir: str = "/map-data"
    # Protomaps daily planet build (public, no API key). Date-stamped;
    # rotates ~weekly. Used as source for region extracts (z8+ detail).
    pmtiles_source_url: str = ""

    # Mapterhorn global Terrain-RGB tileset for hillshade + 3D terrain.
    # Used to extract z0-7 global DEM overview and per-region z7-15 DEM.
    dem_source_url: str = ""

    # BRouter trail-snapping routing engine (internal Docker service).
    brouter_url: str = "http://brouter:17777"
    debug: bool = False
    jwt_secret: str = _INSECURE_DEFAULT
    encryption_key: str = ""  # Fernet key for AI API key encryption
    cors_origins: list[str] = ["http://localhost:3000"]
    public_base_url: str = "http://localhost:8000"  # used in ICS feed URLs

    # How many reverse proxies sit between the internet and this app. Each one
    # appends the address it saw to X-Forwarded-For, so the Nth entry from the
    # RIGHT is the address our outermost trusted proxy observed. Anything a
    # client sends itself lands further left and is ignored.
    #
    # 0 (the default) means "no proxy" and uses the socket peer address — safe
    # if this app is exposed directly, and safe-but-useless behind a proxy,
    # where every request appears to come from the proxy. The bundled Caddy
    # stack is exactly one hop, so docker-compose sets this to 1. Raise it only
    # if you add another proxy in front of Caddy (Cloudflare, a load balancer);
    # setting it higher than the real hop count lets clients forge the address,
    # which for the login limiter means bypassing it entirely.
    trusted_proxy_hops: int = 0

    # Session-scoped decryption-key cache (see app.services.crypto_context).
    redis_url: str = "redis://redis:6379/0"
    # Encrypts each user's unwrapped DEK/privkey at rest in Redis while their
    # session is active. Shared across every backend/worker process (unlike a
    # per-process ephemeral key, which would make a cached session unreadable
    # by whichever replica didn't create it) — so, like JWT_SECRET, it must
    # come from the shared .env, not be generated per-process.
    session_cache_key: str = ""
    # Sliding idle timeout for how long a decrypted session stays usable
    # without the user re-entering their password — independent of, and
    # deliberately shorter than, the JWT's own 30-day expiry. Tune down for
    # a hosted-many-strangers deployment, up for a single-family homelab
    # that values convenience over this particular margin.
    crypto_session_ttl_seconds: int = 7 * 24 * 3600

    # Bootstraps the host's garmin-sync container (USB dock, always
    # co-located on this same machine — see garmin-sync/sync.py) as a
    # pre-authorized personal sync agent for the admin account during
    # onboarding, with no manual pairing step. Generated once by setup.sh
    # and shared via .env with both containers, same pattern as
    # JWT_SECRET/ENCRYPTION_KEY — garmin-sync never runs anywhere but this
    # host, so there's no separate-device trust boundary a manually-copied
    # pairing token would meaningfully add here (unlike a future mobile
    # app, which does need one). Optional: operators without a Garmin
    # device just leave it unset. See app.services.sync_agent_auth.
    garmin_sync_bootstrap_token: str = ""

    @field_validator("database_url")
    @classmethod
    def _pin_postgres_driver(cls, v: str) -> str:
        # A bare postgresql:// URL means whichever driver SQLAlchemy prefers,
        # and 2.1 changed that from psycopg2 to psycopg 3. This backend is
        # psycopg2 throughout (execute_values bulk inserts, COPY through the
        # raw cursor), and every install's DATABASE_URL — .env and the
        # all-in-one image's env.sh alike — is a bare postgresql:// URL, so
        # the driver is named here rather than in each of them.
        if v.startswith("postgresql://"):
            return "postgresql+psycopg2://" + v[len("postgresql://"):]
        return v


settings = Settings()


def _missing_secret(name: str) -> str:
    return (
        f"{name} is not set. Generate one and add it to your .env file:\n"
        f"  {name}=$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
    )


# Values that are not secrets: the built-in default, nothing at all (HS256
# with an empty key signs tokens anyone can forge), and the placeholder
# .env.example once shipped — a .env copied from it by hand, rather than
# written by setup.sh, would otherwise have started on a secret that is
# public in this repository.
_NOT_SECRETS = {_INSECURE_DEFAULT, "", "changeme"}

if settings.jwt_secret.strip() in _NOT_SECRETS:
    if settings.debug:
        log.warning(
            "JWT_SECRET is not set — using insecure default. "
            "This is only allowed in DEBUG mode."
        )
        settings.jwt_secret = _INSECURE_DEFAULT
    else:
        # Refuse to start with the bundled default secret in production.
        # Anyone with the source code can sign valid JWTs otherwise.
        raise RuntimeError(_missing_secret("JWT_SECRET"))

if not settings.encryption_key:
    if settings.debug:
        log.warning(
            "ENCRYPTION_KEY is not set — AI API keys will use a key derived "
            "from JWT_SECRET. This is only allowed in DEBUG mode."
        )
    else:
        raise RuntimeError(_missing_secret("ENCRYPTION_KEY"))

if not settings.session_cache_key:
    if settings.debug:
        log.warning(
            "SESSION_CACHE_KEY is not set — cached decryption sessions will "
            "use a key derived from JWT_SECRET. This is only allowed in "
            "DEBUG mode."
        )
    else:
        raise RuntimeError(_missing_secret("SESSION_CACHE_KEY"))

# CORS wildcard + credentials is a footgun: browsers reject the combination,
# so the app would silently break for users.  Fail fast with a clear message.
if "*" in settings.cors_origins:
    raise RuntimeError(
        "CORS_ORIGINS=['*'] is incompatible with credentialed requests. "
        "List explicit origins instead, e.g. CORS_ORIGINS=https://tracks.example.com"
    )
