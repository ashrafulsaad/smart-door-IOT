# Door Sentinel

A multi-node IoT security system that combines embedded sensing (ESP32), mobile camera triggering (Android), cloud infrastructure (Firebase), and machine learning (YOLOv8 + face recognition) for intelligent door monitoring with real-time threat assessment.

---

## System Architecture

```
┌──────────────┐      UDP Telemetry      ┌──────────────────┐
│  ESP32 MCU   │ ───────────────────────► │   Android Phone  │
│  (Sensor)    │      HTTP Trigger       │   (Camera Node)  │
│  Temp/Humid  │ ───────────────────────► │   ServerService  │
│  Light/PIR   │                         │                  │
└──────────────┘                         └────────┬─────────┘
                                                  │
                                    ┌─────────────┼─────────────┐
                                    │  Firebase Cloud Platform  │
                                    ├───────────────────────────┤
                                    │  RTDB:                    │
                                    │   /telemetry/latest       │
                                    │   /status/phone           │
                                    │   /commands               │
                                    │   /logs                   │
                                    │   /livestream/frame       │
                                    │  Storage:                 │
                                    │   /captures/*.jpg         │
                                    └───────┬─────────┬─────────┘
                                            │         │
                                  ┌─────────▼──┐  ┌───▼──────────┐
                                  │ Web App    │  │ Desktop ML   │
                                  │ (Next.js)  │  │ (Python)     │
                                  │ Dashboard  │  │ YOLOv8       │
                                  │ Auth+Admin │  │ Face Recog   │
                                  │ Controls   │  │ Threat Score │
                                  └────────────┘  └──────────────┘
```

## Project Structure

```
DoorSentinel/
├── app/                              # Android App (Kotlin, Jetpack Compose)
│   ├── src/main/java/.../
│   │   ├── ServerService.kt          # Camera, Firebase, remote commands, live video
│   │   ├── LocalServer.kt            # HTTP server for ESP32 triggers
│   │   ├── MainActivity.kt           # UI shell with tab navigation
│   │   ├── PicturesScreen.kt         # Gallery with async thumbnails + image viewer
│   │   ├── SettingsScreen.kt         # Runtime config UI (burst, upload, torch, ML)
│   │   ├── SentinelPrefs.kt          # SharedPreferences wrapper
│   │   ├── ThumbnailCache.kt         # LRU bitmap cache for gallery performance
│   │   └── DoorSentinelApplication.kt
│   ├── google-services.json          # Firebase config (Android)
│   └── build.gradle.kts              # Dependencies
│
├── web/                              # Website (Next.js 16, Firebase Hosting)
│   ├── src/
│   │   ├── app/
│   │   │   ├── globals.css           # Dark theme CSS (~985 lines)
│   │   │   ├── layout.js             # Root layout + AuthProvider
│   │   │   ├── page.js               # Login page (Google Sign-In)
│   │   │   ├── dashboard/page.js     # Main dashboard
│   │   │   └── admin/page.js         # User management
│   │   └── lib/
│   │       ├── firebase.js           # Firebase Web SDK init
│   │       └── AuthContext.js        # Auth state + role management
│   ├── .env.local                    # Firebase keys (already configured)
│   ├── firebase.json                 # Hosting config
│   ├── .firebaserc                   # Project alias
│   └── package.json
│
├── ml/                               # ML Pipeline (Python)
│   ├── sentinel_ml.py                # CLI: YOLOv8 + face recognition pipeline
│   ├── sentinel_gui.py               # Desktop GUI app (customtkinter)
│   ├── requirements.txt              # Python dependencies
│   ├── known_faces/                  # Directory for registered face photos
│   ├── results/                      # Downloaded + annotated images
│   └── README.md                     # ML-specific setup guide
│
└── README.md                         # This file
```

## Features

