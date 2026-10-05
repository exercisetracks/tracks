# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# tracks-web: the released entry point. Caddy with the built frontend and the
# Caddyfile baked in, so an install needs no source checkout and no files of
# its own beyond compose.yml and .env.
#
# Built from the repository root (it needs both frontend/ and caddy/):
#   docker build -f caddy/Dockerfile -t exercisetracks/tracks-web .
# The root .dockerignore keeps everything else out of the build context.

# Node 22: see frontend/Dockerfile for why nothing older can build this.
FROM node:22-alpine AS build
WORKDIR /app
RUN apk add --no-cache libc6-compat
# Lockfile and patches before the install, for the reasons frontend/Dockerfile
# gives (reproducible tree; patch-package runs as a postinstall hook).
COPY frontend/package.json frontend/package-lock.json ./
COPY frontend/patches ./patches
RUN npm ci
COPY frontend/ .
ARG VITE_API_URL=/api
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build

FROM caddy:2-alpine
COPY caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
ENV TRACKS_FRONTEND=static
