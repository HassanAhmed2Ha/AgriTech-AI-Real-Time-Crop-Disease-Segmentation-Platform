---
title: AgriTech Crop Disease Detector
emoji: 🌿
colorFrom: green
colorTo: blue
sdk: docker
app_port: 7860
pinned: false
---

# AgriTech Crop Disease Detection API

FastAPI server that receives camera frames from the Android client and runs
YOLOv11 crop disease segmentation, returning structured JSON results.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/health` | Service health + model/W&B status |
| `POST` | `/api/v1/detect` | Submit a frame, get detections |

## API Contract

### POST `/api/v1/detect`

**Request:** `multipart/form-data` with field `image` (JPEG or PNG).

**Response JSON:**
```json
{
  "detections": [
    {
      "class_id": 0,
      "class_name": "Late_blight",
      "score": 0.87,
      "x1": 120.5, "y1": 80.2, "x2": 340.1, "y2": 290.6,
      "mask": [0.0, 0.82, ...]
    }
  ],
  "inference_ms": 24,
  "model_version": "yolo11s-seg-v1",
  "image_width": 640,
  "image_height": 640
}
```

## Environment Secrets (Hugging Face Space Settings)

| Secret Name | Description |
|---|---|
| `WANDB_API_KEY` | Weights & Biases API key for inference monitoring |
| `MODEL_PATH` | (optional) Override model path. Default: `weights/best.pt` |
| `CONF_THRESH` | (optional) Detection confidence. Default: `0.35` |

## Local Development

```bash
pip install -r requirements.txt
# Place model weights at weights/best.pt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

## Android Client

Set in the project root `.env`:
```
AGRITECH_API_URL=https://your-space-url.hf.space/api/v1/
```
