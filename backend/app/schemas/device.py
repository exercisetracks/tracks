# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from pydantic import BaseModel, ConfigDict


class DeviceOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    serial_number: str
    manufacturer: str | None = None
    manufacturer_id: int | None = None
    product_name: str | None = None
    product_id: int | None = None
    software_version: str | None = None
    claimed: bool = False
