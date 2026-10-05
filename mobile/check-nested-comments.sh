#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Kotlin block comments NEST. A literal "/*" inside a KDoc — writing a path like
# `/api/*` or `/device-sync/*` — opens a nested comment, and the closing "*/"
# then belongs to it, leaving the doc comment unterminated. The compiler
# reports "unclosed comment" tens of lines later, pointing at innocent code.
#
# This has bitten twice. It costs a second to check.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if grep -rn --include='*.kt' -e '^\s*\*.*/\*' . 2>/dev/null | grep -v '/\*\*'; then
  echo
  echo "Found '/*' inside a doc comment — Kotlin will treat it as a nested"
  echo "comment opener. Reword the path, e.g. '/api/*' -> '/api paths'."
  exit 1
fi
echo "check-nested-comments: clean"