### Android App
| Feature | Description |
|---------|-------------|
| **ESP32 Triggering** | Receives HTTP triggers from PIR sensor via LocalServer |
| **Fast Camera** | Camera stays permanently bound — ~60ms trigger-to-capture |
| **Burst Capture** | Configurable multi-shot burst (1-20 photos) |
| **Face Detection** | On-device Google ML Kit face detection |
| **Smart Upload** | Only uploads photos with faces detected (saves Firebase costs) |
| **Remote Commands** | Listens on Firebase RTDB for capture/torch/livestream commands |
| **Live Video** | Streams 320×240 JPEG frames via RTDB at configurable FPS |
| **Telemetry Push** | Pushes temp/humidity/light readings to RTDB |
| **Gallery** | Async thumbnail loading, full-screen viewer with pinch-to-zoom |
| **Settings** | Runtime configurable: burst count, shutter speed, upload rules, light threshold |

### Web Dashboard
| Feature | Description |
|---------|-------------|
| **Google Auth** | Sign in with Google, admin approval for new users |
| **Real-time Telemetry** | Live temperature, humidity, light with flash animations |
| **Remote Controls** | Capture photo, toggle torch, start/stop livestream |
| **Live Video Feed** | Canvas-based frame renderer from RTDB |
| **FPS Slider** | Adjust livestream frame rate 1-10 fps |
| **Event Timeline** | Grouped events with photo thumbnails + fullscreen viewer |
| **ML Results** | Displays threat level, person count, face IDs, scene analysis |
| **Admin Panel** | Approve/reject users, role management |
| **Phone Status** | Online/offline indicator with pulse animation |

### ML Pipeline
| Feature | Description |
|---------|-------------|
| **YOLOv8** | Person + object detection with bounding boxes |
| **Face Recognition** | Identifies known faces vs unknown intruders |
| **Scene Analysis** | Brightness, blur, edge density, lighting classification |
| **Threat Assessment** | Scores 0-100 based on persons, unknown faces, lighting |
| **Desktop GUI** | Full Linux GUI app with real-time monitoring |
| **Watch Mode** | Continuously monitors Firebase for new events |

---

## Quick Start

Everything runs from one command:

```bash
# full stack: generates configs + installs deps + starts web + starts ML
python3 run.py

# just the website
python3 run.py --web-only

# just the ML pipeline (process all, then exit)
python3 run.py --ml-only --once

# desktop GUI + website
python3 run.py --gui

# also build the Android APK
python3 run.py --build-apk

# just generate config files
python3 run.py --setup-only
```

### Prerequisites

- Android Studio (for the Android app)
- Node.js 18+ and npm
- Python 3.9+
- A Firebase project with Authentication, Realtime Database, Storage, and Hosting enabled

---

### Step 1: Firebase Console Setup

