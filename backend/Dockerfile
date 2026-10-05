# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later

# ── tippecanoe build stage ───────────────────────────────────────────────────
# Build tippecanoe + tile-join from source (no reliable apt package). These
# replace the old pure-Python MVT tiler: one tippecanoe run produces the whole
# multi-layer region overlay.pmtiles, and tile-join stitches per-region overlays
# into master_overlay.pmtiles. Only the two binaries are copied into the runtime
# image (the build toolchain is discarded).
FROM debian:trixie-slim AS tippecanoe
ENV DEBIAN_FRONTEND=noninteractive
ARG TIPPECANOE_REF=2.79.0
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       git build-essential libsqlite3-dev zlib1g-dev ca-certificates \
    && git clone --depth 1 --branch "${TIPPECANOE_REF}" https://github.com/felt/tippecanoe /tmp/tippecanoe \
    && make -C /tmp/tippecanoe -j"$(nproc)" \
    && make -C /tmp/tippecanoe install \
    && strip /usr/local/bin/tippecanoe /usr/local/bin/tile-join \
    && rm -rf /tmp/tippecanoe /var/lib/apt/lists/*

# go-pmtiles' CLI, for `pmtiles extract` when a region is downloaded. Fetched
# here rather than in the runtime image so it can be stripped with this
# stage's binutils: the release binary carries its symbol table and DWARF,
# a third of its size, and nothing here ever debugs it.
#
# The release asset is named for the machine (x86_64 / arm64), so it is read
# from the build's own `uname -m` rather than hard-coded: an arm64 image (a
# Raspberry Pi, an ARM NAS) otherwise carries an x86 binary that cannot run.
ARG PMTILES_VERSION=1.31.2
RUN set -e; apt-get update && apt-get install -y --no-install-recommends curl; \
    arch="$(uname -m)"; if [ "$arch" = "aarch64" ]; then arch=arm64; fi; \
    curl -fsSL -o /tmp/pmtiles.tar.gz \
       "https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/go-pmtiles_${PMTILES_VERSION}_Linux_${arch}.tar.gz" \
    && tar -xzf /tmp/pmtiles.tar.gz -C /usr/local/bin pmtiles \
    && strip /usr/local/bin/pmtiles \
    && rm -rf /tmp/pmtiles.tar.gz /var/lib/apt/lists/*

# ── ffmpeg build stage ───────────────────────────────────────────────────────
# app.services.music_transcode needs ffprobe and an mp3 encoder, nothing more.
# Debian's ffmpeg package brings the whole of FFmpeg's dependency tree with it
# — LLVM and Mesa for GPU filters, a speech synthesiser, every video codec —
# which was over 300 MB of a 1.5 GB image, for transcoding music.
#
# So it is built here from the release tarball (signature-verified when the
# checksum was pinned) with every decoder and demuxer — an upload can be in
# any format, and they are plain C costing a few MB — but the only encoder that
# ships is libmp3lame. flac, png, the mp3/flac/image2 muxers and the lavfi
# source are the exceptions: tests/test_services/test_music_transcode.py
# generates its inputs with them, which is what lets that suite run against
# this exact build rather than a different ffmpeg.
#
# Same base as the runtime image, so the libmp3lame it links against is the
# one the runtime installs.
FROM python:3.14-slim AS ffmpeg
ARG FFMPEG_VERSION=9.0.2
ARG FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       build-essential nasm pkg-config curl ca-certificates xz-utils libmp3lame-dev zlib1g-dev \
    && curl -fsSL -o /tmp/ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    && echo "${FFMPEG_SHA256}  /tmp/ffmpeg.tar.xz" | sha256sum -c - \
    && mkdir /tmp/ffmpeg && tar -xJf /tmp/ffmpeg.tar.xz -C /tmp/ffmpeg --strip-components=1 \
    && cd /tmp/ffmpeg \
    && ./configure --prefix=/opt/ffmpeg \
       --enable-shared --disable-static --disable-debug --disable-doc --disable-ffplay \
       --disable-autodetect --enable-zlib --enable-libmp3lame \
       --disable-network --disable-hwaccels \
       --disable-devices --enable-indev=lavfi \
       --disable-encoders --enable-encoder=libmp3lame,flac,png \
       --disable-muxers --enable-muxer=mp3,flac,image2 \
       --disable-protocols --enable-protocol=file,pipe \
    && make -j"$(nproc)" && make install \
    && strip /opt/ffmpeg/bin/* /opt/ffmpeg/lib/*.so.* \
    && rm -rf /opt/ffmpeg/include /opt/ffmpeg/lib/pkgconfig /opt/ffmpeg/share

# ── runtime image ─────────────────────────────────────────────────────────────
FROM python:3.14-slim

ENV DEBIAN_FRONTEND=noninteractive

WORKDIR /app

# libsqlite3-0 + zlib1g are tippecanoe/tile-join runtime deps; libmp3lame0 is
# the slim ffmpeg's (see its stage above). osmium-tool cuts OSM extracts.
# gosu lets entrypoint.sh do root-only setup (chown) then run the app as HOST_UID.
# No libwebp: the DEM tiles are decoded by Pillow, whose wheel bundles its own.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates libmp3lame0 osmium-tool libsqlite3-0 zlib1g gosu \
    && rm -rf /var/lib/apt/lists/*

COPY --from=tippecanoe /usr/local/bin/tippecanoe /usr/local/bin/tile-join /usr/local/bin/pmtiles /usr/local/bin/
COPY --from=ffmpeg /opt/ffmpeg /opt/ffmpeg
ENV PATH="/opt/ffmpeg/bin:${PATH}"
RUN echo /opt/ffmpeg/lib > /etc/ld.so.conf.d/ffmpeg.conf && ldconfig

COPY requirements.txt requirements-dev.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Test dependencies, controlled by a build arg. On by default because this
# image is what `docker compose` builds for development, and the test suite
# runs inside it — without them, `docker exec backend pytest` fails until
# somebody pip-installs into the running container by hand, and that repair
# evaporates the next time the container is recreated.
#
# Build with --build-arg INSTALL_DEV_DEPS=false for a production image.
ARG INSTALL_DEV_DEPS=true
RUN if [ "$INSTALL_DEV_DEPS" = "true" ]; then \
      pip install --no-cache-dir -r requirements-dev.txt; \
    fi

COPY . .

RUN chmod +x entrypoint.sh
ENTRYPOINT ["./entrypoint.sh"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
