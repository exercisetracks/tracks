# syntax=docker/dockerfile:1.7
#
# garmin-sync: pulls .fit files from a Garmin MTP device and pushes workout
# files back to GARMIN/Workouts/.
#
# Needs privileged mode (or explicit USB device passthrough) in docker-compose
# so libmtp can open the USB device.

FROM debian:trixie-slim

# Runtime deps:
#   libmtp9 / libmtp-runtime — ctypes target (libmtp.so.9)
#   mtp-tools                — mtp-detect for in-container debugging
#   usbutils                 — lsusb (first-pass vendor check)
#   python3                  — stdlib covers everything except sealing
#   python3-nacl              — libsodium sealed-box: seals each downloaded FIT
#                               file against the account's public key before
#                               it ever leaves this machine (see sync.py's
#                               module docstring). Debian package, not pip —
#                               keeps this image dependency-manager-free.
#   procps                   — pgrep for HEALTHCHECK
#   ca-certificates, tzdata  — sane TLS + timezone defaults
#   locales                  — C.UTF-8 so libmtp string handling is sane
#   tini                     — PID 1 so SIGTERM reaches the Python process
#   gosu                     — entrypoint.sh does root-only setup (chown the
#                               /data bind mount) then drops to HOST_UID,
#                               same pattern as backend/entrypoint.sh
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        libmtp9 \
        libmtp-runtime \
        mtp-tools \
        usbutils \
        python3 \
        python3-nacl \
        procps \
        ca-certificates \
        tzdata \
        locales \
        tini \
        gosu \
 && sed -i 's/^# *\(C.UTF-8\)/\1/' /etc/locale.gen || true \
 && locale-gen C.UTF-8 || true \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

COPY sync.py /usr/local/bin/sync.py
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/sync.py /usr/local/bin/entrypoint.sh

HEALTHCHECK --interval=60s --timeout=15s --start-period=30s --retries=3 \
    CMD pgrep -f "/usr/local/bin/sync.py" >/dev/null || exit 1

# No USER directive — must start as root so entrypoint.sh can chown /data
# before dropping to HOST_UID via gosu. docker-compose must NOT set a
# `user:` override for this service (it would skip the root-then-drop step
# entirely and reintroduce the unwritable-/data bug on a fresh install).
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["python3", "/usr/local/bin/sync.py"]
