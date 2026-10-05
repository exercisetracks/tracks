#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
# Adjust ownership of the fit-files directory to match the UID/GID used by Docker containers.
# Allows the containers (running as ${UID:-$(id -u)}:${GID:-$(id -g)}) to read/write FIT files.

HOST_UID=${UID:-$(id -u)}
HOST_GID=${GID:-$(id -g)}

echo "Changing ownership of ./fit-files to ${HOST_UID}:${HOST_GID}"
chown -R "${HOST_UID}:${HOST_GID}" ./fit-files