1. Go to [Firebase Console](https://console.firebase.google.com) → your project (`doorsentinel-2`)

2. **Enable Google Authentication:**
   - Authentication → Sign-in method → Google → **Enable**
   - Add your email as support email

3. **Realtime Database rules** (for development):
   ```json
   {
     "rules": {
       ".read": true,
       ".write": true
     }
   }
   ```
   > ⚠️ Tighten these before production

4. **Storage rules** (for development):
   ```
   rules_version = '2';
   service firebase.storage {
     match /b/{bucket}/o {
       match /{allPaths=**} {
         allow read, write: if true;
       }
     }
   }
   ```

5. **Get Service Account Key** (for ML pipeline):
   - ⚙ Project Settings → Service accounts → **Generate new private key**
   - Save as `ml/serviceAccountKey.json`

---

### Step 2: Android App

1. **Open in Android Studio:**
   - Open project: `DoorSentinel/`
   - Wait for Gradle sync to complete (may take 2-3 minutes first time)

2. **Build & Run:**
   - Connect your Android phone via USB
   - Click **Run ▶** (or `Shift+F10`)
   - Grant camera and notification permissions when prompted

3. **How it works:**
   - The app starts a foreground service with an HTTP server on port `8080`
   - ESP32 sends `GET /trigger` to capture photos
   - Photos are saved locally and uploaded to Firebase (if faces detected)
   - Telemetry (temp/humidity/light) comes via UDP from ESP32

---

### Step 3: Website

1. **Install dependencies** (already done if `node_modules` exists):
   ```bash
   cd web
   npm install    # skip if node_modules/ already exists
   ```

2. **Test locally:**
   ```bash
   npm run dev
   ```
   Open http://localhost:3000

3. **Deploy to Firebase Hosting:**
   ```bash
   # Login (first time only)
   npx firebase-tools login

   # Build and deploy
   npm run deploy
   ```
   Your site will be live at: **https://doorsentinel-2.web.app**

4. **First login:**
   - Sign in with your Google account
   - The **first user** automatically becomes admin
   - All subsequent users are `pending` until you approve them in the admin panel

---

### Step 4: ML Pipeline

1. **Setup Python environment:**
   ```bash
   cd ml
   python3 -m venv venv
   source venv/bin/activate
   pip install -r requirements.txt
   ```

   > **If `face_recognition` fails to install:**
   > ```bash
   > sudo apt install cmake build-essential    # Linux
   > pip install dlib
   > pip install face_recognition
   > ```
   > Or remove `face_recognition` from requirements.txt — the pipeline
   > will still work with YOLOv8 only.

2. **Place Firebase service account key:**
   - The file you downloaded in Step 1.5 → save as `ml/serviceAccountKey.json`

3. **Register known faces** (optional):
   ```bash
   python sentinel_ml.py --register "YourName"
   # Enter path to a clear photo of your face
   ```

4. **Run the CLI pipeline:**
   ```bash
   # Process all unprocessed events
   python sentinel_ml.py --once

   # Or watch continuously for new events
   python sentinel_ml.py

   # View statistics
   python sentinel_ml.py --stats
   ```

5. **Run the Desktop GUI:**
   ```bash
   python sentinel_gui.py
   ```

---

## Firebase RTDB Structure

```
doorsentinel-2-default-rtdb/
├── telemetry/
│   └── latest/
│       ├── temp: 28.5
│       ├── humidity: 65
│       └── light: 1200
│
├── status/
│   └── phone/
│       └── online: true
│
├── commands/
│   └── {pushId}/
│       ├── type: "capture" | "torch_on" | "torch_off" | "livestream_start" | "livestream_stop" | "stop"
│       ├── fps: 3                    # for livestream_start
│       ├── by: "user_uid"
│       ├── timestamp: 1712345678000
│       └── status: "pending" → "done"
│
├── logs/
│   └── {pushId}/
│       ├── fileName: "2026-04-11_20-15-30_burst0.jpg"
│       ├── imageUrl: "https://..."
│       ├── eventId: "evt_..."
│       ├── burstIndex: 0
│       ├── faceDetected: true
│       ├── faceCount: 1
│       ├── timestamp: 1712345678000
│       ├── telemetry/
│       │   ├── temp: 28.5
│       │   ├── humidity: 65
│       │   └── light: 1200
│       └── ml_analysis/              # Written by Python ML pipeline
│           ├── yolo/
│           │   ├── person_count: 1
│           │   ├── total_objects: 3
│           │   └── detections: [...]
│           ├── faces/
│           │   ├── count: 1
│           │   ├── identified: ["Alice"]
│           │   └── unknown_count: 0
│           ├── scene/
│           │   ├── brightness: 145.2
│           │   ├── lighting: "normal"
│           │   └── is_blurry: false
│           └── threat/
│               ├── score: 15
│               ├── level: "low"
│               └── reasons: [...]
│
├── livestream/
│   ├── status/
│   │   ├── active: true
│   │   └── fps: 3
│   └── frame/
│       ├── data: "base64..."
│       ├── width: 320
│       └── height: 240
│
└── users/
    └── {uid}/
        ├── email: "user@gmail.com"
        ├── displayName: "User Name"
        ├── photoURL: "https://..."
        ├── role: "admin" | "approved" | "pending" | "rejected"
        └── createdAt: 1712345678000
```

## Firebase Cost Optimization

| Feature | Cost Impact | Mitigation |
|---------|-------------|------------|
| Photo uploads | **HIGH** — Storage + bandwidth | `onlyUploadFaces` toggle (default ON) — skips uploads when no face detected |
| Livestream frames | **MEDIUM** — RTDB bandwidth | Low resolution (320×240), JPEG quality 30%, keep streams short |
| Telemetry updates | **LOW** — small payloads | Updates every few seconds |
| Event logs | **LOW** — small JSON objects | Auto-capped at 50 on dashboard |
| Website hosting | **FREE** — static files | Firebase Hosting free tier |

With `onlyUploadFaces` enabled and moderate use, the $300 free tier credit should last months.

---

## ML Pipeline Details

### Processing Flow

```
1. New photo uploaded → Firebase Storage
2. Event logged → Firebase RTDB /logs/{id}
3. ML Pipeline detects unprocessed event
4. Downloads image from imageUrl
5. YOLOv8 → person detection + object detection
6. face_recognition → face detection + identification
7. OpenCV → scene analysis (brightness, blur, edges)
8. Threat assessment → combined score (0-100)
9. Results written → /logs/{id}/ml_analysis
10. Website auto-displays results in event card
```

### Threat Scoring

| Factor | Score | Condition |
|--------|-------|-----------|
| Person detected | +40 | YOLOv8 finds ≥1 person |
| Multiple persons | +20 | YOLOv8 finds >1 person |
| Unknown face | +30 | Face not in known_faces |
| Person but no face | +15 | Person visible but face not detected (obscured) |
| Known face | -25 | Identified person from registered faces |
| Dark environment | +10 | Scene brightness < 50 |

Result levels: **none** (0-9) → **low** (10-39) → **medium** (40-69) → **high** (70-100)

---

## Tech Stack

| Component | Technologies |
|-----------|-------------|
| **Android** | Kotlin, Jetpack Compose, CameraX, ML Kit, Firebase SDK |
| **Website** | Next.js 16, React 19, Firebase Auth + RTDB, CSS (custom dark theme) |
| **ML Pipeline** | Python 3, YOLOv8 (ultralytics), face_recognition (dlib), OpenCV, NumPy |
| **Desktop GUI** | CustomTkinter, Pillow |
| **Cloud** | Firebase RTDB, Firebase Storage, Firebase Hosting, Firebase Auth |
| **Hardware** | ESP32 (sensors), Android phone (camera), Linux laptop (ML) |

## Methodology

This project follows a distributed systems architecture where each node has a specialized role:

### Data Acquisition Layer
The ESP32 microcontroller continuously monitors environmental conditions (temperature via DHT11/22, humidity, ambient light via LDR, motion via PIR). When motion is detected, it sends an HTTP trigger to the Android device over the local network.

### Edge Processing Layer
The Android application performs **on-device face detection** using Google ML Kit before uploading to the cloud. This "face-gated upload" strategy reduces cloud storage costs by 60-80% by filtering out false triggers (empty rooms, pets, shadows). The camera is permanently bound to avoid cold-start latency, achieving ~60ms trigger-to-capture time.

### Cloud Infrastructure Layer
Firebase provides the real-time backbone: RTDB for telemetry/events/commands, Storage for images, Authentication for access control, and Hosting for the web dashboard. The bidirectional RTDB allows remote control of the camera node from any authenticated client.

### ML Analysis Layer
The Python pipeline implements a multi-stage analysis:
1. **Object Detection** (YOLOv8n) - identifies persons and objects in the scene
2. **Face Recognition** (dlib/face_recognition) - matches detected faces against a registered database
3. **Scene Analysis** (OpenCV) - evaluates lighting, blur, edge density
4. **Threat Assessment** - weighted scoring algorithm combining all factors

### Presentation Layer
A Next.js web dashboard provides real-time monitoring with Google Authentication, role-based access control (admin/approved/pending/rejected), and live visualization of ML results.

## Performance Characteristics

| Metric | Value |
|--------|-------|
| Trigger-to-capture latency | ~60ms |
| YOLOv8n inference time | 200-400ms (CPU) |
| Face recognition time | 300-600ms (CPU) |
| End-to-end processing | 2-5s per event |
| Firebase cost with face gating | ~60-80% reduction vs. uploading all frames |
| Web dashboard load time | <2s (static export) |
| RTDB telemetry update rate | ~3s |
| Face recognition accuracy | ~95% (dlib HOG, known faces, good lighting) |

## Security Considerations

- All sensitive config files are gitignored (`google-services.json`, `serviceAccountKey.json`, `.env.local`, `sentinel_config.json`)
- The `setup.py` script auto-generates config from `google-services.json` so no credentials need to be shared
- Firebase Authentication with admin approval flow prevents unauthorized access
- RTDB/Storage rules should be restricted to `auth != null` in production

## License

This project was developed as a Machine Learning and IoT course project.
