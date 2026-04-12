#!/usr/bin/env python3
"""
Door Sentinel — ML Analysis Pipeline
=====================================
Watches Firebase RTDB for new security events, downloads the images,
runs YOLOv8 person detection + face recognition, and writes results
back to RTDB for the monitoring dashboard.

Usage:
    python sentinel_ml.py                    # watch mode (continuous)
    python sentinel_ml.py --once             # process all unprocessed, then exit
    python sentinel_ml.py --register Alice   # register a known face

Requirements:
    pip install -r requirements.txt
    Place serviceAccountKey.json in this directory (download from Firebase Console)
"""

import os
import sys
import json
import time
import argparse
import tempfile
import pickle
from pathlib import Path
from datetime import datetime

import cv2
import numpy as np
import requests
from PIL import Image

import firebase_admin
from firebase_admin import credentials, db, storage

# ── Configuration ──────────────────────────────────────────────────────────────

BASE_DIR = Path(__file__).parent
SERVICE_ACCOUNT_KEY = BASE_DIR / "serviceAccountKey.json"
KNOWN_FACES_DIR = BASE_DIR / "known_faces"
ENCODINGS_FILE = BASE_DIR / "known_faces" / "encodings.pkl"
RESULTS_DIR = BASE_DIR / "results"

FIREBASE_DB_URL = ""
FIREBASE_STORAGE_BUCKET = ""

def _load_config():
    """Load Firebase config from sentinel_config.json or environment."""
    global FIREBASE_DB_URL, FIREBASE_STORAGE_BUCKET
    config_file = BASE_DIR / "sentinel_config.json"
    if config_file.exists():
        with open(config_file) as f:
            cfg = json.load(f)
            FIREBASE_DB_URL = cfg.get("databaseURL", "")
            FIREBASE_STORAGE_BUCKET = cfg.get("storageBucket", "")
    else:
        FIREBASE_DB_URL = os.environ.get("FIREBASE_DB_URL", "")
        FIREBASE_STORAGE_BUCKET = os.environ.get("FIREBASE_STORAGE_BUCKET", "")

    if not FIREBASE_DB_URL:
        print("  error: firebase config missing")
        print(f"  create {config_file} with:")
        print('  {"databaseURL": "https://YOUR-PROJECT.firebasedatabase.app", "storageBucket": "YOUR-PROJECT.firebasestorage.app"}')
        print("  or run: python3 ../setup.py")
        sys.exit(1)

_load_config()

# YOLO confidence threshold
YOLO_CONFIDENCE = 0.4
# Face recognition tolerance (lower = stricter match)
FACE_TOLERANCE = 0.6

# ── Colours for annotated images ───────────────────────────────────────────────

COLOR_PERSON = (0, 255, 100)    # Green
COLOR_FACE = (255, 158, 74)     # Blue-ish (BGR)
COLOR_UNKNOWN = (0, 0, 255)     # Red
COLOR_KNOWN = (0, 255, 0)       # Green
COLOR_TEXT_BG = (0, 0, 0)       # Black

# ── Initialize Firebase ───────────────────────────────────────────────────────

def init_firebase():
    """Initialize Firebase Admin SDK."""
    if not SERVICE_ACCOUNT_KEY.exists():
        print("  error: serviceAccountKey.json not found")
        print("  download from Firebase Console > Project Settings > Service accounts")
        print(f"  save to: {SERVICE_ACCOUNT_KEY}")
        sys.exit(1)

    cred = credentials.Certificate(str(SERVICE_ACCOUNT_KEY))
    firebase_admin.initialize_app(cred, {
        "databaseURL": FIREBASE_DB_URL,
        "storageBucket": FIREBASE_STORAGE_BUCKET,
    })
    print("  firebase       connected")

# ── YOLOv8 Detector ───────────────────────────────────────────────────────────

