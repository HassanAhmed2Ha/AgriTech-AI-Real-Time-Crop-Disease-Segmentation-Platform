# AgriTech Crop Disease Detector

A real-time crop disease detection system with an Android MVVM client and a
cloud-deployable FastAPI inference backend.

## Architecture

```
Android App (CameraX + Compose MVVM)
         │
         │  POST /api/v1/detect  (multipart JPEG)
         ▼
FastAPI Backend (YOLOv11 segmentation)
         │
         │  JSON DetectionResponse
         ▼
Android App (renders bounding boxes + masks over live camera feed)
```

## Project Structure

```
YOLOv11-Benchmark-APP/
├── app/                          # Android application module
│   └── src/main/java/com/aistudio/agritech/
│       ├── MainActivity.kt       # Entry point; initializes ApiClient
│       ├── data/
│       │   ├── model/
│       │   │   └── Detection.kt  # Domain model for a single detection
│       │   └── remote/
│       │       ├── ApiClient.kt          # OkHttp + Retrofit singleton
│       │       ├── ApiService.kt         # Retrofit interface (/detect)
│       │       └── DetectionResponse.kt  # Moshi API response models
│       ├── ui/
│       │   ├── components/
│       │   │   ├── CameraPreviewOverlay.kt  # CameraX live feed
│       │   │   └── DetectionOverlay.kt      # Bounding box + mask renderer
│       │   └── theme/
│       │       ├── Color.kt
│       │       ├── Theme.kt
│       │       └── Type.kt
│       └── viewmodel/
│           └── BenchmarkViewModel.kt     # Coordinates API calls & metrics
│
└── backend/                      # FastAPI inference server
    ├── main.py                   # Server entry point + /detect endpoint
    ├── schemas.py                # Pydantic request/response models
    ├── requirements.txt          # Python dependencies
    └── README.md                 # Backend setup & API docs
```

## Quick Start

### Android Client

1. Open the project in Android Studio.
2. Copy `.env.example` to `.env` and set `AGRITECH_API_URL` to your server's URL.
3. Build and run on a device or emulator with a camera.

### Backend Server

```bash
cd backend/
pip install -r requirements.txt
# Place model weights at backend/weights/yolo11s-seg.pt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

See [backend/README.md](backend/README.md) for detailed configuration.

## Environment Variables

| Variable | Description |
|---|---|
| `AGRITECH_API_URL` | Base URL of the FastAPI server (e.g. `http://192.168.1.100:8000/api/v1/`) |
| `GEMINI_API_KEY` | Gemini API key for AI Studio integration (optional) |
