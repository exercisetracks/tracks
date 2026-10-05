# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# exercisetracks/tracks — all of Tracks in one container.
#
#   docker run -d --name tracks -p 4080:80 \
#     -v tracks-data:/data -v tracks-maps:/map-data exercisetracks/tracks:1
#
# Development runs each service in its own container (docker-compose.yml), and
# that is still how they are built. This image is assembled *from* those
# component images rather than defining anything a second time, so what an
# install runs is exactly what the suites tested, plus a supervisor:
#
#   postgres  redis  backend  worker  tiles  brouter  web  [garmin-sync]
#
# One container instead of eight because the alternative asked every operator
# to fetch a compose file, a .env template and a setup script, and run the
# script before anything worked. Here there is nothing to prepare: secrets are
# generated on first start (tracks-init), and everything worth keeping is in
# /data. The cost is that the services cannot be scaled or upgraded one at a
# time — irrelevant for a household install, which is what this is for.
#
# Built by scripts/release.sh after the component images, from this directory:
#   docker build --build-arg VERSION=1.1.0 -f deploy/Dockerfile deploy

ARG REGISTRY=exercisetracks
ARG VERSION
FROM ${REGISTRY}/tracks-web:${VERSION} AS web
FROM ${REGISTRY}/tracks-brouter:${VERSION} AS brouter
FROM ${REGISTRY}/tracks-tiles:${VERSION} AS tiles
FROM ${REGISTRY}/tracks-garmin-sync:${VERSION} AS garminsync

FROM ${REGISTRY}/tracks-backend:${VERSION}

# PostgreSQL 16 from the PostgreSQL project's own repository, not Debian's 17:
# 16 is what the development stack and the test suite run against, and a
# database file is tied to its major version — changing it later is a
# dump-and-restore, so it should not change by accident. The signing key is
# fetched over HTTPS from postgresql.org and pinned to that one source.
#
# libmtp9 and usbutils are the USB watch bridge's (garmin-sync/sync.py loads
# libmtp through ctypes and checks the bus with lsusb). tini is PID 1, so
# signals reach the supervisor and orphaned processes are reaped.
RUN set -e; \
    . /etc/os-release; \
    curl -fsSL -o /usr/share/keyrings/postgresql.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc; \
    echo "deb [signed-by=/usr/share/keyrings/postgresql.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends postgresql-16 redis-server libmtp9 usbutils tini; \
    rm -rf /var/lib/apt/lists/*; \
    # Debian's package creates and starts a cluster of its own; this image
    # keeps its cluster in /data, so the packaged one is only in the way.
    rm -rf /var/lib/postgresql/16 /etc/postgresql; \
    # The packages also pull in LLVM and its z3 solver (~150 MB) for JIT query
    # compilation, and Perl (~50 MB) for Debian's pg_ctlcluster wrappers.
    # Neither is used: JIT pays off on analytical queries over millions of
    # rows, not a household's activity history, so it is off (tracks-run), and
    # this image calls PostgreSQL's own binaries directly. Removed past their
    # declared dependencies, which leaves apt unable to install more packages
    # in a derived image — build such an image from tracks-backend instead.
    dpkg --purge --force-depends $(dpkg-query -Wf '${Package}\n' 'libllvm*' 'libz3-*' 'libperl5*' 'perl-modules-*') \
        >/dev/null; \
    rm -rf /usr/lib/postgresql/16/lib/llvmjit* /usr/lib/postgresql/16/lib/bitcode; \
    for bin in postgres initdb pg_ctl pg_dump psql pg_isready createdb; do \
        ! ldd "/usr/lib/postgresql/16/bin/$bin" | grep -q "not found"; \
    done; \
    pip install --no-cache-dir supervisor==4.3.0

COPY --from=web /usr/bin/caddy /usr/bin/caddy
COPY --from=web /srv /srv
COPY --from=web /etc/caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=brouter /opt/brouter /opt/brouter
COPY --from=tiles /usr/local/bin/reload-wrapper.sh /usr/local/bin/reload-wrapper.sh
COPY --from=garminsync /usr/local/bin/sync.py /opt/garmin-sync/sync.py
# The tile server is the same go-pmtiles build the backend already carries for
# extracts (both pinned to one version), so it is linked rather than copied.
RUN ln -s /usr/local/bin/pmtiles /usr/local/bin/go-pmtiles

COPY rootfs/ /
RUN chmod 755 /usr/local/bin/tracks-*

# Every address is loopback: in one container the services reach each other
# on 127.0.0.1, and nothing but the web server's port 80 is published.
ENV TRACKS_FRONTEND=static \
    TRACKS_BACKEND=127.0.0.1:8000 \
    TRACKS_TILES=127.0.0.1:8080 \
    REDIS_URL=redis://127.0.0.1:6379/0 \
    BROUTER_URL=http://127.0.0.1:17777 \
    FIT_FILES_DIR=/data/fit-files \
    MAP_DATA_DIR=/map-data \
    TRUSTED_PROXY_HOPS=1 \
    XDG_DATA_HOME=/data \
    XDG_CONFIG_HOME=/data/caddy-config

# Declared so a container started without -v still keeps its data across
# restarts. The README always names them, because an anonymous volume is lost
# the moment the container is removed and recreated — which is how it updates.
VOLUME ["/data", "/map-data"]
EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1/api/healthz >/dev/null || exit 1

WORKDIR /
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/tracks-init"]