class PersonDetector:
    """YOLOv8-based object detector focused on person detection."""

    def __init__(self):
        from ultralytics import YOLO
        print("  yolov8         loading...")
        self.model = YOLO("yolov8n.pt")
        print("  yolov8         ready")

    def detect(self, image_path: str) -> dict:
        """
        Run detection on an image.
        Returns dict with person_count, detections list, and annotated image path.
        """
        img = cv2.imread(image_path)
        if img is None:
            return {"error": "Could not read image", "person_count": 0, "detections": []}

        results = self.model(img, conf=YOLO_CONFIDENCE, verbose=False)
        result = results[0]

        detections = []
        person_count = 0
        person_boxes = []

        for box in result.boxes:
            cls_id = int(box.cls[0])
            cls_name = result.names[cls_id]
            confidence = float(box.conf[0])
            x1, y1, x2, y2 = box.xyxy[0].tolist()

            detection = {
                "class": cls_name,
                "confidence": round(confidence, 3),
                "bbox": {
                    "x1": int(x1), "y1": int(y1),
                    "x2": int(x2), "y2": int(y2)
                }
            }
            detections.append(detection)

            if cls_name == "person":
                person_count += 1
                person_boxes.append((int(x1), int(y1), int(x2), int(y2), confidence))

            # Draw bounding box
            color = COLOR_PERSON if cls_name == "person" else (200, 200, 200)
            cv2.rectangle(img, (int(x1), int(y1)), (int(x2), int(y2)), color, 2)

            # Label
            label = f"{cls_name} {confidence:.0%}"
            label_size = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)[0]
            cv2.rectangle(img, (int(x1), int(y1) - label_size[1] - 8),
                         (int(x1) + label_size[0] + 4, int(y1)), COLOR_TEXT_BG, -1)
            cv2.putText(img, label, (int(x1) + 2, int(y1) - 4),
                       cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1)

        # Save annotated image
        annotated_path = image_path.replace(".jpg", "_annotated.jpg")
        cv2.imwrite(annotated_path, img)

        return {
            "person_count": person_count,
            "total_objects": len(detections),
            "detections": detections,
            "person_boxes": person_boxes,
            "annotated_path": annotated_path,
            "annotated_img": img,   # Return img so face boxes can be drawn on it later
        }

# ── Face Recognizer ────────────────────────────────────────────────────────────

class FaceRecognizer:
    """Face detection + recognition using face_recognition library."""

    def __init__(self):
        self.known_encodings = []
        self.known_names = []
        self._load_known_faces()

    def _load_known_faces(self):
        """Load pre-computed face encodings from disk."""
        if ENCODINGS_FILE.exists():
            with open(ENCODINGS_FILE, "rb") as f:
                data = pickle.load(f)
                self.known_encodings = data["encodings"]
                self.known_names = data["names"]
            print(f"  faces          {len(self.known_names)} known ({', '.join(set(self.known_names))})")
        else:
            print("  faces          none registered (use --register)")

    def register_face(self, name: str, image_path: str):
        """Register a new known face from an image."""
        import face_recognition

        img = face_recognition.load_image_file(image_path)
        encodings = face_recognition.face_encodings(img)

        if not encodings:
            print(f"  error: no face found in {image_path}")
            return False

        for enc in encodings:
            self.known_encodings.append(enc)
            self.known_names.append(name)

        # Save to disk
        KNOWN_FACES_DIR.mkdir(parents=True, exist_ok=True)
        with open(ENCODINGS_FILE, "wb") as f:
            pickle.dump({"encodings": self.known_encodings, "names": self.known_names}, f)

        # Save a copy of the image for visual reference
        person_dir = KNOWN_FACES_DIR / name
        person_dir.mkdir(parents=True, exist_ok=True)
        import shutil
        import uuid
        dest = person_dir / f"learned_{uuid.uuid4().hex[:8]}.jpg"
        shutil.copy2(image_path, dest)

        print(f"  registered     {len(encodings)} face(s) for '{name}'")
        return True

    def recognize(self, image_path: str) -> dict:
        """
        Detect and recognize faces in an image.
        Returns dict with face_count, identified list, unknown_count, and raw face crops.
        """
        import face_recognition

        img_rgb = face_recognition.load_image_file(image_path)
        img_bgr = cv2.imread(image_path)   # for cropping (cv2 is faster)
        h, w = img_rgb.shape[:2]

        face_locations = face_recognition.face_locations(img_rgb, model="hog")
        face_encodings = face_recognition.face_encodings(img_rgb, face_locations)

        faces = []
        identified = []
        unknown_count = 0

        for i, ((top, right, bottom, left), encoding) in enumerate(zip(face_locations, face_encodings)):
            name = "Unknown"
            confidence = 0.0

            if self.known_encodings:
                distances = face_recognition.face_distance(self.known_encodings, encoding)
                best_idx = np.argmin(distances)
                best_dist = distances[best_idx]

                if best_dist < FACE_TOLERANCE:
                    name = self.known_names[best_idx]
                    confidence = round(1.0 - best_dist, 3)
                    identified.append(name)
                else:
                    unknown_count += 1
            else:
                unknown_count += 1

            # ── Extract face crop ────────────────────────────────────────────────────────────────
            # Add 20% padding around detected face bbox for better context
            pad_v = int((bottom - top) * 0.20)
            pad_h = int((right - left) * 0.20)
            top_p    = max(0, top - pad_v)
            bottom_p = min(h, bottom + pad_v)
            left_p   = max(0, left - pad_h)
            right_p  = min(w, right + pad_h)
            crop_bgr = img_bgr[top_p:bottom_p, left_p:right_p]

            # Save crop locally
            faces_dir = RESULTS_DIR / "faces"
            faces_dir.mkdir(parents=True, exist_ok=True)
            base = Path(image_path).stem
            crop_path = str(faces_dir / f"{base}_face{i}.jpg")
            cv2.imwrite(crop_path, crop_bgr)

            faces.append({
                "name": name,
                "confidence": confidence,
                "bbox": {"x1": left, "y1": top, "x2": right, "y2": bottom},
                "crop_path": crop_path,   # local path, caller uploads to Storage
                "face_index": i,
            })

        return {
            "face_count": len(faces),
            "identified": list(set(identified)),
            "unknown_count": unknown_count,
            "faces": faces,
        }

