# Door Sentinel — ML Analysis Pipeline

Real-time security image analysis using **YOLOv8 person detection** and **face recognition**, running on your laptop and connected to Firebase.

## Architecture

```
Phone (Camera) ──→ Firebase Storage ──→ ML Pipeline (Laptop) ──→ Firebase RTDB ──→ Website
                                       ├── YOLOv8 Person Detection
                                       ├── Face Recognition
                                       ├── Scene Analysis
                                       └── Threat Assessment
```

## Setup

### 1. Get Firebase Service Account Key

1. Go to [Firebase Console](https://console.firebase.google.com) → Your project
2. Click **⚙ Project Settings** → **Service accounts**
3. Click **Generate new private key**
4. Save it as `ml/serviceAccountKey.json`

### 2. Install Dependencies

```bash
cd ml
python -m venv venv
source venv/bin/activate   # or `venv\Scripts\activate` on Windows
pip install -r requirements.txt
```

> **Note**: `face_recognition` requires `dlib`. If it fails to install:
> - Linux: `sudo apt install cmake build-essential`
> - macOS: `brew install cmake`
> - Or skip it by removing from requirements.txt (pipeline works without it, just skips face ID)

### 3. Register Known Faces (Optional)

```bash
# Register your face so the system can identify you
python sentinel_ml.py --register "YourName"
# It will ask for a photo path

# Or create a directory: ml/known_faces/YourName/ and put photos there
mkdir -p known_faces/YourName
cp ~/my_photo.jpg known_faces/YourName/
python sentinel_ml.py --register "YourName"
```

## Usage

### Watch Mode (Continuous)
```bash
python sentinel_ml.py
```
Continuously monitors Firebase for new events, processes them through the ML pipeline, and writes results back to RTDB.

### One-Shot Mode
```bash
python sentinel_ml.py --once
```
Processes all unprocessed events once, then exits.

### View Statistics
```bash
python sentinel_ml.py --stats
```

## ML Components

| Component | Library | What it does |
|---|---|---|
| Person Detection | YOLOv8 (ultralytics) | Detects people and objects in each photo |
| Face Recognition | face_recognition (dlib) | Identifies known faces vs unknown intruders |
| Scene Analysis | OpenCV | Analyzes brightness, blur, edges |
| Threat Assessment | Custom | Scores threat level (0-100) based on all factors |

## Results

The pipeline writes results to `logs/{eventKey}/ml_analysis` in Firebase RTDB:

```json
{
  "yolo": {
    "person_count": 1,
    "total_objects": 3,
    "detections": [{ "class": "person", "confidence": 0.92, "bbox": {...} }]
  },
  "faces": {
    "count": 1,
    "identified": ["Alice"],
    "unknown_count": 0
  },
  "scene": {
    "brightness": 145.2,
    "lighting": "normal",
    "is_blurry": false
  },
  "threat": {
    "score": 15,
    "level": "low",
    "reasons": ["1 person(s) detected", "Known: Alice"]
  }
}
```

These results automatically appear on the web dashboard in each event card.
