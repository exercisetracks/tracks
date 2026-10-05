# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The backend refuses to start on a JWT secret that is not a secret.

Anyone who knows the signing secret can mint a token for any account, so a
value that is public — or empty — is no protection at all. Checked in a
subprocess, because the check runs when app.config is first imported.
"""
import os
import subprocess
import sys
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parent.parent


def _starts_with(jwt_secret: str) -> subprocess.CompletedProcess:
    env = {**os.environ, "JWT_SECRET": jwt_secret, "DEBUG": "false"}
    return subprocess.run([sys.executable, "-c", "import app.config"],
                          cwd=BACKEND, env=env, capture_output=True, text=True)


@pytest.mark.parametrize("secret", ["", "   ", "changeme", "change-this-jwt-secret-in-production"])
def test_a_public_or_empty_secret_is_refused(secret):
    result = _starts_with(secret)
    assert result.returncode != 0
    assert "JWT_SECRET is not set" in result.stderr


def test_a_real_secret_starts():
    assert _starts_with("x7Qp0-generated-by-setup-sh-9fKz").returncode == 0
