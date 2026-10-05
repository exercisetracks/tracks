# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from abc import ABC, abstractmethod
from pathlib import Path


class BaseParser(ABC):
    """All FIT file parsers inherit from this."""

    @abstractmethod
    def can_parse(self, fit_path: Path) -> bool:
        """Return True if this parser handles the given file."""
        ...

    @abstractmethod
    def parse(self, fit_path: Path) -> dict:
        """Parse the file and return structured data."""
        ...
