"""
AgriTech Crop Disease Detection — FastAPI Backend (with W&B Monitoring)
========================================================================
"""

from __future__ import annotations

import io
import os
import time
import logging
from contextlib import asynccontextmanager
from typing import Optional

import numpy as np
import wandb
from fastapi import FastAPI, File, HTTPException, UploadFile, status, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from PIL import Image

from schemas import DetectionResponse, RemoteDetection

# ---------------------------------------------------------------------------
# Logging & MLOps Configuration
# ---------------------------------------------------------------------------
logging.basicConfig(level=logging.INFO, format="%(levelname)s | %(name)s | %(message)s")
log = logging.getLogger("agritech")

MODEL_PATH = os.getenv("MODEL_PATH", "weights/best.pt")
CONF_THRESH = float(os.getenv("CONF_THRESH", "0.35"))
IOU_THRESH = float(os.getenv("IOU_THRESH", "0.45"))
DEVICE = os.getenv("DEVICE", "cpu")
MODEL_VERSION = "yolo11s-seg-v1"

CLASS_NAMES = [
    "Late_blight",
    "Leaf Miner",
    "Magnesium Deficiency",
    "Nitrogen Deficiency",
    "Pottassium Deficiency",
    "Spotted Wilt Virus"
]

# ---------------------------------------------------------------------------
# Model & W&B lifecycle
# ---------------------------------------------------------------------------
_model = None
_wandb_enabled = False


def _load_model():
    global _model
    try:
        from ultralytics import YOLO
        log.info("Loading model from '%s' on device='%s'…", MODEL_PATH, DEVICE)
        _model = YOLO(MODEL_PATH)
        _model.to(DEVICE)
        log.info("Model loaded successfully.")
    except Exception as e:
        log.warning("Model load failed: %s. Running in STUB mode.", e)
        _model = None


def _init_wandb():
    """Initialize W&B monitoring. Silently skips if WANDB_API_KEY is not set."""
    global _wandb_enabled
    api_key = os.getenv("WANDB_API_KEY")
    if not api_key:
        log.info("WANDB_API_KEY not set — W&B monitoring disabled. Set the secret to enable it.")
        return

    try:
        wandb.login(key=api_key, verify=True, relogin=True)
        wandb.init(
            project="agritech-production",
            job_type="inference-monitoring",
            config={"model_version": MODEL_VERSION, "conf_thresh": CONF_THRESH},
        )
        _wandb_enabled = True
        log.info("W&B monitoring active — project: agritech-production")
    except Exception as e:
        log.warning("W&B init failed (continuing without monitoring): %s", e)
        _wandb_enabled = False


@asynccontextmanager
async def lifespan(app: FastAPI):
    _load_model()
    _init_wandb()
    yield
    if _wandb_enabled:
        wandb.finish()
    log.info("Server shutting down.")


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------
app = FastAPI(title="AgriTech Crop Disease Detection API", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["POST", "GET"],
    allow_headers=["*"],
)


# ---------------------------------------------------------------------------
# Helpers & Monitoring
# ---------------------------------------------------------------------------

def log_to_wandb(inference_ms: int, detections: list[RemoteDetection]):
    """Background task to log metrics to W&B. No-op if W&B is disabled."""
    if not _wandb_enabled:
        return

    if not detections:
        wandb.log({"inference_ms": inference_ms, "disease_count_in_frame": 0,
                   "status": "no_disease_detected"})
        return

    top_detection = max(detections, key=lambda d: d.score)
    wandb.log({
        "inference_ms": inference_ms,
        "top_class_id": top_detection.class_id,
        "top_class_name": top_detection.class_name,
        "top_score": top_detection.score,
        "disease_count_in_frame": len(detections),
    })


def _decode_image(raw_bytes: bytes) -> np.ndarray:
    image = Image.open(io.BytesIO(raw_bytes)).convert("RGB")
    return np.array(image)


def _run_inference(image_np: np.ndarray) -> tuple[list[RemoteDetection], int]:
    if _model is None:
        time.sleep(0.025)
        return [], 25

    t0 = time.perf_counter()
    results = _model.predict(
        source=image_np, conf=CONF_THRESH, iou=IOU_THRESH, device=DEVICE, verbose=False
    )
    inference_ms = int((time.perf_counter() - t0) * 1000)

    detections: list[RemoteDetection] = []
    for result in results:
        boxes = result.boxes
        masks = result.masks
        if boxes is None:
            continue
        for i, box in enumerate(boxes):
            cls_id = int(box.cls[0].item())
            mask_flat = None
            if masks is not None and i < len(masks.data):
                mask_tensor = masks.data[i].cpu().numpy()
                mask_img = Image.fromarray(
                    (mask_tensor * 255).astype(np.uint8)
                ).resize((160, 160), Image.BILINEAR)
                mask_flat = (np.array(mask_img) / 255.0).flatten().tolist()

            detections.append(RemoteDetection(
                class_id=cls_id,
                class_name=CLASS_NAMES[cls_id] if cls_id < len(CLASS_NAMES) else "Unknown",
                score=float(box.conf[0].item()),
                x1=float(box.xyxy[0][0]), y1=float(box.xyxy[0][1]),
                x2=float(box.xyxy[0][2]), y2=float(box.xyxy[0][3]),
                mask=mask_flat,
            ))
    return detections, inference_ms


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.get("/health", summary="Health check")
async def health():
    """Returns service status and model load state."""
    return {
        "status": "ok",
        "model_loaded": _model is not None,
        "model_version": MODEL_VERSION,
        "device": DEVICE,
        "wandb_enabled": _wandb_enabled,
    }


@app.post("/api/v1/detect", response_model=DetectionResponse)
async def detect(
    background_tasks: BackgroundTasks,
    image: UploadFile = File(..., description="JPEG or PNG camera frame"),
):
    """
    Accepts a multipart image upload, runs YOLOv11 segmentation inference,
    and returns structured disease detections.
    """
    if image.content_type not in ("image/jpeg", "image/png", "image/webp"):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"Unsupported content type: {image.content_type}. Use image/jpeg or image/png.",
        )

    raw = await image.read()
    if not raw:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Empty image payload.",
        )

    try:
        image_np = _decode_image(raw)
    except Exception as exc:
        log.exception("Image decode failed")
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Image decode error: {exc}",
        )

    img_h, img_w = image_np.shape[:2]

    try:
        detections, inference_ms = _run_inference(image_np)
    except Exception as exc:
        log.exception("Inference failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Inference error: {exc}",
        )

    log.info("Detected %d instance(s) in %dms | %dx%d", len(detections), inference_ms, img_w, img_h)

    # Fire-and-forget W&B logging (doesn't block the response)
    background_tasks.add_task(log_to_wandb, inference_ms, detections)

    return DetectionResponse(
        detections=detections,
        inference_ms=inference_ms,
        model_version=MODEL_VERSION,
        image_width=img_w,
        image_height=img_h,
    )