# ── Scene Analyzer ─────────────────────────────────────────────────────────────

class SceneAnalyzer:
    """Analyzes scene properties: brightness, blur, motion hints."""

    @staticmethod
    def analyze(image_path: str) -> dict:
        img = cv2.imread(image_path)
        if img is None:
            return {}

        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape

        # Brightness (0-255)
        brightness = float(np.mean(gray))

        # Contrast (std dev of brightness)
        contrast = float(np.std(gray))

        # Blur detection (Laplacian variance — lower = more blurry)
        laplacian_var = float(cv2.Laplacian(gray, cv2.CV_64F).var())
        is_blurry = laplacian_var < 100

        # Dominant color
        avg_color = img.mean(axis=0).mean(axis=0).tolist()  # BGR
        avg_color_rgb = [int(avg_color[2]), int(avg_color[1]), int(avg_color[0])]

        # Edge density (how busy the scene is)
        edges = cv2.Canny(gray, 50, 150)
        edge_density = float(np.count_nonzero(edges)) / (h * w)

        # Time of day estimation based on brightness
        if brightness < 50:
            lighting = "dark"
        elif brightness < 120:
            lighting = "dim"
        elif brightness < 200:
            lighting = "normal"
        else:
            lighting = "bright"

        return {
            "brightness": round(brightness, 1),
            "contrast": round(contrast, 1),
            "blur_score": round(laplacian_var, 1),
            "is_blurry": is_blurry,
            "edge_density": round(edge_density, 4),
            "lighting": lighting,
            "resolution": f"{w}x{h}",
            "avg_color_rgb": avg_color_rgb,
        }

# ── Threat Assessment ──────────────────────────────────────────────────────────

def assess_threat(yolo_result: dict, face_result: dict, scene: dict) -> dict:
    """
    Compute a threat/alert level based on combined ML results.
    Returns a threat assessment dict.
    """
    score = 0
    reasons = []

    # Person detected
    if yolo_result["person_count"] > 0:
        score += 40
        reasons.append(f"{yolo_result['person_count']} person(s) detected")

    # Multiple persons
    if yolo_result["person_count"] > 1:
        score += 20
        reasons.append("Multiple persons")

    # Unknown faces
    if face_result.get("unknown_count", 0) > 0:
        score += 30
        reasons.append(f"{face_result['unknown_count']} unknown face(s)")

    # No faces but person detected (obscured face = suspicious)
    if yolo_result["person_count"] > 0 and face_result.get("face_count", 0) == 0:
        score += 15
        reasons.append("Person detected but no face visible")

    # Known faces reduce score
    if face_result.get("identified"):
        score -= 25
        reasons.append(f"Known: {', '.join(face_result['identified'])}")

    # Dark scene
    if scene.get("lighting") == "dark":
        score += 10
        reasons.append("Dark environment")

    score = max(0, min(100, score))

    if score >= 70:
        level = "high"
    elif score >= 40:
        level = "medium"
    elif score >= 10:
        level = "low"
    else:
        level = "none"

    return {
        "score": score,
        "level": level,
        "reasons": reasons,
    }

