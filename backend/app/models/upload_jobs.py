# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Column, Integer, String, DateTime, ForeignKey
from sqlalchemy.sql import func
from app.database import Base, PJson


class FitUploadJob(Base):
    __tablename__ = "fit_upload_jobs"

    id               = Column(Integer, primary_key=True)
    user_id          = Column(Integer, ForeignKey("users.id"), nullable=False)
    status           = Column(String(20), server_default="pending", nullable=False)
    total_files      = Column(Integer, server_default="0")
    processed_files  = Column(Integer, server_default="0")
    failed_files     = Column(Integer, server_default="0")
    result           = Column(PJson, default=None)
    error_detail     = Column(String, nullable=True)
    created_at       = Column(DateTime(timezone=True), server_default=func.now())
    updated_at       = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    def to_dict(self):
        return {
            "id": self.id,
            "status": self.status,
            "total_files": self.total_files,
            "processed_files": self.processed_files,
            "failed_files": self.failed_files,
            "result": self.result,
            "error_detail": self.error_detail,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }
