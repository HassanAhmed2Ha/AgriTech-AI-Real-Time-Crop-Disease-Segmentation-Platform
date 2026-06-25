"""
Pydantic schemas for the AgriTech detection API.

These models define the exact JSON contract between the FastAPI server
and the Android client's DetectionResponse / RemoteDetection Moshi models.
Field names use snake_case to match the @Json annotations on the Android side.
"""

from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


class RemoteDetection(BaseModel):
    """A single detected crop disease instance."""

    class_id: int = Field(..., description="Integer class index (0–5).")
    class_name: str = Field(..., description="Human-readable disease label.")
    score: float = Field(..., ge=0.0, le=1.0, description="Confidence score.")
    x1: float = Field(..., description="Left bounding-box edge (model input space, 0–640).")
    y1: float = Field(..., description="Top bounding-box edge.")
    x2: float = Field(..., description="Right bounding-box edge.")
    y2: float = Field(..., description="Bottom bounding-box edge.")
    mask: Optional[List[float]] = Field(
        default=None,
        description="Flattened 160×160 segmentation mask (25,600 floats), or null.",
    )


class DetectionResponse(BaseModel):
    """Top-level API response returned by POST /api/v1/detect."""

    detections: List[RemoteDetection] = Field(
        ..., description="List of all detected disease instances in this frame."
    )
    inference_ms: int = Field(
        ..., description="Pure server-side model inference time in milliseconds."
    )
    model_version: str = Field(
        ..., description="Identifier of the model used (e.g. 'yolo11s-seg-v1')."
    )
    image_width: int = Field(..., description="Width of the received image in pixels.")
    image_height: int = Field(..., description="Height of the received image in pixels.")