# ── Image Downloader ──────────────────────────────────────────────────────────

def download_image(url: str, dest_path: str) -> bool:
    """Download an image from a URL."""
    try:
        resp = requests.get(url, timeout=30)
        resp.raise_for_status()
        with open(dest_path, "wb") as f:
            f.write(resp.content)
        return True
    except Exception as e:
        print(f"  error: download failed - {e}")
        return False

# ── Process a Single Event ────────────────────────────────────────────────────

def process_event(log_key: str, log_data: dict,
                  detector: PersonDetector, recognizer: FaceRecognizer):
    """Process a single security event through the ML pipeline."""

    image_url = log_data.get("imageUrl")
    if not image_url:
        return

    filename = log_data.get("fileName", "unknown.jpg")
    ts = log_data.get('timestamp', 0)
    time_str = datetime.fromtimestamp(ts / 1000).strftime("%Y-%m-%d %H:%M:%S") if ts else "--"
    print(f"\n  [{time_str}] {filename}")

    # Create results directory
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    local_path = str(RESULTS_DIR / filename)

    # Download image
    if not download_image(image_url, local_path):
        return

    # Stage 1: YOLOv8 Detection
    t0 = time.time()
    yolo_result = detector.detect(local_path)
    yolo_time = time.time() - t0
    print(f"    yolo         {yolo_result['person_count']} person(s), "
          f"{yolo_result['total_objects']} objects ({yolo_time:.2f}s)")

    for det in yolo_result["detections"]:
        print(f"                 - {det['class']} ({det['confidence']:.0%})")

    # Stage 2: Face Recognition
    t0 = time.time()
    face_result = recognizer.recognize(local_path)
    face_time = time.time() - t0
    print(f"    faces        {face_result['face_count']} detected ({face_time:.2f}s)")

    if face_result["identified"]:
        print(f"    identified   {', '.join(face_result['identified'])}")
    if face_result["unknown_count"] > 0:
        print(f"    unknown      {face_result['unknown_count']}")

    # ── Draw face bounding boxes on the YOLO-annotated image ──────────────────
    annotated_img = yolo_result.get("annotated_img")
    if annotated_img is not None:
        for face in face_result.get("faces", []):
            bbox = face.get("bbox", {})
            x1, y1, x2, y2 = bbox.get("x1",0), bbox.get("y1",0), bbox.get("x2",0), bbox.get("y2",0)
            name = face.get("name", "Unknown")
            conf = face.get("confidence", 0)
            color = COLOR_KNOWN if name != "Unknown" else COLOR_UNKNOWN
            # Draw face box (2px border)
            cv2.rectangle(annotated_img, (x1, y1), (x2, y2), color, 2)
            # Face label
            label = f"{name}" if name != "Unknown" else f"Unknown"
            ls = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)[0]
            cv2.rectangle(annotated_img, (x1, y2), (x1 + ls[0] + 4, y2 + ls[1] + 6), COLOR_TEXT_BG, -1)
            cv2.putText(annotated_img, label, (x1 + 2, y2 + ls[1] + 2),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.45, color, 1)
        # Re-save the annotated image with both YOLO + face boxes
        annotated_path = yolo_result["annotated_path"]
        cv2.imwrite(annotated_path, annotated_img)
        print(f"    annotated    face boxes drawn on {os.path.basename(annotated_path)}")
    # Stage 3: Scene Analysis
    scene = SceneAnalyzer.analyze(local_path)
    print(f"    scene        {scene.get('lighting', '?')}, "
          f"brightness {scene.get('brightness', 0)}, "
          f"{'blurry' if scene.get('is_blurry') else 'sharp'}")

    # Stage 4: Threat Assessment
    threat = assess_threat(yolo_result, face_result, scene)
    print(f"    threat       {threat['level']} ({threat['score']})")
    for r in threat["reasons"]:
        print(f"                 - {r}")

    # ── Upload annotated image to Firebase Storage ────────────────────────────
    annotated_url = None
    annotated_path = yolo_result.get("annotated_path")
    if annotated_path and os.path.exists(annotated_path):
        try:
            bucket = storage.bucket()
            annotated_storage_path = f"annotated/{filename.replace('.jpg', '_annotated.jpg')}"
            blob = bucket.blob(annotated_storage_path)
            blob.upload_from_filename(annotated_path)
            blob.make_public()
            annotated_url = blob.public_url
            print(f"    uploaded     annotated image → Firebase Storage")
        except Exception as e:
            print(f"    warning      annotated upload failed: {e}")

    # ── Upload face crops to Firebase Storage and finalise face details ────────
    face_details = []
    for face in face_result.get("faces", []):
        crop_path = face.get("crop_path")
        crop_url  = None
        if crop_path and os.path.exists(crop_path):
            try:
                bucket = storage.bucket()
                crop_name = os.path.basename(crop_path)
                blob = bucket.blob(f"faces/{crop_name}")
                blob.upload_from_filename(crop_path)
                blob.make_public()
                crop_url = blob.public_url
            except Exception as e:
                print(f"    warning      crop upload failed: {e}")

        face_details.append({
            "name":       face["name"],
            "confidence": face["confidence"],
            "bbox":       face["bbox"],
            "cropUrl":    crop_url,   # face image shown on the dashboard
        })
        label = face["name"]
        if crop_url:
            print(f"    crop         [{label}] → Firebase Storage")

    # Write results to RTDB
    ml_analysis = {
        "processedAt": int(time.time() * 1000),
        "processingTime": round(yolo_time + face_time, 2),
        "annotatedImageUrl": annotated_url,
        "yolo": {
            "person_count": yolo_result["person_count"],
            "total_objects": yolo_result["total_objects"],
            "detections": yolo_result["detections"][:10],
            "inference_time": round(yolo_time, 3),
        },
        "faces": {
            "count":         face_result["face_count"],
            "identified":    face_result.get("identified", []),
            "unknown_count": face_result.get("unknown_count", 0),
            "details":       face_details[:5],  # includes cropUrl per face
        },
        "scene":  scene,
        "threat": threat,
    }

    ref = db.reference(f"logs/{log_key}/ml_analysis")
    ref.set(ml_analysis)
    print(f"    synced       results written to rtdb")

    # Clean up downloaded image (keep annotated)
    if os.path.exists(local_path) and yolo_result.get("annotated_path"):
        os.remove(local_path)

