# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Celery task queue — work that shouldn't run inline in a request/response
cycle. See celery_app.py for the app/broker config and imports.py for the
first (and so far only) task.
"""
