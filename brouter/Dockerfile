# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# BRouter, the trail-aware routing engine, from its official release.
#
# The same version the phone vendors (mobile/vendor/pull-brouter.sh), so a
# route planned offline on the phone and one asked of the server come from the
# same engine and the same profiles. It replaced simid/brouter-server, which
# was BRouter 1.6.3 on an early-access Java 16 build from 2022 and existed for
# amd64 only — the one thing keeping Tracks off ARM.

# A Java runtime holding only what BRouter uses (`jdeps --print-module-deps`
# on the jar), about a quarter of a full JRE. java.desktop is among them for
# AWT classes the server never draws with; it runs headless.
FROM eclipse-temurin:25-jdk AS jre
RUN jlink --add-modules java.base,java.desktop,java.sql,jdk.unsupported \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /opt/brouter/jre

FROM debian:trixie-slim AS fetch
ARG BROUTER_VERSION=1.7.10
ARG BROUTER_SHA256=023fec3ba997758e8cd7ab9e1bae52e962af3f00b57683e3de86b84ffad01532
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates unzip \
    && curl -fsSL -o /tmp/brouter.zip \
       "https://github.com/abrensch/brouter/releases/download/v${BROUTER_VERSION}/brouter-${BROUTER_VERSION}.zip" \
    && echo "${BROUTER_SHA256}  /tmp/brouter.zip" | sha256sum -c - \
    && unzip -q /tmp/brouter.zip -d /tmp \
    && mkdir -p /opt/brouter/profiles /opt/brouter/customprofiles \
    && cp "/tmp/brouter-${BROUTER_VERSION}/brouter-${BROUTER_VERSION}-all.jar" /opt/brouter/brouter.jar \
    # The profiles the backend routes with, plus lookups.dat, which BRouter
    # reads from the profile directory and refuses to route without. From the
    # release rather than upstream master, so they match the jar.
    && cp "/tmp/brouter-${BROUTER_VERSION}/profiles2/lookups.dat" \
          "/tmp/brouter-${BROUTER_VERSION}/profiles2/trekking.brf" \
          "/tmp/brouter-${BROUTER_VERSION}/profiles2/hiking-mountain.brf" \
          "/tmp/brouter-${BROUTER_VERSION}/profiles2/shortest.brf" \
          /opt/brouter/profiles/

FROM debian:trixie-slim
COPY --from=jre /opt/brouter/jre /opt/brouter/jre
COPY --from=fetch /opt/brouter /opt/brouter
EXPOSE 17777
# Segments (the rd5 routing tiles) live in /map-data, downloaded per region by
# the backend; profiles are baked in above.
CMD ["/opt/brouter/jre/bin/java", "-Djava.awt.headless=true", "-Xmx256M", "-Xms128M", \
     "-cp", "/opt/brouter/brouter.jar", "btools.server.RouteServer", \
     "/map-data/brouter/segments4", "/opt/brouter/profiles", "/opt/brouter/customprofiles", \
     "17777", "4"]