# ── ML Stats Writer ───────────────────────────────────────────────────────────

def _write_ml_stats(logs_ref):
    """Compute aggregate stats and write to RTDB at ml/stats."""
    logs = logs_ref.get()
    if not logs:
        return
    total = 0
    processed = 0
    persons = 0
    faces = 0
    threats = 0
    for key, data in logs.items():
        total += 1
        ml = data.get("ml_analysis")
        if ml:
            processed += 1
            if ml.get("yolo", {}).get("person_count", 0) > 0:
                persons += 1
            if ml.get("faces", {}).get("count", 0) > 0:
                faces += 1
            threat_level = ml.get("threat", {}).get("level", "none")
            if threat_level != "none":
                threats += 1
    stats = {
        "total_events": total,
        "ml_processed": processed,
        "persons_detected": persons,
        "faces_detected": faces,
        "threats": threats,
        "unprocessed": total - processed,
        "updated_at": int(time.time() * 1000),
    }
    db.reference("ml/stats").set(stats)
    print(f"  [stats] total={total} processed={processed} persons={persons} faces={faces} threats={threats}")

# ── Watch Mode ─────────────────────────────────────────────────────────────────

def watch_mode(detector: PersonDetector, recognizer: FaceRecognizer):
    """
    Continuously watch Firebase RTDB for new unprocessed events.
    """
    print("\n  watching for new events (ctrl+c to stop)")
    ref = db.reference("logs")
    cmd_ref = db.reference("commands")

    processed = set()
    last_cleanup_time = time.time()

    while True:
        try:
            # 0. Auto-Cleanup (Every 5 minutes)
            now = time.time()
            if now - last_cleanup_time > 300:
                last_cleanup_time = now
                cleanup_threshold_ms = (now - 3 * 3600) * 1000 # 3 hours ago
                old_logs = ref.order_by_child("timestamp").end_at(cleanup_threshold_ms).get()
                if old_logs:
                    print(f"\n  [cleanup] found {len(old_logs)} old event(s). deleting to save storage.")
                    import urllib.parse
                    for k, v in old_logs.items():
                        # Delete from RTDB
                        ref.child(k).delete()
                        # Delete from Storage
                        img_url = v.get("imageUrl")
                        if img_url and "firebasestorage" in img_url:
                            try:
                                # Extract filename from tokenized URL
                                # https://firebasestorage.googleapis.com/v0/b/bucket/o/intruders%2Ffilename.jpg?alt=...
                                path_part = img_url.split("/o/")[1].split("?")[0]
                                file_path = urllib.parse.unquote(path_part)
                                bucket = storage.bucket()
                                blob = bucket.blob(file_path)
                                blob.delete()
                            except Exception as e:
                                pass

            # 1. Process commands (register_face, run_ml_all, ml_stats, etc.)
            cmds = cmd_ref.order_by_child("status").equal_to("pending").get()
            if cmds:
                for key, cmd_data in cmds.items():
                    cmd_type = cmd_data.get("type", "")

                    if cmd_type == "register_face":
                        name = cmd_data.get("name")
                        img_url = cmd_data.get("imageUrl")
                        if name and img_url:
                            print(f"\n  [command] teaching ML to identify '{name}'")
                            import tempfile
                            with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
                                tmp_path = tmp.name
                            if download_image(img_url, tmp_path):
                                recognizer.register_face(name, tmp_path)
                                os.remove(tmp_path)

                    elif cmd_type == "run_ml_all":
                        print("\n  [command] reprocessing ALL events")
                        all_logs = ref.get()
                        if all_logs:
                            for lk, ld in all_logs.items():
                                if ld.get("imageUrl"):
                                    process_event(lk, ld, detector, recognizer)
                                    processed.add(lk)

                    elif cmd_type == "ml_stats":
                        print("\n  [command] writing ML stats to RTDB")
                        _write_ml_stats(ref)

                    elif cmd_type == "ml_watch_start":
                        print("\n  [command] watch mode already running ✓")

                    elif cmd_type == "ml_watch_stop":
                        print("\n  [command] stop requested — exiting watch mode")
                        cmd_ref.child(key).update({"status": "done"})
                        return  # exit watch loop

                    # Mark command done
                    cmd_ref.child(key).update({"status": "done"})

            # 2. Process logs
            logs = ref.get()
            if not logs:
                time.sleep(5)
                continue

            for key, data in logs.items():
                if key in processed:
                    continue
                if data.get("ml_analysis"):
                    processed.add(key)
                    continue
                if not data.get("imageUrl"):
                    processed.add(key)
                    continue

                process_event(key, data, detector, recognizer)
                processed.add(key)

            time.sleep(3)

        except KeyboardInterrupt:
            print("\n  stopped.")
            break
        except Exception as e:
            print(f"\n  error: {e}")
            time.sleep(5)

# ── One-shot Mode ──────────────────────────────────────────────────────────────

def process_all(detector: PersonDetector, recognizer: FaceRecognizer):
    """Process all unprocessed events once, then exit."""
    print("\n  processing all unprocessed events...")
    ref = db.reference("logs")
    logs = ref.get()

    if not logs:
        print("  no events found.")
        return

    unprocessed = {k: v for k, v in logs.items()
                   if not v.get("ml_analysis") and v.get("imageUrl")}

    if not unprocessed:
        print("  all events already processed.")
        return

    print(f"  found {len(unprocessed)} unprocessed event(s)\n")

    for key, data in unprocessed.items():
        process_event(key, data, detector, recognizer)

    print(f"\n  done. processed {len(unprocessed)} event(s).")

# ── Face Registration ──────────────────────────────────────────────────────────

def register_face_interactive(name: str, recognizer: FaceRecognizer):
    """Register a known face from an image file or webcam."""
    print(f"\n  registering face: {name}")

    person_dir = KNOWN_FACES_DIR / name
    if person_dir.is_dir():
        images = list(person_dir.glob("*.jpg")) + list(person_dir.glob("*.png"))
        if images:
            for img_path in images:
                recognizer.register_face(name, str(img_path))
            return

    img_path = input(f"  path to a photo of {name}: ").strip()
    if not os.path.exists(img_path):
        print(f"  error: file not found - {img_path}")
        return

    # Save a copy
    person_dir.mkdir(parents=True, exist_ok=True)
    import shutil
    dest = person_dir / os.path.basename(img_path)
    shutil.copy2(img_path, dest)

    recognizer.register_face(name, str(dest))

# ── Offline Folder Processing ──────────────────────────────────────────────────

def process_folder_offline(folder_path: str, detector: PersonDetector, recognizer: FaceRecognizer):
    """Run full ML analysis on a local folder of undocumented photos without Firebase."""
    folder = Path(folder_path)
    if not folder.exists() or not folder.is_dir():
        print(f"  error: folder not found - {folder_path}")
        return

    images = list(folder.glob("*.jpg")) + list(folder.glob("*.png")) + list(folder.glob("*.jpeg"))
    if not images:
        print(f"  no images found in {folder_path}")
        return

    print(f"\n  [Offline Analysis] Processing {len(images)} image(s) in {folder.name}...")
    
    for i, file in enumerate(images):
        print(f"\n  [{i+1}/{len(images)}] {file.name}")
        
        # YOLO
        t0 = time.time()
        yolo_res = detector.detect(str(file))
        yolo_time = time.time() - t0
        print(f"    yolo         {yolo_res['person_count']} person(s), {yolo_res['total_objects']} objects ({yolo_time:.2f}s)")
        
        # Faces
        t0 = time.time()
        face_res = recognizer.recognize(str(file))
        face_time = time.time() - t0
        print(f"    faces        {face_res['face_count']} detected ({face_time:.2f}s)")
        if face_res["identified"]:
            print(f"    identified   {', '.join(face_res['identified'])}")
            
        # Scene & Threat
        scene = SceneAnalyzer.analyze(str(file))
        print(f"    scene        {scene.get('lighting', '?')}, brightness {scene.get('brightness', 0)}")
        
        threat = assess_threat(yolo_res, face_res, scene)
        print(f"    threat       {threat['level']} ({threat['score']})")


# ── Stats Command ──────────────────────────────────────────────────────────────

def show_stats():
    """Show ML processing statistics from RTDB."""
    ref = db.reference("logs")
    logs = ref.get()

    if not logs:
        print("No events in database.")
        return

    total = len(logs)
    processed = sum(1 for v in logs.values() if v.get("ml_analysis"))
    persons_detected = sum(
        v.get("ml_analysis", {}).get("yolo", {}).get("person_count", 0)
        for v in logs.values()
    )
    faces_identified = set()
    unknown_faces = 0
    threat_counts = {"high": 0, "medium": 0, "low": 0, "none": 0}

    for v in logs.values():
        ml = v.get("ml_analysis", {})
        faces = ml.get("faces", {})
        if faces.get("identified"):
            faces_identified.update(faces["identified"])
        unknown_faces += faces.get("unknown_count", 0)
        threat = ml.get("threat", {}).get("level", "none")
        threat_counts[threat] = threat_counts.get(threat, 0) + 1

    print()
    print("  Door Sentinel - ML Stats")
    print("  " + "-" * 40)
    print(f"  total events       {total}")
    print(f"  ml processed       {processed}")
    print(f"  persons detected   {persons_detected}")
    print(f"  faces identified   {len(faces_identified)} unique ({', '.join(faces_identified) or 'none'})")
    print(f"  unknown faces      {unknown_faces}")
    print(f"  threats            {threat_counts['high']} high, "
          f"{threat_counts['medium']} medium, "
          f"{threat_counts['low']} low")
    print()

# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Door Sentinel ML Pipeline — YOLOv8 + Face Recognition"
    )
    parser.add_argument("--once", action="store_true",
                       help="Process all unprocessed events once, then exit")
    parser.add_argument("--register", metavar="NAME",
                       help="Register a known face by name")
    parser.add_argument("--stats", action="store_true",
                       help="Show ML processing statistics")
    parser.add_argument("--run-folder", metavar="PATH",
                       help="Run offline ML analysis on a folder of local images")
    args = parser.parse_args()

    # Header
    print()
    print("  Door Sentinel")
    print("  ML Analysis Pipeline")
    print("  " + "-" * 40)

    # Init Firebase (skip if offline folder mode)
    if not args.run_folder:
        init_firebase()

    # Stats mode
    if args.stats:
        show_stats()
        return

    # Init ML models
    detector = PersonDetector()
    recognizer = FaceRecognizer()

    # Register mode
    if args.register:
        register_face_interactive(args.register, recognizer)
        return

    # Folder Offline Mode
    if args.run_folder:
        process_folder_offline(args.run_folder, detector, recognizer)
        return

    # Process mode
    if args.once:
        process_all(detector, recognizer)
    else:
        watch_mode(detector, recognizer)

if __name__ == "__main__":
    main()
